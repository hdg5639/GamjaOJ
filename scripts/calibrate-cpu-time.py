"""Private, resumable paired wall/CPU sample calibration; never writes the DB.

Select independent training/holdout examples by language, calling convention and
existing budget band. Keep old wall budgets; qualify CPU proposals under load.
Reports and sources stay in the operator's private directory.
"""
import argparse,copy,hashlib,json,math,sys,tempfile
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from runner.judge import Runner,LANGUAGES,checked_profile,CompileCache,GeneratedCache
from runner.execution_contract import contract
from scripts.resource_evidence import audit_plan,worst_plan

DEFAULT={'CPP':3,'JAVA':5,'PYTHON':8}
def canonical(v):return json.dumps(v,ensure_ascii=False,sort_keys=True,separators=(',',':'))
def save(p,v):
 p.parent.mkdir(parents=True,exist_ok=True);tmp=p.with_suffix('.tmp');tmp.write_text(canonical(v)+'\n');tmp.replace(p)
def group(language,job,limits):
 return ':'.join([language,'CALLABLE' if 'api' in job['problem'] else 'STDIO','short' if limits[language]<=2*DEFAULT[language] else 'long'])

def main():
 p=argparse.ArgumentParser();p.add_argument('--audit',type=Path,required=True);p.add_argument('--inventory',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--phase',choices=['measure','qualify','all'],default='all');args=p.parse_args()
 rows=[json.loads(l) for l in args.inventory.read_text().splitlines()];buckets=defaultdict(list);jobs={};excluded=[]
 for row in rows:
  path=args.audit/'jobs'/(row['version']+'.json')
  if not path.exists() or row.get('held'):excluded.append(row['version']);continue
  job=json.loads(path.read_text())
  if job['packageHash']!=row['packageHash']:excluded.append(row['version']);continue
  jobs[row['version']]=job;limits=row['limits'] or DEFAULT
  for language in LANGUAGES:
   mp=args.audit/'measurements'/(row['version']+'-'+language+'.json')
   if language not in job['references'] or not mp.exists():continue
   measurement=json.loads(mp.read_text())
   if measurement.get('status')!='MEASURED' or measurement['packageHash']!=row['packageHash']:continue
   buckets[group(language,job,limits)].append((measurement['maxWallMs'],row,language,measurement))
 selected=[]
 for key,values in sorted(buckets.items()):
  values.sort(key=lambda v:(v[0],v[1]['version']))
  # Stratify short/medium/heavy execution. One independent held-out version.
  positions=sorted(set(round((len(values)-1)*q) for q in [0,.33,.67,.9]))
  controls=[i for i,v in enumerate(values) if jobs[v[1]['version']].get('slow',{}).get(v[2]) and v[1]['limits'] and v[1]['limits'][v[2]]<=30]
  if controls and not any(pos in controls for pos in positions):positions.append(controls[0])
  for i,pos in enumerate(positions):
   wall,row,language,old=values[pos];job=jobs[row['version']]
   plan=audit_plan(job)
   if 'api' in plan:plan['callable']=json.loads((args.audit/'drivers'/(row['version']+'.json')).read_text())['languages'][language]
   plan=worst_plan(plan,old['reports'][0])
   selected.append(dict(group=key,version=row['version'],language=language,holdout=i>=3 or len(positions)<4 and i==len(positions)-1,plan=plan,limits=row['limits'] or DEFAULT,packageHash=row['packageHash']))
 save(args.output/'sample-manifest.json',{'groups':{k:len(v) for k,v in buckets.items()},'samples':[{k:v for k,v in s.items() if k!='plan'} for s in selected],'excluded':excluded,'contract':contract()})
 cache=CompileCache();generated=GeneratedCache()
 def run(sample,source,*,observe=False,cpu=None,mode='EXCLUSIVE',judge_all=True):
  language=sample['language'];limits=sample['limits'];profile=LANGUAGES[language]|{'testWallSeconds':limits[language]}
  if 'memory' in limits:profile['memoryMb']=limits['memory'][language]
  if cpu is not None:profile['testCpuSeconds']=cpu
  with tempfile.TemporaryDirectory(prefix='gamja-cpu-calibration-') as d:
   r=Runner(profile['image'],d);r.profile=checked_profile(profile,language,r.image);r.observe_cpu=observe;r.execution_mode=mode;r.judge_all=judge_all;r.compile_cache=cache;r.generated_cache=generated
   return r.judge(source.encode(),sample['plan'])
 if args.phase in ('measure','all'):
  for s in selected:
   path=args.output/'pairs'/(s['version']+'-'+s['language']+'.json')
   if path.exists():continue
   source=jobs[s['version']]['references'][s['language']]
   baseline=run(s,source);observed=run(s,source,observe=True)
   save(path,{'sample':{k:v for k,v in s.items() if k!='plan'},'sourceHash':hashlib.sha256(source.encode()).hexdigest(),'baseline':baseline,'observed':observed,'contract':contract()})
   print('PAIRED',s['group'],s['version'],baseline['verdict'],observed['verdict'],flush=True)
 if args.phase not in ('qualify','all'):return
 fits={};issues=[]
 for key in buckets:
  train=[s for s in selected if s['group']==key and not s['holdout']];ratios=[]
  try:
   for s in train:
    record=json.loads((args.output/'pairs'/(s['version']+'-'+s['language']+'.json')).read_text())
    if record['contract']!=contract() or record['baseline']['verdict']!='AC' or record['observed']['verdict']!='AC':raise ValueError('paired reference did not pass or stale contract')
    old={t['id']:t for t in record['baseline']['tests']}
    for t in record['observed']['tests']:
     if t.get('cpu_measurement')!='cgroup-v2-delta' or t['cpu_ms']<=0:raise ValueError('CPU evidence missing')
     ratios.append(max(0,(t['cpu_ms']-150)/max(1,old[t['id']]['wall_ms'])))
   if len(train)<2:raise ValueError('insufficient independent training problems')
   factor=round(max(.5,max(ratios)*1.1),4)
   if factor>1:raise ValueError('group conversion is unstable')
   fits[key]={'factor':factor,'offsetSeconds':.15,'trainingProblems':len(train),'status':'PENDING'}
  except (ValueError,KeyError,FileNotFoundError) as e:issues.append({'group':key,'reason':str(e)})
 def qualify(s):
  key=s['group'];fit=fits[key];budget=round(min(180,math.ceil((s['limits'][s['language']]*fit['factor']+.15)*100)/100),3)
  path=args.output/'qualification'/(s['version']+'-'+s['language']+'.json');source=jobs[s['version']]['references'][s['language']]
  if path.exists():
   saved=json.loads(path.read_text())
   if saved.get('cpuSeconds')==budget and saved.get('contract')==contract() and saved.get('reports') and saved['reports'][0].get('source_sha256')==hashlib.sha256(source.encode()).hexdigest():return saved
  reports=[run(s,source,cpu=budget,mode='FUNCTIONAL') for _ in range(2)]
  for alternate in jobs[s['version']].get('allowedReferences',{}).get(s['language'],[]):reports.append(run(s,alternate['source'],cpu=budget,mode='FUNCTIONAL'))
  slow=jobs[s['version']].get('slow',{}).get(s['language']);controls=[]
  if slow:
   # Compare the same inefficient program under the saved wall budget and CPU
   # proposal. Only actual resource failures count as separation evidence.
   controls=[run(s,slow,judge_all=False),run(s,slow,cpu=budget,mode='FUNCTIONAL',judge_all=False)]
  proof={'group':key,'version':s['version'],'language':s['language'],'holdout':s['holdout'],'cpuSeconds':budget,'reports':reports,'controls':controls,'contract':contract()};save(path,proof)
  print('QUALIFIED',key,s['version'],[r['verdict'] for r in reports],flush=True)
  return proof
 with ThreadPoolExecutor(max_workers=4) as pool:
  proofs=list(pool.map(qualify,[s for s in selected if s['group'] in fits]))
 for key,fit in fits.items():
  group_proofs=[p for p in proofs if p['group']==key];holdouts=[p for p in group_proofs if p['holdout']]
  failures=[p['version'] for p in group_proofs if any(r['verdict']!='AC' for r in p['reports']) or (p['controls'] and p['controls'][0]['verdict'] in ('TLE','MLE') and p['controls'][1]['verdict']=='AC')]
  fit.update(status='READY' if holdouts and not failures else 'EXCLUDED',failures=failures,holdoutProblems=len(holdouts))
 save(args.output/'conversion-review.json',{'policy':'CPU_NORMALIZED_V1','fits':fits,'issues':issues,'samples':len(selected),'contract':contract(),'inventorySha256':hashlib.sha256(args.inventory.read_bytes()).hexdigest()})
 print('COMPLETE',len(selected),'samples',sum(f['status']=='READY' for f in fits.values()),'ready groups',flush=True)

if __name__=='__main__':main()
