"""Resumable private calibration on the dedicated Runner; never updates production.
Runs two bounded functional jobs, preserves hashes/reports, checks original correct/slow
programs under measured budgets. A measurement alone is not a worst-case/intent certificate.
"""
import argparse,copy,hashlib,json,math,os,sys,tempfile
from concurrent.futures import ThreadPoolExecutor,as_completed
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from runner.judge import Runner,LANGUAGES,checked_profile,CompileCache,GeneratedCache
from runner.execution_contract import contract

def canonical(value):return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'))
def digest(value):return hashlib.sha256(canonical(value).encode()).hexdigest()
def write(path,value):
 tmp=path.with_suffix('.tmp');tmp.write_text(canonical(value)+'\n');tmp.replace(path)
def budget(language,wall_ms,memory_bytes):
 if any(type(v) not in (int,float) or not math.isfinite(v) or v<=0 for v in (wall_ms,memory_bytes)):raise ValueError('positive finite resource evidence required')
 floor={'JAVA':0.75,'CPP':0.25,'PYTHON':0.35}[language]
 seconds=round(max(floor,math.ceil((wall_ms*1.5+50)/50)*0.05),3)
 memory=max({'JAVA':192,'CPP':32,'PYTHON':48}[language],math.ceil((memory_bytes/1048576*1.2+8)/16)*16)
 if seconds>20 or memory>LANGUAGES[language]['memoryMb']:raise ValueError('reference exceeds bounded calibration capacity')
 return dict(testWallSeconds=seconds,memoryMb=memory)

def worst_plan(plan,report):
 result=copy.deepcopy(plan);ranked=sorted(report['tests'],key=lambda t:t['wall_ms'],reverse=True)
 ids={t['id'] for t in ranked[:3]}
 # Always repeat the largest declared generated witness, even when startup dominates tiny fixed cases.
 generated=[t for t in ranked if t.get('kind')=='generated']
 if generated:ids.add(max(generated,key=lambda t:t.get('input_bytes',0))['id'])
 if ranked:ids.add(max(ranked,key=lambda t:t.get('memory_peak_bytes',0))['id'])
 result['tests']=[t for t in result['tests'] if t['id'] in ids]
 if result.get('generated'):
  result['generated']['tests']=[t for t in result['generated']['tests'] if t['id'] in ids]
  if not result['generated']['tests']:result.pop('generated')
 # validate_problem requires a fixed suite even when the longest witness is generated.
 if not result['tests']:result['tests']=plan['tests'][:1]
 return result

