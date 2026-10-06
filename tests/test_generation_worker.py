import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from generation.worker import CodexCli, once, context_spec, schema, draft_schema, review_schema, final_schema

class GenerationWorkerTests(unittest.TestCase):
    def test_experimental_spec_uses_only_one_author_context_and_no_oracle_or_api(self):
        seen=[]
        class Adapter(CodexCli):
            def context(self,assignment,directory,oracle=False):
                seen.append((assignment,directory,oracle))
                return {'title':'draft'}, {'input_tokens':10}
        with tempfile.TemporaryDirectory() as path:
            result=Adapter(path).produce({'spec':{'phase':'EXPERIMENTAL_SPEC_DRAFT','request':'DP'},'model':'gpt-5.6-sol','effort':'medium','repair':None},Path(path))
        self.assertEqual(1,len(seen));self.assertFalse(seen[0][2])
        self.assertIsNone(result['oracle']);self.assertEqual('experimental-spec-draft-v1',result['usage']['promptProfile'])
        self.assertEqual('CHATGPT_MANAGED',result['usage']['billingMode'])
        self.assertEqual(12,len(draft_schema()['required']))
        self.assertNotIn('reference',draft_schema()['properties'])

    def test_final_plan_has_independent_reviewer_without_solution_strategies(self):
        seen=[]
        class Adapter(CodexCli):
            def context(self,assignment,directory,oracle=False):
                seen.append((context_spec(assignment['spec']),str(directory)))
                return ({'parts':[['1','2'],['a','b']]} if len(seen)==1 else {'accepted':True,'issues':[]}),None
        with tempfile.TemporaryDirectory() as path:
            result=Adapter(path).produce({'model':'gpt-5.6-sol','effort':'medium','spec':{'phase':'EXPERIMENTAL_FINAL_PLAN','request':'ORIGINAL_ROTATION_REQUEST','definition':{'statement':'fixed','referenceStrategy':'PRIVATE','oracleStrategy':'PRIVATE'}}},Path(path))
        self.assertEqual('ORIGINAL_ROTATION_REQUEST',seen[1][0]['request'])
        self.assertEqual(2,len(seen));self.assertNotEqual(seen[0][1],seen[1][1]);self.assertNotIn('PRIVATE',json.dumps(seen))
        self.assertEqual('EXPERIMENTAL_FINAL_REVIEW',seen[1][0]['phase']);self.assertEqual(result['artifacts'],seen[1][0]['plan'])
        self.assertEqual('experimental-publication-v2',result['usage']['promptProfile']);self.assertTrue(result['oracle']['accepted'])
        self.assertEqual({'accepted','issues'},set(final_schema(True)['required']))

    def test_review_is_single_independent_context_with_review_schema(self):
        seen=[]
        class Adapter(CodexCli):
            def context(self,assignment,directory,oracle=False):
                seen.append(context_spec(assignment['spec']))
                return {'verdict':'REVISE','issues':['ambiguous'],'validCases':[],'invalidCases':[],'mutants':[]},None
        spec={'phase':'EXPERIMENTAL_REVIEW','definition':{'statement':'fixed','referenceStrategy':'PRIVATE','oracleStrategy':'PRIVATE'}}
        with tempfile.TemporaryDirectory() as path:result=Adapter(path).produce({'spec':spec,'model':'gpt-5.6-sol','effort':'medium'},Path(path))
        self.assertEqual(1,len(seen));self.assertNotIn('PRIVATE',json.dumps(seen));self.assertIn('referenceStrategy',spec['definition'])
        self.assertIsNone(result['oracle']);self.assertEqual('experimental-independent-review-v1',result['usage']['promptProfile'])
        self.assertEqual({'verdict','issues','validCases','invalidCases','mutants'},set(review_schema()['required']))

    def test_experimental_implementation_oracle_does_not_receive_reference_or_reference_strategy(self):
        seen=[]
        class Adapter(CodexCli):
            def context(self,assignment,directory,oracle=False):
                seen.append((assignment,oracle))
                return ({'source':'independent'} if oracle else {name: ['a','b','c'] if name=='hints' else 'PRIVATE_REFERENCE' if name=='reference' else name for name in schema()['required']}), {'input_tokens':1}
        assignment={'model':'gpt-5.6-sol','effort':'medium','spec':{'phase':'EXPERIMENTAL_IMPLEMENTATION','definition':{'statement':'fixed','referenceStrategy':'PRIVATE_STRATEGY','oracleStrategy':'exhaustive'},'request':'PRIVATE_ORIGINAL_REQUEST','requirementsPolicy':'v1'}}
        with tempfile.TemporaryDirectory() as path:result=Adapter(path).produce(assignment,Path(path))
        self.assertEqual(2,len(seen));self.assertTrue(seen[1][1])
        self.assertNotIn('PRIVATE_REFERENCE',json.dumps(seen[1]))
        self.assertNotIn('PRIVATE_STRATEGY',json.dumps(seen[1]))
        self.assertNotIn('PRIVATE_ORIGINAL_REQUEST',json.dumps(seen[1]))
        self.assertIn('exhaustive',json.dumps(seen[1]))
        self.assertIn('referenceStrategy',assignment['spec']['definition'])
        self.assertEqual('experimental-implementation-v1',result['usage']['promptProfile'])

    def test_oracle_receives_no_personal_feedback_or_learning_focus(self):
        spec={'statement':'sum','learnerFeedback':{'summary':'private code notes'},'learningFocus':'overflow','theme':{'scenario':'PRIVATE_STORY'},'themeDomain':'space','recentStories':[{'title':'PRIVATE_OLD'}]}
        self.assertEqual({'statement':'sum'},context_spec(spec,True))
        self.assertEqual(spec,context_spec(spec,False))
        self.assertIn('learnerFeedback',spec)

    def test_oracle_is_new_context_and_never_receives_reference(self):
        seen=[]
        class Adapter(CodexCli):
            def context(self,assignment,directory,oracle=False):
                seen.append((assignment.copy(),directory,oracle))
                return ({'source':'oracle'} if oracle else {name: ('PRIVATE_REFERENCE' if name=='reference' else ['one','two','three'] if name=='hints' else name) for name in schema()['required']}), {'input_tokens':10}
        with tempfile.TemporaryDirectory() as path:
            result=Adapter(path).produce({'spec':{'statement':'sum'},'model':'gpt-5.6-sol','effort':'medium'},Path(path))
        self.assertEqual(2,len(seen))
        self.assertNotEqual(seen[0][1],seen[1][1])
        self.assertNotIn('PRIVATE_REFERENCE',json.dumps(seen[1][0]))
        self.assertTrue(seen[1][2])
        self.assertEqual('CHATGPT_MANAGED',result['usage']['billingMode'])

    def test_oracle_only_repair_reuses_all_author_artifacts_and_keeps_context_independent(self):
        seen=[]
        class Adapter(CodexCli):
            def context(self,assignment,directory,oracle=False):
                seen.append((assignment,oracle))
                return {'source':'fixed oracle'}, {'input_tokens':10}
        artifacts={name: ('PRIVATE_REFERENCE' if name=='reference' else name) for name in schema()['required']}
        assignment={'spec':{'statement':'sum','learnerFeedback':{'summary':'PRIVATE_LEARNING'}},
                    'model':'gpt-5.6-sol','effort':'medium','repair':{'fields':['oracle'],'artifacts':artifacts,
                    'oracle':{'source':'old independent oracle'},'failedChecks':['oracle-0:CE']}}
        with tempfile.TemporaryDirectory() as path:
            result=Adapter(path).produce(assignment,Path(path))
        self.assertEqual(1,len(seen))
        self.assertTrue(seen[0][1])
        self.assertNotIn('PRIVATE_REFERENCE',json.dumps(seen))
        self.assertNotIn('PRIVATE_LEARNING',json.dumps(seen))
        self.assertEqual(artifacts,result['artifacts'])
        self.assertEqual(['author'],result['usage']['reused'])
        self.assertIsNone(result['usage']['author'])

    def test_generator_only_repair_calls_one_small_schema_and_preserves_oracle(self):
        seen=[]
        class Adapter(CodexCli):
            def context(self,assignment,directory,oracle=False):
                seen.append((assignment,oracle))
                return {'generator':'fixed generator'}, {'input_tokens':10}
        artifacts={name:name for name in schema()['required']}
        assignment={'spec':{'statement':'sum'},'model':'gpt-5.6-sol','effort':'medium',
                    'repair':{'fields':['generator'],'artifacts':artifacts,'oracle':{'source':'unchanged oracle'}}}
        with tempfile.TemporaryDirectory() as path:
            result=Adapter(path).produce(assignment,Path(path))
        self.assertEqual(1,len(seen));self.assertFalse(seen[0][1])
        self.assertEqual(['generator'],seen[0][0]['repair']['fields'])
        self.assertEqual({'generator':'generator'},seen[0][0]['repair']['previous'])
        self.assertEqual('reference',result['artifacts']['reference'])
        self.assertEqual({'source':'unchanged oracle'},result['oracle'])
        self.assertEqual(['oracle'],result['usage']['reused'])
        self.assertEqual(['generator'],schema(False,['generator'])['required'])

    def test_story_only_repair_does_not_regenerate_any_code_or_oracle(self):
        seen=[]
        class Adapter(CodexCli):
            def context(self,assignment,directory,oracle=False):
                seen.append((assignment,oracle))
                return {'title':'새 제목','context':'새 상황'}, {'input_tokens':10}
        artifacts={name:name for name in schema()['required']}
        assignment={'spec':{'templateId':'sequence-sum-v1','theme':{'setting':'공연'}},'model':'gpt-5.6-sol','effort':'medium',
                    'feedback':'STORY_TOO_SIMILAR','repair':{'fields':['title','context'],'artifacts':artifacts,'oracle':{'source':'independent'}}}
        with tempfile.TemporaryDirectory() as path:result=Adapter(path).produce(assignment,Path(path))
        self.assertEqual(1,len(seen));self.assertFalse(seen[0][1])
        for name in ('reference','generator','inputValidator','hints','editorial'):self.assertEqual(artifacts[name],result['artifacts'][name])
        self.assertEqual({'source':'independent'},result['oracle'])
        self.assertEqual(['oracle'],result['usage']['reused'])

    def test_verified_structure_only_authors_new_teaching_and_story(self):
        seen=[]
        class Adapter(CodexCli):
            def context(self,assignment,directory,oracle=False):
                seen.append((assignment,oracle))
                return {'title':'new', 'context':'new story', 'editorial':'new teaching', 'hints':['a','b','c']}, {'input_tokens':10}
        code={'reference':'verified reference', 'generator':'verified generator', 'inputValidator':'verified validator'}
        reuse={'artifacts':code, 'oracle':{'source':'independent verified oracle'}}
        assignment={'spec':{'statement':'sum'}, 'model':'gpt-5.6-sol', 'effort':'medium', 'reuse':reuse}
        with tempfile.TemporaryDirectory() as path:result=Adapter(path).produce(assignment,Path(path))
        self.assertEqual(1,len(seen));self.assertFalse(seen[0][1])
        self.assertEqual(['title','context','editorial','hints'],seen[0][0]['fields'])
        self.assertEqual(code['reference'],seen[0][0]['reuseReference'])
        self.assertNotIn('independent verified oracle',json.dumps(seen))
        self.assertEqual(reuse['oracle'],result['oracle'])
        for key,value in code.items():self.assertEqual(value,result['artifacts'][key])
        self.assertEqual(['reference','generator','inputValidator','oracle'],result['usage']['reused'])
        self.assertIsNone(result['usage']['oracle'])
        self.assertEqual(code,reuse['artifacts'])

    def test_lost_delivery_reuses_saved_output_without_model_retry(self):
        import uuid
        assignment={'id':str(uuid.uuid4()),'token':str(uuid.uuid4())}
        class Api:
            count=0
            def post(self,path,body):
                if path=='/claim':
                    if self.count:return None
                    self.count+=1
                    return assignment
                if not hasattr(self,'failed'):
                    self.failed=True
                    raise OSError('lost response')
        class Adapter:
            count=0
            def produce(self,assignment,path):
                self.count+=1
                return {'artifacts':{},'oracle':{},'usage':None,'error':None}
        api,adapter=Api(),Adapter()
        with tempfile.TemporaryDirectory() as path:
            with self.assertRaises(OSError):once(api,adapter,Path(path))
            once(api,adapter,Path(path))
            self.assertEqual(1,adapter.count)
            self.assertEqual(1,len(list(Path(path).glob('*/completion.delivered'))))

    def test_cli_prompt_profile_preserves_model_sandbox_schema_and_oracle_isolation(self):
        import sys
        with tempfile.TemporaryDirectory() as path:
            root=Path(path)
            (root/'auth.json').write_text('{"auth_mode":"chatgpt"}')
            cli=root/'fake-codex'
            cli.write_text('#!' + sys.executable + '\n' + "import json, os, pathlib, sys\nif '--version' in sys.argv:\n    print('codex-cli 0.160.0'); sys.exit(0)\nargs=sys.argv[1:]\noutput=pathlib.Path(args[args.index('--output-last-message')+1])\noutput.write_text('{}')\noutput.with_name('captured.json').write_text(json.dumps({'args':args,'prompt':sys.stdin.read(),'hasApiKey':'OPENAI_API_KEY' in os.environ}))\nprint(json.dumps({'type':'turn.completed','usage':{'input_tokens':1}}))\n")
            cli.chmod(0o700)
            adapter=CodexCli(root,str(cli))
            assignment={'model':'gpt-5.6-sol','effort':'medium',
                        'spec':{'templateId':'sequence-sum-v1','statement':'sum','learnerFeedback':{'summary':'PRIVATE_NOTES'},'theme':{'scenario':'PRIVATE_THEME'},'recentStories':[{'title':'PRIVATE_OLD_STORY'}]}}
            with patch.dict('os.environ',{'OPENAI_API_KEY':'test-secret-never-forward'}):
                adapter.context(assignment,root/'author')
                adapter.context(assignment,root/'oracle',oracle=True)
            author=json.loads((root/'author/captured.json').read_text())
            oracle=json.loads((root/'oracle/captured.json').read_text())
            args=author['args']
            self.assertEqual('gpt-5.6-sol',args[args.index('--model')+1])
            self.assertIn('model_reasoning_effort="medium"',args)
            self.assertEqual('read-only',args[args.index('--sandbox')+1])
            self.assertIn('features.shell_tool=false',args)
            self.assertFalse(author['hasApiKey']);self.assertFalse(oracle['hasApiKey'])
            self.assertIn('Long.parseLong',author['prompt'])
            self.assertIn('REQUIREMENT FIDELITY v1',author['prompt'])
            self.assertNotIn('REQUIREMENT FIDELITY v1',oracle['prompt'])
            self.assertIn('PRIVATE_NOTES',author['prompt'])
            self.assertNotIn('PRIVATE_NOTES',oracle['prompt'])
            self.assertNotIn('PRIVATE_THEME',oracle['prompt'])
            self.assertNotIn('PRIVATE_OLD_STORY',oracle['prompt'])
            self.assertIn('PRIVATE_THEME',author['prompt'])
            self.assertNotIn('Implementation scope:',oracle['prompt'])
            self.assertIn('BigInteger',oracle['prompt'])
            self.assertEqual(schema(),json.loads((root/'author/schema.json').read_text()))
            self.assertEqual(schema(True),json.loads((root/'oracle/schema.json').read_text()))
            assignment['spec']['templateId']='parentheses-v1'
            adapter.context(assignment,root/'parentheses-author')
            adapter.context(assignment,root/'parentheses-oracle',oracle=True)
            author=json.loads((root/'parentheses-author/captured.json').read_text())['prompt']
            oracle=json.loads((root/'parentheses-oracle/captured.json').read_text())['prompt']
            self.assertIn('unbalanced parentheses string is valid input',author)
            self.assertIn('explicit stack',oracle)
            self.assertNotIn('PRIVATE_NOTES',oracle)
            self.assertNotIn('BigInteger',oracle)
            self.assertNotIn('prefix-sum',author)
            self.assertNotIn('Implementation scope:',author)
            assignment['spec']['templateId']='sequence-recipe-v1-ODD-SQUARE-SUM'
            assignment['spec']['contractFamily']='sequence-recipe-v1'
            assignment['spec']['recipe']={'filter':'ODD','transform':'SQUARE','reduction':'SUM'}
            adapter.context(assignment,root/'recipe-author')
            adapter.context(assignment,root/'recipe-oracle',oracle=True)
            author=json.loads((root/'recipe-author/captured.json').read_text())['prompt']
            oracle=json.loads((root/'recipe-oracle/captured.json').read_text())['prompt']
            self.assertIn('ORIGINAL values first',author)
            self.assertIn('long BEFORE multiplication',author)
            self.assertNotIn('3 / 1 2 3 -> 6',author)
            self.assertIn('BigInteger',oracle)
            self.assertIn('SQUARE',oracle)
            self.assertNotIn('PRIVATE_NOTES',oracle)
            self.assertNotIn('PRIVATE_THEME',oracle)
            assignment['spec']['templateId']='graph-recipe-v1-D-W-DISTANCE'
            assignment['spec']['contractFamily']='graph-recipe-v1'
            assignment['spec']['recipe']={'directed':True,'weighted':True,'query':'DISTANCE'}
            adapter.context(assignment,root/'graph-author')
            adapter.context(assignment,root/'graph-oracle',oracle=True)
            author=json.loads((root/'graph-author/captured.json').read_text())['prompt']
            oracle=json.loads((root/'graph-oracle/captured.json').read_text())['prompt']
            self.assertIn('Dijkstra',author);self.assertIn('Floyd-Warshall',oracle)
            self.assertIn('FOUR complete flattened single-line inputs',author)
            self.assertNotIn('PRIVATE_NOTES',oracle);self.assertNotIn('PRIVATE_THEME',oracle)
            self.assertNotIn('prefix-sum',author);self.assertNotIn('1 2 3 -> 6',author)
            draft={'model':'gpt-5.6-sol','effort':'medium','repair':None,'spec':{'phase':'EXPERIMENTAL_SPEC_DRAFT','request':'DP request'}}
            adapter.context(draft,root/'draft-author')
            capture=json.loads((root/'draft-author/captured.json').read_text())
            self.assertIn('UNVERIFIED',capture['prompt'])
            self.assertIn('DP request',capture['prompt'])
            self.assertNotIn('prefix-sum',capture['prompt'])
            self.assertFalse(capture['hasApiKey'])
            self.assertEqual(draft_schema(),json.loads((root/'draft-author/schema.json').read_text()))
            implementation={'model':'gpt-5.6-sol','effort':'medium','spec':{'phase':'EXPERIMENTAL_IMPLEMENTATION','definition':{'statement':'fixed','referenceStrategy':'PRIVATE_STRATEGY','oracleStrategy':'exhaustive'}}}
            adapter.context(implementation,root/'experimental-author')
            adapter.context(implementation,root/'experimental-oracle',oracle=True)
            author=json.loads((root/'experimental-author/captured.json').read_text())['prompt']
            oracle=json.loads((root/'experimental-oracle/captured.json').read_text())['prompt']
            self.assertIn('JSON ARRAY of FOUR STRINGS',author)
            self.assertNotIn('prefix-sum',author)
            self.assertNotIn('PRIVATE_STRATEGY',oracle)
            review={'model':'gpt-5.6-sol','effort':'medium','spec':{'phase':'EXPERIMENTAL_REVIEW','definition':{'statement':'fixed','referenceStrategy':'PRIVATE_STRATEGY','oracleStrategy':'PRIVATE_ORACLE'}}}
            adapter.context(review,root/'review')
            capture=json.loads((root/'review/captured.json').read_text())
            self.assertNotIn('PRIVATE_STRATEGY',capture['prompt']);self.assertNotIn('PRIVATE_ORACLE',capture['prompt'])
            self.assertIn('REVISE',capture['prompt']);self.assertFalse(capture['hasApiKey'])
            self.assertEqual(review_schema(),json.loads((root/'review/schema.json').read_text()))
            review['spec']['request']='PRIVATE_ORIGINAL_REQUIREMENTS'
            review['spec']['requirementsPolicy']='v1'
            adapter.context(review,root/'requirements-review')
            capture=json.loads((root/'requirements-review/captured.json').read_text())
            self.assertIn('PRIVATE_ORIGINAL_REQUIREMENTS',capture['prompt'])
            self.assertIn('REQUIREMENT FIDELITY REVIEW v1',capture['prompt'])
            self.assertNotIn('PRIVATE_STRATEGY',capture['prompt'])
            self.assertEqual(review_schema(True),json.loads((root/'requirements-review/schema.json').read_text()))

            for phase,review in [('EXPERIMENTAL_FINAL_PLAN',False),('EXPERIMENTAL_FINAL_REVIEW',True)]:
                work={'model':'gpt-5.6-sol','effort':'medium','spec':{'phase':phase,'definition':{'statement':'fixed','referenceStrategy':'PRIVATE_STRATEGY'}}}
                adapter.context(work,root/phase)
                capture=json.loads((root/phase/'captured.json').read_text())
                self.assertNotIn('PRIVATE_STRATEGY',capture['prompt']);self.assertFalse(capture['hasApiKey'])
                self.assertEqual(final_schema(review),json.loads((root/phase/'schema.json').read_text()))






    def test_requirement_review_schema_is_explicit_and_preserves_legacy_shape(self):
        legacy = review_schema()
        current = review_schema(True)
        self.assertNotIn('requirementsReview', legacy['properties'])
        self.assertIn('requirementsReview', current['required'])
        self.assertEqual({'satisfied','coverage','complexity','shortcuts','issues','timeLimits'}, set(current['properties']['requirementsReview']['required']))

    def test_api_auth_never_falls_back_to_api_key(self):
        with tempfile.TemporaryDirectory() as path:
            root=Path(path);(root/'auth.json').write_text('{"auth_mode":"apikey"}')
            with patch('subprocess.Popen') as process:
                with self.assertRaisesRegex(RuntimeError,'NEEDS_CHATGPT_AUTH'):
                    CodexCli(root).context({},root/'context')
                process.assert_not_called()
