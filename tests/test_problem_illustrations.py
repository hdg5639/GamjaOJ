"""Public rule diagrams remain tied to reviewed statements and cannot embed executable content."""
import csv
import hashlib
import importlib.util
import json
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
class ProblemIllustrationContract(unittest.TestCase):
 def test_manifest_is_bound_to_reviewed_text_and_safe_content_hash(self):
  reviewed=json.loads((ROOT/'problems/illustrations/reviewed-statements-v1.json').read_text())
  manifest=json.loads((ROOT/'backend/src/main/resources/problem-illustrations-v1.json').read_text())
  self.assertEqual(len(manifest),26)
  for item in manifest:
   self.assertEqual(item['statementSha256'],reviewed[item['version']])
   data=(ROOT/'frontend/public/problem-illustrations'/item['file']).read_bytes()
   self.assertEqual(item['file'],hashlib.sha256(data).hexdigest()+'.svg')
   doc=ET.fromstring(data)
   self.assertEqual(doc.attrib['viewBox'],'0 0 800 340')
   for node in doc.iter():
    self.assertNotIn(node.tag.split('}')[-1],['script','foreignObject','image','use','animate'])
    self.assertFalse(any(a.startswith('on') or a.endswith('href') for a in node.attrib))
   self.assertEqual(doc.find('{http://www.w3.org/2000/svg}title').text,item['alt'])
 def test_candidate_report_does_not_claim_every_keyword_hit_is_illustrated(self):
  with (ROOT/'problems/illustrations/review-v1.csv').open() as f:rows=list(csv.DictReader(f))
  self.assertEqual(len(rows),492);self.assertEqual(sum(x['status']=='ILLUSTRATED' for x in rows),26)
  self.assertTrue(any(x['status']=='REVIEW_NEEDED' for x in rows))
  self.assertEqual(len({x['version'] for x in rows}),len(rows))
 def test_changed_source_requires_new_rule_review(self):
  spec=importlib.util.spec_from_file_location('draw',ROOT/'scripts/build-problem-illustrations.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
  f=module.FIGURES[0]
  with self.assertRaisesRegex(ValueError,'Statement changed'):
   module.build([dict(version=f['version'],title='changed',category='test',statement='new rules')])
 def test_only_command_tasks_have_step_examples(self):
  manifest=json.loads((ROOT/'backend/src/main/resources/problem-illustrations-v1.json').read_text())
  self.assertEqual({x['version'] for x in manifest if x['kind']=='COMMAND_EXPLANATION'}, {'iamywl-v1-ff911ece9cc1639f','basic-pool-v1-basic-data-structures-hard-02-v1'})
if __name__=='__main__': unittest.main()
