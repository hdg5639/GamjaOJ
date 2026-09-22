"""M0 runner. Never expose this Docker-capable controller as a public endpoint."""
import argparse
import fcntl
import hashlib
import io
import json
import os
from pathlib import Path
import selectors
import subprocess
import tarfile
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parent.parent
POLICY = "java8-judge-v1"
RUN_POLICY = "java8-run-v1"

def runtime_policies(image):
    if image == (ROOT / "runner/java21-image.txt").read_text().strip():
        return "java21-m0-v2", "java21-run-v1"
    if image == (ROOT / "runner/java-image.txt").read_text().strip():
        return POLICY, RUN_POLICY
    raise ValueError("Unapproved runtime image")
SOURCE_LIMIT = 65536
OUTPUT_LIMIT = 65536
BUILD_LIMIT = 8 * 1024 * 1024


class InfrastructureError(Exception):
    pass


def docker(*args):
    try:
        result = subprocess.run(["docker", *args], capture_output=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise InfrastructureError("Docker control command unavailable or timed out") from exc
    if result.returncode:
        raise InfrastructureError(result.stderr.decode(errors="replace")[:2048])
    return result.stdout


def capture(command, stdin, seconds, limit):
    """Drain both pipes concurrently; stdin is a bounded regular file, never a pipe."""
    output = {"stdout": bytearray(), "stderr": bytearray()}
    reason = None
    started = time.monotonic()
    with tempfile.TemporaryFile() as input_file:
        input_file.write(stdin)
        input_file.seek(0)
        with subprocess.Popen(command, stdin=input_file, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE) as process:
            with selectors.DefaultSelector() as selector:
                selector.register(process.stdout, selectors.EVENT_READ, "stdout")
                selector.register(process.stderr, selectors.EVENT_READ, "stderr")
                try:
                    while selector.get_map():
                        if time.monotonic() - started > seconds:
                            reason = "TIME_LIMIT"
                            break
                        for key, _ in selector.select(timeout=0.05):
                            block = os.read(key.fileobj.fileno(), 8192)
                            if not block:
                                selector.unregister(key.fileobj)
                                continue
                            used = sum(len(value) for value in output.values())
                            output[key.data].extend(block[:max(0, limit - used)])
                            if used + len(block) > limit:
                                reason = "OUTPUT_LIMIT"
                                break
                        if reason:
                            break
                    if not reason:
                        try:
                            process.wait(timeout=max(0.01, seconds - (time.monotonic() - started)))
                        except subprocess.TimeoutExpired:
                            reason = "TIME_LIMIT"
                finally:
                    if reason or process.poll() is None:
                        process.kill()
                    process.wait(timeout=5)
            return {"stdout": bytes(output["stdout"]), "stderr": bytes(output["stderr"]),
                    "limit": reason, "client_exit": process.returncode,
                    "wall_ms": round((time.monotonic() - started) * 1000)}


class Runner:
    def __init__(self, image, state_dir=None, attempt=None):
        if "@sha256:" not in image:
            raise ValueError("Runtime image must be pinned by digest")
        self.image = image
        self.state_dir = Path(state_dir or ROOT / ".state")
        self.attempt = str(uuid.UUID(attempt)) if attempt else None

    def sandbox(self, mount, command, stdin=b"", compile_phase=False):
        name = "gamjaoj-sandbox-" + uuid.uuid4().hex
        args = ["create", "--name", name, "--label", "com.gamjaoj.role=sandbox",
                "--pull", "never", "--network", "none", "--read-only", "--init",
                "--user", "65534:65534", "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges=true", "--cpus", "0.5",
                "--memory", "384m", "--memory-swap", "384m", "--pids-limit", "128",
                "--ulimit", "nofile=128:128", "--ulimit", "fsize=16777216:16777216",
                "--ulimit", "core=0:0", "--log-driver", "none",
                "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=32m,mode=1777",
                "--tmpfs", "/classes:rw,noexec,nosuid,nodev,size=32m,mode=1777",
                "--shm-size", "8m", "--mount",
                "type=bind,src=" + str(mount) + ",dst=/work,readonly",
                "--workdir", "/work", "-i", "--entrypoint", "/usr/bin/timeout",
                self.image, "--signal=KILL", "35s" if compile_phase else "8s", *command]
        if self.attempt:
            args[1:1] = ["--label", "com.gamjaoj.attempt=" + self.attempt]
        try:
            docker(*args)
            result = capture(["docker", "start", "--attach", "--interactive", name], stdin,
                             30 if compile_phase else 5,
                             BUILD_LIMIT if compile_phase else OUTPUT_LIMIT)
            if result["limit"]:
                try:
                    docker("kill", name)
                except InfrastructureError:
                    # The program may have exited between the limit and the kill request.
                    state = json.loads(docker("inspect", "--format", "{{json .State}}", name))
                    if state["Running"]:
                        raise
            state = json.loads(docker("inspect", "--format", "{{json .State}}", name))
            if state["Running"] or state.get("Error"):
                raise InfrastructureError("Container did not finish cleanly: " + str(state))
            if not result["limit"] and result["client_exit"] != state["ExitCode"]:
                raise InfrastructureError("Docker attachment lost; exit status is not reliable")
            result["exit_code"] = state["ExitCode"]
            result["oom_killed"] = state["OOMKilled"]
            return result
        finally:
            # Exact per-attempt name only: never prune or touch another project's containers.
            docker("rm", "--force", name)

    def judge(self, source, problem):
        self.state_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        # The lock is host-wide per operator, across checkouts and configured report paths.
        lock_path = Path.home() / ".local/state/gamjaoj/runner.lock"
        lock_path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        with lock_path.open("a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            return self._judge(source, problem)

    def _judge(self, source, problem):
        validate_problem(problem)
        if len(source) > SOURCE_LIMIT:
            raise ValueError("Source exceeds 64 KiB")
        run_id = uuid.uuid4().hex
        run_dir = self.state_dir / "runs" / run_id
        run_dir.mkdir(parents=True, mode=0o700)
        problem_bytes = json.dumps(problem, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode()
        (run_dir / "Main.java").write_bytes(source)
        (run_dir / "problem.json").write_bytes(problem_bytes)
        custom = problem["output_policy"] == "RUN_ONLY"
        report = {"run_id": run_id, "policy": (runtime_policies(self.image)[1 if custom else 0] if self.image in [(ROOT / "runner/java-image.txt").read_text().strip(), (ROOT / "runner/java21-image.txt").read_text().strip()] else POLICY), "image": self.image,
                  "source_sha256": hashlib.sha256(source).hexdigest(),
                  "problem_sha256": hashlib.sha256(problem_bytes).hexdigest(),
                  "problem_version": problem["version"], "verdict": "IE", "tests": []}
        try:
            docker("image", "inspect", self.image)
            with tempfile.TemporaryDirectory(prefix="gamjaoj-") as directory:
                work = Path(directory)
                work.chmod(0o755)
                (work / "Main.java").write_bytes(source)
                (work / "Main.java").chmod(0o444)
                result = self.sandbox(work, ["/bin/sh", "-c",
                    "javac -J-Xmx128m -J-XX:ActiveProcessorCount=1 -proc:none "
                    "-encoding UTF-8 -d /classes /work/Main.java >&2 "
                    "&& tar -C /classes -cf - ."], compile_phase=True)
                report["compile"] = evidence(result)
                if (result["exit_code"] in (137, 143) and not result["limit"]
                        and not result["oom_killed"]):
                    raise InfrastructureError("Unattributed compiler termination")
                if result["limit"] or result["exit_code"]:
                    report["verdict"] = "CE"
                else:
                    classes = work / "compiled"
                    classes.mkdir(mode=0o755)
                    classes.chmod(0o755)  # Worker uses umask 077; sandbox UID still needs traversal.
                    unpack_classes(result["stdout"], classes)
                    for test in problem["tests"]:
                        result = self.sandbox(classes, ["java", "-Xms16m", "-Xmx128m",
                            "-XX:ActiveProcessorCount=1", "-XX:+UseSerialGC",
                            "-XX:+ExitOnOutOfMemoryError", "-XX:-UsePerfData",
                            "-cp", "/work", "Main"], test["input"].encode())
                        verdict = classify(result, None if custom else test["output"].encode())
                        report["tests"].append({"id": test["id"], "verdict": verdict,
                                                **evidence(result), **({"stdout": result["stdout"][:16384].decode(errors="replace"),
                                                "stdout_truncated": len(result["stdout"]) > 16384 or result["limit"] == "OUTPUT_LIMIT"} if custom else {})})
                        report["verdict"] = verdict
                        if verdict not in ("AC", "OK"):
                            break
        except (InfrastructureError, OSError, tarfile.TarError) as exc:
            report["verdict"] = "IE"
            report["error"] = str(exc)[:2048]
        temporary = run_dir / "result.tmp"
        temporary.write_text(json.dumps(report, ensure_ascii=False, indent=2))
        temporary.replace(run_dir / "result.json")
        return report


def unpack_classes(archive, destination):
    total = 0
    count = 0
    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:") as package:
        for member in package:
            path = Path(member.name)
            if path.is_absolute() or ".." in path.parts:
                raise InfrastructureError("Invalid compiler artifact path")
            if member.isdir():
                continue
            count += 1
            total += member.size
            if not member.isfile() or path.suffix != ".class" or count > 4096 or total > BUILD_LIMIT:
                raise InfrastructureError("Invalid compiler artifact")
            target = destination / path
            target.parent.mkdir(parents=True, exist_ok=True, mode=0o755)
            parent = target.parent
            while parent != destination:
                parent.chmod(0o755)
                parent = parent.parent
            target.write_bytes(package.extractfile(member).read())
            target.chmod(0o444)


def validate_problem(problem):
    if problem.get("output_policy") not in ("TOKEN_EXACT", "RUN_ONLY") or not problem.get("version"):
        raise ValueError("Version and TOKEN_EXACT policy required")
    tests = problem.get("tests", [])
    if not 1 <= len(tests) <= 20:
        raise ValueError("Expected 1 to 20 trusted tests")
    if problem["output_policy"] == "RUN_ONLY" and (len(tests) != 1
            or tests[0].get("id") != "custom-input" or tests[0].get("output") != ""
            or len(tests[0].get("input", "").encode()) > 16384):
        raise ValueError("Custom run requires one bounded input and no expected output")
    identifiers = set()
    for test in tests:
        if not isinstance(test.get("id"), str) or test["id"] in identifiers:
            raise ValueError("Test ids must be unique strings")
        identifiers.add(test["id"])
        for key in ("input", "output"):
            if not isinstance(test.get(key), str) or len(test[key].encode()) > OUTPUT_LIMIT:
                raise ValueError("Test input/output exceeds contract")


def classify(result, expected):
    # Versioned precedence: enforced output/time limit, kernel OOM, exit, comparison.
    if result["limit"] == "OUTPUT_LIMIT":
        return "OLE"
    if result["limit"] == "TIME_LIMIT":
        return "TLE"
    if result["oom_killed"]:
        return "MLE"
    if result["exit_code"] in (137, 143):
        raise InfrastructureError("Unattributed forced termination")
    if result["exit_code"]:
        return "RE"
    if expected is None:
        return "OK"
    return "AC" if result["stdout"].split() == expected.split() else "WA"


def evidence(result):
    return {key: value for key, value in result.items() if key not in ("stdout", "stderr")} | {
        "stdout_sha256": hashlib.sha256(result["stdout"]).hexdigest(),
        "stderr": result["stderr"][:4096].decode(errors="replace")}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--problem", type=Path, default=ROOT / "problems/sum-v1.json")
    parser.add_argument("--image", default=(ROOT / "runner/java-image.txt").read_text().strip())
    args = parser.parse_args()
    with args.source.open("rb") as source_file:
        source = source_file.read(SOURCE_LIMIT + 1)
    try:
        result = Runner(args.image).judge(source, json.loads(args.problem.read_text()))
    except (ValueError, OSError) as exc:
        parser.error(str(exc))
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 2 if result["verdict"] == "IE" else 0


if __name__ == "__main__":
    raise SystemExit(main())
