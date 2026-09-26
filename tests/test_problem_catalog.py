"""Independent answer checks plus opt-in Java 8 reference/mutant execution."""
import json
import os
from pathlib import Path
import tempfile
import unittest

from runner.judge import ROOT, Runner, validate_problem


def package(version):
    return json.loads((ROOT / 'problems' / (version + '.json')).read_text())


class CatalogContractTests(unittest.TestCase):
    def test_total_answers_and_bounds(self):
        problem = package('total-v1')
        validate_problem(problem)
        for case in problem['tests']:
            numbers = list(map(int, case['input'].split()))
            n, values = numbers[0], numbers[1:]
            self.assertEqual(n, len(values))
            self.assertTrue(1 <= n <= 1000)
            self.assertTrue(all(-10**9 <= value <= 10**9 for value in values))
            self.assertEqual(str(sum(values)), case['output'].strip(), case['id'])
        self.assertTrue(any(abs(int(t['output'])) > 2**31 for t in problem['tests']))

    def test_parentheses_answers_by_pair_reduction(self):
        problem = package('valid-parentheses-v1')
        validate_problem(problem)
        for case in problem['tests']:
            text = case['input'].strip()
            self.assertTrue(1 <= len(text) <= 1000)
            self.assertLessEqual(set(text), set('()'))
            while '()' in text:
                text = text.replace('()', '')
            self.assertEqual('NO' if text else 'YES', case['output'].strip(), case['id'])
        self.assertTrue(any(t['input'].count('(') == t['input'].count(')') and t['output'].strip() == 'NO'
                            for t in problem['tests']))


@unittest.skipUnless(os.environ.get('GAMJAOJ_DOCKER_TESTS') == '1', 'requires isolated Docker runner')
class CatalogJavaTests(unittest.TestCase):
    def setUp(self):
        self.state = tempfile.TemporaryDirectory()
        self.addCleanup(self.state.cleanup)
        self.runner = Runner((ROOT / 'runner/java-image.txt').read_text().strip(), self.state.name)

    def judge(self, version, source, expected):
        problem = package(version)
        result = self.runner.judge(source.encode(), problem)
        self.assertEqual(expected, result['verdict'], result)
        if expected == 'AC':
            self.assertEqual(len(problem['tests']), len(result['tests']))
        return result

    def test_total_reference_and_int_overflow(self):
        source = (ROOT / 'examples/total/Main.java').read_text()
        self.judge('total-v1', source, 'AC')
        self.judge('total-v1', source.replace('long sum', 'int sum'), 'WA')

    def test_total_rejects_missing_final_value(self):
        source = (ROOT / 'examples/total/Main.java').read_text()
        self.judge('total-v1', source.replace('i < n;', 'i < n - 1;'), 'WA')

    def test_parentheses_reference_and_count_only(self):
        source = (ROOT / 'examples/valid-parentheses/Main.java').read_text()
        self.judge('valid-parentheses-v1', source, 'AC')
        self.judge('valid-parentheses-v1', source.replace('if (depth < 0)', 'if (false)'), 'WA')
