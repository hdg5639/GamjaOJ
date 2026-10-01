import tempfile
import unittest
from pathlib import Path
from runner.memory_peak import MemoryPeak


class MemoryPeakTests(unittest.TestCase):
    def test_peak_is_monotonic_and_survives_cgroup_removal(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);ident='a'*64
            counter=root/'system.slice'/f'docker-{ident}.scope'/'memory.peak'
            counter.parent.mkdir(parents=True);counter.write_text('1048576')
            meter=MemoryPeak(ident,root);meter.sample();self.assertEqual(1048576,meter.peak)
            counter.write_text('2097152');meter.sample();counter.write_text('10');meter.sample()
            counter.unlink();meter.sample();self.assertEqual(2097152,meter.peak)

    def test_missing_counter_is_unmeasured_not_zero_and_invalid_ids_cannot_read_paths(self):
        with tempfile.TemporaryDirectory() as folder:
            meter=MemoryPeak('b'*64,Path(folder));meter.sample();self.assertIsNone(meter.peak)
            self.assertEqual([],MemoryPeak('../../other').paths)

    def test_v1_counter_is_supported_and_another_container_is_not_counted(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);path=root/'memory'/'docker'/('c'*64)/'memory.max_usage_in_bytes'
            path.parent.mkdir(parents=True);path.write_text('3456789')
            first=MemoryPeak('c'*64,root);first.sample();self.assertEqual(3456789,first.peak)
            second=MemoryPeak('d'*64,root);second.sample();self.assertIsNone(second.peak)
