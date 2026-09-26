#!/usr/bin/env python3
"""Two-account HTTPS sharing and dedicated Runner smoke; only synthetic data, no model calls."""
import os
import argparse
import importlib.util
import http.cookiejar
import hashlib
import json
from pathlib import Path
import secrets
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
spec=importlib.util.spec_from_file_location('helper',Path(__file__).with_name('smoke-dedicated-runner.py'))
helper=importlib.util.module_from_spec(spec);spec.loader.exec_module(helper)
ssh,sql=helper.ssh,helper.sql

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--language',choices=['JAVA','CPP','PYTHON'],default='JAVA');args=parser.parse_args()
    base=ssh(os.environ['GAMJAOJ_APP_SSH_TARGET'],"sed -n 's/^PUBLIC_BASE_URL=//p' ~/gamjaoj/web/.env").rstrip('/')
    invitation=ssh(os.environ['GAMJAOJ_APP_SSH_TARGET'],"sed -n 's/^INVITE_CODE=//p' ~/gamjaoj/web/.env")
    names=['catalog_'+secrets.token_hex(5) for _ in range(2)]
    version='catalog-smoke-'+str(uuid.uuid4())
    def client():
        opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        opener.addheaders=[('User-Agent','GamjaOJ-Smoke/1.0')]
        def call(path,method='GET',body=None,key=None,form=False):
            headers={}
            if method!='GET':
                _,csrf=call('/api/auth/csrf');headers[csrf['headerName']]=csrf['token']
            if key:headers['Idempotency-Key']=key
            if body is not None:
                headers['Content-Type']='application/x-www-form-urlencoded' if form else 'application/json'
                body=(urllib.parse.urlencode(body) if form else json.dumps(body)).encode()
            try:r=opener.open(urllib.request.Request(base+path,data=body,headers=headers,method=method),timeout=20)
            except urllib.error.HTTPError as e:r=e
            with r:
                raw=r.read();return r.status,json.loads(raw) if raw else None
        return call
    a,b=client(),client()
    try:
        for name,call in zip(names,[a,b]):
            password=secrets.token_urlsafe(24)
            assert call('/api/auth/signup','POST',dict(username=name,password=password,nickname='Catalog smoke',inviteCode=invitation))[0]==201
            assert call('/api/auth/login','POST',dict(username=name,password=password),form=True)[0]==204
        package=json.loads(sql("SELECT package_json FROM problem_version WHERE id='sum-v1'"))
        package['version']=version;package['title']='공유 검증용 두 수의 합'
        packed=json.dumps(package,ensure_ascii=False,sort_keys=True,separators=(',',':'))
        digest=hashlib.sha256(packed.encode()).hexdigest();quoted=packed.replace("'","''")
        sql(f"INSERT INTO problem_version(id,package_json,package_sha256,runtime_image,runner_policy,ready,owner_id) SELECT '{version}','{quoted}','{digest}',runtime_image,runner_policy,true,(SELECT id FROM app_user WHERE username='{names[0]}') FROM problem_version WHERE id='sum-v1'")
        settings=dict(shared=True,category='구현',tags=['입출력'],difficulty='EASY')
        endpoint='/api/problems/'+version+'/catalog-settings'
        assert not any(p['version']==version for p in b('/api/problems')[1])
        assert b(endpoint,'PUT',settings)[0]==404
        assert a(endpoint,'PUT',settings)[0]==200
        public=next(p for p in b('/api/problems')[1] if p['version']==version)
        assert public['shared'] and not public['mine'] and public['difficulty']=='EASY'
        assert public['solveStatus']=='UNATTEMPTED' and public['pendingSubmissions']==0
        assert 'tests' not in public and 'package_json' not in public
        assert b('/api/problems/'+version+'/teaching')[0]==200
        code=(Path(__file__).resolve().parents[1]/'examples/Main.java').read_text()
        if args.language=='CPP':code='#include <iostream>\nint main(){long long a,b;std::cin>>a>>b;std::cout<<a+b;}'
        if args.language=='PYTHON':code='a,b=map(int,input().split());print(a+b)'
        key=str(uuid.uuid4());body=dict(problemVersion=version,source=code,language=args.language)
        status,submitted=b('/api/submissions','POST',body,key);assert status==202
        assert b('/api/submissions','POST',body,key)[1]['id']==submitted['id']
        deadline=time.monotonic()+120
        while time.monotonic()<deadline:
            result=b('/api/submissions/'+submitted['id'])[1]
            if result['status']=='FINISHED':break
            time.sleep(1)
        else:raise AssertionError('Shared problem judging timed out')
        assert result['verdict']=='AC',result['verdict']
        assert result['language']==args.language and result['execution']['id']==args.language
        status,run=b('/api/runs','POST',{**body,'input':'17 25\n'},str(uuid.uuid4()));assert status==202
        deadline=time.monotonic()+120
        while time.monotonic()<deadline:
            run=b('/api/runs/'+run['id'])[1]
            if run['status']=='FINISHED':break
            time.sleep(1)
        assert run['verdict']=='OK' and run['language']==args.language
        assert run['stdout'].strip()=='42'
        failure_sources={
            'CPP':{'CE':'int main( {','TLE':'int main(){for(;;){asm volatile("");}}',
                   'MLE':'#include <cstdlib>\n#include <unistd.h>\nint main(){for(;;){volatile char *p=(char*)malloc(16*1024*1024);if(!p){sleep(30);return 1;}for(int i=0;i<16*1024*1024;i+=4096)p[i]=1;}}'},
            'PYTHON':{'CE':'return 1','TLE':'while True: pass','MLE':'a=[]\nwhile True:a.append(bytearray(16*1024*1024))'}}
        for expected,source in failure_sources.get(args.language,{}).items():
            status,failed=b('/api/submissions','POST',{**body,'source':source},str(uuid.uuid4()));assert status==202
            deadline=time.monotonic()+120
            while time.monotonic()<deadline:
                failed=b('/api/submissions/'+failed['id'])[1]
                if failed['status']=='FINISHED':break
                time.sleep(1)
            assert failed['verdict']==expected,(args.language,expected,failed['verdict'])
            print('PASS:',args.language,expected,'on shared problem',flush=True)
        progress=next(p for p in b('/api/problems')[1] if p['version']==version)
        assert progress['solveStatus']=='SOLVED' and progress['pendingSubmissions']==0
        assert next(p for p in a('/api/problems')[1] if p['version']==version)['solveStatus']=='UNATTEMPTED'
        assert a('/api/submissions/'+submitted['id'])[0]==404
        status,session=b('/api/training-sessions','POST',dict(problemVersion=version,goal='공개 문제 검증'),str(uuid.uuid4()));assert status==200
        assert b('/api/training-sessions/'+session['id']+'/end','POST',dict(note='smoke completed'))[0]==200
        assert a(endpoint,'PUT',{**settings,'shared':False})[0]==200
        assert not any(p['version']==version for p in b('/api/problems')[1])
        assert b('/api/submissions','POST',body,str(uuid.uuid4()))[0]==404
        assert b('/api/submissions','POST',body,key)[1]['id']==submitted['id']
        assert b('/api/problems/'+version+'/teaching')[0]==404
        assert sql(f"SELECT count(*) FROM ai_task t JOIN app_user u ON u.id=t.user_id WHERE u.username IN ('{names[0]}','{names[1]}')")=='0'
        print('PASS: two-account private/share/catalog/teaching/training, actual Runner AC and isolated solve status, owner-only settings and submission isolation, withdrawal with replay, zero AI tasks',flush=True)
    finally:
        # Delete the synthetic solver records before their problem, then remove both accounts.
        sql(f"DELETE FROM submission WHERE user_id IN (SELECT id FROM app_user WHERE username IN ('{names[0]}','{names[1]}')); DELETE FROM training_session WHERE user_id IN (SELECT id FROM app_user WHERE username IN ('{names[0]}','{names[1]}')); DELETE FROM problem_version WHERE id='{version}'; DELETE FROM spring_session WHERE principal_name IN ('{names[0]}','{names[1]}'); DELETE FROM app_user WHERE username IN ('{names[0]}','{names[1]}');")

if __name__=='__main__':main()
