#!/usr/bin/env python3
"""Released A/B HTTP/DB/Runner and private PNG admission check; cleans only synthetic accounts.
Never requests AI evaluation. Private references are read from the supplied candidate folder.
"""
import argparse,hashlib,http.cookiejar,importlib.util,json,os,secrets,time,urllib.request,urllib.error,urllib.parse,uuid
from pathlib import Path
spec=importlib.util.spec_from_file_location('runner_smoke',Path(__file__).with_name('smoke-dedicated-runner.py'));helper=importlib.util.module_from_spec(spec);spec.loader.exec_module(helper)
ssh,sql=helper.ssh,helper.sql
class Browser:
 def __init__(self,base):self.base=base;self.client=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
 def call(self,path,method='GET',body=None,key=None,form=False):
  headers={}
  if method!='GET':
   status,csrf=self.call('/api/auth/csrf');assert status==200;headers[csrf['headerName']]=csrf['token']
  if key:headers['Idempotency-Key']=key
  if body is not None:
   headers['Content-Type']='application/x-www-form-urlencoded' if form else 'application/json';body=(urllib.parse.urlencode(body) if form else json.dumps(body)).encode()
  try:r=self.client.open(urllib.request.Request(self.base+path,data=body,headers=headers,method=method),timeout=25)
  except urllib.error.HTTPError as e:r=e
  with r:
   raw=r.read();return r.status,json.loads(raw) if 'json' in r.headers.get('Content-Type','') and raw else raw

def main(folder):
 target=os.environ['GAMJAOJ_APP_SSH_TARGET'];base=ssh(target,"sed -n 's/^PUBLIC_BASE_URL=//p' ~/gamjaoj/web/.env").rstrip('/');invitation=ssh(target,"sed -n 's/^INVITE_CODE=//p' ~/gamjaoj/web/.env")
 banks={b['id']:b for b in [json.loads(p.read_text()) for p in folder.glob('*.json')]};assert len(banks)==8
 names=['exam_probe_'+secrets.token_hex(5) for _ in range(2)];clients=[Browser(base),Browser(base)];password=secrets.token_urlsafe(24);images=0
 try:
  for name,client in zip(names,clients):
   assert client.call('/api/auth/signup','POST',dict(username=name,password=password,nickname='진단 검증',inviteCode=invitation))[0]==201
   assert client.call('/api/auth/login','POST',dict(username=name,password=password),form=True)[0]==204
  a,b=clients;catalog=a.call('/api/diagnostics/banks')[1]
  for family in ('exam-a-v2','exam-b-v2'):
   entry=next(c for c in catalog if c['id']==family);assert entry['setCount']==4 and entry['questionCount']==8 and len(entry['categories'])==4
   assigned=set()
   for attempt in range(4):
    key=str(uuid.uuid4());body={'bankId':family};status,session=a.call('/api/diagnostics','POST',body,key);assert status==200,(status,session)
    assert a.call('/api/diagnostics','POST',body,key)[1]['id']==session['id'];assert session['bankId'] not in assigned;assigned.add(session['bankId']);assert not session['repeatAttempt']
    assert b.call('/api/diagnostics/'+session['id'])[0]==404
    assert a.call('/api/diagnostics','POST',{**body,'categories':entry['categories']},str(uuid.uuid4()))[0] in (400,409)
    candidates={i['problem']['version']:i for i in banks[session['bankId']]['items']};assert len(session['items'])==8
    # First question is solved through the actual service/worker; every other question is admitted then skipped.
    first=True
    while session['current']:
     q=session['current'];item=candidates[q['problemVersion']]
     assert set(q)=={'itemId','problemVersion','title','statement','sampleInput','sampleOutput','examples','languages','api'}
     assert session['items'][next(i for i,x in enumerate(session['items']) if x['id']==q['itemId'])]['difficulty']==item['difficulty']
     assert len(q['examples'])==3 and q['examples'][0]['input']==item['problem']['tests'][0]['input']
     assert [l['id'] for l in q['languages']]==(['JAVA'] if family=='exam-b-v2' else ['JAVA','CPP','PYTHON'])
     assert q['api']==item['problem'].get('api')
     if item.get('image'):
      path='/api/problem-images/'+item['image']['id'];assert b.call(path)[0]==404
      status,png=a.call(path);assert status==200 and hashlib.sha256(png).digest()==hashlib.sha256((folder/item['image']['file']).read_bytes()).digest();images+=1
     if first:
      payload=dict(problemVersion=q['problemVersion'],diagnosticItemId=q['itemId'],source=item['languages']['JAVA']['correct'],language='JAVA');submit_key=str(uuid.uuid4())
      status,result=a.call('/api/submissions','POST',payload,submit_key);assert status==202,(status,result)
      assert a.call('/api/submissions','POST',payload,submit_key)[1]['id']==result['id']
      deadline=time.monotonic()+150
      while time.monotonic()<deadline:
       result=a.call('/api/submissions/'+result['id'])[1]
       if result['status']=='FINISHED':break
       time.sleep(1)
      assert result['status']=='FINISHED' and result['verdict']=='AC',(q['problemVersion'],result['verdict']);assert result['memoryPeakBytes'] is not None
      first=False
     else:assert a.call('/api/diagnostics/'+session['id']+'/items/'+q['itemId']+'/skip','POST',{'reason':'OTHER'})[0]==200
     session=a.call('/api/diagnostics/'+session['id'])[1]
    assert session['status']=='COMPLETED';print('PASS:',session['bankId'],'8-question admission, first reference AC, replay, private media',flush=True)
   status,repeat=a.call('/api/diagnostics','POST',body,str(uuid.uuid4()));assert status==200 and repeat['repeatAttempt'];assert repeat['bankId'] in assigned;assert a.call('/api/diagnostics/'+repeat['id']+'/finish','POST',{})[0]==200
  assert images==33,images
  assert all(p['version'] not in {v for bank in banks.values() for v in [i['problem']['version'] for i in bank['items']]} for p in a.call('/api/problems')[1])
  assert sql("SELECT count(*) FROM ai_task t JOIN app_user u ON u.id=t.user_id WHERE u.username='"+names[0]+"'")=='0'
  print('PASS: A/B 8 banks / 64 admitted questions / 8 real AC / 33 private PNGs / unseen allocation and repeat / zero AI',flush=True)
 finally:
  for name in names:sql("DELETE FROM spring_session WHERE principal_name='"+name+"'; DELETE FROM app_user WHERE username='"+name+"';")
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--banks',type=Path,required=True);a=p.parse_args();main(a.banks)
