"""Release guard regressions: stale evidence and identity conflicts cannot publish."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('stage_basic_pool', ROOT / 'scripts/stage-basic-pool.py')
stage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stage)


class BasicPoolReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name)
        self.package = {'version': 'basic-pool-v1-test-easy-01-v1', 'title': '검증 문제', 'statement': '정수 하나를 그대로 출력하세요.',
                        'output_policy': 'TOKEN_EXACT', 'samples': [{'input': str(i)+'\n', 'output': str(i)+'\n'} for i in range(3)],
                        'tests': [{'id': str(i), 'input': str(i)+'\n', 'output': str(i)+'\n'} for i in range(4)]}
        self.meta = {'version': self.package['version'], 'category': '구현', 'tags': ['입출력'], 'difficulty': 'EASY',
                     'timeLimits': {'JAVA': 5, 'CPP': 3, 'PYTHON': 8, 'analysis': '검증 fixture'}}
        self.write('package.json', self.package)
        self.write('metadata.json', self.meta)
        self.write('teaching.json', {'hints': ['입력', '값', '출력'], 'editorial': '정수를 그대로 출력한다.'})
        self.write('review.json', {'decision': 'ACCEPT', 'packageSha256': stage.digest(self.package), 'checks': ['fixture'], 'issues': []})
        self.evidence = {'status': 'PASS', 'packageSha256': stage.digest(self.package), 'metadataSha256': stage.digest(self.meta),
                          'executionContract': stage.contract(), 'timeLimits': self.meta['timeLimits'],
                          'files': {'teaching.json': hashlib.sha256((self.path/'teaching.json').read_bytes()).hexdigest()},
                          'languages': {lang: {'correctVerdict': 'AC', 'completedTests': 4, 'oracleComparedCases': 12,
                                              'mutantsPublicPassPrivateWA': ['fixture'], 'maxWallMs': 100, 'maxMemoryBytes': 1024} for lang in ('JAVA', 'CPP', 'PYTHON')}}
        self.write('verification.json', self.evidence)

    def write(self, name, value):
        (self.path/name).write_text(json.dumps(value, ensure_ascii=False))

    def test_release_is_atomic_ordinary_and_guards_existing_identity(self):
        sql = stage.stage([self.path])
        self.assertTrue(sql.startswith('BEGIN;'))
        self.assertTrue(sql.endswith('COMMIT;\n'))
        self.assertIn('owner_id IS NULL AND diagnostic_only=false', sql)
        self.assertIn("RAISE EXCEPTION 'Existing basic problem differs'", sql)
        self.assertIn("AND shared=true", sql)
        self.assertNotIn('UPDATE problem_version', sql)
        self.assertIn('true,false,true', sql)
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            stage.stage([self.path, self.path])

    def test_package_metadata_and_source_changes_invalidate_evidence(self):
        for file, value in [('package.json', dict(self.package, title='변경')), ('metadata.json', dict(self.meta, tags=['경계']))]:
            with self.subTest(file=file):
                self.write(file, value)
                with self.assertRaises(ValueError): stage.load_candidate(self.path)
                self.write(file, self.package if file=='package.json' else self.meta)
        self.write('teaching.json', {'hints': ['입력', '값', '출력'], 'editorial': '변경'})
        with self.assertRaisesRegex(ValueError, 'artifact changed'): stage.load_candidate(self.path)

    def test_failed_partial_or_stale_runner_cannot_release(self):
        for changed in [dict(self.evidence, status='FAIL'), dict(self.evidence, executionContract={}), dict(self.evidence, languages={})]:
            self.write('verification.json', changed)
            with self.assertRaises(ValueError): stage.load_candidate(self.path)

    def test_public_examples_cannot_be_replaced_by_hidden_cases(self):
        self.package['samples'][0]['output'] = '99\n'
        self.write('package.json', self.package)
        with self.assertRaisesRegex(ValueError, 'Public samples'): stage.load_candidate(self.path)