class Calibration:
 def __init__(self,args):
  self.args=args;self.cache=CompileCache();self.generated=GeneratedCache();self.execution=contract()
 def run(self,language,source,plan,limits):
  with tempfile.TemporaryDirectory(prefix='gamja-resource-audit-') as directory:
   runner=Runner(LANGUAGES[language]['image'],directory);runner.profile=checked_profile(LANGUAGES[language]|limits,language,runner.image)
   runner.execution_mode='FUNCTIONAL';runner.judge_all=True;runner.compile_cache=self.cache;runner.generated_cache=self.generated
   result=runner.judge(source.encode(),plan)
   if result['verdict']=='IE':raise RuntimeError('Runner infrastructure: '+result.get('error',''))
   return result
 def measure(self,path,language):
  job=json.loads(path.read_text());version=job['version'];source=job['references'][language];plan=copy.deepcopy(job['problem'])
  plan['tests'].extend(copy.deepcopy(job.get('auditTests',[])))
  if 'api' in plan:
   bundles=json.loads((self.args.drivers/(version+'.json')).read_text());plan['callable']=bundles['languages'][language]
  fingerprint=digest(dict(job=job,language=language,driver=plan.get('callable'),contract=self.execution))
  out=self.args.output/(version+'-'+language+'.json')
  saved=json.loads(out.read_text()) if out.exists() else {}
  if saved.get('fingerprint')==fingerprint and saved.get('status')=='MEASURED':return version,language,'REUSED'
  record=dict(version=version,language=language,fingerprint=fingerprint,packageHash=job['packageHash'],sourceHash=hashlib.sha256(source.encode()).hexdigest(),executionContract=self.execution,status='RUNNING',scope='fixed corpus plus declared generated witnesses; not exhaustive worst-case proof',reports=[])
  write(out,record)
  try:
   allowed=job.get('allowedReferences',{}).get(language,[])
   sources=[source]+[alternative['source'] for alternative in allowed]
   for candidate in sources:
    broad=self.run(language,candidate,plan,dict(testWallSeconds=20,memoryMb=LANGUAGES[language]['memoryMb']));record['reports'].append(broad);write(out,record)
    if broad['verdict']!='AC':raise ValueError('allowed reference did not pass broad budget: '+broad['verdict'])
    repeat=worst_plan(plan,broad)
    for _ in range(2):
     report=self.run(language,candidate,repeat,dict(testWallSeconds=20,memoryMb=LANGUAGES[language]['memoryMb']));record['reports'].append(report);write(out,record)
     if report['verdict']!='AC':raise ValueError('allowed reference replay: '+report['verdict'])
   tests=[t for r in record['reports'] for t in r['tests']]
   if any(t.get('memory_measurement')!='cgroup-peak-observed' or not t.get('memory_peak_bytes') for t in tests):raise ValueError('trusted memory observation missing')
   peak=max(t['memory_peak_bytes'] for t in tests);wall=max(t['wall_ms'] for t in tests);proposal=budget(language,wall,peak);record.update(maxWallMs=wall,maxMemoryBytes=peak,proposal=proposal);write(out,record)
   qualified=self.run(language,source,plan,proposal);record['qualified']=qualified;write(out,record)
   if qualified['verdict']!='AC':raise ValueError('measured resource proposal rejected correct reference: '+qualified['verdict'])
   record['qualifiedAlternates']=[]
   for alternative in allowed:
    qualified=self.run(language,alternative['source'],plan,proposal)
    record['qualifiedAlternates'].append(dict(name=alternative['name'],sourceHash=hashlib.sha256(alternative['source'].encode()).hexdigest(),qualified=qualified));write(out,record)
    if qualified['verdict']!='AC':raise ValueError('measured proposal rejected allowed '+alternative['name']+': '+qualified['verdict'])
   witness=job.get('slow',{}).get(language)
   if witness:
    tiny=copy.deepcopy(plan);tiny.pop('generated',None);tiny['tests']=tiny['tests'][:3]
    small=self.run(language,witness,tiny,proposal);large=self.run(language,witness,plan,proposal)
    record['slow']=dict(sourceHash=hashlib.sha256(witness.encode()).hexdigest(),small=small,large=large);write(out,record)
    if small['verdict']!='AC':raise ValueError('slow witness is not correct on public examples: '+small['verdict'])
    if job.get('intent',{}).get('efficiencyRequired') and large['verdict'] not in ('TLE','MLE'):raise ValueError('intended inefficient solution not separated by calibrated limits')
   record['status']='MEASURED';record['intentReviewRequired']=not bool(job.get('intentCertificate'));write(out,record)
  except Exception as error:
   record.update(status='FAILED',error=str(error)[:2000]);write(out,record)
  return version,language,record['status']
 def main(self):
  tasks=[]
  for path in sorted(self.args.jobs.glob('*.json')):
   job=json.loads(path.read_text())
   if self.args.complete_only and len(job['references'])!=3:continue
   if self.args.prefix and not job['version'].startswith(self.args.prefix):continue
   for language in job['references']:
    if not self.args.language or language==self.args.language:tasks.append((path,language))
  self.args.output.mkdir(parents=True,exist_ok=True)
  if self.args.limit:tasks=tasks[:self.args.limit]
  with ThreadPoolExecutor(max_workers=2) as pool:
   pending=[pool.submit(self.measure,*task) for task in tasks]
   for f in as_completed(pending):print(*f.result(),flush=True)
  print('Measured tasks:',len(tasks),flush=True)
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--jobs',type=Path,required=True);p.add_argument('--drivers',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--complete-only',action='store_true');p.add_argument('--prefix');p.add_argument('--language',choices=list(LANGUAGES));p.add_argument('--limit',type=int);a=p.parse_args();Calibration(a).main()
