"""Pull-only judge coordinator. Its credential can access judge jobs, never the DB."""
import argparse
import fcntl
import hashlib
import json
import logging
import os
from pathlib import Path
import signal
import threading
import urllib.error
import urllib.request
import uuid
from contextlib import ExitStack
from runner.telemetry import Timings

from runner.judge import POLICY, RUN_POLICY, runtime_policies, ROOT, Runner, CompileCache, GeneratedCache, docker

LOG = logging.getLogger("gamjaoj.worker")


def atomic_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    temporary = path.with_suffix(".tmp")
    with temporary.open("w") as output:
        json.dump(value, output, ensure_ascii=False)
        output.flush()
        os.fsync(output.fileno())
    temporary.replace(path)


class Api:
    def __init__(self, url, token):
        if len(token) < 32:
            raise ValueError("WORKER_TOKEN must be at least 32 characters")
        self.url, self.token = url.rstrip("/"), token
        self.client = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def post(self, path, body):
        request = urllib.request.Request(self.url + "/internal/judge" + path,
            data=json.dumps(body).encode(), method="POST",
            headers={"Authorization": "Bearer " + self.token, "Content-Type": "application/json"})
        with self.client.open(request, timeout=15) as response:
            if response.status == 204:
                return None
            data = response.read(4 * 1024 * 1024 + 1)
            if len(data) > 4 * 1024 * 1024:
                raise ValueError("Oversized worker assignment")
            return json.loads(data)


