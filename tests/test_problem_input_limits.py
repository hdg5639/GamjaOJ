import unittest
from runner.judge import validate_problem, INPUT_LIMIT, OUTPUT_LIMIT

class ProblemInputLimitsTest(unittest.TestCase):
    def problem(self, input='', output=''):
        return {'version':'large-fixed-test','output_policy':'TOKEN_EXACT','tests':[{'id':'fixed','input':input,'output':output}]}
    def test_large_fixed_inputs_keep_output_and_sandbox_bounds(self):
        validate_problem(self.problem('0 '*100000,'0\n'))
        validate_problem(self.problem('a'*INPUT_LIMIT,''))
        with self.assertRaises(ValueError):validate_problem(self.problem('a'*(INPUT_LIMIT+1),''))
        with self.assertRaises(ValueError):validate_problem(self.problem('', 'a'*(OUTPUT_LIMIT+1)))
        with self.assertRaises(ValueError):validate_problem(self.problem('가'*(INPUT_LIMIT//3+1),''))
