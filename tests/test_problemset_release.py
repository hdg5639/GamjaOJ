import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('problemset_stage',ROOT/'scripts/stage-problemset.py');stage=importlib.util.module_from_spec(spec);spec.loader.exec_module(stage)
class ReleaseTest(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name);self.source=self.root/'source';path='BFS/example';original=self.source/path;original.mkdir(parents=True);self.directory=self.root/'candidate';self.directory.mkdir()
  sample='2\n1\n2\n';hidden='10\n'+'1\n'*10;so='#1 1\n#2 2\n';ho=''.join(f'#{i} 1\n' for i in range(1,11));values={'문제.txt':'문제','Solution.java':'original reference','sample_input.txt':sample,'sample_output.txt':so,'input.txt':hidden,'output.txt':ho}
  for name,value in values.items():(original/name).write_text(value)
  version='iamywl-v1-'+hashlib.sha256(path.encode()).hexdigest()[:16]
  self.package=dict(version=version,title='예제',statement='https://github.com/iamywl/problemset/tree/'+stage.COMMIT+'/'+path,output_policy='TOKEN_EXACT',samples=[dict(input=sample,output=so)],tests=[dict(id='upstream-samples',input=sample,output=so),dict(id='upstream-hidden',input=hidden,output=ho)])
  meta=dict(version=version,upstreamCommit=stage.COMMIT,upstreamPath=path,sourceHashes={name:hashlib.sha256(value.encode()).hexdigest() for name,value in values.items()},sourceCaseCounts=dict(samples=2,hidden=10),originalCategory='BFS',adaptations=[])
  (self.directory/'metadata.json').write_text(json.dumps(meta));(self.directory/'reference.java').write_text('reference');self.review=dict(upstreamPath=path,thinking=dict(layer=3,insight=2,implementation=2,edgeCases=3,rationale='연결 관계를 탐색합니다.'));self.save()
 def save(self):
  (self.directory/'package.json').write_text(stage.canonical(self.package));fingerprint=stage.digest(self.package);ref=hashlib.sha256((self.directory/'reference.java').read_bytes()).hexdigest();report=dict(problem_sha256=fingerprint,source_sha256=ref,verdict='AC',judge_all=True,tests=[dict(id=t['id'],verdict='AC') for t in self.package['tests']],execution_profile=stage.checked_profile(stage.LANGUAGES['JAVA']|dict(testWallSeconds=10),'JAVA',stage.LANGUAGES['JAVA']['image']),runner_environment=dict(contract=stage.contract()));self.evidence=dict(packageHash=fingerprint,referenceHash=ref,report=report);self.write_evidence()
 def write_evidence(self):(self.directory/'verification.json').write_text(json.dumps(self.evidence))
 def load(self):return stage.load(self.directory,self.source,self.review)
 def test_passes_attributed_frozen_java_evidence(self):self.assertEqual(self.load()[3],'너비 우선 탐색')
 def test_rejects_changed_package_or_reference(self):
  (self.directory/'reference.java').write_text('changed')
  with self.assertRaisesRegex(ValueError,'Changed package'):self.load()
 def test_rejects_changed_runner_contract(self):
  self.evidence['report']['runner_environment']['contract']={};self.write_evidence()
  with self.assertRaisesRegex(ValueError,'Runner contract'):self.load()
 def test_rejects_missing_supplied_hidden_inputs_even_with_passing_receipt(self):
  self.package['tests'][1]['input']='1\n1\n';self.package['tests'][1]['output']='#1 1\n';self.save()
  with self.assertRaisesRegex(ValueError,'hidden input omitted'):self.load()
 def test_rejects_partial_receipt(self):
  self.evidence['report']['tests'].pop();self.write_evidence()
  with self.assertRaisesRegex(ValueError,'Incomplete'):self.load()
 def test_rejects_fractional_or_boolean_profile(self):
  for value in (True,3.5):
   self.review['thinking']['layer']=value
   with self.assertRaisesRegex(ValueError,'Profile range'):self.load()
 def test_missing_title_cannot_be_published_with_a_passing_receipt(self):
  for title in ('   ',None,42):
   with self.subTest(title=title):
    self.package['title']=title;self.save()
    with self.assertRaisesRegex(ValueError,'public title'):self.load()
 def test_entire_release_required(self):
  with self.assertRaisesRegex(ValueError,'Entire 358'):stage.stage(self.root,self.source,{})
if __name__=='__main__':unittest.main()
