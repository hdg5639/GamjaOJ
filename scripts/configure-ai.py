#!/usr/bin/env python3
"""Transfer only approved AI settings to the application host; never print secrets.
Does not restart/deploy anything or configure provider billing. Paid calls default OFF.
"""
import argparse
from decimal import Decimal
import json
from pathlib import Path
import re
import shlex
import subprocess


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target',default='ocr-serv')
    parser.add_argument('--env-file',type=Path,default=Path('.env'))
    parser.add_argument('--operators',default=None,help='Optional operator allowlist; omitted preserves existing roles')
    parser.add_argument('--budget-usd',default='10')
    parser.add_argument('--enable',action='store_true',help='Explicit operator activation after verifying key and shared budget')
    args=parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.@-]*',args.target):parser.error('Invalid SSH target')
    if Decimal(args.budget_usd)<0:parser.error('Budget must be non-negative')
    if any(not re.fullmatch(r'[A-Za-z0-9_.-]+',name) for name in (args.operators or '').split(',') if name):parser.error('Invalid operator username')
    local={}
    for line in args.env_file.read_text().splitlines():
        if '=' in line and not line.lstrip().startswith('#'):
            key,value=line.split('=',1);local[key.strip()]=value.strip().strip('\"\'')
    key=local.get('OPENAI_API_KEY','')
    if not key or any(c.isspace() for c in key):parser.error('OPENAI_API_KEY is missing or invalid')
    update={'OPENAI_API_KEY':key,'AI_API_ENABLED':str(args.enable).lower(),'AI_MONTHLY_BUDGET_USD':str(Decimal(args.budget_usd)),
            'AI_DEFAULT_MODEL':'gpt-5.6-luna','AI_DEFAULT_REASONING':'low',
            'AI_STRONG_MODEL':'gpt-5.6-terra','AI_STRONG_REASONING':'medium',
            'CODEX_GENERATION_MODEL':'gpt-5.6-sol','CODEX_GENERATION_REASONING':'medium'}
    if args.operators is not None:update['AI_OPERATOR_USERS']=args.operators
    remote='''import json,os,secrets,sys
from pathlib import Path
path=Path.home()/'gamjaoj/web/.env'
config=dict(line.split('=',1) for line in path.read_text().splitlines() if line and not line.startswith('#') and '=' in line)
updates=json.load(sys.stdin)
for key,value in updates.items():
    if key.endswith(('_MODEL','_REASONING')):
        config.setdefault(key,value)
    else:
        config[key]=value
config.setdefault('GENERATION_WORKER_TOKEN',secrets.token_urlsafe(32))
temporary=path.with_suffix('.ai-tmp')
fd=os.open(temporary,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
with os.fdopen(fd,'w') as stream:
    stream.write(''.join(k+'='+v+'\\n' for k,v in config.items()));stream.flush();os.fsync(stream.fileno())
os.chmod(temporary,0o600)
temporary.replace(path)
print('AI configuration saved; paid calls '+('enabled' if config['AI_API_ENABLED']=='true' else 'disabled')+'. Redeploy application to apply.')
'''
    subprocess.run(['ssh','-o','BatchMode=yes',args.target,'python3 -c '+shlex.quote(remote)],input=json.dumps(update).encode(),check=True)


if __name__=='__main__':main()
