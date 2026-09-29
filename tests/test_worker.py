import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
import urllib.error
import uuid
from unittest.mock import patch

from runner.judge import POLICY, ROOT
from runner.worker import Worker, atomic_json


class WorkerRecoveryTests(unittest.TestCase):
    def test_both_slot_identities_survive_restart_and_are_distinct(self):
        class Api:
            def post(self,path,body):
                if path != '/claim': raise AssertionError(path)
                return None
        with tempfile.TemporaryDirectory() as directory:
            states=[Path(directory),Path(directory)/'slots/1']
            first=[Worker(Api(),state) for state in states]
            self.assertNotEqual(first[0].identity,first[1].identity)
            restored=[Worker(Api(),state) for state in states]
            self.assertEqual([w.identity for w in first],[w.identity for w in restored])
            for worker in restored:self.assertFalse(worker.once())

    def test_lost_completion_response_is_replayed_without_another_execution(self):
        problem = json.loads((ROOT / "problems/sum-v1.json").read_text())
        source = (ROOT / "examples/Main.java").read_text()
        assignment = {"submissionId": str(uuid.uuid4()), "token": str(uuid.uuid4()), "attempt": 1,
            "source": source, "sourceSha256": hashlib.sha256(source.encode()).hexdigest(),
            "problem": problem, "problemSha256": hashlib.sha256(json.dumps(problem, sort_keys=True,
                ensure_ascii=False, separators=(",", ":")).encode()).hexdigest(),
            "runtimeImage": (ROOT / "runner/java-image.txt").read_text().strip(), "runnerPolicy": POLICY,
            "executionMode": "FUNCTIONAL"}
        executed, deliveries = [], []
        report = {"policy": POLICY, "image": assignment["runtimeImage"], "source_sha256": assignment["sourceSha256"],
                  "problem_sha256": assignment["problemSha256"], "problem_version": "sum-v1", "verdict": "IE", "tests": []}

        class FakeRunner:
            def __init__(self, image, state, attempt):
                self.state = state
            def judge(self, code, package):
                if self.execution_mode != "FUNCTIONAL": raise AssertionError("Scheduling mode lost")
                executed.append(code)
                atomic_json(self.state / "runs" / "run" / "result.json", report)
                return report

        class Api:
            claimed = False
            def post(self, path, body):
                if path == "/claim":
                    if self.claimed:
                        return None
                    self.claimed = True
                    return assignment
                if path.endswith("/result"):
                    deliveries.append(body)
                    if len(deliveries) == 1:
                        raise OSError("response lost after commit")

        with tempfile.TemporaryDirectory() as directory, patch("runner.worker.docker", return_value=b""):
            api = Api()
            with self.assertRaises(OSError):
                Worker(api, directory, FakeRunner).once()
            Worker(api, directory, FakeRunner).once()
            self.assertEqual(1, len(executed))
            self.assertEqual(deliveries[0], deliveries[1])
            self.assertEqual(1, len(list((Path(directory) / "pending").glob("*.delivered"))))

    def test_build_mismatch_blocks_new_execution_but_replays_saved_report(self):
        problem=json.loads((ROOT/'problems/sum-v1.json').read_text())
        source='class Main {}'
        assignment=dict(submissionId=str(uuid.uuid4()),token=str(uuid.uuid4()),source=source,
            sourceSha256=hashlib.sha256(source.encode()).hexdigest(),problem=problem,
            problemSha256=hashlib.sha256(json.dumps(problem,sort_keys=True,ensure_ascii=False,separators=(',',':')).encode()).hexdigest(),
            runtimeImage=(ROOT/'runner/java-image.txt').read_text().strip(),runnerPolicy=POLICY,
            runnerEnvironment={'format':'older-build'})
        deliveries=[]; heartbeats=[]
        class Api:
            def post(self,path,body):
                if path=='/claim':return assignment
                if path.endswith('/result'):deliveries.append(body)
                if path.endswith('/heartbeat'):heartbeats.append(body)
        def forbidden(*args,**kwargs):raise AssertionError('Mismatched build executed')
        with tempfile.TemporaryDirectory() as directory:
            worker=Worker(Api(),directory,forbidden)
            with self.assertRaisesRegex(ValueError,'Runner build'):worker.once()
            self.assertEqual([],heartbeats)
            saved={'verdict':'IE','runner_environment':{'contract':assignment['runnerEnvironment']}}
            atomic_json(Path(directory)/'attempts'/assignment['token']/'runs/run/result.json',saved)
            self.assertTrue(worker.once())
            self.assertEqual(deliveries[0]['report'],saved)

    def test_language_snapshot_is_required_and_cannot_override_limits_or_image(self):
        from runner.judge import LANGUAGES
        source='print(3)';problem=json.loads((ROOT/'problems/sum-v1.json').read_text())
        base=dict(submissionId=str(uuid.uuid4()),token=str(uuid.uuid4()),source=source,
            sourceSha256=hashlib.sha256(source.encode()).hexdigest(),problem=problem,
            problemSha256=hashlib.sha256(json.dumps(problem,sort_keys=True,ensure_ascii=False,separators=(',',':')).encode()).hexdigest(),
            runtimeImage=LANGUAGES['PYTHON']['image'],runnerPolicy=LANGUAGES['PYTHON']['policy'],language='PYTHON')
        def forbidden(*args,**kwargs):raise AssertionError('Invalid language executed')
        for patch_data in ({}, {'executionProfile':LANGUAGES['PYTHON']|{'testWallSeconds':999}},
                           {'executionProfile':LANGUAGES['CPP']}, {'executionProfile':LANGUAGES['PYTHON'],'language':'CPP'}):
            assignment=base|patch_data
            class Api:
                def post(self,path,body):
                    if path=='/claim':return assignment
                    raise AssertionError('Invalid assignment renewed or completed')
            with self.subTest(patch=patch_data),tempfile.TemporaryDirectory() as directory:
                with self.assertRaisesRegex(ValueError,'language execution snapshot'):
                    Worker(Api(),directory,forbidden).once()

        assignment=base | {'executionProfile':LANGUAGES['PYTHON'] | {'testWallSeconds':2}}
        used=[]
        class Api:
            def post(self,path,body):
                if path=='/claim':return assignment
                if path.endswith('/result'):used.append(body['report']['execution_profile'])
        class FakeRunner:
            def __init__(self,*args,**kwargs):pass
            def judge(self,source,problem):return {'execution_profile':self.profile,'verdict':'AC'}
        with tempfile.TemporaryDirectory() as directory, patch('runner.worker.docker',return_value=b''):
            Worker(Api(),directory,FakeRunner).once()
        self.assertEqual([assignment['executionProfile']],used)

    def test_stale_saved_result_is_retained_without_overwriting_active_attempt(self):
        class Api:
            def post(self, path, body):
                if path == "/claim": return None
                raise urllib.error.HTTPError(path, 409, "stale", {}, None)
        with tempfile.TemporaryDirectory() as directory:
            pending = Path(directory) / "pending" / "result.json"
            atomic_json(pending, {"submissionId": str(uuid.uuid4()), "token": str(uuid.uuid4()), "report": {}})
            Worker(Api(), directory).once()
            self.assertFalse(pending.exists())
            self.assertTrue(pending.with_suffix(".stale").exists())


