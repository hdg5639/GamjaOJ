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

from runner.judge import POLICY, RUN_POLICY, runtime_policies, ROOT, Runner, docker

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
        self.state.mkdir(parents=True, exist_ok=True, mode=0o700)
        identity = self.state / "identity.json"
        if not identity.exists():
            atomic_json(identity, {"workerId": str(uuid.uuid4())})
        self.identity = json.loads(identity.read_text())

    def deliver(self, path):
        completion = json.loads(path.read_text())
        try:
            self.api.post("/" + completion["submissionId"] + "/result",
                          {"token": completion["token"], "report": completion["report"]})
        except urllib.error.HTTPError as error:
            if error.code not in (404, 409):
                raise
            # A superseded result is retained for operators but never overwrites the active result.
            path.replace(path.with_suffix(".stale"))
            LOG.info("discarded stale delivery for %s", completion["submissionId"])
            return
        path.replace(path.with_suffix(".delivered"))
        LOG.info("delivered result for %s", completion["submissionId"])

    def once(self):
        for pending in sorted((self.state / "pending").glob("*.json")):
            self.deliver(pending)
        assignment = self.api.post("/claim", self.identity)
        if assignment is None:
            return False
        job_id = str(uuid.UUID(assignment["submissionId"]))
        token = str(uuid.UUID(assignment["token"]))
        path = self.state / "pending" / (token + ".json")
        work = self.state / "attempts" / token
        atomic_json(work / "assignment.json", assignment)
        source = assignment["source"].encode()
        problem = json.dumps(assignment["problem"], sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode()
        if (hashlib.sha256(source).hexdigest() != assignment["sourceSha256"]
                or hashlib.sha256(problem).hexdigest() != assignment["problemSha256"]
                or assignment["runnerPolicy"] != runtime_policies(assignment["runtimeImage"])[1 if assignment["problem"].get("output_policy") == "RUN_ONLY" else 0]):
            raise ValueError("Assignment does not match the pinned runner contract")
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
            reports = list((work / "runs").glob("*/result.json"))
            if reports:
                report = json.loads(reports[0].read_text())
            else:
                self.cleanup_attempt(token)
                report = self.runner_factory(assignment["runtimeImage"], work, attempt=token).judge(source, assignment["problem"])
            atomic_json(path, {"submissionId": job_id, "token": token, "report": report})
            self.deliver(path)
        finally:
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
    args = parser.parse_args()
    os.umask(0o077)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    args.state_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    stopping = threading.Event()
    signal.signal(signal.SIGTERM, lambda *_: stopping.set())
    signal.signal(signal.SIGINT, lambda *_: stopping.set())
    with (args.state_dir / "worker.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        worker = Worker(Api(os.environ["GAMJAOJ_API_URL"], os.environ["WORKER_TOKEN"]), args.state_dir)
        for attempt in (args.state_dir / "attempts").glob("*"):
            # A prior coordinator cannot still hold this state lock. Reap only its saved attempts.
            worker.cleanup_attempt(str(uuid.UUID(attempt.name)))
        while not stopping.is_set():
            try:
                worked = worker.once()
            except Exception as error:
                # Do not log request bodies, credentials, private tests, or submitted source.
                LOG.error("worker operation failed: %s", type(error).__name__)
                if args.once:
                    raise SystemExit(1) from None
                stopping.wait(5)
                continue
            if args.once:
                return
            if not worked:
                stopping.wait(2)


if __name__ == "__main__":
    main()
