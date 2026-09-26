#!/usr/bin/env python3
"""Exercise the released eight-question pilot over HTTPS and the dedicated Runner; no model calls."""
import argparse
import importlib.util
import http.cookiejar
import json
from pathlib import Path
import secrets
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
spec=importlib.util.spec_from_file_location('runner_smoke',Path(__file__).with_name('smoke-dedicated-runner.py'))
helper=importlib.util.module_from_spec(spec);spec.loader.exec_module(helper)
ssh,sql=helper.ssh,helper.sql

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--reassess',action='store_true');parser.add_argument('--language',choices=['JAVA','CPP','PYTHON'],default='JAVA');args=parser.parse_args()
    references=json.loads((Path(__file__).resolve().parents[1]/'tests/fixtures/diagnostic-language-references.json').read_text())
    bank=json.loads((Path(__file__).resolve().parents[1]/'diagnostics/core-a-v2.json').read_text())
    base=ssh('ocr-serv',"sed -n 's/^PUBLIC_BASE_URL=//p' ~/gamjaoj/web/.env").rstrip('/')
    invitation=ssh('ocr-serv',"sed -n 's/^INVITE_CODE=//p' ~/gamjaoj/web/.env")
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
        entry=next(b for b in catalog if b['id']==bank['id']);assert entry['questionCount']==8 and len(entry['categories'])==4
        assert set(entry)=={'id','categories','questionCount'}
        assert all(not p['version'].startswith('diagnostic-') for p in call('/api/problems')[1])
        key=str(uuid.uuid4());body=dict(bankId=bank['id'],categories=entry['categories'])
        status,saved=call('/api/diagnostics','POST',body,key);assert status==200
        assert call('/api/diagnostics','POST',body,key)[1]['id']==saved['id'];session=saved['id']
        def solve(saved,content):
            session=saved['id']
            for item in content['items']:
                question=saved['current'];assert question['problemVersion']==item['problem']['version']
                assert set(question)=={'itemId','problemVersion','title','statement','sampleInput','sampleOutput','languages'}
                payload=dict(problemVersion=question['problemVersion'],diagnosticItemId=question['itemId'],source=item['reference'] if args.language=='JAVA' else references[question['problemVersion']][args.language],language=args.language)
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
                assert result['verdict']=='AC',(question['problemVersion'],result['verdict'])
                saved=call('/api/diagnostics/'+session)[1]
                print('PASS:',question['problemVersion'],'AC and automatic advance',flush=True)
            return saved
        saved=solve(saved,bank)
        assert saved['status']=='COMPLETED' and saved['current'] is None
        assert all(i['status']=='PASSED' and i['attempts']==1 for i in saved['items'])
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
            completed=solve(next_session,target);assert completed['status']=='COMPLETED'
            assert all(i['status']=='PASSED' and i['attempts']==1 for i in completed['items'])
            assert call('/api/diagnostics/'+session+'/reassessments')[1]==[]
            assert call('/api/diagnostics/'+session+'/reassessments','POST',body,str(uuid.uuid4()))[0]==409
            print('PASS: A -> B correspondence, eight B AC, replay, prior exposure refusal and reserved catalog',flush=True)
        assert sql(f"SELECT count(*) FROM ai_task a JOIN app_user u ON u.id=a.user_id WHERE u.username='{username}'")=='0'
        assert sql(f"SELECT count(*) FROM judge_attempt a JOIN submission s ON s.id=a.submission_id WHERE s.diagnostic_item_id IN (SELECT id FROM diagnostic_item WHERE session_id='{session}') AND a.status='COMPLETED'")=='8'
        print('PASS: eight-question HTTPS pilot, private catalog, one attempt/item, private reassessment options, zero AI tasks',flush=True)
    finally:
        sql(f"DELETE FROM spring_session WHERE principal_name='{username}'; DELETE FROM app_user WHERE username='{username}';")

if __name__=='__main__':main()
