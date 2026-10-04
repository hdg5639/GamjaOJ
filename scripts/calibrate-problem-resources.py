"""Resumable private calibration on the dedicated Runner; never updates production.
Runs a bounded number of functional jobs, preserves hashes/reports, checks original correct/slow
programs under measured budgets. A measurement alone is not a worst-case/intent certificate.
"""
import argparse,copy,hashlib,json,math,os,sys,tempfile
from concurrent.futures import ThreadPoolExecutor,as_completed
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from runner.judge import Runner,LANGUAGES,checked_profile,CompileCache,GeneratedCache
from runner.execution_contract import contract
from scripts.resource_evidence import complete_qualification,audit_plan,worst_plan,complete_measurement,public_example_plan,profiling_seconds,compatible_execution_evidence

def canonical(value):return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'))
def digest(value):return hashlib.sha256(canonical(value).encode()).hexdigest()
def write(path,value):
 tmp=path.with_suffix('.tmp');tmp.write_text(canonical(value)+'\n');tmp.replace(path)
def budget(language,wall_ms,memory_bytes):
 if any(type(v) not in (int,float) or not math.isfinite(v) or v<=0 for v in (wall_ms,memory_bytes)):raise ValueError('positive finite resource evidence required')
 floor={'JAVA':0.75,'CPP':0.25,'PYTHON':0.35}[language]
 seconds=round(max(floor,math.ceil((wall_ms*1.5+50)/50)*0.05),3)
 memory=max({'JAVA':192,'CPP':32,'PYTHON':48}[language],math.ceil((memory_bytes/1048576*1.2+8)/16)*16)
 if seconds>60 or memory>LANGUAGES[language]['memoryMb']:raise ValueError('reference exceeds bounded calibration capacity')
 return dict(testWallSeconds=seconds,memoryMb=memory)


class AuditCompileCache(CompileCache):
 """Operator-only inventory scope; production cache admission is unchanged.
 Successful builds are reusable, while test execution and verdicts never are.
 """
 def __init__(self,scopes):super().__init__();self.scopes=scopes
 def key(self,version,image,source):
  if version not in self.scopes:return None
  profile=next((p for p in LANGUAGES.values() if p['image']==image),None)
  if profile is None:return None
  return (version,self.scopes[version],image,tuple(profile['compileCommand']),hashlib.sha256(source).hexdigest())

class AuditGeneratedCache(GeneratedCache):
 """Reuse private deterministic input/oracle bytes, never learner verdicts.
 All inventory versions are fenced by package and pinned helper runtime identities.
 Production generated-cache admission remains unchanged.
 """
 def __init__(self,scopes):super().__init__();self.scopes=scopes
 def key(self,version,generator,reference,seed,expected):
  if version not in self.scopes:return None
  profile=LANGUAGES['JAVA'];sha=lambda text:hashlib.sha256(text.encode()).hexdigest()
  return (version,self.scopes[version],profile['image'],tuple(profile['compileCommand']),sha(generator),sha(reference),seed,expected)

