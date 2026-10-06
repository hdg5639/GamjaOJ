"""Explicit live Codex -> Runner -> private problem -> submission smoke."""
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
from smoke_generation_cleanup import cleanup_sql, active_checks_sql, resource_evidence_sql

def ssh(command, data=None):
    return subprocess.run(['ssh','-o','BatchMode=yes',os.environ['GAMJAOJ_APP_SSH_TARGET'],command],input=data,text=True,capture_output=True,check=True).stdout.strip()

def sql(query):
    return ssh('docker exec -i gamjaoj-postgres-1 psql -U gamjaoj -d gamjaoj -At -v ON_ERROR_STOP=1',query)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--allow-operator',action='store_true',help='Permit synthetic accounts when the deployment explicitly grants operator access to all users')
    parser.add_argument('--execute',action='store_true',help='Authorize real ChatGPT-managed Codex generation')
    parser.add_argument('--template',choices=['sequence-sum-v1','parentheses-v1'],default='sequence-sum-v1')
    parser.add_argument('--category',choices=['sequences','strings','graphs'],help='Use category selection with --tags')
    parser.add_argument('--count',type=int,choices=[1,2],default=1,help='Two consecutive generations verify theme diversity and private structure reuse')
    parser.add_argument('--tags',help='Comma-separated learning tags, using the category API instead of the legacy template request')
    parser.add_argument('--recommend',action='store_true',help='Select a supported recommendation before generation; requires --tags')
    args=parser.parse_args()
    if args.recommend and not args.tags:parser.error('--recommend requires --tags')
    if not args.execute:
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
    names=['genprobe_'+secrets.token_hex(5) for _ in range(2)]
    a,b=browser(),browser()
    key=str(uuid.uuid4()); terminal=False
    try:
        for call,name in zip((a,b),names):
            password=secrets.token_urlsafe(24)
            assert call('/api/auth/signup','POST',dict(username=name,password=password,nickname='생성 검증',inviteCode=invitation))[0]==201
            assert call('/api/auth/login','POST',dict(username=name,password=password),form=True)[0]==204
            assert args.allow_operator or call('/api/ai/status')[1]['operator'] is False
        recommendation=None
        if args.recommend:
            category=args.category or ('sequences' if args.template=='sequence-sum-v1' else 'strings')
            required=args.tags.split(',')
            status,result=a('/api/generation/recommendations?'+urllib.parse.urlencode({'category':category,'tags':args.tags}))
            assert status==200,(status,result)
            recommendation=result['suggestions'][0]
            assert set(required).issubset(recommendation['tags'])
            assert recommendation['recentCount']==0
            assert a('/api/generation')[1]==[], 'Recommendation must not create a job'
            args.tags=','.join(recommendation['tags'])
        themeHistory=[];stories=[];structures=[];authoring=[]
        for index in range(args.count):
            if index:key=str(uuid.uuid4())
            request={'category':args.category or ('sequences' if args.template=='sequence-sum-v1' else 'strings'),'tags':args.tags.split(',')} if args.tags else {'template':args.template,'focus':'edge-cases'}
            status,job=a('/api/generation','POST',request,key=key)
            assert status==200,(status,job)
            assert b('/api/generation')[1]==[]
            assert b('/api/generation/'+key+'/review','POST',{'artifactHash':'wrong','approve':True})[0]==404
            last=None
            for _ in range(300):
                status,jobs=a('/api/generation')
                if status in (502,503,504):
                    time.sleep(4);continue
                assert status==200,(status,jobs)
                job=next(j for j in jobs if j['id']==key)
                if job['status']!=last:
                    last=job['status'];print(last,flush=True)
                if last in ('READY','FAILED','NEEDS_AUTH','NEEDS_REVIEW','THEME_FAILED'):
                    terminal=True;break
                time.sleep(4)
            assert job['status']=='READY',(job['status'],job.get('error'))
            if recommendation:assert job['preview']['templateId']==recommendation['template'],job['preview']['templateId']
            assert job['theme']['status']=='COMPLETED',job['theme']
            themeHistory.append(job['theme']);stories.append({k:job['artifacts'][k] for k in ('title','context')})
            structures.append(job['preview']['structure'])
            if args.tags:assert set(args.tags.split(',')).issubset(structures[-1]['tags']),structures[-1]
            assert structures[-1]['reused']==(index>0),structures
            authoring.append(json.loads(sql("SELECT result_json::jsonb->'usage' FROM generation_attempt WHERE job_id='"+key+"' AND revision=0")))
            if index:
                assert set(authoring[-1]['reused'])=={'reference','generator','inputValidator','oracle'},authoring[-1]
                assert authoring[-1]['oracle'] is None,authoring[-1]
            if len(themeHistory)>1:assert themeHistory[-1]['domain']!=themeHistory[-2]['domain'],themeHistory
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
        evidence={'contract':{k:job['preview'].get(k) for k in ('templateId','contractFamily','recipe','sample')},'structures':structures,'authoring':authoring,'stories':stories,'themes':themeHistory,'job':key,'status':job['status'],'validation':job['validation'],'submissionVerdict':submission['verdict'],'privateAccess':'PASS'}
        if recommendation:evidence['recommendation']=recommendation
        evidence['resources']=json.loads(sql(resource_evidence_sql(key)))
        evidence['themeUsage']=json.loads(sql("SELECT json_build_object('calls',count(*),'actualUsd',sum(a.actual_usd),'unsettled',count(*) FILTER (WHERE a.actual_usd IS NULL)) FROM ai_attempt a JOIN ai_task t ON t.id=a.task_id JOIN app_user u ON u.id=t.user_id WHERE t.kind='THEME' AND u.username IN ('"+"','".join(names)+"')"))
        evidence['generationUsage']=json.loads(sql("SELECT result_json::jsonb->'usage' FROM generation_attempt WHERE job_id='"+key+"' AND revision="+str(job['revision'])))
        # Record actual production queue timing and compiler reuse before deleting fixtures.
        evidence['runnerPerformance']=json.loads(sql("SELECT json_build_object("
            "'executions',count(*),'compileCacheHits',count(*) FILTER (WHERE (j.result_json::jsonb #>> '{compile,cache_hit}')='true'),"
            "'validationSeconds',round(EXTRACT(EPOCH FROM (max(j.finished_at)-min(j.created_at)))::numeric,2)) "
            "FROM generation_execution e JOIN judge_job j ON j.submission_id=e.submission_id WHERE e.job_id='"+key+"' AND e.revision="+str(job['revision'])))
        Path('.state').mkdir(exist_ok=True)
        Path('.state/personal-generation-smoke.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2))
        print('PASS: generation, real Runner gates, private access and AC submission',flush=True)
        print(json.dumps(evidence['runnerPerformance']),flush=True)
    finally:
        # Never remove work while a worker may still hold a lease.
        active=sql("SELECT count(*) FROM generation_job WHERE owner_id IN (SELECT id FROM app_user WHERE username IN ('"+"','".join(names)+"')) AND status IN ('QUEUED','GENERATING','AWAITING_REVIEW','VALIDATING')")
        pending=sql("SELECT count(*) FROM judge_job j JOIN submission s ON s.id=j.submission_id JOIN app_user u ON u.id=s.user_id WHERE u.username IN ('"+"','".join(names)+"') AND j.status<>'FINISHED'")
        if active=='0' and pending=='0' and sql(active_checks_sql("SELECT id FROM app_user WHERE username IN ('"+"','".join(names)+"')"))=='0':
            quoted="','".join(names)
            ownJobs="SELECT id FROM generation_job WHERE owner_id IN (SELECT id FROM app_user WHERE username IN ('"+quoted+"'))"
            sql("BEGIN; "+cleanup_sql("SELECT id FROM app_user WHERE username IN ('"+quoted+"')")+"DELETE FROM generation_execution WHERE job_id IN ("+ownJobs+"); "
                "DELETE FROM submission WHERE user_id IN (SELECT id FROM app_user WHERE username IN ('"+quoted+"')); "
                "DELETE FROM generation_attempt WHERE job_id IN ("+ownJobs+"); "
                "DELETE FROM generation_job WHERE id IN ("+ownJobs+"); "
                "DELETE FROM problem_version WHERE owner_id IN (SELECT id FROM app_user WHERE username IN ('"+quoted+"')); "
                "DELETE FROM spring_session WHERE principal_name IN ('"+quoted+"'); "
                "DELETE FROM app_user WHERE username IN ('"+quoted+"'); COMMIT;")
            print('Synthetic accounts and problems removed',flush=True)
        else:print('Active probe retained for diagnosis: '+key,flush=True)

if __name__=='__main__':main()
