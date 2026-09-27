"""Candidate content checks. Docker results do not constitute human pedagogical review."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from diagnostics.build_core_bank import bank, pilot_bank
from runner.judge import ROOT, Runner, validate_problem

class DiagnosticBankTests(unittest.TestCase):
    def test_reproducible_fixed_pairs_and_private_rubrics(self):
        data=json.loads((ROOT/'diagnostics/core-a-v1.json').read_text())
        self.assertEqual(data,bank())
        self.assertFalse(data['reviewed'])
        self.assertEqual(len(data['items']),8)
        self.assertEqual(len({i['problem']['version'] for i in data['items']}),8)
        groups={}
        for item in data['items']:
            validate_problem(item['problem'])
            groups.setdefault(item['category'],[]).append(item['difficulty'])
            for field in ('skills','positiveEvidence','negativeEvidence','unobservable'):
                self.assertTrue(item['rubric'][field])
            self.assertGreaterEqual(len(item['problem']['tests']),5)
        self.assertEqual(len(groups),4)
        for pair in groups.values():self.assertEqual(sorted(pair),['EASY','MEDIUM'])

class DiagnosticStagingTests(unittest.TestCase):
    def test_staging_is_private_and_rejects_incomplete_or_published_banks(self):
        spec=importlib.util.spec_from_file_location('stage_bank',ROOT/'scripts/stage-diagnostic-bank.py')
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        sql=module.stage(bank())
        self.assertIn("VALUES ('core-a-v1',false)",sql)
        self.assertEqual(sql.count('INSERT INTO problem_version'),8)
        self.assertNotIn('public class Main',sql)
        for change in ['reviewed','pair','duplicate','rubric','run-only']:
            data=copy.deepcopy(bank())
            if change=='reviewed':data['reviewed']=True
            if change=='pair':data['items'].pop()
            if change=='duplicate':data['items'].append(data['items'][0])
            if change=='rubric':data['items'][0]['rubric'].pop('unobservable')
            if change=='run-only':data['items'][0]['problem']['output_policy']='RUN_ONLY'
            with self.subTest(change=change),self.assertRaises(ValueError):module.stage(data)

@unittest.skipUnless(os.environ.get('GAMJAOJ_DOCKER_TESTS')=='1','requires isolated Docker Runner')
class DiagnosticBankRunnerTests(unittest.TestCase):
    data=staticmethod(bank)
    def test_all_references_and_targeted_mutants(self):
        with tempfile.TemporaryDirectory() as directory:
            runner=Runner((ROOT/'runner/java-image.txt').read_text().strip(),directory)
            for item in self.data()['items']:
                with self.subTest(version=item['problem']['version']):
                    for kind,expected in [('reference','AC'),('mutant','WA')]:
                        result=runner.judge(item[kind].encode(),item['problem'])
                        self.assertEqual(result['verdict'],expected,(item['problem']['version'],kind,result))
                        if kind=='reference':self.assertEqual(len(result['tests']),len(item['problem']['tests']))
                        print(item['problem']['version'],kind,result['verdict'],flush=True)

    def test_additional_boundary_and_strategy_mutants(self):
        replacements=[
            ("t/60%24","t%24"),
            ("r=x;c=y;}}","r=x;c=y;}else break;}"),
            ("best=1,cur=1","best=0,cur=1"),
            ("long[] p=new long", "int[] p=new int"),
            ("System.out.println(a.size());", "System.out.println(n);") ,
            ("ok&&d.isEmpty()", "ok"),
            ("k<4", "k<2"),
            ("System.out.println(d[h-1][w-1]);", "System.out.println(d[h-1][w-1]<0?-1:h+w-2);")
        ]
        with tempfile.TemporaryDirectory() as directory:
            runner=Runner((ROOT/'runner/java-image.txt').read_text().strip(),directory)
            for item,(old,new) in zip(self.data()['items'],replacements):
                with self.subTest(version=item['problem']['version']):
                    self.assertIn(old,item['reference'])
                    mutant=item['reference'].replace(old,new)
                    if item['category']=='arrays-strings' and item['difficulty']=='MEDIUM':
                        mutant=mutant.replace('s.nextLong()','s.nextInt()')
                    result=runner.judge(mutant.encode(),item['problem'])
                    self.assertEqual(result['verdict'],'WA',(item['problem']['version'],result))
                    print(item['problem']['version'],'additional mutant WA',flush=True)

class DiagnosticPilotRunnerTests(DiagnosticBankRunnerTests):
    data=staticmethod(pilot_bank)


ALGO_MIX=ROOT/'diagnostics/private/algo-mix-a-v1.json'


@unittest.skipUnless(ALGO_MIX.exists(),'private bank artifact is kept outside the public repository')
class AlgoMixBankTests(unittest.TestCase):
    """Imported member-authored bank: structure, privacy of verification material and private staging."""
    def setUp(self):
        self.data=json.loads(ALGO_MIX.read_text())

    def test_statements_use_ascii_minus_matching_expected_outputs(self):
        for item in self.data['items']:
            self.assertNotIn('\u2212',item['problem']['statement'],item['problem']['version'])
            self.assertNotIn('testNotes',item['rubric'])

    def test_ten_categories_with_exact_pairs_and_contract_limits(self):
        from runner.judge import validate_problem
        pairs={}
        for item in self.data['items']:
            validate_problem(item['problem'])
            pairs.setdefault(item['category'],[]).append(item['difficulty'])
            p=item['problem']
            self.assertTrue(p['version'].startswith('diagnostic-algo-mix-a-v1-') and len(p['version'])<=80)
            self.assertEqual('T01',p['tests'][0]['id'])
            self.assertTrue(all(len(t['input'].encode())<=65536 and len(t['output'].encode())<=65536 for t in p['tests']))
            # Only statement-level fields reach the staged package; verification material stays outside it.
            self.assertEqual(set(p)-{'generated'},{'version','title','statement','output_policy','tests'})
            for field in ('skills','positiveEvidence','negativeEvidence','unobservable','evidencePolicy'):
                self.assertTrue(item['rubric'][field])
            self.assertNotIn('**',p['statement']);self.assertNotIn('| Java 8',p['statement'])
        self.assertEqual({k:sorted(v) for k,v in pairs.items()},{c:['EASY','MEDIUM'] for c in
            ['arrays-strings','basic-data-structures','bfs','dfs','backtracking','dp','binary-search','greedy','graph','mst']})
        generated=[i for i in self.data['items'] if 'generated' in i['problem']]
        self.assertEqual(3,len(generated))
        self.assertEqual(143,sum(len(i['problem']['tests']) for i in self.data['items']))
        for item in generated:
            self.assertEqual(item['reference'],item['problem']['generated']['reference'])
            self.assertEqual(['11','12'],[t['seed'] for t in item['problem']['generated']['tests']])

    def test_staging_stays_unreviewed(self):
        spec=importlib.util.spec_from_file_location('stage_bank',ROOT/'scripts/stage-diagnostic-bank.py')
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        sql=module.stage(self.data)
        self.assertIn("VALUES ('algo-mix-a-v1',false)",sql)
        self.assertNotIn('"languages"',sql);self.assertNotIn('"slow"',sql);self.assertNotIn('"mutants"',sql)
