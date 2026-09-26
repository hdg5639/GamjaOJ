"""Verify confirmed feedback to real Runner AC to learner reflection. Source analysis is a fixture; no model calls."""
import os
import http.cookiejar
import json
import secrets
import subprocess
import urllib.request
import urllib.parse
import urllib.error
import uuid
import hashlib
import time
from pathlib import Path


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
    names=['followprobe_'+secrets.token_hex(5) for _ in range(2)]
    a,b=browser(),browser();job=str(uuid.uuid4());old=str(uuid.uuid4());analysis=str(uuid.uuid4())
    target='generated-'+job+'-r0';quoted="','".join(names)
    users="SELECT id FROM app_user WHERE username IN ('"+quoted+"')"
    def literal(value):return "'"+value.replace("'","''")+"'"
    try:
        for call,name in zip((a,b),names):
            password=secrets.token_urlsafe(24)
            assert call('/api/auth/signup','POST',dict(username=name,password=password,nickname='다음 훈련 검증',inviteCode=invite))[0]==201
            assert call('/api/auth/login','POST',dict(username=name,password=password),form=True)[0]==204
        owner=a('/api/me')[1]['id']
        package=json.loads(Path('problems/total-v1.json').read_text());package['version']=target
        package['title']='[검증용] 다음 훈련 합계'
        packed=json.dumps(package,ensure_ascii=False,sort_keys=True,separators=(',',':'))
        feedback=json.dumps(dict(summary='합계 범위 확인',observations=['합산 시 정수 범위 확인'],nextSteps=['누적값을 long으로 관리하기'],uncertainty='합성 분석 fixture'),ensure_ascii=False)
        sql(f"""BEGIN;
INSERT INTO generation_job(id,owner_id,template_id,status,model,effort,focus) VALUES ('{job}','{owner}','sequence-sum-v1','READY','fixture','low','overflow');
INSERT INTO problem_version(id,package_json,package_sha256,runtime_image,runner_policy,ready,owner_id)
SELECT '{target}',{literal(packed)},'{hashlib.sha256(packed.encode()).hexdigest()}',runtime_image,runner_policy,true,'{owner}' FROM problem_version WHERE id='total-v1';
INSERT INTO submission(id,user_id,problem_version,source_code,source_sha256,idempotency_key,runtime_image,runner_policy)
SELECT '{old}','{owner}','total-v1','class Main {{}}',repeat('0',64),'{uuid.uuid4()}',runtime_image,runner_policy FROM problem_version WHERE id='total-v1';
INSERT INTO judge_job(submission_id,status,verdict,result_json,result_sha256,finished_at) VALUES ('{old}','FINISHED','WA','{{}}',repeat('0',64),CURRENT_TIMESTAMP);
INSERT INTO ai_task(id,user_id,submission_id,kind,cache_key,settings_json,input_json,status,result_json)
VALUES ('{analysis}','{owner}','{old}','ANALYSIS',repeat('1',64),'{{}}','{{}}','COMPLETED',{literal(feedback)});
COMMIT;""")
        assert b('/api/practice-followups/options?analysisId='+analysis)[0]==404
        body={'analysisId':analysis,'stepIndex':0,'focus':'overflow'}
        status,goal=a('/api/practice-followups','POST',body);assert status==200,(status,goal)
        goal_id=goal['id'];path='/api/practice-followups/'+goal_id
        assert [p['version'] for p in goal['candidates']]==[target]
        assert a('/api/practice-followups','POST',body)[1]['id']==goal_id
        assert b(path)[0]==404
        assert a(path+'/reflect','POST',{'usedHelp':False})[0]==409
        status,started=a(path+'/start','POST',{'problemVersion':target});assert status==200,(status,started)
        assert a(path+'/start','POST',{'problemVersion':target})[1]['sessionId']==started['sessionId']
        # Suppress automatic paid end-of-session analysis for this synthetic test only.
        sql(f"UPDATE training_session SET analysis_checked=true WHERE id='{started['sessionId']}';")
        wrong='public class Main { public static void main(String[] args) { System.out.println(-123456789); } }'
        status,failed=a('/api/submissions','POST',{'problemVersion':target,'source':wrong,'sessionId':started['sessionId']},str(uuid.uuid4()))
        assert status==202,(status,failed)
        for _ in range(60):
            _,failed=a('/api/submissions/'+failed['id'])
            if failed['status']=='FINISHED':break
            time.sleep(2)
        assert failed['verdict']=='WA',failed
        first_session=started['sessionId']
        assert a('/api/training-sessions/'+first_session+'/end','POST',{'note':'실제 WA 뒤 재연습'})[0]==200
        assert a(path)[1]['status']=='NEEDS_PRACTICE'
        status,repeated=a(path+'/repeat','POST',{'round':1});assert status==200,(status,repeated)
        assert repeated['round']==2 and repeated['attempts'][0]['sessionId']==first_session
        assert a(path+'/repeat','POST',{'round':1})[1]==repeated
        assert b(path+'/repeat','POST',{'round':1})[0]==404
        assert a(path+'/start','POST',{'problemVersion':target,'round':1})[0]==409
        status,started=a(path+'/start','POST',{'problemVersion':target,'round':2});assert status==200,(status,started)
        assert started['sessionId']!=first_session
        sql(f"UPDATE training_session SET analysis_checked=true WHERE id='{started['sessionId']}';")
        source=Path('examples/total/Main.java').read_text()
        status,submission=a('/api/submissions','POST',{'problemVersion':target,'source':source,'sessionId':started['sessionId']},str(uuid.uuid4()))
        assert status==202,(status,submission)
        for _ in range(60):
            _,submission=a('/api/submissions/'+submission['id'])
            if submission['status']=='FINISHED':break
            time.sleep(2)
        assert submission['verdict']=='AC',submission
        assert a('/api/training-sessions/'+started['sessionId']+'/end','POST',{'note':'합성 목표와 실제 Java8 AC 연결 확인'})[0]==200
        assert a(path)[1]['status']=='AWAITING_REFLECTION'
        status,reflected=a(path+'/reflect','POST',{'usedHelp':False,'round':2});assert status==200,(status,reflected)
        assert reflected['status']=='SELF_REPORTED_UNASSISTED_AC' and reflected['reviewedSubmissionId']==submission['id']
        assert a(path+'/reflect','POST',{'usedHelp':False,'round':2})[1]==reflected
        assert a(path+'/reflect','POST',{'usedHelp':True,'round':2})[0]==409
        assert b(path+'/reflect','POST',{'usedHelp':False})[0]==404
        assert a(path+'/reflect','POST',{'usedHelp':False,'round':1})[0]==409
        assert reflected['attempts'][0]['status']=='NEEDS_PRACTICE'
        assert a('/api/submissions/'+failed['id'])[1]['verdict']=='WA'
        assert a('/api/practice-followups')[1][0]['status']==reflected['status']
        assert sql(f"SELECT count(*) FROM ai_task WHERE user_id='{owner}';")=='1'
        assert sql(f"SELECT count(*) FROM ai_attempt WHERE task_id='{analysis}';")=='0'
        print('PASS: fixture feedback -> confirmed goal -> private candidate -> real Runner Java8 WA -> repeat -> real AC -> persisted self-report; replay and account isolation; no model calls')
    finally:
        sql(f"""BEGIN;
DELETE FROM practice_followup WHERE user_id IN ({users});
DELETE FROM submission WHERE user_id IN ({users});
DELETE FROM training_session WHERE user_id IN ({users});
DELETE FROM generation_job WHERE id='{job}';
DELETE FROM problem_version WHERE id='{target}';
DELETE FROM spring_session WHERE principal_name IN ('{quoted}');
DELETE FROM app_user WHERE username IN ('{quoted}');
COMMIT;""")


if __name__=='__main__':main()
