from concurrent.futures import ThreadPoolExecutor
import tempfile
import threading
import unittest

from runner.scheduling import execution_lock
from runner.judge import CompileCache


class SchedulingTests(unittest.TestCase):
    def test_two_functional_slots_and_exclusive_barrier(self):
        with tempfile.TemporaryDirectory() as directory, ThreadPoolExecutor(max_workers=4) as pool:
            release = threading.Event()
            entered = [threading.Event() for _ in range(2)]
            slots = []
            def functional(index):
                with execution_lock('FUNCTIONAL', directory) as slot:
                    slots.append(slot)
                    entered[index].set()
                    if not release.wait(5): raise AssertionError('test release missing')
            first = [pool.submit(functional, i) for i in range(2)]
            try:
                self.assertTrue(all(event.wait(2) for event in entered))
                self.assertEqual({0, 1}, set(slots))
                exclusive_entered = threading.Event()
                def exclusive():
                    with execution_lock('EXCLUSIVE', directory): exclusive_entered.set()
                waiting = pool.submit(exclusive)
                self.assertFalse(exclusive_entered.wait(.1))
                third_entered = threading.Event()
                def third():
                    with execution_lock('FUNCTIONAL', directory): third_entered.set()
                overflow = pool.submit(third)
                self.assertFalse(third_entered.wait(.1))
            finally:
                release.set()
            for future in first + [waiting, overflow]: future.result(timeout=5)
            self.assertTrue(exclusive_entered.is_set())
            self.assertTrue(third_entered.is_set())

    def test_exclusive_blocks_functional_and_invalid_mode_fails(self):
        with tempfile.TemporaryDirectory() as directory, ThreadPoolExecutor(max_workers=1) as pool:
            entered = threading.Event()
            def functional():
                with execution_lock('FUNCTIONAL', directory): entered.set()
            with execution_lock('EXCLUSIVE', directory):
                future = pool.submit(functional)
                self.assertFalse(entered.wait(.1))
            future.result(timeout=3)
            with self.assertRaises(ValueError):
                with execution_lock('untrusted', directory): pass

    def test_shared_cache_remains_bounded_under_concurrent_eviction(self):
        cache = CompileCache(max_entries=4)
        result = dict(stdout=b'classes', stderr=b'', exit_code=0, limit=None, oom_killed=False)
        def use(prefix):
            for n in range(200):
                key = (prefix,n)
                cache.put(key,result)
                found = cache.get(key)
                if found is not None: self.assertEqual(result,found)
        with ThreadPoolExecutor(max_workers=4) as pool:
            list(pool.map(use,range(4)))
        self.assertLessEqual(len(cache.entries),4)
