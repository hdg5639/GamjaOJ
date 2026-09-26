"""Synthetic lineage + real HTTP/DB hold, one real Runner submission. No model calls."""
import os
import hashlib
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


def ssh(command,data=None):
    return subprocess.run(['ssh','-o','BatchMode=yes',os.environ['GAMJAOJ_APP_SSH_TARGET'],command],input=data,text=True,capture_output=True,check=True,timeout=30).stdout.strip()

def sql(statement):
    return ssh('docker exec -i gamjaoj-postgres-1 psql -U gamjaoj -d gamjaoj -At -v ON_ERROR_STOP=1',statement)

def quote(value):return "'"+str(value).replace("'","''")+"'"

def main():
    base=ssh("sed -n 's/^PUBLIC_BASE_URL=//p' ~/gamjaoj/web/.env")
    invitation=ssh("sed -n 's/^INVITE_CODE=//p' ~/gamjaoj/web/.env")
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
        try:response=client.open(urllib.request.Request(base+path,data=body,headers=headers,method=method),timeout=20)
        except urllib.error.HTTPError as error:response=error
        with response:
            raw=response.read();return response.status,json.loads(raw) if raw else None
    username='ledger_'+secrets.token_hex(5);password=secrets.token_urlsafe(24)
    jobs=[str(uuid.uuid4()) for _ in range(4)];evidence=[str(uuid.uuid4()) for _ in range(3)]
    versions=['generated-'+job+'-r0' for job in jobs]
    owner=None
    try:
        assert call('/api/auth/signup','POST',dict(username=username,password=password,nickname='근거 보류 검사',inviteCode=invitation))[0]==201
        owner=sql('SELECT id FROM app_user WHERE username='+quote(username))
        assert call('/api/auth/login','POST',dict(username=username,password=password),form=True)[0]==204
        package=json.loads(sql("SELECT package_json FROM problem_version WHERE id='sum-v1'"))
        statements=['BEGIN;']
        snapshots=[]
        for index,job in enumerate(jobs):
            ready=index<3
            reuse=None if index==0 else json.dumps(dict(sourceJobId=jobs[index-1]))
            statements.append(f"INSERT INTO generation_job(id,owner_id,template_id,status,model,effort,structure_reuse_json) VALUES ({quote(job)},{quote(owner)},'sequence-sum-v1',{quote('READY' if ready else 'VALIDATING')},'synthetic','low',{quote(reuse) if reuse else 'NULL'});")
            plan=dict(package,version=versions[index],title='합성 근거 보류 '+str(index));raw=json.dumps(plan,sort_keys=True,ensure_ascii=False,separators=(',',':'))
            statements.append(f"INSERT INTO problem_version(id,owner_id,package_json,package_sha256,runtime_image,runner_policy,ready) SELECT {quote(versions[index])},{quote(owner)},{quote(raw)},{quote(hashlib.sha256(raw.encode()).hexdigest())},runtime_image,runner_policy,{str(ready).lower()} FROM problem_version WHERE id='sum-v1';")
            if ready:
                snapshot=json.dumps(dict(fixture=True,job=job));digest=hashlib.sha256(snapshot.encode()).hexdigest();snapshots.append(digest)
                statements.append(f"INSERT INTO generation_evidence(id,job_id,revision,snapshot_json,snapshot_sha256) VALUES ({quote(evidence[index])},{quote(job)},0,{quote(snapshot)},{quote(digest)});")
            if index:
                statements.append(f"INSERT INTO generation_dependency(job_id,source_job_id,source_revision,source_evidence_id,source_evidence_sha256) VALUES ({quote(job)},{quote(jobs[index-1])},0,{quote(evidence[index-1])},{quote(snapshots[index-1])});")
        sql(''.join(statements)+'COMMIT;')
        key=str(uuid.uuid4());request=dict(problemVersion=versions[1],source=Path('examples/Main.java').read_text())
        status,saved=call('/api/submissions','POST',request,key);assert status==202,(status,saved)
        deadline=time.monotonic()+90
        while time.monotonic()<deadline:
            status,done=call('/api/submissions/'+saved['id'])
            if done['status']=='FINISHED':break
            time.sleep(1)
        assert done['verdict']=='AC',done
        assert call('/api/problems/'+versions[0]+'/review-hold','POST',dict(reason='합성 공유 구조 오류'))[0]==200
        assert call('/api/problems/'+versions[0]+'/review-hold','POST',dict(reason='재전송'))[1]['reason']=='합성 공유 구조 오류'
        listed=call('/api/generation')[1]
        assert all(row['problemHeld'] for row in listed)
        assert next(row for row in listed if row['id']==jobs[3])['status']=='NEEDS_REVIEW'
        assert sql(f"SELECT count(*) FROM generation_evidence_revocation r JOIN generation_evidence e ON e.id=r.evidence_id JOIN generation_job g ON g.id=e.job_id WHERE g.owner_id={quote(owner)}")=='3'
        for index in range(3):
            assert sql(f"SELECT snapshot_sha256 FROM generation_evidence WHERE id={quote(evidence[index])}")==snapshots[index]
        assert call('/api/submissions','POST',request,str(uuid.uuid4()))[0] in (404,409)
        assert call('/api/training-sessions','POST',dict(problemVersion=versions[1],goal='blocked'),str(uuid.uuid4()))[0] in (404,409)
        assert call('/api/submissions','POST',request,key)[1]['id']==saved['id']
        history=call('/api/submissions/'+saved['id'])[1];assert history['verdict']=='AC' and history['problemHeld']
        assert sql(f"SELECT ready FROM problem_version WHERE id={quote(versions[3])}")=='f'
        Path('.state').mkdir(exist_ok=True)
        Path('.state/evidence-hold-live.json').write_text(json.dumps(dict(kind='synthetic-lineage-real-http-db-runner',revoked=3,heldProblems=4,pendingBlocked=True,historyPreserved=True,submissionVerdict='AC')))
        print('PASS: transitive three-evidence/four-problem hold; pending publication blocked; real AC history/replay preserved; new submit/training denied')
    finally:
        if owner:
            pending=sql(f"SELECT count(*) FROM judge_job j JOIN submission s ON s.id=j.submission_id WHERE s.user_id={quote(owner)} AND j.status<>'FINISHED'")
            if pending!='0':raise RuntimeError('Active fixture preserved: '+username)
            sql(f"BEGIN; DELETE FROM submission WHERE user_id={quote(owner)}; DELETE FROM generation_job WHERE owner_id={quote(owner)}; DELETE FROM problem_version WHERE owner_id={quote(owner)}; DELETE FROM spring_session WHERE principal_name={quote(username)}; DELETE FROM app_user WHERE id={quote(owner)}; COMMIT;")
            print('Synthetic lineage/account cleaned')

if __name__=='__main__':main()
