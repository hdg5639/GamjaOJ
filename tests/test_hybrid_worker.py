import json
import sys
import tempfile
import unittest
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch
from generation.worker import CodexCli, hybrid_once, rule_author_once, atomic, main, quota_exhausted


def assignment(role='CONTRACT'):
    token = str(uuid.uuid4())
    envelope = dict(branchId=str(uuid.uuid4()), generationId=str(uuid.uuid4()), revision=0,
                    role=role, token=token, inputHash='input', contractHash=None, publicHash=None)
    return dict(pipelineVersion='HYBRID_V1', id=envelope['generationId'], token=token,
                model='configured-core', effort='medium',
                deadlineAt=(datetime.now(timezone.utc) + timedelta(seconds=120)).isoformat(),
                spec=dict(phase='HYBRID_V1', role=role, input={'request': 'fixed task'},
                          assignment=envelope, instructions='ROLE_SPECIFIC_INSTRUCTIONS'),
                outputSchema={'type': 'object', 'properties': {}, 'required': [], 'additionalProperties': False})


def rule_assignment():
    work = assignment('AUTHOR')
    work['pipelineVersion'] = work['spec']['phase'] = 'RULE_AUTHOR_V1'
    work['spec']['assignment'] = dict(attemptId=str(uuid.uuid4()), token=work['token'], inputHash='rule-input')
    work['effort'] = 'high'
    return work


class Api:
    def __init__(self, work=None):
        self.work, self.results, self.fail = work, [], False

    def post(self, path, body):
        if path == '/hybrid/claim':
            work, self.work = self.work, None
            return work
        assert path == '/hybrid/result'
        self.results.append(body)
        if self.fail:
            raise OSError('response lost')


