"""Create a reviewable all-or-nothing resource release. No SQL is executed here.
Requires all three languages, complete maximum-input/intent certificates, requalified
references and inefficient witnesses. Private reports contain no public answers.
"""
import argparse,copy,hashlib,json,sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from scripts.resource_evidence import canonical,digest,complete_qualification,audit_plan,complete_measurement,public_example_plan,profiling_seconds,compatible_execution_evidence

def quote(v):return "'"+str(v).replace("'","''")+"'"

def stage(jobs,measurements,certificates,output):
 output.mkdir(parents=True,exist_ok=True)
 # An earlier READY result must not survive a later incomplete/stale assessment.
 (output/'resource-release.sql').unlink(missing_ok=True)
 execution=json.loads((Path(__file__).resolve().parents[1]/'backend/src/main/resources/runner-execution-contract.json').read_text())
 updates=[];issues=[]
 for path in sorted(jobs.glob('*.json')):
  job=json.loads(path.read_text());version=job['version'];certificate=certificates/(version+'.json')
  reports={};proposed={};memory={}
  if not certificate.exists():issues.append(dict(version=version,reason='maximum-input/intent certificate missing'));continue
  proof=json.loads(certificate.read_text())
  if proof.get('packageHash')!=job['packageHash'] or proof.get('maximumInputsReviewed') is not True or not proof.get('allowedApproaches') or not proof.get('reviewedBy') or (job.get('intent',{}).get('efficiencyRequired') and not proof.get('inefficientApproaches')):
   issues.append(dict(version=version,reason='invalid or stale intent certificate'));continue
  expected_plan=audit_plan(job)
  bundles=None
  if 'api' in expected_plan:
   driver_file=jobs.parent/'drivers'/(version+'.json')
   if not driver_file.exists():issues.append(dict(version=version,reason='trusted language driver evidence missing'));continue
   bundles=json.loads(driver_file.read_text())['languages']
  for language in ['JAVA','CPP','PYTHON']:
   record=measurements/(version+'-'+language+'.json')
   if not record.exists():issues.append(dict(version=version,language=language,reason='measured reference missing'));continue
   report=json.loads(record.read_text());source=job['references'].get(language)
   if not source or report.get('status')!='MEASURED' or report.get('packageHash')!=job['packageHash'] or report.get('sourceHash')!=hashlib.sha256(source.encode()).hexdigest() or report.get('qualified',{}).get('verdict')!='AC':
    issues.append(dict(version=version,language=language,reason='qualification/source/package fence failed'));continue
   qualified=report['qualified'];proposal=report.get('proposal',{})
   trusted_profile=execution['languages'][language]|proposal
   if bundles:expected_plan['callable']=bundles[language]
   if not compatible_execution_evidence(report,execution) or set(proposal)!={'testWallSeconds','memoryMb'} or not complete_qualification(qualified,language,report['sourceHash'],expected_plan,trusted_profile):
    issues.append(dict(version=version,language=language,reason='complete qualified corpus/profile evidence missing'));continue
   measurement_sources=[source]+[a['source'] for a in job.get('allowedReferences',{}).get(language,[])]
   broad_profile=execution['languages'][language]|dict(testWallSeconds=profiling_seconds(job,language),memoryMb=execution['languages'][language]['memoryMb'])
   if not complete_measurement(report,language,measurement_sources,expected_plan,broad_profile):
    issues.append(dict(version=version,language=language,reason='full measurement/peak replay evidence missing'));continue
   alternatives=job.get('allowedReferences',{}).get(language,[]);qualified_alternatives=report.get('qualifiedAlternates',[])
   if len(alternatives)!=len(qualified_alternatives) or any(record.get('name')!=alternative['name'] or record.get('sourceHash')!=hashlib.sha256(alternative['source'].encode()).hexdigest() or not complete_qualification(record.get('qualified',{}),language,record['sourceHash'],expected_plan,trusted_profile) for alternative,record in zip(alternatives,qualified_alternatives)):
    issues.append(dict(version=version,language=language,reason='allowed algorithm alternative not fully qualified'));continue
   if language not in proof.get('languageEvidence',{}):issues.append(dict(version=version,language=language,reason='maximum-input language evidence missing'));continue
   evidence=proof['languageEvidence'][language]
   if evidence.get('measurementHash')!=digest(report):issues.append(dict(version=version,language=language,reason='maximum-input evidence identity mismatch'));continue
   slow=report.get('slow',{});witness=job.get('slow',{}).get(language)
   if job.get('resourceTimePolicy') is not None and report.get('resourceTimePolicy')!=job['resourceTimePolicy']:
    issues.append(dict(version=version,language=language,reason='resource time policy requires new qualification'));continue
   efficiency_required=job.get('intent',{}).get('efficiencyRequired') or (job.get('resourceTimePolicy') is None and proof.get('inefficientApproaches'))
   if efficiency_required and (evidence.get('inefficientWitnessSeparated') is not True or not witness or slow.get('sourceHash')!=hashlib.sha256(witness.encode()).hexdigest() or not expected_plan.get('samples') or not complete_qualification(slow.get('small',{}),language,slow['sourceHash'],public_example_plan(expected_plan),trusted_profile) or slow.get('large',{}).get('verdict') not in ('TLE','MLE')):
    issues.append(dict(version=version,language=language,reason='intended inefficient approach not separated'));continue
   proposed[language]=report['proposal']['testWallSeconds'];memory[language]=report['proposal']['memoryMb'];reports[language]=digest(report)
  if len(proposed)!=3:continue
  proposed.update(memory=memory,analysis='Measured and requalified on pinned production Runner. Per-language peak/replay and maximum-input/intent evidence: '+digest(proof)+'. Time includes startup; memory is container cgroup peak. Allowed algorithms: '+', '.join(proof['allowedApproaches']))
  updates.append(dict(version=version,packageHash=job['packageHash'],oldLimits=job['oldLimits'],limits=proposed,measurementHashes=reports,certificateHash=digest(proof)))
 output.mkdir(parents=True,exist_ok=True)
 summary=dict(status='READY' if not issues and updates else 'INCOMPLETE',total=len(list(jobs.glob('*.json'))),qualified=len(updates),issues=issues,updates=updates)
 (output/'release-review.json').write_text(canonical(summary)+'\n')
 if summary['status']!='READY':print('INCOMPLETE',summary['qualified'],'/',summary['total'],'issues',len(issues));return False
 # Fail closed before any update if the live inventory or a concurrent resource setting changed.
 statements=['BEGIN;','SET LOCAL lock_timeout = \'5s\';','SET LOCAL statement_timeout = \'30s\';', 'LOCK TABLE problem_version IN SHARE ROW EXCLUSIVE MODE;']
 ids=','.join(quote(u['version']) for u in updates)
 statements.append('DO $guard$ BEGIN IF (SELECT count(*) FROM problem_version WHERE ready AND NOT review_hold) <> '+str(len(updates))+' OR EXISTS (SELECT 1 FROM problem_version WHERE ready AND NOT review_hold AND id NOT IN ('+ids+')) THEN RAISE EXCEPTION \'active inventory changed\'; END IF; END $guard$;')
 for u in updates:
  old='NULL' if u['oldLimits'] is None else quote(canonical(u['oldLimits']))+'::jsonb'
  new=quote(canonical(u['limits']))
  where='id='+quote(u['version'])+' AND package_sha256='+quote(u['packageHash'])+' AND ready AND NOT review_hold'
  statements.append('DO $guard$ BEGIN IF NOT EXISTS (SELECT 1 FROM problem_version WHERE '+where+' AND (time_limits_json::jsonb IS NOT DISTINCT FROM '+old+' OR time_limits_json::jsonb='+new+'::jsonb)) THEN RAISE EXCEPTION \'resource or package fence changed\'; END IF; END $guard$;')
 for u in updates:
  statements.append('UPDATE problem_version SET time_limits_json='+quote(canonical(u['limits']))+' WHERE id='+quote(u['version'])+';')
 # Existing diagnostics/submissions retain frozen profiles. New sessions inherit the new limits.
 statements.append('COMMIT;')
 (output/'resource-release.sql').write_text('\n'.join(statements)+'\n');print('READY',len(updates),'review and backup before applying');return True
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--jobs',type=Path,required=True);p.add_argument('--measurements',type=Path,required=True);p.add_argument('--certificates',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();raise SystemExit(0 if stage(a.jobs,a.measurements,a.certificates,a.output) else 1)
