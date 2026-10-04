import copy,importlib.util,json,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('stage_exam',ROOT/'scripts/stage-diagnostic-bank.py');stage=importlib.util.module_from_spec(spec);spec.loader.exec_module(stage)

class ExamDiagnosticStagingTests(unittest.TestCase):
 def candidate(self):
  data=json.loads((ROOT/'diagnostics/core-a-v2.json').read_text());data['id']='exam-a-set-01-v2';data['allocationUnit']='WHOLE_SET'
  for item in data['items']:item['difficulty']={'EASY':'CORE','MEDIUM':'APPLIED'}[item['difficulty']]
  return data
 def test_core_applied_does_not_approve_or_relabel_as_easy(self):
  sql=stage.stage(self.candidate());self.assertIn("'CORE'",sql);self.assertIn("'APPLIED'",sql);self.assertIn("'exam-a-set-01-v2',false",sql);self.assertNotIn('reviewed=true',sql)
 def test_whole_set_rejects_partial_mixed_or_unreviewed_roles(self):
  for mutation in ['partial','mixed','wrong']:
   data=self.candidate()
   if mutation=='partial':data['items']=data['items'][:2]
   if mutation=='mixed':data['items'][0]['difficulty']='EASY'
   if mutation=='wrong':data['items'][0]['difficulty']='HARD'
   with self.assertRaises(ValueError):stage.stage(data)
