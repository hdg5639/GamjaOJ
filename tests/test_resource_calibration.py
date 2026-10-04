import hashlib,importlib.util,json,tempfile,unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

def load(name,file):
 s=importlib.util.spec_from_file_location(name,Path(__file__).resolve().parents[1]/'scripts'/file);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
cal=load('resource_calibration','calibrate-problem-resources.py');stage=load('resource_stage','stage-resource-limits.py')
class CalibrationTests(unittest.TestCase):
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
 def test_signed_certificate_cannot_hide_missing_case_changed_profile_or_missing_slow_witness(self):
  for failure in ['case','profile','contract','slow','plan','alternate']:
   with self.subTest(failure=failure),tempfile.TemporaryDirectory() as folder:
    root=Path(folder);jobs,reports,proof=self.complete_fixture(root);p=reports/'test-v1-CPP.json';r=json.loads(p.read_text());c=json.loads((proof/'test-v1.json').read_text())
    if failure=='case':r['qualified']['tests'].pop()
    elif failure=='profile':r['qualified']['execution_profile']['memoryMb']+=16
    elif failure=='contract':r['executionContract']['files']['runner/judge.py']='stale'
    elif failure=='plan':r['qualified']['problem_sha256']='another plan with the same case IDs'
    elif failure=='alternate':
     path=jobs/'test-v1.json';job=json.loads(path.read_text());job['allowedReferences']={'CPP':[{'name':'ordered tree','source':'unmeasured'}]};path.write_text(json.dumps(job))
    else:c.update(inefficientApproaches=['quadratic scan']);c['languageEvidence']['CPP']['inefficientWitnessSeparated']=True
    p.write_text(json.dumps(r));c['languageEvidence']['CPP']['measurementHash']=stage.digest(r);(proof/'test-v1.json').write_text(json.dumps(c))
    self.assertFalse(stage.stage(jobs,reports,proof,root/'out'));self.assertFalse((root/'out/resource-release.sql').exists())
