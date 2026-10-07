import tempfile,unittest
from pathlib import Path
from runner.cpu_usage import CpuUsage
from runner.judge import Runner,LANGUAGES,checked_profile

class CpuUsageTests(unittest.TestCase):
 def test_live_counter_is_final_delta_and_invalid_counters_fail_closed(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d)/'cgroup';proc=Path(d)/'proc';(proc/'42').mkdir(parents=True);(proc/'42/cgroup').write_text('0::/sandbox\n');(root/'sandbox').mkdir(parents=True)
   path=root/'sandbox/cpu.stat';path.write_text('usage_usec 100000\nuser_usec 80000\nsystem_usec 20000\n');(root/'sandbox/memory.events').write_text('oom_kill 0\n');meter=CpuUsage(42,root,proc)
   path.write_text('usage_usec 250001\n');self.assertEqual(150.001,meter.milliseconds())
   (root/'sandbox/memory.events').write_text('oom_kill 1\n');self.assertTrue(meter.oom_killed())
   path.write_text('usage_usec 1\n')
   with self.assertRaises(ValueError):meter.milliseconds()
   path.unlink()
   with self.assertRaises(OSError):meter.milliseconds()
 def test_profile_pins_commands_and_requires_bounded_cpu_budget(self):
  base=LANGUAGES['JAVA'];profile=base|{'testCpuSeconds':2.65};self.assertEqual(profile,checked_profile(profile,'JAVA',base['image']))
  for change in [{'testCpuSeconds':True},{'testCpuSeconds':float('nan')},{'testCpuSeconds':181},{'testCpuSeconds':.1234},{'testCommand':['sh']}]:
   with self.assertRaises(ValueError):checked_profile(profile|change,'JAVA',base['image'])
 def test_only_cpu_formal_functional_jobs_parallelize_tests(self):
  r=Runner(LANGUAGES['JAVA']['image']);r.judge_all=True;r.execution_mode='FUNCTIONAL';self.assertEqual(1,r.test_parallelism())
  r.profile=r.profile|{'testCpuSeconds':2.65};self.assertEqual(4,r.test_parallelism())
  r.execution_mode='EXCLUSIVE';self.assertEqual(1,r.test_parallelism())
  r.execution_mode='FUNCTIONAL';r.judge_all=False;self.assertEqual(1,r.test_parallelism())
