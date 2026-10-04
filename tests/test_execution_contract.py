import hashlib
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

from runner.execution_contract import ROOT, contract
from runner.judge import Runner, EXECUTION_CONTRACT, COMPILE_COMMAND, LANGUAGES, checked_profile


class ExecutionContractTests(unittest.TestCase):
    def test_only_bounded_wall_and_memory_budgets_can_vary_and_drive_sandbox_limits(self):
        base = LANGUAGES['PYTHON']
        for changes in ({'testWallSeconds': 0}, {'testWallSeconds': 21}, {'testWallSeconds': True},
                        {'testWallSeconds': 0.1001}, {'memoryMb': 999}, {'memoryMb': True}, {'testCommand': ['unsafe']}, {'extra': 1}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                checked_profile(base | changes, 'PYTHON', base['image'])
        state=json.dumps(dict(Running=False,Error='',ExitCode=0,OOMKilled=False)).encode()
        with tempfile.TemporaryDirectory() as directory, patch('runner.judge.docker',return_value=state) as docker, patch('runner.judge.capture') as capture:
            capture.return_value=dict(limit=None,client_exit=0,stdout=b'',stderr=b'',wall_ms=1)
            runner=Runner(base['image'],directory)
            runner.profile=checked_profile(base | {'testWallSeconds': 0.35, 'memoryMb': 64},'PYTHON',base['image'])
            runner.sandbox(Path(directory),runner.profile['testCommand'])
            self.assertEqual((0.35,65536),capture.call_args.args[-2:])
            args=docker.call_args_list[0].args
            self.assertEqual('64m',args[args.index('--memory')+1])
            runner.sandbox(Path(directory),runner.profile['compileCommand'],compile_phase=True)
            self.assertEqual(30,capture.call_args.args[-2])

    def test_export_matches_sources_and_changes_with_settings_or_build(self):
        exported = json.loads((ROOT/'backend/src/main/resources/runner-execution-contract.json').read_text())
        self.assertEqual(contract(), exported)
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            shutil.copytree(ROOT/'runner',root/'runner',ignore=shutil.ignore_patterns('__pycache__'))
            old=contract(root)
            with (root/'runner/worker.py').open('a') as stream:stream.write('\n# new build\n')
            self.assertNotEqual(old,contract(root))
            self.assertEqual(hashlib.sha256((root/'runner/worker.py').read_bytes()).hexdigest(),contract(root)['files']['runner/worker.py'])
            profile=json.loads((root/'runner/execution-profile.json').read_text())
            profile['testWallSeconds']=6
            (root/'runner/execution-profile.json').write_text(json.dumps(profile))
            self.assertNotEqual(old['profile'],contract(root)['profile'])

    def test_declared_settings_drive_actual_sandbox_arguments_and_capture_limits(self):
        state=json.dumps(dict(Running=False,Error='',ExitCode=0,OOMKilled=False)).encode()
        with tempfile.TemporaryDirectory() as directory, patch('runner.judge.docker',return_value=state) as docker, patch('runner.judge.capture') as capture:
            capture.return_value=dict(limit=None,client_exit=0,stdout=b'',stderr=b'',wall_ms=1)
            runner=Runner((ROOT/'runner/java-image.txt').read_text().strip(),directory)
            runner.sandbox(Path(directory),COMPILE_COMMAND,compile_phase=True)
            args=docker.call_args_list[0].args
            self.assertEqual('384m',args[args.index('--memory')+1])
            self.assertEqual('0.5',args[args.index('--cpus')+1])
            self.assertIn('--read-only',args)
            self.assertIn('35s',args)
            self.assertEqual((30,8*1024*1024),capture.call_args.args[-2:])
            self.assertEqual(EXECUTION_CONTRACT['profile']['sandboxFlags'][0],'--pull')
            runner.sandbox(Path(directory),EXECUTION_CONTRACT['profile']['testCommand'])
            self.assertEqual((5,65536),capture.call_args.args[-2:])
