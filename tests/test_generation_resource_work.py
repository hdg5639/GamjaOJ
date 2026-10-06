import copy
import json
from pathlib import Path
import tempfile
import unittest
from generation.worker import CodexCli, context_spec, review_schema

class ResourceWorkTests(unittest.TestCase):
    def test_prose_repair_uses_one_call_without_regenerating_code_or_oracle(self):
        seen=[]
        class Adapter(CodexCli):
            def context(self, assignment, directory, oracle=False):
                seen.append((assignment,oracle));return {'title':'fixed','statement':'same math'}, {'input_tokens':19}
        with tempfile.TemporaryDirectory() as path:
            result=Adapter(path).produce({'spec':{'phase':'EXPERIMENTAL_PROSE_REPAIR','definition':{'constraints':'fixed'}},'model':'fixture','effort':'medium'},Path(path))
        self.assertEqual(1,len(seen));self.assertIsNone(result['oracle']);self.assertEqual(19,result['usage']['author']['input_tokens'])

    def test_maximum_review_never_receives_solution_sources_or_expected_hidden_answers(self):
        seen=[]
        class Adapter(CodexCli):
            def context(self, assignment, directory, oracle=False):
                seen.append(copy.deepcopy(assignment))
                if assignment['spec']['phase']=='RESOURCE_QUALIFICATION':
                    return {'cpp':'PRIVATE_CPP','python':'PRIVATE_PY','ordinaryJava':'PRIVATE_JAVA','maximumGenerator':'GENERATOR','coverage':[]},{'input_tokens':11}
                return {'accepted':True,'issues':[]},{'input_tokens':12}
        work={'model':'fixture','effort':'medium','spec':{'phase':'RESOURCE_QUALIFICATION','definition':{'inputDefinition':'format'},'reference':'PRIVATE_REF','validator':'PRIVATE_VALIDATOR','package':{'tests':[{'input':'HIDDEN_INPUT','output':'HIDDEN_OUTPUT'}]}}}
        with tempfile.TemporaryDirectory() as path:result=Adapter(path).produce(work,Path(path))
        review=json.dumps(seen[1]);self.assertEqual('RESOURCE_MAXIMUM_REVIEW',seen[1]['spec']['phase'])
        for private in ('PRIVATE_REF','PRIVATE_VALIDATOR','PRIVATE_CPP','PRIVATE_PY','PRIVATE_JAVA','HIDDEN_INPUT','HIDDEN_OUTPUT'):self.assertNotIn(private,review)
        self.assertIn('GENERATOR',review);self.assertEqual(12,result['usage']['oracle']['input_tokens'])

    def test_new_scope_schema_is_explicit_without_breaking_legacy_reviews(self):
        self.assertNotIn('failureScope',review_schema()['properties'])
        self.assertEqual(['PROSE','IMPLEMENTATION','CONTRACT','REVIEW'],review_schema(recovery=True)['properties']['failureScope']['enum'])
        redacted=context_spec({'phase':'RESOURCE_MAXIMUM_REVIEW','definition':{'referenceStrategy':'PRIVATE','oracleStrategy':'PRIVATE','constraints':'FIXED'}})
        self.assertEqual({'constraints':'FIXED'},redacted['definition'])