class Calibration:
 def __init__(self,args):
  scopes={}
  for path in getattr(args,'jobs',Path('/nonexistent')).glob('*.json'):
   job=json.loads(path.read_text());scopes[job['version']]=job['packageHash']
  self.args=args;self.cache=AuditCompileCache(scopes);self.generated=AuditGeneratedCache(scopes);self.execution=contract()
 def reusable(self,job,language,plan,record):
  source_hash=hashlib.sha256(job['references'][language].encode()).hexdigest()
  if record.get('status')!='MEASURED' or not compatible_execution_evidence(record,self.execution) or record.get('packageHash')!=job['packageHash'] or record.get('sourceHash')!=source_hash:return False
  try:
   sources=[job['references'][language]]+[a['source'] for a in job.get('allowedReferences',{}).get(language,[])]
   broad_profile=LANGUAGES[language]|dict(testWallSeconds=profiling_seconds(job,language),memoryMb=LANGUAGES[language]['memoryMb'])
   if not complete_measurement(record,language,sources,plan,broad_profile):return False
   proposal=budget(language,record['maxWallMs'],record['maxMemoryBytes'])
   if proposal!=record.get('proposal') or not complete_qualification(record.get('qualified',{}),language,source_hash,plan,LANGUAGES[language]|proposal):return False
   allowed=job.get('allowedReferences',{}).get(language,[]);qualified=record.get('qualifiedAlternates',[])
   if len(allowed)!=len(qualified) or any(item.get('name')!=alternative['name'] or item.get('sourceHash')!=hashlib.sha256(alternative['source'].encode()).hexdigest() or not complete_qualification(item.get('qualified',{}),language,item['sourceHash'],plan,LANGUAGES[language]|proposal) for alternative,item in zip(allowed,qualified)):return False
   witness=job.get('slow',{}).get(language);slow=record.get('slow',{})
   if slow.get('sourceHash')!=(hashlib.sha256(witness.encode()).hexdigest() if witness else None):return False
   if witness and (not complete_qualification(slow.get('small',{}),language,slow['sourceHash'],public_example_plan(plan),LANGUAGES[language]|proposal) or (job.get('intent',{}).get('efficiencyRequired') and slow.get('large',{}).get('verdict') not in ('TLE','MLE'))):return False
   return True
  except (KeyError,TypeError,ValueError):return False
 def run(self,language,source,plan,limits):
  with tempfile.TemporaryDirectory(prefix='gamja-resource-audit-') as directory:
   runner=Runner(LANGUAGES[language]['image'],directory);runner.profile=checked_profile(LANGUAGES[language]|limits,language,runner.image)
   runner.execution_mode='FUNCTIONAL';runner.judge_all=True;runner.compile_cache=self.cache;runner.generated_cache=self.generated
   result=runner.judge(source.encode(),plan)
   if result['verdict']=='IE':raise RuntimeError('Runner infrastructure: '+result.get('error',''))
   return result
 def measure(self,path,language):
  job=json.loads(path.read_text());version=job['version'];source=job['references'][language];plan=audit_plan(job)
  if 'api' in plan:
   bundles=json.loads((self.args.drivers/(version+'.json')).read_text());plan['callable']=bundles['languages'][language]
  fingerprint=digest(dict(version=version,packageHash=job['packageHash'],plan=plan,language=language,profilingSeconds=profiling_seconds(job,language),source=source,allowed=job.get('allowedReferences',{}).get(language,[]),slow=job.get('slow',{}).get(language),intent=job.get('intent',{}),contract=self.execution))
  out=self.args.output/(version+'-'+language+'.json')
  saved=json.loads(out.read_text()) if out.exists() else {}
  if self.reusable(job,language,plan,saved):return version,language,'REUSED'
  record=dict(version=version,language=language,fingerprint=fingerprint,packageHash=job['packageHash'],sourceHash=hashlib.sha256(source.encode()).hexdigest(),executionContract=self.execution,status='RUNNING',scope='fixed corpus plus declared generated witnesses; not exhaustive worst-case proof',reports=[])
  write(out,record)
  try:
   allowed=job.get('allowedReferences',{}).get(language,[])
   sources=[source]+[alternative['source'] for alternative in allowed]
   for candidate in sources:
    broad=self.run(language,candidate,plan,dict(testWallSeconds=profiling_seconds(job,language),memoryMb=LANGUAGES[language]['memoryMb']));record['reports'].append(broad);write(out,record)
    if broad['verdict']!='AC':raise ValueError('allowed reference did not pass broad budget: '+broad['verdict'])
    repeat=worst_plan(plan,broad)
    for _ in range(2):
     report=self.run(language,candidate,repeat,dict(testWallSeconds=profiling_seconds(job,language),memoryMb=LANGUAGES[language]['memoryMb']));record['reports'].append(report);write(out,record)
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
    tiny=public_example_plan(plan)
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
  # Large private witnesses must not keep the whole inventory in controller memory.
  jobs=[]
  for path in self.args.jobs.glob('*.json'):
   job=json.loads(path.read_text())
   priority=0 if job.get('auditGenerated') else 1 if job.get('auditTests') else 2
   jobs.append((priority,path,job['version'],tuple(job['references'])))
  if jobs:del job
  # Resolve newly reviewed maximum-input failures before spending hours on small suites.
  jobs.sort(key=lambda item:(item[0],item[1].name))
  for priority,path,version,languages in jobs:
   if self.args.complete_only and len(languages)!=3:continue
   if self.args.prefix and not version.startswith(self.args.prefix):continue
   for language in languages:
    if not self.args.language or language==self.args.language:tasks.append((path,language))
  self.args.output.mkdir(parents=True,exist_ok=True)
  if self.args.limit:tasks=tasks[:self.args.limit]
  with ThreadPoolExecutor(max_workers=getattr(self.args,'workers',2)) as pool:
   pending=[pool.submit(self.measure,*task) for task in tasks]
   for f in as_completed(pending):print(*f.result(),flush=True)
  print('Measured tasks:',len(tasks),flush=True)
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--jobs',type=Path,required=True);p.add_argument('--drivers',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--complete-only',action='store_true');p.add_argument('--prefix');p.add_argument('--language',choices=list(LANGUAGES));p.add_argument('--limit',type=int);p.add_argument('--workers',type=int,choices=[1,2,4],default=2);a=p.parse_args();Calibration(a).main()
