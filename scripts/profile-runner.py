"""Replay saved validation jobs, without publishing or calling a model.
Input is a private JSON array: id, role, source, sourceSha256, problem,
problemSha256, image, expected. Output contains timings/hashes, never source/input.
Run on the dedicated Runner host; existing host lock remains in force.
This offline profile excludes production queue, coordinator and HTTP delivery.
"""
import argparse
from collections import defaultdict
import hashlib
import json
import os
from pathlib import Path
import tempfile
import uuid
import time
from concurrent.futures import ThreadPoolExecutor
from runner.judge import Runner, CompileCache


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--control',choices=('cli','engine'),default='cli',help='Non-streaming Docker control transport')
    parser.add_argument('--disable-cache',action='store_true',help='Uncached comparison; never changes the service worker')
    parser.add_argument('--slots',type=int,choices=(1,2),default=1,help='Replay only explicitly marked FUNCTIONAL jobs concurrently')
    args=parser.parse_args()
    os.environ['GAMJAOJ_DOCKER_CONTROL']=args.control
    jobs=json.loads(args.input.read_text());cache=None if args.disable_cache else CompileCache();rows=[]
    for job in jobs:
        if job.get('executionMode','EXCLUSIVE') not in ('EXCLUSIVE','FUNCTIONAL'):
            raise ValueError('Unknown execution mode')
        source=job['source'].encode()
        problem=json.dumps(job['problem'],sort_keys=True,ensure_ascii=False,separators=(',',':')).encode()
        if hashlib.sha256(source).hexdigest()!=job['sourceSha256'] or hashlib.sha256(problem).hexdigest()!=job['problemSha256']:
            raise ValueError('Saved source/package hash mismatch')
    def execute(job):
        with tempfile.TemporaryDirectory(prefix='gamjaoj-profile-') as directory:
            runner=Runner(job['image'],directory,attempt=str(uuid.uuid4()));runner.compile_cache=cache
            runner.execution_mode=job.get('executionMode','EXCLUSIVE') if args.slots==2 else 'EXCLUSIVE'
            report=runner.judge(job['source'].encode(),job['problem'])
            timing=json.loads((Path(directory)/'runs'/report['run_id']/'performance.json').read_text())
            timing.update(submissionId=job['id'],role=job['role'],expected=job['expected'])
            if report['verdict']!=job['expected']:
                raise RuntimeError('Replay verdict mismatch: '+job['role'])
            return timing
    started=time.monotonic()
    def save():
        args.output.write_text(json.dumps(dict(mode='offline-saved-validation-replay',control=args.control,
            slots=args.slots,elapsedMs=round((time.monotonic()-started)*1000,3),
            cacheEnabled=not args.disable_cache,jobs=rows),indent=2))
    with ThreadPoolExecutor(max_workers=args.slots) as pool:
        offset=0
        while offset<len(jobs):
            end=offset+1
            if args.slots==2 and jobs[offset].get('executionMode')=='FUNCTIONAL':
                while end<len(jobs) and jobs[end].get('executionMode')=='FUNCTIONAL': end+=1
            for timing in pool.map(execute,jobs[offset:end]):
                rows.append(timing);save()
            offset=end
    save()
    phases=defaultdict(float)
    for row in rows:
        for part in row['segments']:
            if "elapsedMs" in part:
                phases[part['phase']]+=part['elapsedMs']
    print(json.dumps(dict(jobs=len(rows),cacheHits=sum(row['compileCacheHit'] is True for row in rows),
                         elapsedMs=round((time.monotonic()-started)*1000,3),slots=args.slots,
                         totalMs=round(sum(row['elapsedMs'] for row in rows),3),
                         phasesMs={key:round(value,3) for key,value in phases.items()}),indent=2))

if __name__=='__main__':main()
