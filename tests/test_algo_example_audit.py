import unittest
import json
from diagnostics.time_limits import POLICY, limits_for
from diagnostics.audit_algo_examples import check
from diagnostics.algo_mix_examples import EXAMPLES
from diagnostics.fix_algo_mix_examples import EXAMPLES as CORRECTED


class AlgoExampleAuditTests(unittest.TestCase):
    def test_all_forty_authored_examples_satisfy_contracts(self):
        for index, examples in EXAMPLES.items():
            for text, _ in examples:
                with self.subTest(question=index+1, text=text):
                    self.assertTrue(check(index+1,text))

    def test_declared_string_length_and_query_bounds_are_checked(self):
        with self.assertRaisesRegex(AssertionError,'declared length'):
            check(1,'4 1\nabc\n1 4 a\n')
        with self.assertRaises(AssertionError):
            check(1,'3 1\nabc\n1 4 a\n')

    def test_forbidden_pairs_are_bidirectional_and_not_circular(self):
        for sample in CORRECTED:
            self.assertEqual(list(map(str,check(10,sample['input']))),sample['output'].split())
        self.assertEqual(check(10,'3 0\n'),[6])
        self.assertEqual(check(10,'2 1\n1 2\n'),[0])
        self.assertEqual(check(10,'1 0\n'),[1])

    def test_pair_count_order_and_duplicates_are_checked(self):
        for text in ('3 1\n','3 1\n2 1\n','3 2\n1 2\n1 2\n'):
            with self.subTest(text=text), self.assertRaises(AssertionError):
                check(10,text)

    def test_grid_shape_and_command_count_are_checked(self):
        with self.assertRaises(AssertionError): check(6,'2 3\nS..\n..\n')
        with self.assertRaises(AssertionError): check(3,'2\nCOUNT\n')

    def test_corrected_limits_keep_exact_hash_fence(self):
        supplement = json.loads(POLICY.with_name('algo-mix-example-correction-limits.json').read_text())['items'][0]
        original = next(item for item in json.loads(POLICY.read_text())['items'] if 'diagnostic-algo-mix-a-v2-safe-presentation-order-v1' in item['versions'])
        self.assertEqual(supplement['limits'], original['limits'])
        with self.assertRaisesRegex(ValueError, 'hash mismatch'):
            limits_for({'version': next(iter(supplement['versions'])), 'statement': 'changed'})
