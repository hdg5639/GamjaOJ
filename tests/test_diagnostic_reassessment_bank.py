import json
import os
from pathlib import Path
import tempfile
import unittest
from diagnostics.build_reassessment_bank import bank
from diagnostics.build_core_bank import pilot_bank
from runner.judge import Runner,ROOT,validate_problem

class ReassessmentBankTests(unittest.TestCase):
    def test_reproducible_pairs_and_nonidentical_contracts(self):
        data=bank();self.assertEqual(data,json.loads((ROOT/'diagnostics/core-b-v1.json').read_text()))
        self.assertFalse(data['reviewed']);self.assertEqual(len(data['items']),8)
        for a,b in zip(pilot_bank()['items'],data['items']):
            validate_problem(b['problem'])
            self.assertEqual((a['category'],a['difficulty']),(b['category'],b['difficulty']))
            self.assertNotEqual(a['problem']['statement'],b['problem']['statement'])
            self.assertNotEqual(b['reference'],b['additionalMutant'])
            self.assertGreaterEqual(len(b['problem']['tests']),6)
            self.assertIn('A/B correspondence does not prove equivalent difficulty or improvement.',b['rubric']['unobservable'])

@unittest.skipUnless(os.environ.get('GAMJAOJ_DOCKER_TESTS')=='1','requires Docker Runner')
class ReassessmentRunnerTests(unittest.TestCase):
    def test_references_wrong_solutions_and_old_a_solutions(self):
        with tempfile.TemporaryDirectory() as work:
            runner=Runner((ROOT/'runner/java-image.txt').read_text().strip(),work)
            for a,b in zip(pilot_bank()['items'],bank()['items']):
                for kind,code,expected in [('reference',b['reference'],'AC'),('mutant',b['mutant'],'WA'),('additional',b['additionalMutant'],'WA'),('old A reference',a['reference'],None)]:
                    result=runner.judge(code.encode(),b['problem'])
                    if expected:self.assertEqual(result['verdict'],expected,(b['problem']['version'],kind,result))
                    else:self.assertIn(result['verdict'],['WA','RE','TLE'])
                    print(b['problem']['version'],kind,result['verdict'],flush=True)

class CorrespondenceReleaseTests(unittest.TestCase):
    def test_hash_and_scope_fences(self):
        import importlib.util
        spec=importlib.util.spec_from_file_location('release_pairs',ROOT/'scripts/release-diagnostic-correspondence.py')
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        a=(ROOT/'diagnostics/core-a-v2.json').read_bytes();b=(ROOT/'diagnostics/core-b-v1.json').read_bytes()
        review=json.loads((ROOT/'diagnostics/core-b-v1-review.json').read_text())
        sql=module.release(a,b,review)
        self.assertEqual(sql.count('INSERT INTO diagnostic_reassessment_pair'),8)
        self.assertEqual(sql.count('COMMIT;'),1)
        with self.assertRaises(ValueError):module.release(a+b' ',b,review)
        with self.assertRaises(ValueError):module.release(a,b+b' ',review)
