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
        self.package = {'version': 'basic-pool-v1-test-easy-01-v1', 'title': 'Test', 'statement': 'Print the input.',
                        'output_policy': 'TOKEN_EXACT', 'tests': cases,
                        'samples': [{'input': '1\n', 'output': '1\n'} for _ in range(3)]}
        self.meta = {'version': self.package['version'], 'timeLimits': {'JAVA': 5, 'CPP': 3, 'PYTHON': 8}}
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

    def test_invalid_version_identity_fails_before_runner_calls(self):
        changed=dict(self.package,version='basic-pool-v2-test-easy-01-v2')
        self.write('package.json',json.dumps(changed))
        with patch.object(verify,'judge') as run:
            with self.assertRaisesRegex(ValueError,'Invalid ordinary version identity'):
                verify.verify(self.directory)
            run.assert_not_called()

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

    def test_generator_probe_preserves_original_generator_and_seed(self):
        with tempfile.TemporaryDirectory() as state:
            directory=Path(state)
            (directory/'qa').mkdir()
            (directory/'qa/checks.py').write_text('def validate(t):\n assert int(t.strip()) in (1,2)\n')
            package={'version':'audit-test-v1','output_policy':'TOKEN_EXACT',
                     'tests':[{'id':'sample','input':'1\n','output':'1\n'}],
                     'generated':{'generator':'public class Main {}','reference':'public class Main {}',
                                  'tests':[{'id':'large-seed-11','seed':'11','expected':'REFERENCE'}]}}
            (directory/'package.json').write_text(json.dumps(package))
            (directory/'metadata.json').write_text(json.dumps({'id':'BP999','timeLimits':{'JAVA':5}}))
            owner=self
            class ProbeRunner:
                def __init__(self,image,state):self.image=image
                def judge(self,source,probe):
                    audit.verify.validate_problem(probe)
                    owner.assertIn(b'VALID',source)
                    owner.assertEqual(package['generated']['generator'],probe['generated']['generator'])
                    owner.assertNotIn('reference',probe['generated'])
                    owner.assertEqual([{'id':'large-seed-11','seed':'11','expected':'VALID'}],probe['generated']['tests'])
                    self.generated_cache.put(None,{'input':b'2\n'})
                    return {'verdict':'AC','tests':[{'id':'large-seed-11','kind':'generated',
                             'input_sha256':hashlib.sha256(b'2\n').hexdigest(),'input_bytes':2}]}
            with patch.object(audit.verify,'Runner',ProbeRunner):result=audit.audit(directory)
            self.assertEqual('PASS',result['status'])
            self.assertEqual(audit.verify.digest(package),result['packageSha256'])
            self.assertEqual([{'inputSha256':hashlib.sha256(b'2\n').hexdigest(),'inputBytes':2,'id':'large-seed-11'}],result['generated'])

class BasicPoolApiPreflightTests(unittest.TestCase):
    setUp=BasicPoolVerificationTests.setUp
    write=BasicPoolVerificationTests.write
    def test_missing_later_directory_fails_before_remote_user_creation(self):
        import sys
        smoke=module('pool_smoke_tests','smoke-basic-pool.py')
        with patch.object(sys,'argv',['smoke-basic-pool.py',str(self.directory),str(self.directory/'missing'),'--output',str(self.directory/'live.json')]):
            with patch.object(smoke.helper,'ssh') as remote:
                with self.assertRaises(FileNotFoundError):smoke.main()
                remote.assert_not_called()

    def test_missing_solution_fails_before_remote_user_creation(self):
        import sys
        smoke=module('pool_smoke_source_tests','smoke-basic-pool.py')
        (self.directory/'solutions/cpp/Main.cpp').unlink()
        with patch.object(sys,'argv',['smoke-basic-pool.py',str(self.directory),'--output',str(self.directory/'live.json')]):
            with patch.object(smoke.helper,'ssh') as remote:
                with self.assertRaises(FileNotFoundError):smoke.main()
                remote.assert_not_called()
