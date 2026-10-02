#!/usr/bin/env python3
"""Verify an attributed external STDIO corpus against its frozen supplied tests.

This checks reference agreement on the upstream samples and hidden cases, not
an independent exhaustive correctness proof. All imported code runs in Runner.
"""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import tempfile
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from runner.judge import Runner, LANGUAGES, checked_profile, validate_problem
from runner.execution_contract import contract


def canonical(value):
    return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'))


def digest(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def verify(directory):
    package=json.loads((directory/'package.json').read_text())
    source=(directory/'reference.java').read_bytes()
    validate_problem(package)
    with tempfile.TemporaryDirectory(prefix='gamja-problemset-') as state:
        runner=Runner(LANGUAGES['JAVA']['image'],state)
        runner.profile=checked_profile(LANGUAGES['JAVA']|{'testWallSeconds':10},'JAVA',runner.image)
        runner.judge_all=True
        report=runner.judge(source,package)
    result={'packageHash':digest(package),'referenceHash':hashlib.sha256(source).hexdigest(),
            'scope':'upstream sample and hidden input/output batches; no independent oracle',
            'report':report}
    (directory/'verification.json').write_text(canonical(result)+'\n')
    return report['verdict']


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root',type=Path)
    parser.add_argument('--retry-failed',action='store_true')
    args=parser.parse_args()
    totals={}
    for i,directory in enumerate(sorted(args.root.iterdir()),1):
        if not (directory/'package.json').is_file():continue
        previous=directory/'verification.json'
        if previous.exists():
            saved=json.loads(previous.read_text())
            same=(saved['packageHash']==digest(json.loads((directory/'package.json').read_text()))
                  and saved['referenceHash']==hashlib.sha256((directory/'reference.java').read_bytes()).hexdigest()
                  and saved['report'].get('runner_environment',{}).get('contract')==contract())
            if same and (not args.retry_failed or saved['report']['verdict']=='AC'):
                verdict=saved['report']['verdict']
            else:verdict=verify(directory)
        else:verdict=verify(directory)
        totals[verdict]=totals.get(verdict,0)+1
        print(f'{i} {directory.name} {verdict} {canonical(totals)}',flush=True)
    print(canonical(totals),flush=True)
    if any(key!='AC' for key in totals):raise SystemExit(1)

if __name__=='__main__':main()
