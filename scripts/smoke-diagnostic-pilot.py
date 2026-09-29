#!/usr/bin/env python3
"""Exercise a released diagnostic bank over HTTPS and the dedicated Runner.

No model calls unless --evaluate is given; then one completed-session evaluation is requested and awaited.
"""
import os
import argparse
import importlib.util
import http.cookiejar
import json
from pathlib import Path
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from diagnostics.time_limits import limits_for
spec=importlib.util.spec_from_file_location('runner_smoke',Path(__file__).with_name('smoke-dedicated-runner.py'))
helper=importlib.util.module_from_spec(spec);spec.loader.exec_module(helper)
ssh,sql=helper.ssh,helper.sql

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--reassess',action='store_true');parser.add_argument('--language',choices=['JAVA','CPP','PYTHON'],default='JAVA')
    parser.add_argument('--bank',type=Path,default=Path(__file__).resolve().parents[1]/'diagnostics/core-a-v2.json')
    parser.add_argument('--attempts',type=int,choices=range(1,6),default=1,help='Formal attempts per item: wrong solutions first, reference last')
    parser.add_argument('--evaluate',action='store_true',help='Request the completed-session evaluation (spends model budget)')
    parser.add_argument('--keep',action='store_true',help='Keep the synthetic account for a manual browser check; credentials go to .state only')
    args=parser.parse_args()
    references=json.loads((Path(__file__).resolve().parents[1]/'tests/fixtures/diagnostic-language-references.json').read_text())
    bank=json.loads(args.bank.read_text());count=len(bank['items'])
    base=ssh(os.environ['GAMJAOJ_APP_SSH_TARGET'],"sed -n 's/^PUBLIC_BASE_URL=//p' ~/gamjaoj/web/.env").rstrip('/')
    invitation=ssh(os.environ['GAMJAOJ_APP_SSH_TARGET'],"sed -n 's/^INVITE_CODE=//p' ~/gamjaoj/web/.env")
    client=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()));client.addheaders=[('User-Agent','GamjaOJ-Smoke/1.0')]
    def call(path,method='GET',body=None,key=None,form=False):
        headers={}
        if method!='GET':
            _,csrf=call('/api/auth/csrf');headers[csrf['headerName']]=csrf['token']
        if key:headers['Idempotency-Key']=key
        if body is not None:
            headers['Content-Type']='application/x-www-form-urlencoded' if form else 'application/json'
            body=(urllib.parse.urlencode(body) if form else json.dumps(body)).encode()
        try:r=client.open(urllib.request.Request(base+path,data=body,headers=headers,method=method),timeout=20)
        except urllib.error.HTTPError as e:r=e
        with r:
            raw=r.read();return r.status,json.loads(raw) if raw else None
    username='pilot_'+secrets.token_hex(5);password=secrets.token_urlsafe(24)
    try:
        assert call('/api/auth/signup','POST',dict(username=username,password=password,nickname='Pilot verification',inviteCode=invitation))[0]==201
        assert call('/api/auth/login','POST',dict(username=username,password=password),form=True)[0]==204
        catalog=call('/api/diagnostics/banks')[1]
        entry=next(b for b in catalog if b['id']==bank['id']);assert entry['questionCount']==count and len(entry['categories'])==count//2
        assert set(entry)=={'id','categories','questionCount'}
        assert all(not p['version'].startswith('diagnostic-') for p in call('/api/problems')[1])
        key=str(uuid.uuid4());body=dict(bankId=bank['id'],categories=entry['categories'])
        status,saved=call('/api/diagnostics','POST',body,key);assert status==200
        assert call('/api/diagnostics','POST',body,key)[1]['id']==saved['id'];session=saved['id']
        def solve(saved,content):
            session=saved['id']
            by_version={item['problem']['version']:item for item in content['items']}
            for _ in content['items']:
                question=saved['current'];item=by_version[question['problemVersion']]
                assert set(question)=={'itemId','problemVersion','title','statement','sampleInput','sampleOutput','examples','languages'}
                limits=limits_for(item['problem'])
                if limits is not None:
                    assert {p['id']:p['timeLimitMs'] for p in question['languages']}=={l:limits[l]*1000 for l in ('JAVA','CPP','PYTHON')}
                correct=item['reference'] if args.language=='JAVA' else item['languages'][args.language]['correct'] if 'languages' in item else references[question['problemVersion']][args.language]
                wrong=item['languages'][args.language]['wrong'] if 'languages' in item else item['mutant'] if args.language=='JAVA' else None
                sources=[wrong]*(attempts-1)+[correct]
                if None in sources:raise SystemExit('No wrong solution for '+args.language+' in this bank')
                for index,source in enumerate(sources):
                    payload=dict(problemVersion=question['problemVersion'],diagnosticItemId=question['itemId'],source=source,language=args.language)
                    request=str(uuid.uuid4());status,submitted=call('/api/submissions','POST',payload,request);assert status==202
                    assert call('/api/submissions','POST',payload,request)[1]['id']==submitted['id']
                    deadline=time.monotonic()+120
                    while time.monotonic()<deadline:
                        result=call('/api/submissions/'+submitted['id'])[1]
                        if result['status']=='FINISHED':break
                        time.sleep(1)
                    else:raise AssertionError('Diagnostic judging timed out')
                    assert result['language']==args.language
                    assert result['execution']['id']==args.language
                    if limits is not None:
                        assert result['execution']['timeLimitMs']==limits[args.language]*1000
                    expected='AC' if index==len(sources)-1 else 'WA'
                    assert result['verdict']==expected,(question['problemVersion'],index,result['verdict'])
                saved=call('/api/diagnostics/'+session)[1]
                print('PASS:',question['problemVersion'],f'{len(sources)-1} WA then AC and automatic advance',flush=True)
            return saved
        attempts=args.attempts;saved=solve(saved,bank)
        assert saved['status']=='COMPLETED' and saved['current'] is None
        assert all(i['status']=='PASSED' and i['attempts']==attempts for i in saved['items'])
        options=call('/api/diagnostics/'+session+'/reassessments')[1]
        assert all(set(option)=={'id','categories','questionCount'} for option in options)
        if args.reassess:
            target=json.loads((Path(__file__).resolve().parents[1]/'diagnostics/core-b-v1.json').read_text())
            entry=next(b for b in options if b['id']==target['id']);assert entry['questionCount']==8
            assert not any(b['id']==target['id'] for b in call('/api/diagnostics/banks')[1])
            key=str(uuid.uuid4());body=dict(bankId=target['id'],categories=entry['categories'])
            status,next_session=call('/api/diagnostics/'+session+'/reassessments','POST',body,key);assert status==200
            assert next_session['sourceSessionId']==session
            assert call('/api/diagnostics/'+session+'/reassessments','POST',body,key)[1]['id']==next_session['id']
            attempts=1;completed=solve(next_session,target);assert completed['status']=='COMPLETED'
            assert all(i['status']=='PASSED' and i['attempts']==1 for i in completed['items'])
            assert call('/api/diagnostics/'+session+'/reassessments')[1]==[]
            assert call('/api/diagnostics/'+session+'/reassessments','POST',body,str(uuid.uuid4()))[0]==409
            print('PASS: A -> B correspondence, eight B AC, replay, prior exposure refusal and reserved catalog',flush=True)
        assert sql(f"SELECT count(*) FROM ai_task a JOIN app_user u ON u.id=a.user_id WHERE u.username='{username}'")=='0'
        assert sql(f"SELECT count(*) FROM judge_attempt a JOIN submission s ON s.id=a.submission_id WHERE s.diagnostic_item_id IN (SELECT id FROM diagnostic_item WHERE session_id='{session}') AND a.status='COMPLETED'")==str(count*args.attempts)
        assert sql(f"SELECT count(*) FROM submission s JOIN judge_job j ON j.submission_id=s.id WHERE s.diagnostic_item_id IN (SELECT id FROM diagnostic_item WHERE session_id='{session}') AND s.execution_profile_json::jsonb=j.result_json::jsonb->'execution_profile'")==str(count*args.attempts)
        print(f'PASS: {count}-question HTTPS pilot, private catalog, {args.attempts} attempt(s)/item, private reassessment options, zero AI tasks',flush=True)
        if args.evaluate:
            status,evaluation=call('/api/diagnostics/'+session+'/evaluations','POST');assert status==200,(status,evaluation)
            size=sql(f"SELECT octet_length(evidence_json)||' '||(evidence_json::json->>'sourceCompaction' IS NOT NULL) FROM diagnostic_evaluation WHERE id='{uuid.UUID(evaluation['id'])}'")
            print('evaluation evidence bytes, compacted:',size,'status:',evaluation['status'],flush=True)
            deadline=time.monotonic()+600
            while evaluation['status'] in ('QUEUED','RUNNING') and time.monotonic()<deadline:
                time.sleep(5);evaluation=next(e for e in call('/api/diagnostics/'+session+'/evaluations')[1] if e['id']==evaluation['id'])
            usage=sql(f"SELECT coalesce(string_agg(a.status||' '||coalesce(a.error_code,'')||' usd='||coalesce(a.actual_usd::text,'?')||' usage='||coalesce(a.usage_json,'?'),'; '),'none') FROM ai_attempt a JOIN diagnostic_evaluation e ON e.ai_task_id=a.task_id WHERE e.id='{uuid.UUID(evaluation['id'])}'")
            interpretation=evaluation.get('interpretation') or {}
            print('evaluation:',evaluation['status'],evaluation.get('errorCode'),'observations:',len(interpretation.get('observations',[])),'attempts:',usage,flush=True)
            Path('.state').mkdir(exist_ok=True);Path('.state/diagnostic-evaluation-smoke.json').write_text(json.dumps(evaluation,ensure_ascii=False,indent=1))
            status,profile=call('/api/diagnostics/'+session+'/evaluations/'+evaluation['id']+'/profile');assert status==200,(status,profile)
            mapped=sorted(o['index'] for c in profile['categories'] for o in c['observations'])
            assert mapped==list(range(len(interpretation.get('observations',[])))),('unmapped observations',mapped)
            assert {c['id'] for c in profile['categories'] if c['selected']}=={i['category'] for i in bank['items']}
            tones={t:sum(o['tone']==t for c in profile['categories'] for o in c['observations']) for t in ('STRENGTH','WATCH','RISK')}
            print('profile: categories',len(profile['categories']),'tones',tones,'repeated',sum(o['repeated'] for c in profile['categories'] for o in c['observations']),
                  'rule matches',{c['id']:len(c['ruleIds']) for c in profile['categories'] if c['ruleIds']},flush=True)
            Path('.state/diagnostic-profile-smoke.json').write_text(json.dumps(profile,ensure_ascii=False,indent=1))
    finally:
        if args.keep:
            Path('.state').mkdir(exist_ok=True);Path('.state/kept-account.json').write_text(json.dumps({'base':base,'username':username,'password':password}))
            print('kept synthetic account',username,'(credentials in .state/kept-account.json; delete it after the check)',flush=True)
        else:
            sql(f"DELETE FROM spring_session WHERE principal_name='{username}'; DELETE FROM app_user WHERE username='{username}';")

if __name__=='__main__':main()
