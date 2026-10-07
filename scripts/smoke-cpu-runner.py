"""Real dedicated-Runner probes: CPU limits, testcase order and shared sandbox cap."""
import json,sys,tempfile,threading,time,uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from runner.judge import Runner,LANGUAGES,checked_profile,docker

def main():
 identities=[str(uuid.uuid4()) for _ in range(5)];peak=0;stop=threading.Event()
 def monitor():
  nonlocal peak
  while not stop.is_set():
   labels=docker('ps','--filter','label=com.gamjaoj.role=sandbox','--format','{{.Label "com.gamjaoj.attempt"}}').decode().splitlines()
   peak=max(peak,sum(label in identities for label in labels));stop.wait(.05)
 def run(index,source,tests,cpu,mode='FUNCTIONAL'):
  with tempfile.TemporaryDirectory() as d:
   r=Runner(LANGUAGES['PYTHON']['image'],d,attempt=identities[index]);r.profile=checked_profile(LANGUAGES['PYTHON']|{'testCpuSeconds':cpu},'PYTHON',r.image);r.execution_mode=mode;r.judge_all=True
   return r.judge(source.encode(),{'version':'cpu-smoke','output_policy':'TOKEN_EXACT','tests':tests})
 one=[{'id':'sample','input':'','output':'3'}]
 busy=run(0,'while True: pass',one,.15);assert busy['verdict']=='TLE',busy
 idle=run(0,'import time;time.sleep(.3);print(3)',one,.3);assert idle['verdict']=='AC',idle
 # All four cases finish in a different order but evidence stays in plan order.
 tests=[{'id':str(i),'input':str(i),'output':str(i)} for i in range(4)]
 source='import sys,time\nx=int(sys.stdin.read());time.sleep(1+(3-x)*.05);print(x)'
 thread=threading.Thread(target=monitor,daemon=True);thread.start();started=time.monotonic()
 try:
  with ThreadPoolExecutor(max_workers=5) as pool:reports=list(pool.map(lambda i:run(i,source,tests,1),range(5)))
 finally:stop.set();thread.join(timeout=10)
 for report in reports:
  assert report['verdict']=='AC',report
  assert [t['id'] for t in report['tests']]==['0','1','2','3']
  assert all(t['cpu_measurement']=='cgroup-v2-delta' for t in report['tests'])
 assert 5<=peak<=20,peak
 # First failure is still the first planned case, even if later cases finish first.
 failed=run(0,source,[{'id':str(i),'input':str(i),'output':'9' if i==0 else str(i)} for i in range(4)],1)
 assert failed['verdict']=='WA' and len(failed['tests'])==4 and failed['tests'][0]['verdict']=='WA',failed
 # Trusted helper construction stays separate; generated learner cases can
 # overlap without changing generated evidence order or hashes.
 with tempfile.TemporaryDirectory() as d:
  r=Runner(LANGUAGES['PYTHON']['image'],d,attempt=identities[0]);r.profile=checked_profile(LANGUAGES['PYTHON']|{'testCpuSeconds':1},'PYTHON',r.image);r.execution_mode='FUNCTIONAL';r.judge_all=True
  plan={'version':'cpu-generated-smoke','output_policy':'TOKEN_EXACT','tests':[{'id':'sample','input':'1 2','output':'3'}],
        'generated':{'generator':'public class Main {public static void main(String[] a){System.out.println("1 2");}}','reference':'public class Main {public static void main(String[] a){System.out.println(3);}}','tests':[{'id':f'generated-{i}','seed':str(i),'expected':'REFERENCE'} for i in range(4)]}}
  generated=r.judge(b'import sys,time;time.sleep(.1);print(sum(map(int,sys.stdin.read().split())))',plan)
  assert generated['verdict']=='AC' and [t['id'] for t in generated['tests']]==['sample']+[f'generated-{i}' for i in range(4)],generated
  assert all(t.get('input_sha256') and t.get('cpu_ms') for t in generated['tests'][1:]),generated
 print(json.dumps({'status':'PASS','requests':5,'testsPerRequest':4,'peakSandboxes':peak,'elapsedSeconds':round(time.monotonic()-started,3),'busyVerdict':busy['verdict'],'idleVerdict':idle['verdict'],'cpuEvidence':True,'orderedResults':True}))
if __name__=='__main__':main()
