import unittest
from unittest.mock import patch
from runner.telemetry import Timings

class TimingTests(unittest.TestCase):
    def test_failure_keeps_elapsed_and_reraises(self):
        with patch('runner.telemetry.time.monotonic',side_effect=[1,2,2.125]):
            timings=Timings()
            with self.assertRaisesRegex(ValueError,'original'):
                with timings.measure('compile'):
                    raise ValueError('original')
        self.assertEqual(125,timings.segments[0]['elapsedMs'])
        self.assertFalse(timings.segments[0]['success'])
        self.assertIn('finishedAt',timings.segments[0])

    def test_call_preserves_result_and_does_not_record_arguments(self):
        timings=Timings()
        self.assertEqual('private',timings.call('prepare',lambda value:value,'private'))
        snapshot=timings.snapshot()
        self.assertNotIn('private',str(snapshot))
        self.assertTrue(snapshot['segments'][0]['success'])
        self.assertGreaterEqual(snapshot['elapsedMs'],0)
