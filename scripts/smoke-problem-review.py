"""Verify review-hold on synthetic private records; no model or Runner calls."""
import os
import http.cookiejar
import json
import secrets
import subprocess
import urllib.request
import urllib.parse
import urllib.error
import uuid


def ssh(command, data=None):
    return subprocess.run(['ssh','-o','BatchMode=yes',os.environ['GAMJAOJ_APP_SSH_TARGET'],command],input=data,text=True,capture_output=True,check=True).stdout.strip()


def sql(query):
    return ssh('docker exec -i gamjaoj-postgres-1 psql -U gamjaoj -d gamjaoj -At -v ON_ERROR_STOP=1',query)


def main():
    base=ssh("sed -n 's/^PUBLIC_BASE_URL=//p' ~/gamjaoj/web/.env")
    invite=ssh("sed -n 's/^INVITE_CODE=//p' ~/gamjaoj/web/.env")
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
                try:value=json.loads(raw) if raw else None
                except ValueError:raise AssertionError(f'Non-JSON response: HTTP {response.status} at {path}')
                return response.status,value
        return call
    names=['holdprobe_'+secrets.token_hex(5) for _ in range(2)]
    a,b=browser(),browser();draft=str(uuid.uuid4());submission=str(uuid.uuid4());key=str(uuid.uuid4())
    version='experimental-check-'+draft
    quoted="','".join(names)
    try:
        for call,name in zip((a,b),names):
            password=secrets.token_urlsafe(24)
            assert call('/api/auth/signup','POST',dict(username=name,password=password,nickname='보류 검증',inviteCode=invite))[0]==201
            assert call('/api/auth/login','POST',dict(username=name,password=password),form=True)[0]==204
        owner=a('/api/me')[1]['id']
        # Published fixture only: does not exercise or claim to prove generation validation.
        sql(f"""BEGIN;
INSERT INTO generation_spec_draft (id,owner_id,request_text,status,model,effort) VALUES ('{draft}','{owner}','review fixture','PUBLISHED','fixture','low');
INSERT INTO problem_version (id,package_json,package_sha256,runtime_image,runner_policy,ready,owner_id)
SELECT '{version}',package_json,package_sha256,runtime_image,runner_policy,true,'{owner}' FROM problem_version WHERE id='total-v1';
INSERT INTO submission (id,user_id,problem_version,source_code,source_sha256,idempotency_key,runtime_image,runner_policy)
SELECT '{submission}','{owner}','{version}','class Main {{}}',repeat('0',64),'{key}',runtime_image,runner_policy FROM problem_version WHERE id='{version}';
INSERT INTO judge_job (submission_id,status,verdict,result_json,result_sha256,finished_at) VALUES ('{submission}','FINISHED','WA','{{}}',repeat('0',64),CURRENT_TIMESTAMP);
COMMIT;""")
        path=f'/api/problems/{version}/review-hold'
        assert b(path,'POST',{'reason':'foreign'})[0]==404
        assert a(path,'POST',{'reason':'합성 문제의 예제 검토'})[0]==200
        assert a(path,'POST',{'reason':'retry'})[1]['reason']=='합성 문제의 예제 검토'
        problem=next(p for p in a('/api/problems')[1] if p['version']==version)
        assert problem['problemHeld'] and not problem['submissionsEnabled']
        assert not any(p['version']==version for p in b('/api/problems')[1])
        record=a('/api/submissions/'+submission)[1]
        assert record['problemHeld'] and record['verdict']=='WA' and record['source']=='class Main {}'
        assert b('/api/submissions/'+submission)[0]==404
        for route,body in [('/api/submissions',{'problemVersion':version,'source':'class Main {}'}),('/api/runs',{'problemVersion':version,'source':'class Main {}','input':''}),('/api/training-sessions',{'problemVersion':version,'goal':''})]:
            assert a(route,'POST',body,str(uuid.uuid4()))[0]==404
        assert a('/api/ai/tasks','POST',{'submissionId':submission,'kind':'ANALYSIS','question':'','strong':False})[0]==409
        assert a(f'/api/generation/spec-drafts/{draft}')[1]['problemHeld']
        assert a(f'/api/problems/{version}/teaching')[0]==404
        print('PASS: owner-only hold, replay, historical WA/source, new work gates and account isolation; no model calls')
    finally:
        sql(f"""BEGIN;
DELETE FROM submission WHERE id='{submission}';
DELETE FROM generation_spec_draft WHERE id='{draft}';
DELETE FROM problem_version WHERE id='{version}';
DELETE FROM spring_session WHERE principal_name IN ('{quoted}');
DELETE FROM app_user WHERE username IN ('{quoted}');
COMMIT;""")


if __name__=='__main__':
    main()
