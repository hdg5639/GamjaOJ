import copy
import unittest

from scripts.resource_time_policy import relaxed_limits, measured_seconds


class LearnerTimePolicyTests(unittest.TestCase):
    def test_existing_limits_only_widen_and_memory_and_input_are_preserved(self):
        old = {'JAVA': .75, 'CPP': .35, 'PYTHON': .45, 'memory': {'JAVA': 192, 'CPP': 32, 'PYTHON': 48}, 'analysis': 'Measured'}
        saved = copy.deepcopy(old)
        new = relaxed_limits(old)
        self.assertEqual((3, 5, 8), tuple(new[x] for x in ('CPP', 'JAVA', 'PYTHON')))
        self.assertEqual(new['memory'], old['memory'])
        self.assertEqual(old, saved)
        self.assertIsNone(relaxed_limits(None))

    def test_long_existing_times_keep_bounded_monotonic_headroom(self):
        old = {'JAVA': 120, 'CPP': 12, 'PYTHON': 180, 'analysis': 'Original'}
        new = relaxed_limits(old)
        self.assertEqual((180, 24, 180), tuple(new[x] for x in ('JAVA', 'CPP', 'PYTHON')))
        for language in ('JAVA', 'CPP', 'PYTHON'):
            self.assertGreaterEqual(new[language], old[language])
        with self.assertRaises(ValueError):
            relaxed_limits(dict(old, CPP=float('nan')))

    def test_general_correct_implementations_get_more_margin_than_sensitive_ones(self):
        self.assertEqual(10, measured_seconds('CPP', 3000))
        self.assertEqual(6.5, measured_seconds('CPP', 3000, algorithm_sensitive=True))
        self.assertEqual(8, measured_seconds('PYTHON', 200))
        with self.assertRaises(ValueError):
            measured_seconds('PYTHON', 100000)

    def test_new_policy_cannot_reuse_legacy_exact_profile_evidence(self):
        import importlib.util
        from pathlib import Path
        spec = importlib.util.spec_from_file_location('policy_cal', Path(__file__).parents[1] / 'scripts/calibrate-problem-resources.py')
        cal = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cal)
        job = {'resourceTimePolicy': {'version': 'LEARNER_FRIENDLY_V1', 'mode': 'GENERAL'}}
        self.assertEqual(.4, cal.policy_budget({}, 'CPP', 230, 10 * 1048576)['testWallSeconds'])
        self.assertEqual(3, cal.policy_budget(job, 'CPP', 230, 10 * 1048576)['testWallSeconds'])
