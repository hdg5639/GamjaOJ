import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
spec=importlib.util.spec_from_file_location('normalize_generation',Path(__file__).parents[1]/'scripts/normalize-generation-validation.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
POLICY=json.loads((Path(__file__).parents[1]/'generation/resource-profiles-v1.json').read_text())
class AuditNormalizationTests(unittest.TestCase):
    def test_missing_certificate_is_pending_and_outputs_no_hidden_inputs_answers_or_sources(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'jobs').mkdir();(root/'intent-certificates').mkdir()
            job={'version':'sample','packageHash':'hash','intent':{'category':'너비 우선 탐색'},'problem':{'tests':[{'input':'SECRET_INPUT','output':'SECRET_OUTPUT'}]},'references':{'JAVA':'SECRET_SOURCE'}}
            (root/'jobs/sample.json').write_text(json.dumps(job))
            value=module.normalize_inventory(root,POLICY)
        self.assertEqual(1,value['pending']);self.assertEqual(['bfs','input-contract'],value['problems'][0]['profileIds'])
        for secret in ('SECRET_INPUT','SECRET_OUTPUT','SECRET_SOURCE'):self.assertNotIn(secret,json.dumps(value))
    def test_changed_package_certificate_is_rejected_not_reused(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'jobs').mkdir();(root/'intent-certificates').mkdir()
            (root/'jobs/sample.json').write_text(json.dumps({'version':'sample','packageHash':'new','problem':{}}))
            (root/'intent-certificates/sample.json').write_text(json.dumps({'packageHash':'old'}))
            with self.assertRaisesRegex(ValueError,'fence mismatch'):module.normalize_inventory(root,POLICY)
