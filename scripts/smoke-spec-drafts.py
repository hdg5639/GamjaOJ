"""Live experimental spec draft smoke; never publishes a problem or invokes paid API generation."""
import os
import argparse
import http.cookiejar
import json
from pathlib import Path
import secrets
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from smoke_generation_cleanup import cleanup_sql, active_checks_sql


def ssh(command,data=None):
    return subprocess.run(['ssh','-o','BatchMode=yes',os.environ['GAMJAOJ_APP_SSH_TARGET'],command],input=data,text=True,capture_output=True,check=True).stdout.strip()


def sql(query):
    return ssh('docker exec -i gamjaoj-postgres-1 psql -U gamjaoj -d gamjaoj -At -v ON_ERROR_STOP=1',query)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute',action='store_true')
    parser.add_argument('--build',action='store_true',help='Also author implementation and run preliminary Runner checks')
    parser.add_argument('--review',action='store_true',help='Also independently review and verify boundary/mutant evidence')
    parser.add_argument('--publish',action='store_true',help='Verify final checks, private publication and actual submission')
    args=parser.parse_args()
    if args.publish and not args.review:parser.error('--publish requires --review')
    if args.review and not args.build:parser.error('--review requires --build')
    if not args.execute:parser.error('--execute is required to invoke the real Codex model')
    invitation=ssh("sed -n 's/^INVITE_CODE=//p' ~/gamjaoj/web/.env")
    base=ssh("sed -n 's/^PUBLIC_BASE_URL=//p' ~/gamjaoj/web/.env")
    def browser():
        client=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        client.addheaders=[('User-Agent','GamjaOJ-Smoke/1.0')]
        def call(path,method='GET',body=None,key=None,form=False):
            headers={}
            if method!='GET':
                status,csrf=call('/api/auth/csrf')
                assert status==200 and 'headerName' in csrf,('CSRF response',status)
                headers[csrf['headerName']]=csrf['token']
            if key:headers['Idempotency-Key']=key
            if body is not None:
                headers['Content-Type']='application/x-www-form-urlencoded' if form else 'application/json'
                body=(urllib.parse.urlencode(body) if form else json.dumps(body)).encode()
            try:response=client.open(urllib.request.Request(base+path,data=body,headers=headers,method=method),timeout=30)
            except urllib.error.HTTPError as error:response=error
            with response:
                raw=response.read()
                try:value=json.loads(raw) if raw else None
                except ValueError:value={'error':'Non-JSON HTTP response'}
                return response.status,value
        return call
    names=['specprobe_'+secrets.token_hex(5) for _ in range(2)]
    quoted="','".join(names)
    owners="SELECT id FROM app_user WHERE username IN ('"+quoted+"')"
    a,b=browser(),browser();key=str(uuid.uuid4())
    try:
        for call,name in zip((a,b),names):
            password=secrets.token_urlsafe(24)
            assert call('/api/auth/signup','POST',dict(username=name,password=password,nickname='초안 검증',inviteCode=invitation))[0]==201
            assert call('/api/auth/login','POST',dict(username=name,password=password),form=True)[0]==204
        request={'request':'동적 계획법으로 푸는 0/1 선택 문제를 만들어 주세요. 같은 항목을 중복 선택하는 습관이면 틀리는 문제이고, 창고가 아닌 탐사 장비 테마를 원합니다. 작은 N에서는 모든 부분집합을 열거하여 독립 검증할 수 있도록 해 주세요.'}
        path='/api/generation/spec-drafts'
        assert a(path,'POST',request,key)[0]==200
        assert a(path,'POST',request,key)[1]['id']==key
        assert a(path,'POST',{'request':'changed'},key)[0]==409
        assert b(path+'/'+key)[0]==404
        assert b(path)[1]==[]
        last=None
        for _ in range(240):
            status,draft=a(path+'/'+key);assert status==200,(status,draft)
            if draft['status']!=last:last=draft['status'];print(last,flush=True)
            if last not in ('QUEUED','GENERATING','BUILD_QUEUED','BUILD_GENERATING','CHECKING','REVIEW_QUEUED','REVIEW_GENERATING','REVIEW_CHECKING','FINAL_QUEUED','FINAL_GENERATING','FINAL_CHECKING'):break
            time.sleep(4)
        assert last=='DRAFT_READY',draft
        assert draft['mode']=='EXPERIMENTAL'
        assert len(draft['spec']['samples'])>=2
        assert a('/api/generation/'+key+'/review','POST',{'artifactHash':draft['specHash'],'approve':True})[0]==404
        for table,column in [('generation_job','owner_id'),('problem_version','owner_id'),('submission','user_id'),('ai_task','user_id')]:
            assert sql('SELECT count(*) FROM '+table+' WHERE '+column+' IN ('+owners+')')=='0',table
        completion=json.loads(sql("SELECT completion_json FROM generation_spec_draft WHERE id='"+key+"'"))
        assert completion['oracle'] is None
        assert completion['usage']['billingMode']=='CHATGPT_MANAGED'
        evidence={'draft':draft,'usage':completion['usage'],'privateAccess':'PASS','idempotency':'PASS','noPublication':'PASS','apiTasks':0}
        if args.build:
            body={'specHash':draft['specHash']}
            assert b(path+'/'+key+'/build','POST',body)[0]==404
            assert a(path+'/'+key+'/build','POST',{'specHash':'stale'})[0]==409
            assert a(path+'/'+key+'/build','POST',body)[0]==200
            assert a(path+'/'+key+'/build','POST',body)[0]==200
            for _ in range(300):
                status,draft=a(path+'/'+key);assert status==200,(status,draft)
                if draft['status']!=last:last=draft['status'];print(last,flush=True)
                if last not in ('QUEUED','GENERATING','BUILD_QUEUED','BUILD_GENERATING','CHECKING','REVIEW_QUEUED','REVIEW_GENERATING','REVIEW_CHECKING','FINAL_QUEUED','FINAL_GENERATING','FINAL_CHECKING'):break
                time.sleep(4)
            assert last=='CHECKED',(last,draft['error'])
            assert draft['checks']['executions']==13 and draft['checks']['publishable'] is False
            version='experimental-check-'+key
            assert version not in [p['version'] for p in a('/api/problems')[1]]
            assert a('/api/submissions')[1]==[]
            assert a('/api/runs')[1]==[]
            evidence['checkedDraft']=draft
            completion=json.loads(sql("SELECT build_completion_json FROM generation_spec_draft WHERE id='"+key+"'"))
            evidence['buildUsage']=completion['usage']
            assert sql('SELECT count(*) FROM ai_task WHERE user_id IN ('+owners+')')=='0'
            assert sql("SELECT ready FROM problem_version WHERE id='"+version+"'")=='f'
            print('PASS: 13 real Runner checks, independent oracle and unpublished result',flush=True)
        if args.review:
            body={'specHash':draft['specHash']}
            assert b(path+'/'+key+'/review','POST',body)[0]==404
            assert a(path+'/'+key+'/review','POST',{'specHash':'stale'})[0]==409
            assert a(path+'/'+key+'/review','POST',body)[0]==200
            assert a(path+'/'+key+'/review','POST',body)[0]==200
            for _ in range(300):
                status,draft=a(path+'/'+key);assert status==200,(status,draft)
                if draft['status']!=last:last=draft['status'];print(last,flush=True)
                if last not in ('QUEUED','GENERATING','BUILD_QUEUED','BUILD_GENERATING','CHECKING','REVIEW_QUEUED','REVIEW_GENERATING','REVIEW_CHECKING','FINAL_QUEUED','FINAL_GENERATING','FINAL_CHECKING'):break
                time.sleep(4)
            evidence['reviewedDraft']=draft
            completion=json.loads(sql("SELECT review_completion_json FROM generation_spec_draft WHERE id='"+key+"'"))
            evidence['reviewUsage']=completion['usage']
            Path('.state').mkdir(exist_ok=True)
            Path('.state/experimental-review-smoke.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2))
            assert last=='REVIEW_CHECKED',(last,draft['error'],draft.get('review'))
            assert draft['review']['executions']==6 and draft['review']['publishable'] is False
            assert all(r['verdict']==r['expected'] for r in draft['review']['results'])
            assert a('/api/submissions')[1]==[] and a('/api/runs')[1]==[]
            assert sql('SELECT count(*) FROM ai_task WHERE user_id IN ('+owners+')')=='0'
            assert sql("SELECT ready FROM problem_version WHERE id='experimental-check-"+key+"'")=='f'
            print('PASS: independent semantic review and 6 real Runner boundary/invalid/mutant checks',flush=True)
        if args.publish:
            body={'specHash':draft['specHash']}
            assert b(path+'/'+key+'/publish','POST',body)[0]==404
            assert a(path+'/'+key+'/publish','POST',{'specHash':'stale'})[0]==409
            assert a(path+'/'+key+'/publish','POST',body)[0]==200
            assert a(path+'/'+key+'/publish','POST',body)[0]==200
            for _ in range(360):
                status,draft=a(path+'/'+key);assert status==200
                if draft['status']!=last:last=draft['status'];print(last,flush=True)
                if last not in ('QUEUED','GENERATING','BUILD_QUEUED','BUILD_GENERATING','CHECKING','REVIEW_QUEUED','REVIEW_GENERATING','REVIEW_CHECKING','FINAL_QUEUED','FINAL_GENERATING','FINAL_CHECKING'):break
                time.sleep(4)
            evidence['publishedDraft']=draft
            completion=json.loads(sql("SELECT final_completion_json FROM generation_spec_draft WHERE id='"+key+"'"))
            evidence['finalUsage']=completion['usage']
            evidence['finalPlan']=completion['artifacts']
            evidence['finalPlanReview']=completion['oracle']
            Path('.state/experimental-publication-smoke.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2))
            assert last=='PUBLISHED',(last,draft['error'],draft.get('publication'))
            version=draft['problemVersion']
            assert version in [p['version'] for p in a('/api/problems')[1]]
            assert version not in [p['version'] for p in b('/api/problems')[1]]
            assert b('/api/problems/'+version+'/teaching')[0]==404
            assert a('/api/problems/'+version+'/teaching')[0]==200
            source=json.loads(sql("SELECT build_artifacts_json FROM generation_spec_draft WHERE id='"+key+"'"))['reference']
            submission={'problemVersion':version,'source':source}
            assert b('/api/submissions','POST',submission,str(uuid.uuid4()))[0]==404
            status,submitted=a('/api/submissions','POST',submission,str(uuid.uuid4()));assert status in (200,201,202),(status,submitted)
            for _ in range(120):
                status,submitted=a('/api/submissions/'+submitted['id']);assert status==200
                if submitted['status']=='FINISHED':break
                time.sleep(3)
            assert submitted['verdict']=='AC',(submitted['status'],submitted['verdict'])
            assert sql('SELECT count(*) FROM ai_task WHERE user_id IN ('+owners+')')=='0'
            evidence['privateSubmission']='AC'
            Path('.state/experimental-publication-smoke.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2))
            print('PASS: private EXPERIMENTAL publication and actual owner submission AC; foreign access denied',flush=True)
        Path('.state').mkdir(exist_ok=True)
        Path('.state/spec-drafts-smoke.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2))
        print('PASS: real Codex flow, private access, replay and no paid API task',flush=True)
    finally:
        if sql(active_checks_sql(owners))=='0' and sql("SELECT count(*) FROM judge_job j JOIN submission s ON s.id=j.submission_id WHERE s.user_id IN ("+owners+") AND j.status<>'FINISHED'")=="0" and sql("SELECT count(*) FROM generation_spec_draft WHERE owner_id IN ("+owners+") AND status IN ('QUEUED','GENERATING','BUILD_QUEUED','BUILD_GENERATING','CHECKING','REVIEW_QUEUED','REVIEW_GENERATING','REVIEW_CHECKING','FINAL_QUEUED','FINAL_GENERATING','FINAL_CHECKING')")=='0':
            sql("BEGIN; "+cleanup_sql(owners)+"DELETE FROM generation_spec_execution WHERE draft_id IN (SELECT id FROM generation_spec_draft WHERE owner_id IN ("+owners+")); DELETE FROM submission WHERE user_id IN ("+owners+"); DELETE FROM problem_version WHERE id LIKE 'experimental-check-%' AND owner_id IN ("+owners+"); DELETE FROM generation_spec_draft WHERE owner_id IN ("+owners+"); DELETE FROM spring_session WHERE principal_name IN ('"+quoted+"'); DELETE FROM app_user WHERE username IN ('"+quoted+"'); COMMIT;")
            print('Synthetic drafts and accounts removed',flush=True)
        else:print('Active probe retained: '+key,flush=True)


if __name__=='__main__':main()
