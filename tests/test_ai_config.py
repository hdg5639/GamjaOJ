import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('configure_ai',Path(__file__).resolve().parents[1]/'scripts/configure-ai.py')
configure=importlib.util.module_from_spec(spec)
spec.loader.exec_module(configure)

class AiConfigTests(unittest.TestCase):
    def transfer(self,*args):
        with tempfile.TemporaryDirectory() as directory:
            env=Path(directory)/'.env'
            env.write_text('OPENAI_API_KEY=test-key-not-real\n')
            with patch('sys.argv',['configure-ai','--target','app.example.invalid','--env-file',str(env),*args]),patch.object(configure.subprocess,'run') as call:
                configure.main()
                return json.loads(call.call_args.kwargs['input'])
    def test_paid_activation_does_not_require_or_overwrite_operator_accounts(self):
        config=self.transfer('--enable')
        self.assertEqual('true',config['AI_API_ENABLED'])
        self.assertEqual('10',config['AI_MONTHLY_BUDGET_USD'])
        self.assertNotIn('AI_OPERATOR_USERS',config)
    def test_default_remains_disabled_and_roles_change_only_explicitly(self):
        config=self.transfer('--operators','owner')
        self.assertEqual('false',config['AI_API_ENABLED'])
        self.assertEqual('owner',config['AI_OPERATOR_USERS'])
