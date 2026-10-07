"""Stage hash/old-limit fenced CPU budgets from a held-out sample review. No DB writes."""
import argparse,copy,hashlib,json,math,os,sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
DEFAULT={'CPP':3,'JAVA':5,'PYTHON':8}
def canonical(v):return json.dumps(v,ensure_ascii=False,sort_keys=True,separators=(',',':'))
def quote(v):return "'"+str(v).replace("'","''")+"'"
def main():
 os.umask(0o077);p=argparse.ArgumentParser();p.add_argument('--audit',type=Path,required=True);p.add_argument('--inventory',type=Path,required=True);p.add_argument('--review',type=Path,required=True);p.add_argument('--output',type=Path,required=True);args=p.parse_args()
 review=json.loads(args.review.read_text());raw=args.inventory.read_bytes()
 if review.get('policy')!='CPU_NORMALIZED_V1' or review['inventorySha256']!=hashlib.sha256(raw).hexdigest():raise ValueError('stale calibration inventory')
 rows=[json.loads(l) for l in raw.decode().splitlines()];updates=[];excluded=[]
 for row in rows:
  path=args.audit/'jobs'/(row['version']+'.json')
  if not path.exists() or row.get('held'):excluded.append(row['version']);continue
  job=json.loads(path.read_text())
  if job['packageHash']!=row['packageHash']:raise ValueError('package changed: '+row['version'])
  before=row['limits'];limits=copy.deepcopy(before or (DEFAULT|{'analysis':'Existing language default wall budgets retained.'}));cpu={}
  for language in DEFAULT:
   group=':'.join([language,'CALLABLE' if 'api' in job['problem'] else 'STDIO','short' if limits[language]<=2*DEFAULT[language] else 'long'])
   fit=review['fits'].get(group,{})
   if fit.get('status')!='READY':continue
   factor=fit['factor'];offset=fit['offsetSeconds']
   if not .5<=factor<=1 or not 0<=offset<=.5 or fit['trainingProblems']<2 or fit['holdoutProblems']<1:raise ValueError('invalid sample fit')
   cpu[language]=round(min(180,math.ceil((limits[language]*factor+offset)*100)/100),3)
  if not cpu:excluded.append(row['version']);continue
  limits['cpu']=cpu;limits['analysis']=limits['analysis'][:5400]+' CPU_NORMALIZED_V1: paired representative samples and independent holdout qualification; original wall budgets and memory retained. CPU boundary/TLE results receive one isolated replay. This conversion is not a new maximum-input qualification.'
  updates.append({'version':row['version'],'hash':row['packageHash'],'before':before,'after':limits})
 if not updates:raise ValueError('no qualified CPU groups')
 args.output.mkdir(parents=True,exist_ok=True,mode=0o700)
 def transaction(rollback=False):
  values=[]
  for u in updates:
   before=u['after'] if rollback else u['before'];after=u['before'] if rollback else u['after']
   values.append('('+quote(u['version'])+','+quote(u['hash'])+','+('NULL' if before is None else quote(canonical(before))+'::jsonb')+','+('NULL' if after is None else quote(canonical(after)))+')')
  return "\n".join(['BEGIN;',"SELECT pg_advisory_xact_lock(hashtext('gamjaoj-cpu-time-release'));",'CREATE TEMP TABLE cpu_limit_changes(version TEXT PRIMARY KEY,package_hash TEXT,before_limits JSONB,after_limits TEXT) ON COMMIT DROP;','INSERT INTO cpu_limit_changes VALUES '+',\n'.join(values)+';',"DO $$ BEGIN IF EXISTS (SELECT 1 FROM cpu_limit_changes c LEFT JOIN problem_version p ON p.id=c.version WHERE p.id IS NULL OR p.package_sha256<>c.package_hash OR NOT p.ready OR p.time_limits_json::jsonb IS DISTINCT FROM c.before_limits) THEN RAISE EXCEPTION 'CPU limits inventory changed; no limits updated'; END IF; END $$;",'UPDATE problem_version p SET time_limits_json=c.after_limits FROM cpu_limit_changes c WHERE p.id=c.version;','COMMIT;'])+'\n'
 (args.output/'cpu-release.sql').write_text(transaction());(args.output/'cpu-rollback.sql').write_text(transaction(True))
 (args.output/'release-review.json').write_text(canonical({'policy':review['policy'],'problems':len(updates),'languageProfiles':sum(len(u['after']['cpu']) for u in updates),'excluded':excluded,'fits':review['fits'],'sampleCases':review['samples'],'inventorySha256':review['inventorySha256'],'calibrationReviewSha256':hashlib.sha256(args.review.read_bytes()).hexdigest()})+'\n')
 print('READY',len(updates),'problems',sum(len(u['after']['cpu']) for u in updates),'language profiles',len(excluded),'excluded')
if __name__=='__main__':main()
