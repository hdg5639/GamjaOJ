"""Bounded real-Runner qualification (two production functional slots). Private evidence; never publishes candidate banks."""
import argparse,hashlib,json,sys,tempfile,time
from concurrent.futures import ThreadPoolExecutor,as_completed
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
from runner.judge import Runner,LANGUAGES

def desired_limits(item):
 path=ROOT/'diagnostics/private/exam-ab-v2-limits.json';limits={l:p['testWallSeconds'] for l,p in LANGUAGES.items()}
 if path.exists():
  digest=hashlib.sha256(json.dumps(item['problem'],ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()).hexdigest()
  for policy in json.loads(path.read_text())['items']:
   if item['problem']['version'] in policy['versions']:
    if policy['versions'][item['problem']['version']]!=digest:raise ValueError('Limit package hash mismatch')
    limits=policy['limits'];break
 return limits

def execute(language,source,problem,limits=None):
 with tempfile.TemporaryDirectory() as directory:
  runner=Runner(LANGUAGES[language]['image'],directory);runner.execution_mode='FUNCTIONAL'
  if limits:runner.profile={**LANGUAGES[language],'testWallSeconds':limits[language]}
  return runner.judge(source.encode(),problem)
def verify_item(item,digest):
 limits=desired_limits(item)
 result=dict(timeLimits=limits,itemSha256=digest,version=item['problem']['version'],languages={},mutants=[],failures=[])
 for language,sources in item['languages'].items():
  plan=dict(item['problem'])
  if 'api' in plan:plan['callable']=plan['api']
  report=execute(language,sources['correct'],plan,limits)
  result['languages'][language]=report
  if report['verdict']!='AC':result['failures'].append(language+' reference '+report['verdict'])
  if any(t['wall_ms']>limits[language]*750 for t in report['tests']):result['failures'].append(language+' less than 25% headroom')
  if any(t.get('memory_peak_bytes') is None for t in report['tests']):result['failures'].append(language+' missing cgroup memory peak')
 for index,source in enumerate(item['logicMutants']):
  report=execute('PYTHON',source,item['logicProblem'])
  result['mutants'].append(dict(index=index,full=report))
  if report['verdict']!='WA' or len(report['tests'])<=3 or any(t['verdict']!='AC' for t in report['tests'][:3]):result['failures'].append('mutant '+str(index)+' public/private WA contract failed: '+report['verdict'])
 result['passed']=not result['failures'];return item['id'],result
def verify(folder,output):
 records=json.loads(output.read_text()) if output.exists() else {};todo=[]
 for bankpath in sorted(folder.glob('*.json')):
  for item in json.loads(bankpath.read_text())['items']:
   digest=hashlib.sha256(json.dumps(item,sort_keys=True,ensure_ascii=False).encode()).hexdigest()
   if records.get(item['id'],{}).get('itemSha256')==digest and records[item['id']].get('passed') and all(r['execution_profile']['testWallSeconds']==desired_limits(item)[l] for l,r in records[item['id']]['languages'].items()):continue
   todo.append((item,digest))
 with ThreadPoolExecutor(max_workers=2) as pool:
  futures=[pool.submit(verify_item,item,digest) for item,digest in todo]
  for future in as_completed(futures):
   key,result=future.result();records[key]=result
   temporary=output.with_suffix('.tmp');temporary.write_text(json.dumps(records,ensure_ascii=False,indent=2)+'\n');temporary.replace(output)
   print(('PASS' if result['passed'] else 'FAIL')+' '+key+' '+str(result['failures']),flush=True)
 print('Qualified:',sum(v['passed'] for v in records.values()),'/',len(records),flush=True)
 return len(records)==64 and all(v['passed'] for v in records.values())
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--folder',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();raise SystemExit(0 if verify(a.folder,a.output) else 1)
