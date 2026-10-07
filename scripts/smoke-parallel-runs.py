"""Real Runner batch run: one compile, four sandboxes, ordered independent results."""
import json,sys,tempfile,threading,uuid
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from runner.judge import Runner,LANGUAGES,docker

def main():
 token=str(uuid.uuid4());peak=0;stop=threading.Event();compiles=0
 def monitor():
  nonlocal peak
  while not stop.is_set():
   names=docker('ps','--filter','label=com.gamjaoj.role=sandbox','--filter','label=com.gamjaoj.attempt='+token,'--format','{{.Names}}').decode().splitlines()
   peak=max(peak,len(names));stop.wait(.05)
 with tempfile.TemporaryDirectory() as d:
  r=Runner(LANGUAGES['PYTHON']['image'],d,attempt=token);r.profile=r.profile|{'testCpuSeconds':1};r.execution_mode='FUNCTIONAL'
  original=r.sandbox
  def sandbox(*args,**kwargs):
   nonlocal compiles
   if kwargs.get('compile_phase'):compiles+=1
   return original(*args,**kwargs)
  r.sandbox=sandbox
  plan={'version':'batch-run-probe','output_policy':'RUN_ONLY','tests':[{'id':f'custom-input-{i+1}','input':str(i),'output':''} for i in range(8)]}
  source=b"import sys,time\nx=int(sys.stdin.read());time.sleep(1+(3-x%4)*.1)\nif x==1: raise ValueError('expected probe failure')\nprint(x)\n"
  thread=threading.Thread(target=monitor,daemon=True);thread.start()
  try:report=r.judge(source,plan)
  finally:stop.set();thread.join(timeout=10)
  assert compiles==1 and peak==4,(compiles,peak)
  assert report['verdict']=='RE' and not report.get('judge_all',False) and len(report['tests'])==8,report
  for i,t in enumerate(report['tests']):
   assert t['id']==f'custom-input-{i+1}' and t['verdict']==('RE' if i==1 else 'OK'),t
   assert t['cpu_measurement']=='cgroup-v2-delta',t
   if i!=1:assert t['stdout'].strip()==str(i),t
  assert 'expected probe failure' in report['tests'][1]['stderr'],report
 print(json.dumps({'status':'PASS','inputs':8,'compiles':compiles,'peakSandboxes':peak,'orderedResults':True,'continuesAfterFailure':True}))
if __name__=='__main__':main()
