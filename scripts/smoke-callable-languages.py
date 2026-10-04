"""Actual HTTP/PostgreSQL/worker smoke; only synthetic account/problem, no paid AI."""
import hashlib, importlib.util, json, os, secrets, time, uuid
from pathlib import Path
spec=importlib.util.spec_from_file_location('exam_smoke',Path(__file__).with_name('smoke-exam-diagnostics.py'));helper=importlib.util.module_from_spec(spec);spec.loader.exec_module(helper)
Browser,ssh,sql=helper.Browser,helper.ssh,helper.sql

def main():
 target=os.environ['GAMJAOJ_APP_SSH_TARGET'];base=ssh(target,"sed -n 's/^PUBLIC_BASE_URL=//p' ~/gamjaoj/web/.env").rstrip('/');invite=ssh(target,"sed -n 's/^INVITE_CODE=//p' ~/gamjaoj/web/.env")
 name='callable_'+secrets.token_hex(5);version=name+'-v1';client=Browser(base);password=secrets.token_urlsafe(24);jobs=[]
 def finish(result,custom=False):
  deadline=time.monotonic()+150
  while time.monotonic()<deadline:
   status,result=client.call(('/api/runs/' if custom else '/api/submissions/')+result['id']);assert status==200,(status,result)
   if result['status']=='FINISHED':return result
   time.sleep(1)
  raise AssertionError('Runner completion timeout: '+result['id'])
 try:
  assert client.call('/api/auth/signup','POST',dict(username=name,password=password,nickname='호출형 검증',inviteCode=invite))[0]==201
  assert client.call('/api/auth/login','POST',dict(username=name,password=password),form=True)[0]==204
  bundle=json.loads(Path('backend/target/native-callable-fixtures/single-JAVA.json').read_text());profile=json.loads(Path('runner/languages.json').read_text())['JAVA']
  pack=dict(version=version,title='비공개 호출형 검증',statement='검증용 문제',output_policy='TOKEN_EXACT',api=bundle,tests=[dict(id='sample',input='[[["solution"]],[["solution"]]]',output='1\n1\n')]);raw=json.dumps(pack,ensure_ascii=False,separators=(',',':'));quote=lambda s:"'"+s.replace("'","''")+"'"
  sql('INSERT INTO problem_version (id,package_json,package_sha256,runtime_image,runner_policy,owner_id) SELECT '+','.join(map(quote,[version,raw,hashlib.sha256(raw.encode()).hexdigest(),profile['image'],profile['policy']]))+',id FROM app_user WHERE username='+quote(name))
  public=next(p for p in client.call('/api/problems')[1] if p['version']==version);assert [l['id'] for l in public['languages']]==['JAVA','CPP','PYTHON']
  sources={'JAVA':'public class UserSolution {int n=0;public int solution(){return ++n;}}','CPP':'class UserSolution {int n=0;public:int solution(){return ++n;}};','PYTHON':'class UserSolution:\n    def __init__(self): self.n=0\n    def solution(self):\n        self.n+=1\n        return self.n\n'}
  for language,source in sources.items():
   body=dict(problemVersion=version,source=source,language=language,input=pack['tests'][0]['input']);key=str(uuid.uuid4());status,run=client.call('/api/runs','POST',body,key);assert status==202,(status,run)
   assert client.call('/api/runs','POST',body,key)[1]['id']==run['id'];done=finish(run,True);assert done['verdict']=='OK' and done['stdout']=='1\n1\n',(language,done['verdict']);jobs.append(done['id'])
   body.pop('input');key=str(uuid.uuid4());status,submission=client.call('/api/submissions','POST',body,key);assert status==202,(status,submission)
   assert client.call('/api/submissions','POST',body,key)[1]['id']==submission['id'];done=finish(submission);assert done['verdict']=='AC' and done['memoryPeakBytes']>0,(language,done['verdict']);jobs.append(done['id'])
   saved=json.loads(sql('SELECT callable_package FROM submission WHERE id='+quote(done['id'])));assert saved['callable']['format']==language+'_CALLABLE_V1'
   print('PASS live custom OK / formal AC / replay / memory / saved plan',language,flush=True)
  status,session=client.call('/api/diagnostics','POST',{'bankId':'exam-b-v2'},str(uuid.uuid4()));assert status==200,(status,session);q=session['current'];assert [l['id'] for l in q['languages']]==['JAVA','CPP','PYTHON']
  for language in ('CPP','PYTHON'):
   body=dict(problemVersion=q['problemVersion'],diagnosticItemId=q['itemId'],source=q['api']['languages'][language]['template'],language=language,input=q['examples'][0]['input'])
   status,run=client.call('/api/runs','POST',body,str(uuid.uuid4()));assert status==202,(status,run);done=finish(run,True);assert done['verdict']=='OK',(language,done['verdict']);jobs.append(done['id']);print('PASS live B diagnostic callable template/run',language,flush=True)
  after=client.call('/api/diagnostics/'+session['id'])[1];assert after['current']['itemId']==q['itemId'] and after['items'][0]['attempts']==0
  assert sql('SELECT count(*) FROM ai_task t JOIN app_user u ON u.id=t.user_id WHERE u.username='+quote(name))=='0'
  print('PASS no diagnostic attempts consumed, no AI; synthetic fixture cleanup',flush=True)
 finally:
  # Never delete an in-flight fixture/job: preserve it for investigation if the worker stalls.
  pending=sql("SELECT count(*) FROM judge_job j JOIN submission s ON s.id=j.submission_id JOIN app_user u ON u.id=s.user_id WHERE u.username='"+name+"' AND j.status<>'FINISHED'")
  if pending!='0': raise RuntimeError('Unfinished synthetic jobs preserved for '+name)
  sql("DELETE FROM submission WHERE user_id=(SELECT id FROM app_user WHERE username='"+name+"'); DELETE FROM problem_version WHERE id='"+version+"'; DELETE FROM spring_session WHERE principal_name='"+name+"'; DELETE FROM app_user WHERE username='"+name+"';")
if __name__=='__main__':main()
