"""Explicit paid Responses analysis/hint -> cached feedback -> personal Codex generation smoke."""
import os
import argparse
import http.cookiejar
import json
from pathlib import Path
import secrets
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import subprocess

def ssh(command, data=None):
    return subprocess.run(['ssh','-o','BatchMode=yes',os.environ['GAMJAOJ_APP_SSH_TARGET'],command],input=data,text=True,capture_output=True,check=True).stdout.strip()

def sql(query):
    return ssh('docker exec -i gamjaoj-postgres-1 psql -U gamjaoj -d gamjaoj -At -v ON_ERROR_STOP=1',query)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute',action='store_true',help='Authorize paid Responses and ChatGPT-managed generation smoke')
    if not parser.parse_args().execute:
        parser.error('--execute is required; this runs the real generation model')
    invitation=ssh("sed -n 's/^INVITE_CODE=//p' ~/gamjaoj/web/.env")
    base=ssh("sed -n 's/^PUBLIC_BASE_URL=//p' ~/gamjaoj/web/.env")
    def browser():
        client=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        client.addheaders=[('User-Agent','GamjaOJ-Smoke/1.0')]
        def call(path,method='GET',body=None,key=None,form=False):
            headers={}
            if method!='GET':
                _,csrf=call('/api/auth/csrf');headers[csrf['headerName']]=csrf['token']
            if key:headers['Idempotency-Key']=key
            if body is not None:
                headers['Content-Type']='application/x-www-form-urlencoded' if form else 'application/json'
                body=(urllib.parse.urlencode(body) if form else json.dumps(body)).encode()
            try:response=client.open(urllib.request.Request(base+path,data=body,headers=headers,method=method),timeout=30)
            except urllib.error.HTTPError as error:response=error
            with response:
                raw=response.read()
                if raw:
                    try:value=json.loads(raw)
                    except ValueError:value={'error':'Non-JSON HTTP response'}
                else:value=None
                return response.status,value
        return call
    names=['aiprobe_'+secrets.token_hex(5) for _ in range(2)]
    a,b=browser(),browser()
    key=str(uuid.uuid4()); terminal=False
    try:
        for call,name in zip((a,b),names):
            password=secrets.token_urlsafe(24)
            assert call('/api/auth/signup','POST',dict(username=name,password=password,nickname='생성 검증',inviteCode=invitation))[0]==201
            assert call('/api/auth/login','POST',dict(username=name,password=password),form=True)[0]==204
            assert call('/api/ai/status')[1]['operator'] is False
        source=Path('examples/total/Main.java').read_text().replace('long sum','int sum')
        status,submitted=a('/api/submissions','POST',{'problemVersion':'total-v1','source':source},key=str(uuid.uuid4()))
        assert status==202,(status,submitted)
        submission_id=submitted['id']
        for _ in range(90):
            submitted=a('/api/submissions/'+submission_id)[1]
            if submitted['status']=='FINISHED':break
            time.sleep(2)
        assert submitted['verdict']=='WA'
        attempts=[]
        for kind,question in [('ANALYSIS',''),('HINT','합계 자료형을 점검하려면 어떤 입력을 직접 실행해 보면 좋을까요?')]:
            body={'submissionId':submission_id,'kind':kind,'question':question,'strong':False}
            status,task=a('/api/ai/tasks','POST',body);assert status==200,(status,task)
            for _ in range(90):
                task=a('/api/ai/tasks/'+task['id'])[1]
                if task['status'] in ('COMPLETED','FAILED','UNKNOWN','HELD_BUDGET','HELD_DISABLED'):break
                time.sleep(2)
            assert task['status']=='COMPLETED',(task['status'],task['errorCode'])
            assert task['model']=='gpt-5.6-luna'
            assert a('/api/ai/tasks','POST',body)[1]['id']==task['id']
            assert b('/api/ai/tasks/'+task['id'])[0]==404
            audit=json.loads(sql("SELECT json_build_object('attempts',count(*),'actualUsd',sum(actual_usd),'unsettled',count(*) FILTER (WHERE actual_usd IS NULL)) FROM ai_attempt WHERE task_id='"+task['id']+"';"))
            assert audit['attempts']==1 and audit['unsettled']==0 and audit['actualUsd']>0,audit
            attempts.append(audit)
            if kind=='ANALYSIS':analysis=task
            print('PASS: '+kind+' completed, cached, isolated and settled '+str(audit['actualUsd'])+' USD',flush=True)
        assert a('/api/submissions/'+submission_id)[1]['verdict']=='WA'
        assert b('/api/ai/tasks','POST',{'submissionId':submission_id,'kind':'ANALYSIS','question':'','strong':False})[0]==404
        options=a('/api/generation/learning-context')[1]
        assert analysis['id'] in [option['id'] for option in options]
        assert b('/api/generation/learning-context')[1]==[]
        status,job=a('/api/generation','POST',{'template':'sequence-sum-v1','focus':'overflow','sourceAnalysisId':analysis['id']},key=key)
        snapshot=json.loads(sql("SELECT learning_context_json FROM generation_job WHERE id='"+key+"';"))
        assert snapshot['summary']==analysis['result']['summary']
        assert 'source' not in snapshot

        assert status==200,(status,job)
        assert b('/api/generation')[1]==[]
        assert b('/api/generation/'+key+'/review','POST',{'artifactHash':'wrong','approve':True})[0]==404
        last=None
        for _ in range(400):
            status,jobs=a('/api/generation')
            if status in (502,503,504):
                time.sleep(4);continue
            assert status==200,(status,jobs)
            job=next(j for j in jobs if j['id']==key)
            if job['status']!=last:
                last=job['status'];print(last,flush=True)
            if last in ('READY','FAILED','NEEDS_AUTH','NEEDS_REVIEW'):
                terminal=True;break
            time.sleep(4)
        assert job['status']=='READY',(job['status'],job.get('error'))
        version=job['problemVersion']
        assert version in [p['version'] for p in a('/api/problems')[1]]
        assert version not in [p['version'] for p in b('/api/problems')[1]]
        assert b('/api/problems/'+version+'/teaching')[0]==404
        assert b('/api/submissions','POST',{'problemVersion':version,'source':'public class Main {}'},key=str(uuid.uuid4()))[0]==404
        assert b('/api/runs','POST',{'problemVersion':version,'source':'public class Main {}','input':'1 0'},key=str(uuid.uuid4()))[0]==404
        assert b('/api/training-sessions','POST',{'problemVersion':version,'goal':'test'},key=str(uuid.uuid4()))[0]==404
        status,submission=a('/api/submissions','POST',{'problemVersion':version,'source':job['artifacts']['reference']},key=str(uuid.uuid4()))
        assert status==202,(status,submission)
        for _ in range(90):
            submission=a('/api/submissions/'+submission['id'])[1]
            if submission['status']=='FINISHED':break
            time.sleep(2)
        assert submission['verdict']=='AC',submission['verdict']
        evidence={'job':key,'status':job['status'],'validation':job['validation'],'submissionVerdict':submission['verdict'],'privateAccess':'PASS','apiAttempts':attempts,'analysisLinked':True}
        Path('.state').mkdir(exist_ok=True)
        Path('.state/ai-personalization-smoke.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2))
        print('PASS: paid analysis/hint, cache/account isolation, analysis-linked Codex generation, real Runner gates and AC',flush=True)
    finally:
        # Never remove work while a worker may still hold a lease.
        active=sql("SELECT count(*) FROM generation_job WHERE id='"+key+"' AND status IN ('QUEUED','GENERATING','VALIDATING')")
        pending=sql("SELECT count(*) FROM judge_job j JOIN submission s ON s.id=j.submission_id JOIN app_user u ON u.id=s.user_id WHERE u.username IN ('"+"','".join(names)+"') AND j.status<>'FINISHED'")
        if active=='0' and pending=='0':
            quoted="','".join(names)
            sql("BEGIN; DELETE FROM generation_execution WHERE job_id='"+key+"'; "
                "DELETE FROM submission WHERE user_id IN (SELECT id FROM app_user WHERE username IN ('"+quoted+"')); "
                "DELETE FROM generation_attempt WHERE job_id='"+key+"'; "
                "DELETE FROM generation_job WHERE id='"+key+"'; "
                "DELETE FROM problem_version WHERE owner_id IN (SELECT id FROM app_user WHERE username IN ('"+quoted+"')); "
                "DELETE FROM spring_session WHERE principal_name IN ('"+quoted+"'); "
                "DELETE FROM app_user WHERE username IN ('"+quoted+"'); COMMIT;")
            print('Synthetic accounts and problems removed',flush=True)
        else:print('Active probe retained for diagnosis: '+key,flush=True)

if __name__=='__main__':main()
