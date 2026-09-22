import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import urllib.error
import uuid
from unittest.mock import patch

from runner.judge import POLICY, ROOT
from runner.worker import Worker, atomic_json


class WorkerRecoveryTests(unittest.TestCase):
    def test_lost_completion_response_is_replayed_without_another_execution(self):
        problem = json.loads((ROOT / "problems/sum-v1.json").read_text())
        source = (ROOT / "examples/Main.java").read_text()
        assignment = {"submissionId": str(uuid.uuid4()), "token": str(uuid.uuid4()), "attempt": 1,
            "source": source, "sourceSha256": hashlib.sha256(source.encode()).hexdigest(),
            "problem": problem, "problemSha256": hashlib.sha256(json.dumps(problem, sort_keys=True,
                ensure_ascii=False, separators=(",", ":")).encode()).hexdigest(),
            "runtimeImage": (ROOT / "runner/java-image.txt").read_text().strip(), "runnerPolicy": POLICY}
        executed, deliveries = [], []
        report = {"policy": POLICY, "image": assignment["runtimeImage"], "source_sha256": assignment["sourceSha256"],
                  "problem_sha256": assignment["problemSha256"], "problem_version": "sum-v1", "verdict": "IE", "tests": []}

        class FakeRunner:
            def __init__(self, image, state, attempt):
                self.state = state
            def judge(self, code, package):
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