class HybridWorkerTests(unittest.TestCase):
    def test_rule_author_redelivery_and_restart_preserve_envelope_without_reinvocation(self):
        class RuleApi(Api):
            def post(self, path, body):
                assert path.startswith('/rule-author/')
                return super().post(path.replace('/rule-author/', '/hybrid/'), body)
        class Adapter(CodexCli):
            def context(self, work, directory, oracle=False):
                self.calls = getattr(self, 'calls', 0) + 1
                assert work['spec']['role'] == 'AUTHOR' and not oracle
                return {'rules': []}, None
        with tempfile.TemporaryDirectory() as tmp:
            state = Path(tmp); api = RuleApi(rule_assignment()); adapter = Adapter(tmp)
            api.fail = True
            with self.assertRaises(OSError):
                rule_author_once(api, adapter, state)
            api.fail = False
            self.assertFalse(rule_author_once(api, adapter, state))
            self.assertEqual(1, adapter.calls)
            self.assertEqual(api.results[0], api.results[1])
            self.assertIn('attemptId', api.results[0])
            self.assertNotIn('branchId', api.results[0])
            work = rule_assignment()
            atomic(state / work['token'] / 'assignment.json', work)
            self.assertFalse(rule_author_once(api, adapter, state))
            self.assertEqual('INTERRUPTED_USAGE_UNKNOWN', api.results[-1]['error'])
            self.assertIsNone(api.results[-1]['usage']['providerUsage'])
            self.assertEqual(1, adapter.calls)

    def test_rule_author_has_priority_without_parallel_legacy_or_hybrid_calls(self):
        with tempfile.TemporaryDirectory() as tmp:
            with patch('sys.argv', ['worker', '--state', tmp, '--once', '--hybrid']), patch.dict('os.environ', {
                    'GAMJAOJ_API_URL': 'http://unused.test', 'GENERATION_WORKER_TOKEN': 'x' * 32,
                    'GENERATION_CODEX_HOME': tmp}), patch('generation.worker.Api'), patch('generation.worker.CodexCli'), \
                    patch('generation.worker.rule_author_once', return_value=True) as rule, \
                    patch('generation.worker.hybrid_once') as hybrid, patch('generation.worker.once') as legacy:
                main()
                rule.assert_called_once()
                self.assertEqual(Path(tmp) / 'rule-author', rule.call_args.args[2])
                hybrid.assert_not_called(); legacy.assert_not_called()

    def test_mixed_mode_preserves_legacy_and_never_runs_two_calls_at_once(self):
        with tempfile.TemporaryDirectory() as tmp:
            for has_hybrid in (False, True):
                with patch('sys.argv', ['worker', '--state', tmp, '--once']), patch.dict('os.environ', {
                        'GAMJAOJ_API_URL': 'http://unused.test', 'GENERATION_WORKER_TOKEN': 'x' * 32,
                        'GENERATION_CODEX_HOME': tmp, 'GENERATION_HYBRID_ENABLED': 'true'}), \
                        patch('generation.worker.Api'), patch('generation.worker.CodexCli'), \
                        patch('generation.worker.rule_author_once', return_value=False), \
                        patch('generation.worker.hybrid_once', return_value=has_hybrid) as hybrid, \
                        patch('generation.worker.once', return_value=True) as legacy:
                    main()
                    hybrid.assert_called_once()
                    self.assertEqual(Path(tmp) / 'hybrid', hybrid.call_args.args[2])
                    self.assertEqual(0 if has_hybrid else 1, legacy.call_count)

    def test_main_requires_explicit_hybrid_flag_and_separates_state(self):
        with tempfile.TemporaryDirectory() as tmp:
            for enabled in (False, True):
                args = ['worker', '--state', tmp, '--once'] + (['--hybrid'] if enabled else [])
                with patch('sys.argv', args), patch.dict('os.environ', {
                        'GAMJAOJ_API_URL': 'http://unused.test', 'GENERATION_WORKER_TOKEN': 'x' * 32,
                        'GENERATION_CODEX_HOME': tmp}), \
                        patch('generation.worker.once', return_value=False) as legacy, \
                        patch('generation.worker.rule_author_once', return_value=False), \
                        patch('generation.worker.hybrid_once', return_value=False) as hybrid:
                    main()
                    self.assertEqual(0 if enabled else 1, legacy.call_count)
                    self.assertEqual(1 if enabled else 0, hybrid.call_count)
                    if enabled:
                        self.assertEqual(Path(tmp) / 'hybrid', hybrid.call_args.args[2])

    def test_two_separate_calls_no_implicit_oracle_and_usage_preserved(self):
        seen = []
        class Adapter(CodexCli):
            def context(self, work, directory, oracle=False):
                seen.append((work['spec']['role'], directory, oracle))
                return {'schemaVersion': '1'}, None
        with tempfile.TemporaryDirectory() as tmp:
            state = Path(tmp)
            adapter = Adapter(tmp)
            api = Api(assignment())
            self.assertTrue(hybrid_once(api, adapter, state))
            api.work = assignment('CORE')
            self.assertTrue(hybrid_once(api, adapter, state))
            self.assertEqual(['CONTRACT', 'CORE'], [s[0] for s in seen])
            self.assertNotEqual(seen[0][1], seen[1][1])
            self.assertFalse(any(s[2] for s in seen))
            self.assertIsNone(api.results[0]['usage']['providerUsage'])
            self.assertEqual('CORE', api.results[1]['role'])
            self.assertNotIn('oracle', api.results[1])

    def test_lost_ack_redelivers_exact_saved_result_without_model(self):
        calls = []
        class Adapter:
            def produce(self, work, directory):
                calls.append(work)
                return {'payload': {'schemaVersion': '1'}, 'usage': None, 'error': None}
        with tempfile.TemporaryDirectory() as tmp:
            api = Api(assignment()); api.fail = True
            with self.assertRaises(OSError):
                hybrid_once(api, Adapter(), Path(tmp))
            api.fail = False
            self.assertFalse(hybrid_once(api, Adapter(), Path(tmp)))
            self.assertEqual(1, len(calls))
            self.assertEqual(api.results[0], api.results[1])

    def test_process_restart_never_repeats_unknown_usage_invocation(self):
        with tempfile.TemporaryDirectory() as tmp:
            work = assignment(); state = Path(tmp)
            atomic(state / work['token'] / 'assignment.json', work)
            api = Api()
            self.assertFalse(hybrid_once(api, object(), state))
            self.assertEqual('INTERRUPTED_USAGE_UNKNOWN', api.results[0]['error'])
            self.assertIsNone(api.results[0]['usage']['providerUsage'])
            self.assertEqual(work['spec']['assignment']['branchId'], api.results[0]['branchId'])
            self.assertFalse(hybrid_once(api, object(), state))
            self.assertEqual(1, len(api.results))

    def test_cli_receives_only_role_data_read_only_no_secrets_and_complete_schema(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'auth.json').write_text(json.dumps({'auth_mode': 'chatgpt'}))
            cli = root / 'fake-codex'
            cli.write_text('#!' + sys.executable + '\n' + '''import json, pathlib, sys, os
if '--version' in sys.argv:
    print('codex-cli 0.155.1'); sys.exit(0)
a=sys.argv[1:]
p=pathlib.Path(a[a.index('--output-last-message')+1])
p.write_text('{}')
p.with_name('captured.json').write_text(json.dumps({'args':a,'prompt':sys.stdin.read(),'env':dict(os.environ)}))
print(json.dumps({'type':'turn.completed','usage':{'input_tokens':1}}))
''')
            cli.chmod(0o700)
            work = assignment('CORE')
            work['spec']['assignment']['privateMarker'] = 'PRIVATE_ENVELOPE'
            with patch.dict('os.environ', {'OPENAI_API_KEY': 'PRIVATE_API', 'GENERATION_WORKER_TOKEN': 'PRIVATE_WORKER'}):
                result = CodexCli(root, str(cli)).produce(work, root)
            captured = json.loads((root / 'core' / 'captured.json').read_text())
            args = captured['args']
            self.assertEqual('read-only', args[args.index('--sandbox') + 1])
            self.assertIn('features.shell_tool=false', args)
            self.assertIn('--ignore-rules', args)
            self.assertIn('ROLE_SPECIFIC_INSTRUCTIONS', captured['prompt'])
            self.assertNotIn('PRIVATE_ENVELOPE', captured['prompt'])
            self.assertNotIn('prefix-sum', captured['prompt'])
            self.assertNotIn('OPENAI_API_KEY', captured['env'])
            self.assertNotIn('GENERATION_WORKER_TOKEN', captured['env'])
            self.assertEqual(work['outputSchema'], json.loads((root / 'core' / 'schema.json').read_text()))
            self.assertEqual({'input_tokens': 1}, result['usage']['providerUsage'])

    def test_rule_cli_uses_server_schema_and_instructions(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'auth.json').write_text(json.dumps({'auth_mode': 'chatgpt'}))
            cli = root / 'fake-codex'
            cli.write_text('#!' + sys.executable + '\n' + '''import json, pathlib, sys, os
if '--version' in sys.argv:
    print('codex-cli 0.155.1'); sys.exit(0)
a=sys.argv[1:]
p=pathlib.Path(a[a.index('--output-last-message')+1])
p.write_text('{}')
p.with_name('captured.json').write_text(json.dumps({'args':a,'prompt':sys.stdin.read(),'env':dict(os.environ)}))
print(json.dumps({'type':'turn.completed','usage':{'input_tokens':1}}))
''')
            cli.chmod(0o700)
            work = rule_assignment()
            work['spec']['assignment']['privateMarker'] = 'PRIVATE_ENVELOPE'
            with patch.dict('os.environ', {'OPENAI_API_KEY': 'PRIVATE_API', 'GENERATION_WORKER_TOKEN': 'PRIVATE_WORKER'}):
                result = CodexCli(root, str(cli)).produce(work, root)
            captured = json.loads((root / 'author' / 'captured.json').read_text())
            args = captured['args']
            self.assertEqual('read-only', args[args.index('--sandbox') + 1])
            self.assertIn('features.shell_tool=false', args)
            self.assertIn('--ignore-rules', args)
            self.assertIn('ROLE_SPECIFIC_INSTRUCTIONS', captured['prompt'])
            self.assertNotIn('PRIVATE_ENVELOPE', captured['prompt'])
            self.assertNotIn('prefix-sum', captured['prompt'])
            self.assertNotIn('OPENAI_API_KEY', captured['env'])
            self.assertNotIn('GENERATION_WORKER_TOKEN', captured['env'])
            self.assertEqual(work['outputSchema'], json.loads((root / 'author' / 'schema.json').read_text()))
            self.assertEqual({'input_tokens': 1}, result['usage']['providerUsage'])

    def test_expired_assignment_does_not_launch_codex_execution(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'auth.json').write_text(json.dumps({'auth_mode': 'chatgpt'}))
            adapter = CodexCli(root); adapter.version_checked = True
            work = assignment(); work['deadlineAt'] = '2020-01-01T00:00:00Z'
            with patch('subprocess.Popen') as process:
                with self.assertRaisesRegex(RuntimeError, 'HYBRID_DEADLINE_EXCEEDED'):
                    adapter.produce(work, root)
                process.assert_not_called()

    def test_quota_error_event_is_reported_as_quota_without_provider_text(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'auth.json').write_text(json.dumps({'auth_mode': 'chatgpt'}))
            cli = root / 'fake-codex'
            cli.write_text('#!' + sys.executable + '''
import json, sys
if '--version' in sys.argv:
    print('codex-cli 0.155.1'); sys.exit(0)
sys.stdin.read()
print(json.dumps({'type': 'turn.failed', 'error': {'message': "You've hit your usage limit. PRIVATE_ACCOUNT_DETAIL"}}))
sys.exit(1)
''')
            cli.chmod(0o700)
            api = Api(assignment('CORE'))
            hybrid_once(api, CodexCli(root, str(cli)), root / 'state')
            self.assertEqual('CODEX_QUOTA_EXHAUSTED', api.results[0]['error'])
            self.assertNotIn('PRIVATE_ACCOUNT_DETAIL', json.dumps(api.results))

    def test_quota_detection_ignores_ordinary_output_and_other_errors(self):
        self.assertTrue(quota_exhausted(b'{"type":"error","message":"429 Too Many Requests"}\n'))
        self.assertFalse(quota_exhausted(b'{"type":"item.completed","text":"usage limit of arrays"}\n'))
        self.assertFalse(quota_exhausted(b'{"type":"error","message":"model not found"}\nnot json\n'))

    def test_failure_text_redacted_and_usage_unknown(self):
        class Broken:
            def produce(self, work, directory):
                raise RuntimeError('secret path or credential')
        with tempfile.TemporaryDirectory() as tmp:
            api = Api(assignment())
            hybrid_once(api, Broken(), Path(tmp))
            self.assertEqual('CODEX_WORKER_FAILURE', api.results[0]['error'])
            self.assertNotIn('secret', json.dumps(api.results))
            self.assertIsNone(api.results[0]['usage']['providerUsage'])


if __name__ == '__main__':
    unittest.main()
