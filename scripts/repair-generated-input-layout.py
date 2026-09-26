"""Audit known line-based generation contracts; optionally rerun full Runner gates, without model calls.
Run on the application host with --env-file ~/gamjaoj/web/.env. Old packages and verdicts are retained.
"""
import argparse
import json
import subprocess
import urllib.request
from pathlib import Path


def sql(query):
    return subprocess.run(['docker','exec','-i','gamjaoj-postgres-1','psql','-U','gamjaoj','-d','gamjaoj','-At','-v','ON_ERROR_STOP=1'],input=query,text=True,capture_output=True,check=True).stdout.strip()


def canonical_input(template, text):
    words=text.split()
    if template=='sequence-sum-v1' or template.startswith('sequence-recipe-v1-'):
        if not words or len(words)!=int(words[0])+1:raise ValueError('Invalid sequence tokens')
        return words[0]+'\n'+' '.join(words[1:])+'\n'
    if template.startswith('graph-recipe-v1-'):
        if len(words)<4 or len(words)!=4+3*int(words[1]):raise ValueError('Invalid graph tokens')
        return ' '.join(words[:4])+'\n'+''.join(' '.join(words[i:i+3])+'\n' for i in range(4,len(words),3))
    return text


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file',type=Path,required=True)
    parser.add_argument('--apply',action='store_true')
    args=parser.parse_args()
    rows=sql("""SELECT json_build_object('id',g.id,'template',g.template_id,'version',p.id,'hash',p.package_sha256,'plan',p.package_json::json)
      FROM generation_job g JOIN problem_version p ON p.id='generated-'||g.id::text||'-r'||g.revision::text
      WHERE g.status='READY' ORDER BY g.created_at""")
    config=dict(line.split('=',1) for line in args.env_file.read_text().splitlines() if line and not line.startswith('#'))
    for raw in rows.splitlines():
        row=json.loads(raw)
        bad=[t['id'] for t in row['plan']['tests'] if canonical_input(row['template'],t['input'])!=t['input']]
        if not bad:continue
        print(json.dumps({'version':row['version'],'mismatchedTests':bad}),flush=True)
        if args.apply:
            url='http://'+config.get('BIND_ADDRESS','127.0.0.1')+':'+config.get('HTTP_PORT','8080')+'/internal/generation/'+row['id']+'/repair-input-layout'
            request=urllib.request.Request(url,data=json.dumps({'packageHash':row['hash']}).encode(),headers={'Authorization':'Bearer '+config['GENERATION_WORKER_TOKEN'],'Content-Type':'application/json'},method='POST')
            with urllib.request.urlopen(request,timeout=30) as response:result=json.load(response)
            print(json.dumps({'id':result['id'],'revision':result['revision'],'status':result['status']}),flush=True)


if __name__=='__main__':main()
