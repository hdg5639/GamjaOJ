#!/usr/bin/env python3
"""Check new ordinary pool problems through authenticated production APIs and Runner.
Only this script's synthetic user/session/submissions are deleted; published problems stay.
"""
import argparse
import hashlib
import http.cookiejar
import importlib.util
import json
import os
from pathlib import Path
import secrets
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
spec=importlib.util.spec_from_file_location('dedicated_smoke',Path(__file__).with_name('smoke-dedicated-runner.py'))
helper=importlib.util.module_from_spec(spec);spec.loader.exec_module(helper)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directories',nargs='+',type=Path)
    parser.add_argument('--language',choices=['ALL','JAVA','CPP','PYTHON'],default='ALL')
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--external-manifest',type=Path,help='Check all 358 attributed profiles and submit selected Java references')
    args=parser.parse_args()
    # Validate every local candidate before creating a user or submitting any job.
    candidates=[]
    languages=('JAVA','CPP','PYTHON') if args.language=='ALL' else (args.language,)
    imported={}
    if args.external_manifest:
        rows=json.loads(args.external_manifest.read_text())
        imported={row['version']:row for row in rows}
        assert len(rows)==len(imported)==358 and languages==('JAVA',)
    for directory in args.directories:
        package=json.loads((directory/'package.json').read_text())
        meta=json.loads((directory/'metadata.json').read_text())
        sources={}
        if imported:
            row=imported[package['version']];layer=row['thinking']['layer']
            meta=meta|dict(category=row['category'],difficulty='EASY' if layer<=2 else 'MEDIUM' if layer<=5 else 'HARD' if layer<=7 else 'EXPERT')
        for language in languages:
            filename={'JAVA':'Main.java','CPP':'Main.cpp','PYTHON':'Main.py'}[language]
            sources[language]=(directory/'reference.java' if imported else directory/'solutions'/language.lower()/filename).read_text()
        candidates.append((package,meta,sources))
    invitation=helper.ssh(os.environ['GAMJAOJ_APP_SSH_TARGET'],"sed -n 's/^INVITE_CODE=//p' ~/gamjaoj/web/.env")
    base=helper.ssh(os.environ['GAMJAOJ_APP_SSH_TARGET'],"sed -n 's/^PUBLIC_BASE_URL=//p' ~/gamjaoj/web/.env").rstrip('/')
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
        try:r=client.open(urllib.request.Request(base+path,data=body,headers=headers,method=method),timeout=20)
        except urllib.error.HTTPError as e:r=e
        with r:
            raw=r.read();return r.status,json.loads(raw) if raw else None
    username='pool_'+secrets.token_hex(5);password=secrets.token_urlsafe(24)
    evidence=[]
    try:
        assert call('/api/auth/signup','POST',dict(username=username,password=password,nickname='기본 문제 검증',inviteCode=invitation))[0]==201
        assert call('/api/auth/login','POST',dict(username=username,password=password),form=True)[0]==204
        status,catalog=call('/api/problems');assert status==200
        by_id={p['version']:p for p in catalog}
        if imported:
            for version,row in imported.items():
                p=by_id[version]
                assert p['category']==row['category'] and p['shared'] and not p['mine'] and p['submissionsEnabled']
                assert p['thinking']['source']=='CURATED_ESTIMATE'
                assert all(p['thinking'][key]==value for key,value in row['thinking'].items())
                assert not any(k in p for k in ('tests','package','reference','teaching','generator','seed'))
            print('PASS all 358 public categories, exact thinking profiles, access and hidden-data privacy',flush=True)
        for package,meta,sources in candidates:
            version=package['version']
            p=by_id[version]
            assert p['category']==meta['category'] and p['difficulty']==meta['difficulty']
            assert p['shared'] and not p['mine'] and p['submissionsEnabled']
            assert p['statement']==package['statement']
            assert [(x['input'],x['output']) for x in p['examples']]==[(x['input'],x['output']) for x in package['samples']]
            assert not p['generated']
            assert not any(k in p for k in ('tests','package','reference','teaching','generator','seed'))
            for language in languages:
                source=sources[language]
                payload=dict(problemVersion=version,source=source,language=language);key=str(uuid.uuid4())
                status,saved=call('/api/submissions','POST',payload,key);assert status==202,saved
                job=str(uuid.UUID(saved['id']))
                assert call('/api/submissions','POST',payload,key)[1]['id']==job
                deadline=time.monotonic()+120
                while time.monotonic()<deadline:
                    status,done=call('/api/submissions/'+job);assert status==200,done
                    if done['status']=='FINISHED':break
                    time.sleep(1)
                else:raise AssertionError('Submission did not complete')
                assert done['verdict']=='AC' and done['memoryPeakBytes']>0,done
                assert len(done['tests'])==len(package['tests'])+len(package.get('generated',{}).get('tests',[]))
                assert all(set(t)=={'number','verdict','wallMs','memoryPeakBytes'} and t['verdict']=='AC' for t in done['tests'])
                stored=json.loads(helper.sql(f"SELECT json_build_object('hash',j.result_json::json->>'problem_sha256','environment',a.execution_environment_json::json,'report',j.result_json::json,'worker',j.worker_id::text) FROM submission s JOIN judge_job j ON j.submission_id=s.id JOIN judge_attempt a ON a.submission_id=s.id AND a.attempt=j.attempt WHERE s.id='{job}'"))
                expected=hashlib.sha256(json.dumps(package,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()).hexdigest()
                assert stored['hash']==expected
                assert stored['report']['runner_environment']['contract']==stored['environment']
                evidence.append(dict(version=version,language=language,submission=job,memoryPeakBytes=done['memoryPeakBytes'],wallMs=done['wallMs'],worker=stored['worker'],packageSha256=expected))
                print('PASS',version,language,'ordinary API/public privacy/frozen package/replay/AC/memory',flush=True)
        args.output.parent.mkdir(parents=True,exist_ok=True)
        args.output.write_text(json.dumps({'status':'PASS','submissions':evidence},ensure_ascii=False,indent=2)+'\n')
    finally:
        helper.sql(f"DELETE FROM spring_session WHERE principal_name='{username}'; DELETE FROM app_user WHERE username='{username}';")


if __name__=='__main__':main()