if __name__ == "__main__":
    unittest.main()


class AttemptRetentionTests(unittest.TestCase):
    def test_old_finished_attempts_are_pruned_but_undelivered_and_recent_ones_stay(self):
        import os, time
        from pathlib import Path
        class Api:
            def post(self, *args): return None
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            worker = Worker(Api(), state)
            old = time.time() - 8 * 86400
            def attempt(token, age):
                run = state / "attempts" / token / "runs" / "r1"
                run.mkdir(parents=True)
                (state / "attempts" / token / "assignment.json").write_text("{}")
                (run / "Main.java").write_text("class Main {}")
                for path in [run / "Main.java", run, run.parent, state / "attempts" / token / "assignment.json", state / "attempts" / token]:
                    os.utime(path, (age, age))
            attempt("finished-old", old)
            attempt("undelivered-old", old)
            attempt("recent", time.time())
            (state / "pending").mkdir(exist_ok=True)
            (state / "pending" / "undelivered-old.json").write_text("{}")
            for name in ("done-old.delivered", "stale-old.stale"):
                (state / "pending" / name).write_text("{}"); os.utime(state / "pending" / name, (old, old))
            (state / "pending" / "done-new.delivered").write_text("{}")
            # A fresh file inside an old directory keeps the attempt (still being written).
            attempt("old-dir-new-file", old); (state / "attempts" / "old-dir-new-file" / "runs" / "r1" / "result.json").write_text("{}")
            self.assertEqual(3, worker.prune(days=7))
            self.assertEqual({"undelivered-old", "recent", "old-dir-new-file"}, {p.name for p in (state / "attempts").iterdir()})
            self.assertEqual({"undelivered-old.json", "done-new.delivered"}, {p.name for p in (state / "pending").iterdir()})

    def test_retention_days_are_bounded(self):
        from unittest.mock import patch
        import runner.worker as module
        for value, expected in (("7", 7), ("0", 1), ("999", 365), ("x", 7)):
            with patch.dict(os.environ, {"GAMJAOJ_ATTEMPT_RETENTION_DAYS": value}):
                self.assertEqual(expected, module.retention_days())
