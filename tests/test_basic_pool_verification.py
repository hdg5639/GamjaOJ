"""Regression checks for author verification recovery and generated-input auditing."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / filename)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


verify = module('pool_verify_tests', 'verify-basic-pool.py')
audit = module('pool_audit_tests', 'audit-basic-pool-inputs.py')


class BasicPoolVerificationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        cases = [{'id': str(i), 'input': '1\n', 'output': '1\n'} for i in range(4)]
        self.package = {'version': 'pool-test-v1', 'title': 'Test', 'statement': 'Print the input.',
                        'output_policy': 'TOKEN_EXACT', 'tests': cases,
                        'samples': [{'input': '1\n', 'output': '1\n'} for _ in range(3)]}
        self.meta = {'timeLimits': {'JAVA': 5, 'CPP': 3, 'PYTHON': 8}}
        self.write('package.json', json.dumps(self.package))
        self.write('metadata.json', json.dumps(self.meta))
        self.write('qa/checks.py', 'def validate(t):\n assert t.strip()=="1"\ndef oracle(t):return "1\\n"\ndef random_cases(seed,count):return ["1\\n"]*count\n')
        mutants = {}
        for lang in verify.LANGUAGES:
            name = {'JAVA': 'Main.java', 'CPP': 'Main.cpp', 'PYTHON': 'Main.py'}[lang]
            self.write('solutions/'+lang.lower()+'/'+name, 'correct')
            mutants[lang] = {}
            for i in range(2 if lang == 'JAVA' else 1):
                relative = 'mutants/'+lang.lower()+'/'+str(i)+'/'+name
                self.write(relative, 'wrong')
                mutants[lang][str(i)] = relative
        self.write('mutants.json', json.dumps(mutants))
        self.calls = []

    def write(self, relative, text):
        path = self.directory/relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def judge(self, language, source, package, limits):
        self.calls.append((language, source))
        wrong = source == 'wrong'
        tests = [{'id': x['id'], 'verdict': 'WA' if wrong and i >= 3 else 'AC',
                  'wall_ms': 50, 'memory_measurement': 'cgroup-peak-observed', 'memory_peak_bytes': 1024}
                 for i, x in enumerate(package['tests'])]
        return {'verdict': 'WA' if wrong else 'AC', 'tests': tests}

    def test_resume_reuses_only_identical_artifacts_and_runtime(self):
        with patch.object(verify, 'judge', self.judge):
            report = verify.verify(self.directory)
            self.assertEqual('PASS', report['status'])
            self.calls.clear()
            verify.verify(self.directory)
            self.assertEqual([], self.calls)
            self.write('solutions/python/Main.py', 'correct revised')
            verify.verify(self.directory)
            self.assertEqual(set(verify.LANGUAGES), {language for language, _ in self.calls})
            self.calls.clear()
            progress = json.loads((self.directory/'verification-progress.json').read_text())
            progress['executionContract'] = {'stale': True}
            self.write('verification-progress.json', json.dumps(progress))
            verify.verify(self.directory)
            self.assertEqual(set(verify.LANGUAGES), {language for language, _ in self.calls})

    def test_mid_verification_mutation_cannot_checkpoint_or_finish(self):
        def changed(*args):
            result = self.judge(*args)
            self.write('qa/additional.txt', 'changed')
            return result
        with patch.object(verify, 'judge', changed):
            with self.assertRaisesRegex(ValueError, 'Candidate changed'):
                verify.verify(self.directory)
        self.assertFalse((self.directory/'verification-progress.json').exists())


class GeneratedInputAuditTests(unittest.TestCase):
    def test_fresh_bytes_validate_and_only_hash_is_recorded(self):
        seen = []
        def validator(text):
            seen.append(text)
            if text != '1\n':raise ValueError('invalid input')
        capture = audit.InputAudit(validator)
        self.assertIsNone(capture.get('same-key'))
        capture.put('same-key', {'input': b'1\n'})
        capture.put('same-key', {'input': b'1\n'})
        self.assertEqual(['1\n', '1\n'], seen)
        self.assertEqual([{'inputSha256': hashlib.sha256(b'1\n').hexdigest(), 'inputBytes': 2}]*2, capture.records)
        with self.assertRaisesRegex(ValueError, 'invalid input'):
            capture.put('same-key', {'input': b'2\n'})
        with self.assertRaises(UnicodeDecodeError):capture.put('same-key', {'input': b'\xff'})
        self.assertEqual(2, len(capture.records))
        self.assertIsNone(capture.get('same-key'))
