import copy,hashlib,importlib.util,json,tempfile,unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

def load(name,file):
 s=importlib.util.spec_from_file_location(name,Path(__file__).resolve().parents[1]/'scripts'/file);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
cal=load('resource_calibration','calibrate-problem-resources.py');stage=load('resource_stage','stage-resource-limits.py')
class CalibrationTests(unittest.TestCase):
 def test_private_generated_witness_requires_new_qualification_and_preserves_original_package(self):
  with tempfile.TemporaryDirectory() as folder:
   root=Path(folder);jobs,reports,proof=self.complete_fixture(root);path=jobs/'test-v1.json';job=json.loads(path.read_text())
   job['auditGenerated']={'generator':'max input generator','reference':'independent oracle','tests':[{'id':'max-generated','seed':'1','expected':'REFERENCE'}]}
   plan=cal.audit_plan(job)
   self.assertNotIn('generated',job['problem']);self.assertEqual(job['auditGenerated'],plan['generated'])
   plan['generated']['tests'][0]['seed']='2';self.assertEqual('1',job['auditGenerated']['tests'][0]['seed'])
   record=json.loads((reports/'test-v1-JAVA.json').read_text());record.update(maxWallMs=100,maxMemoryBytes=1048576)
   audit=cal.Calibration(SimpleNamespace(jobs=jobs,output=root/'out',drivers=root/'drivers'))
   self.assertFalse(audit.reusable(job,'JAVA',cal.audit_plan(job),record))
   path.write_text(json.dumps(job));self.assertFalse(stage.stage(jobs,reports,proof,root/'out'))
   self.assertFalse((root/'out/resource-release.sql').exists())
   job['problem']['generated']={'generator':'original'}
   with self.assertRaises(ValueError):cal.audit_plan(job)
 def test_reuse_rejects_missing_peak_replay_and_inconsistent_observations(self):
  with tempfile.TemporaryDirectory() as folder:
   root=Path(folder);jobs,reports,proof=self.complete_fixture(root)
   job=json.loads((jobs/'test-v1.json').read_text());record=json.loads((reports/'test-v1-JAVA.json').read_text())
   audit=cal.Calibration(SimpleNamespace(jobs=jobs,output=root/'out',drivers=root/'drivers'))
   self.assertTrue(audit.reusable(job,'JAVA',job['problem'],record))
   for mutate in [lambda r:r['reports'].pop(),lambda r:r.update(maxWallMs=99),lambda r:r['reports'][1]['tests'].pop(),lambda r:r['reports'][0]['tests'][0].pop('wall_ms')]:
    changed=copy.deepcopy(record);mutate(changed)
    self.assertFalse(audit.reusable(job,'JAVA',job['problem'],changed))
 def test_audit_build_cache_is_inventory_hash_scoped_and_keeps_production_policy(self):
  cache=cal.AuditCompileCache({'ordinary-v1':'package-a'})
  key=cache.key('ordinary-v1',cal.LANGUAGES['CPP']['image'],b'code')
  self.assertIsNotNone(key);self.assertIsNone(cache.key('unknown',cal.LANGUAGES['CPP']['image'],b'code'))
  self.assertIsNone(cache.key('ordinary-v1','untrusted-image',b'code'))
  self.assertNotEqual(key,cache.key('ordinary-v1',cal.LANGUAGES['CPP']['image'],b'changed'))
  self.assertNotEqual(key,cal.AuditCompileCache({'ordinary-v1':'package-b'}).key('ordinary-v1',cal.LANGUAGES['CPP']['image'],b'code'))
  self.assertIsNone(cal.CompileCache().key('ordinary-v1',cal.LANGUAGES['CPP']['image'],b'code'))
 def test_margins_are_bounded_language_specific_and_reject_capacity_overflow(self):
  self.assertEqual({'testWallSeconds':0.75,'memoryMb':192},cal.budget('JAVA',250,40*1048576))
  self.assertEqual({'testWallSeconds':0.4,'memoryMb':32},cal.budget('CPP',230,10*1048576))
  self.assertEqual({'testWallSeconds':1.55,'memoryMb':64},cal.budget('PYTHON',1000,40*1048576))
  for wall,memory in [(20000,20*1048576),(1000,250*1048576)]:
   with self.assertRaises(ValueError):cal.budget('CPP',wall,memory)
  for wall,memory in [(0,1),(-1,1),(float('nan'),1),(1,float('inf')),(True,1)]:
   with self.assertRaises(ValueError):cal.budget('CPP',wall,memory)
 def test_replay_keeps_large_generated_and_peak_memory_witness_even_when_startup_dominates(self):
  plan={'version':'test','tests':[{'id':str(i)} for i in range(5)],'generated':{'generator':'source','reference':'source','tests':[{'id':'small-slow'},{'id':'max'}]}}
  report={'tests':[{'id':str(i),'wall_ms':100-i,'memory_peak_bytes':999 if i==4 else 1} for i in range(5)]+[{'id':'small-slow','kind':'generated','wall_ms':10,'memory_peak_bytes':1,'input_bytes':10},{'id':'max','kind':'generated','wall_ms':1,'memory_peak_bytes':1,'input_bytes':1000}]}
  replay=cal.worst_plan(plan,report)
  self.assertEqual(['0','1','2','4'],[t['id'] for t in replay['tests']]);self.assertEqual([{'id':'max'}],replay['generated']['tests'])
  self.assertEqual(5,len(plan['tests']))
 def test_incomplete_release_never_emits_applicable_sql(self):
  with tempfile.TemporaryDirectory() as folder:
   root=Path(folder);jobs=root/'jobs';jobs.mkdir();(jobs/'p.json').write_text(json.dumps({'version':'p','packageHash':'hash'}))
   self.assertFalse(stage.stage(jobs,root/'reports',root/'proof',root/'out'))
   self.assertFalse((root/'out/resource-release.sql').exists())
   self.assertEqual('INCOMPLETE',json.loads((root/'out/release-review.json').read_text())['status'])
 def test_allowed_reference_sets_budget_and_must_pass_exact_proposal(self):
  for reject in [False,True]:
   with self.subTest(reject=reject),tempfile.TemporaryDirectory() as folder:
    root=Path(folder);out=root/'out';out.mkdir();path=root/'job.json'
    job={'version':'allowed-v1','packageHash':'package','problem':{'tests':[{'id':'max'}]},'references':{'CPP':'primary'},'allowedReferences':{'CPP':[{'name':'ordered tree','source':'allowed'}]}}
    path.write_text(json.dumps(job));audit=cal.Calibration(SimpleNamespace(output=out,drivers=root/'drivers'));calls=[]
    def run(language,source,plan,limits):
     calls.append((source,dict(limits)))
     return {'verdict':'WA' if reject and source=='allowed' and limits['testWallSeconds']!=20 else 'AC','tests':[{'id':'max','wall_ms':1000 if source=='allowed' else 100,'memory_peak_bytes':70*1048576 if source=='allowed' else 1048576,'memory_measurement':'cgroup-peak-observed'}]}
    with patch.object(audit,'run',side_effect=run):audit.measure(path,'CPP')
    record=json.loads((out/'allowed-v1-CPP.json').read_text());self.assertEqual({'testWallSeconds':1.55,'memoryMb':96},record['proposal']);self.assertEqual('FAILED' if reject else 'MEASURED',record['status'])
    self.assertIn(('allowed',record['proposal']),calls);self.assertEqual(1,len(record['qualifiedAlternates']))
 def complete_fixture(self,root):
  jobs=root/'jobs';jobs.mkdir();reports=root/'reports';reports.mkdir();proof=root/'proof';proof.mkdir()
  job={'version':'test-v1','packageHash':'package','oldLimits':None,'problem':{'tests':[{'id':'sample'},{'id':'max'}]},'intent':{},'references':{l:'reference '+l for l in ['JAVA','CPP','PYTHON']}}
  (jobs/'test-v1.json').write_text(json.dumps(job))
  certificate={'packageHash':'package','maximumInputsReviewed':True,'allowedApproaches':['linear scan'],'reviewedBy':'test fixture','languageEvidence':{}}
  for l in job['references']:
   sourcehash=hashlib.sha256(job['references'][l].encode()).hexdigest();proposal=cal.budget(l,100,1048576)
   r={'status':'MEASURED','packageHash':'package','sourceHash':sourcehash,'executionContract':cal.contract(),'proposal':proposal,'qualified':{'verdict':'AC','language':l,'source_sha256':sourcehash,'problem_sha256':stage.digest(job['problem']),'execution_mode':'FUNCTIONAL','judge_all':True,'execution_profile':cal.LANGUAGES[l]|proposal,'tests':[{'id':i,'verdict':'AC','memory_measurement':'cgroup-peak-observed','memory_peak_bytes':1048576} for i in ['sample','max']]}}
   broad=copy.deepcopy(r['qualified']);broad['execution_profile']=cal.LANGUAGES[l]|dict(testWallSeconds=20,memoryMb=cal.LANGUAGES[l]['memoryMb'])
   for test in broad['tests']:test['wall_ms']=100
   r.update(maxWallMs=100,maxMemoryBytes=1048576,reports=[copy.deepcopy(broad) for _ in range(3)])
   (reports/('test-v1-'+l+'.json')).write_text(json.dumps(r));certificate['languageEvidence'][l]={'measurementHash':stage.digest(r)}
  (proof/'test-v1.json').write_text(json.dumps(certificate));return jobs,reports,proof
 def test_ready_release_has_package_inventory_resource_fences_and_removes_sql_on_later_failure(self):
  with tempfile.TemporaryDirectory() as folder:
   root=Path(folder);jobs,reports,proof=self.complete_fixture(root)
   self.assertTrue(stage.stage(jobs,reports,proof,root/'out'))
   sql=(root/'out/resource-release.sql').read_text()
   for text in ['BEGIN;','COMMIT;','LOCK TABLE','active inventory changed','package_sha256','IS NOT DISTINCT FROM NULL','resource or package fence changed']:self.assertIn(text,sql)
   (reports/'test-v1-CPP.json').unlink()
   self.assertFalse(stage.stage(jobs,reports,proof,root/'out'));self.assertFalse((root/'out/resource-release.sql').exists())
 def test_reuse_tracks_the_measured_language_and_exact_corpus_not_unrelated_translation(self):
  with tempfile.TemporaryDirectory() as folder:
   root=Path(folder);jobs,reports,proof=self.complete_fixture(root);job=json.loads((jobs/'test-v1.json').read_text());record=json.loads((reports/'test-v1-JAVA.json').read_text());record.update(maxWallMs=100,maxMemoryBytes=1048576)
   audit=cal.Calibration(SimpleNamespace(jobs=jobs,drivers=root/'drivers',output=root/'out'))
   job['references']['CPP']='new native C++ translation';job['oldLimits']={'JAVA':8}
   self.assertTrue(audit.reusable(job,'JAVA',job['problem'],record))
   changed=json.loads(json.dumps(job['problem']));changed['tests'].append({'id':'new-max'})
   self.assertFalse(audit.reusable(job,'JAVA',changed,record))
   job['allowedReferences']={'JAVA':[{'name':'unmeasured allowed tree','source':'tree'}]}
   self.assertFalse(audit.reusable(job,'JAVA',job['problem'],record));job.pop('allowedReferences')
   job['slow']={'JAVA':'new slow witness'}
   self.assertFalse(audit.reusable(job,'JAVA',job['problem'],record));job.pop('slow')
   job['references']['JAVA']='changed Java reference'
   self.assertFalse(audit.reusable(job,'JAVA',job['problem'],record))
 def test_signed_certificate_cannot_hide_missing_case_changed_profile_or_missing_slow_witness(self):
  for failure in ['case','profile','contract','slow','plan','alternate','missing-replay','stale-peak']:
   with self.subTest(failure=failure),tempfile.TemporaryDirectory() as folder:
    root=Path(folder);jobs,reports,proof=self.complete_fixture(root);p=reports/'test-v1-CPP.json';r=json.loads(p.read_text());c=json.loads((proof/'test-v1.json').read_text())
    if failure=='case':r['qualified']['tests'].pop()
    elif failure=='profile':r['qualified']['execution_profile']['memoryMb']+=16
    elif failure=='contract':r['executionContract']['files']['runner/judge.py']='stale'
    elif failure=='plan':r['qualified']['problem_sha256']='another plan with the same case IDs'
    elif failure=='missing-replay':r['reports'].pop()
    elif failure=='stale-peak':r['maxMemoryBytes']+=1
    elif failure=='alternate':
     path=jobs/'test-v1.json';job=json.loads(path.read_text());job['allowedReferences']={'CPP':[{'name':'ordered tree','source':'unmeasured'}]};path.write_text(json.dumps(job))
    else:c.update(inefficientApproaches=['quadratic scan']);c['languageEvidence']['CPP']['inefficientWitnessSeparated']=True
    p.write_text(json.dumps(r));c['languageEvidence']['CPP']['measurementHash']=stage.digest(r);(proof/'test-v1.json').write_text(json.dumps(c))
    self.assertFalse(stage.stage(jobs,reports,proof,root/'out'));self.assertFalse((root/'out/resource-release.sql').exists())