class Worker:
    def __init__(self, api, state, runner_factory=Runner):
        self.api, self.state, self.runner_factory = api, Path(state), runner_factory
        self.compile_cache = CompileCache()
        self.generated_cache = GeneratedCache()
        self.state.mkdir(parents=True, exist_ok=True, mode=0o700)
        identity = self.state / "identity.json"
        if not identity.exists():
            atomic_json(identity, {"workerId": str(uuid.uuid4())})
        self.identity = json.loads(identity.read_text())

    def deliver(self, path):
        completion = json.loads(path.read_text())
        delivery = Timings()
        outcome = "unconfirmed"
        try:
            self.api.post("/" + completion["submissionId"] + "/result",
                          {"token": completion["token"], "report": completion["report"]})
            outcome = "accepted"
        except urllib.error.HTTPError as error:
            if error.code not in (404, 409):
                raise
            outcome = "stale"
            # A superseded result is retained for operators but never overwrites the active result.
            path.replace(path.with_suffix(".stale"))
            LOG.info("discarded stale delivery for %s", completion["submissionId"])
            return
        finally:
            LOG.info("performance %s", json.dumps(delivery.snapshot() | {"phase":"result_delivery", "outcome":outcome, "submissionId":completion["submissionId"], "attemptToken":completion["token"]}))
        path.replace(path.with_suffix(".delivered"))
        LOG.info("delivered result for %s", completion["submissionId"])

    def once(self):
        for pending in sorted((self.state / "pending").glob("*.json")):
            self.deliver(pending)
        timings = Timings()
        assignment = timings.call("claim_http", self.api.post, "/claim", self.identity)
        if assignment is None:
            return False
        execution_mode = assignment.get("executionMode", "EXCLUSIVE")
        if execution_mode not in ("EXCLUSIVE", "FUNCTIONAL"):
            raise ValueError("Unsupported execution mode")
        job_id = str(uuid.UUID(assignment["submissionId"]))
        token = str(uuid.UUID(assignment["token"]))
        path = self.state / "pending" / (token + ".json")
        work = self.state / "attempts" / token
        timings.call("assignment_save", atomic_json, work / "assignment.json", assignment)
        source = assignment["source"].encode()
        problem = json.dumps(assignment["problem"], sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode()
        if (hashlib.sha256(source).hexdigest() != assignment["sourceSha256"]
                or hashlib.sha256(problem).hexdigest() != assignment["problemSha256"]
                or assignment["runnerPolicy"] != runtime_policies(assignment["runtimeImage"])[1 if assignment["problem"].get("output_policy") == "RUN_ONLY" else 0]):
            raise ValueError("Assignment does not match the pinned runner contract")
        # Check before heartbeats: an incompatible build must not renew this lease forever.
        # Completed old-build reports still replay against the persisted attempt contract.
        reports = list((work / "runs").glob("*/result.json"))
        execution_profile = assignment.get("executionProfile")
        if not reports:
            from runner.judge import LANGUAGES
            if execution_profile is not None:
                if (execution_profile != LANGUAGES.get(assignment.get("language"))
                        or execution_profile["image"] != assignment["runtimeImage"]):
                    raise ValueError("Unsupported language execution snapshot")
            elif assignment.get("language", "JAVA") != "JAVA":
                raise ValueError("Missing language execution snapshot")
        expected_contract = assignment.get("runnerEnvironment")
        if not reports and expected_contract is not None:
            from runner.judge import EXECUTION_CONTRACT
            if expected_contract != EXECUTION_CONTRACT:
                raise ValueError("Runner build or execution limits differ from the saved assignment")
        self.api.post("/" + job_id + "/heartbeat", {"token": token})
        stopped = threading.Event()

        def heartbeat():
            while not stopped.wait(10):
                try:
                    self.api.post("/" + job_id + "/heartbeat", {"token": token})
                except Exception:
                    # The server fences completion if this lease expires; never invent a successful renewal.
                    LOG.warning("heartbeat failed for %s", job_id)

        renewer = threading.Thread(target=heartbeat, daemon=True)
        renewer.start()
        try:
            # On restart, reuse a durably finished runner report before executing anything again.
            if reports:
                report = json.loads(reports[0].read_text())
            else:
                timings.call("attempt_cleanup", self.cleanup_attempt, token)
                runner = self.runner_factory(assignment["runtimeImage"], work, attempt=token)
                runner.compile_cache = self.compile_cache
                runner.generated_cache = self.generated_cache
                runner.execution_mode = execution_mode
                report = timings.call("judge_total", runner.judge, source, assignment["problem"])
            timings.call("completion_save", atomic_json, path, {"submissionId": job_id, "token": token, "report": report})
            self.deliver(path)
        finally:
            LOG.info("performance %s", json.dumps(timings.snapshot() | {"phase":"worker_attempt", "submissionId":job_id, "attemptToken":token, "workerId":self.identity["workerId"]}))
            stopped.set()
            renewer.join(timeout=16)
        return True

    def cleanup_attempt(self, token):
        # Only a resumed attempt's labelled containers, never another project's resources.
        for container in docker("ps", "-aq", "--filter", "label=com.gamjaoj.role=sandbox",
                                "--filter", "label=com.gamjaoj.attempt=" + token).decode().split():
            docker("rm", "--force", container)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state-dir", type=Path, default=ROOT / ".state/worker")
    parser.add_argument("--once", action="store_true")
    parser.add_argument("--slots", type=int, choices=range(1, 17), default=1, metavar="1-16")
    args = parser.parse_args()
    # Never drop a persistent slot identity: its unfinished attempts must remain recoverable.
    existing = [int(p.parent.name) for p in (args.state_dir / 'slots').glob('*/identity.json') if p.parent.name.isdigit()]
    if existing and max(existing) >= args.slots:
        parser.error(f'This state has {max(existing) + 1} persistent slots; use --slots {max(existing) + 1} or more to preserve recovery')
    os.umask(0o077)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    args.state_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    stopping = threading.Event()
    signal.signal(signal.SIGTERM, lambda *_: stopping.set())
    signal.signal(signal.SIGINT, lambda *_: stopping.set())
    with ExitStack() as locks:
        lock = locks.enter_context((args.state_dir / "worker.lock").open("a"))
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        workers = []
        cache = CompileCache()
        generated = GeneratedCache()
        for index in range(args.slots):
            state = args.state_dir if index == 0 else args.state_dir / "slots" / str(index)
            state.mkdir(parents=True, exist_ok=True, mode=0o700)
            if index:
                slot_lock = locks.enter_context((state / "worker.lock").open("a"))
                fcntl.flock(slot_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            worker = Worker(Api(os.environ["GAMJAOJ_API_URL"], os.environ["WORKER_TOKEN"]), state)
            worker.compile_cache = cache
            worker.generated_cache = generated
            workers.append(worker)
            for attempt in (state / "attempts").glob("*"):
                worker.cleanup_attempt(str(uuid.UUID(attempt.name)))
        failures = []

        def loop(worker):
            while not stopping.is_set():
                try:
                    worked = worker.once()
                except Exception as error:
                    LOG.error("worker operation failed: %s", type(error).__name__)
                    if args.once:
                        failures.append(True)
                        return
                    stopping.wait(5)
                    continue
                if args.once:
                    return
                if not worked:
                    stopping.wait(2)

        threads = [threading.Thread(target=loop, args=(worker,)) for worker in workers]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()
        if failures:
            raise SystemExit(1)


if __name__ == "__main__":
    main()
