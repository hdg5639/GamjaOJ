"""M0 runner. Never expose this Docker-capable controller as a public endpoint."""
import argparse
from collections import OrderedDict
import fcntl
import hashlib
import io
import json
import os
import re
from pathlib import Path
import selectors
import subprocess
import tarfile
import tempfile
import time
import uuid
import threading
from contextlib import ExitStack
from functools import lru_cache
from runner.telemetry import Timings, workspace
from runner.docker_control import EngineControl
from runner.scheduling import execution_lock
from runner.execution_contract import PROFILE, contract

EXECUTION_CONTRACT = contract()

ROOT = Path(__file__).resolve().parent.parent
POLICY = "java8-judge-v1"
RUN_POLICY = "java8-run-v1"
LANGUAGES = EXECUTION_CONTRACT["languages"]

def image_profile(image):
    return next((p for p in LANGUAGES.values() if p["image"] == image), LANGUAGES["JAVA"])

def runtime_policies(image):
    for profile in LANGUAGES.values():
        if image == profile["image"]:
            return profile["policy"], profile["runPolicy"]
    if image == (ROOT / "runner/java21-image.txt").read_text().strip():
        return "java21-m0-v2", "java21-run-v1"
    if image == (ROOT / "runner/java-image.txt").read_text().strip():
        return POLICY, RUN_POLICY
    raise ValueError("Unapproved runtime image")
SOURCE_LIMIT = PROFILE['sourceLimit']
OUTPUT_LIMIT = PROFILE['outputLimit']
BUILD_LIMIT = PROFILE['buildLimit']
COMPILE_COMMAND = PROFILE['compileCommand']


class CompileCache:
    """Worker-local, bounded memory only; never cache verdicts or failed builds.

    Generated version UUIDs, experimental drafts and hybrid validation branch UUIDs isolate jobs. Public problems and
    ordinary custom runs without that scope deliberately receive no cache entry.
    Two worker slots share the cache under a process-local reentrant lock.
    """
    def __init__(self, max_bytes=32 * 1024 * 1024, max_entries=16, ttl=600):
        self.entries = OrderedDict()
        self.lock = threading.RLock()
        self.max_bytes, self.max_entries, self.ttl = max_bytes, max_entries, ttl

    def key(self, version, image, source):
        identifier = r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"
        if not re.fullmatch(r"(?:generated-" + identifier + r"-r[0-9]+|(?:experimental|hybrid)-check-" + identifier + r"|rule-qualify-" + identifier + r")", version):
            return None
        return (version, image, tuple(next((p['compileCommand'] for p in LANGUAGES.values() if p['image'] == image), COMPILE_COMMAND)), hashlib.sha256(source).hexdigest())

    def prune(self):
        now = time.monotonic()
        for key, (created, _) in list(self.entries.items()):
            if now - created >= self.ttl:
                del self.entries[key]
        while (len(self.entries) > self.max_entries or
               sum(len(value[1]["stdout"]) + len(value[1]["stderr"]) for value in self.entries.values()) > self.max_bytes):
            self.entries.popitem(last=False)

    def get(self, key):
        with self.lock:
            self.prune()
            if key not in self.entries:
                return None
            self.entries.move_to_end(key)
            return dict(self.entries[key][1])

    def put(self, key, result):
        if key is None or result["limit"] or result["exit_code"] or result["oom_killed"]:
            return
        with self.lock:
            self.entries[key] = (time.monotonic(), dict(result))
            self.entries.move_to_end(key)
            self.prune()


class GeneratedCache:
    """Worker-local memory for generated large inputs and trusted expected outputs.

    Keyed by problem version, runtime and exact generator/reference/seed digests, so a changed program or
    seed never reuses data. Only the same scoped versions as CompileCache are cached. Verdicts never are.
    """
    def __init__(self, max_bytes=96 * 1024 * 1024, max_entries=24, ttl=1800):
        self.entries = OrderedDict()
        self.lock = threading.RLock()
        self.max_bytes, self.max_entries, self.ttl = max_bytes, max_entries, ttl

    @staticmethod
    def key(version, generator, reference, seed, expected):
        identifier = r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"
        if not re.fullmatch(r"(?:generated-" + identifier + r"-r[0-9]+|(?:experimental|hybrid)-check-" + identifier + r"|rule-qualify-" + identifier + r")", version):
            return None
        digest = lambda text: hashlib.sha256(text.encode()).hexdigest()
        return (version, digest(generator), digest(reference), seed, expected)

    def prune(self):
        now = time.monotonic()
        for key, (created, _) in list(self.entries.items()):
            if now - created >= self.ttl:
                del self.entries[key]
        while (len(self.entries) > self.max_entries or
               sum(len(value[1]["input"]) + len(value[1]["expected"]) for value in self.entries.values()) > self.max_bytes):
            self.entries.popitem(last=False)

    def get(self, key):
        if key is None:
            return None
        with self.lock:
            self.prune()
            if key not in self.entries:
                return None
            self.entries.move_to_end(key)
            return dict(self.entries[key][1])

    def put(self, key, value):
        if key is None:
            return
        with self.lock:
            self.entries[key] = (time.monotonic(), dict(value))
            self.entries.move_to_end(key)
            self.prune()


GENERATED_INPUT_LIMIT = 8 * 1024 * 1024
GENERATED_OUTPUT_LIMIT = 8 * 1024 * 1024


class InfrastructureError(Exception):
    pass


def docker_cli(*args):
    try:
        result = subprocess.run(["docker", *args], capture_output=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise InfrastructureError("Docker control command unavailable or timed out") from exc
    if result.returncode:
        raise InfrastructureError(result.stderr.decode(errors="replace")[:2048])
    return result.stdout


@lru_cache(maxsize=1)
def engine_control():
    # Resolve the same local endpoint as the CLI used for create/start and recovery.
    endpoint = (os.environ.get("DOCKER_HOST") if not os.environ.get("DOCKER_CONTEXT") else None)
    endpoint = endpoint or docker_cli("context", "inspect", "--format", "{{.Endpoints.docker.Host}}").decode().strip()
    return EngineControl(endpoint)


def docker(*args):
    mode = os.environ.get("GAMJAOJ_DOCKER_CONTROL", "cli")
    if mode not in ("cli", "engine"):
        raise InfrastructureError("Unsupported Docker control transport")
    if mode == "engine" and EngineControl.supports(args):
        try:
            return engine_control().command(args)
        except (OSError, ValueError, KeyError, TypeError) as exc:
            raise InfrastructureError("Local Docker control request failed") from exc
    return docker_cli(*args)


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
        self.profile = image_profile(image)
        self.state_dir = Path(state_dir or ROOT / ".state")
        self.attempt = str(uuid.UUID(attempt)) if attempt else None
        self.compile_cache = None
        self.generated_cache = None
        self.timings = Timings()
        self.execution_mode = "EXCLUSIVE"

    def sandbox(self, mount, command, stdin=b"", compile_phase=False, output_limit=None):
        name = "gamjaoj-sandbox-" + uuid.uuid4().hex
        settings = self.profile
        flags = list(PROFILE['sandboxFlags'])
        memory = str(settings['compileMemoryMb' if compile_phase else 'memoryMb'])+'m'
        for flag in ('--memory','--memory-swap'):
            flags[flags.index(flag)+1] = memory
        seconds = settings['compileWallSeconds' if compile_phase else 'testWallSeconds']
        args = ["create", "--name", name, "--label", "com.gamjaoj.role=sandbox",
                *flags, "--mount",
                "type=bind,src=" + str(mount) + ",dst=/work,readonly",
                "--workdir", PROFILE['workdir'], "-i", "--entrypoint", PROFILE['entrypoint'],
                self.image, PROFILE['timeoutSignal'],
                str(seconds + (5 if compile_phase else 3))+'s', *command]
        if self.attempt:
            args[1:1] = ["--label", "com.gamjaoj.attempt=" + self.attempt]
        phase = "compile" if compile_phase else "test"
        try:
            self.timings.call(phase+".container_create", docker, *args)
            result = self.timings.call(phase+".start_attach_wait", capture,["docker", "start", "--attach", "--interactive", name], stdin,
                             seconds,
                             BUILD_LIMIT if compile_phase else (output_limit or OUTPUT_LIMIT))
            if result["limit"]:
                try:
                    docker("kill", name)
                except InfrastructureError:
                    # The program may have exited between the limit and the kill request.
                    state = json.loads(docker("inspect", "--format", "{{json .State}}", name))
                    if state["Running"]:
                        raise
            state = json.loads(self.timings.call(phase+".container_inspect", docker, "inspect", "--format", "{{json .State}}", name))
            if state["Running"] or state.get("Error"):
                raise InfrastructureError("Container did not finish cleanly: " + str(state))
            if not result["limit"] and result["client_exit"] != state["ExitCode"]:
                raise InfrastructureError("Docker attachment lost; exit status is not reliable")
            # Docker timestamps delimit container lifetime, not pure Java CPU time.
            self.timings.segments.append(dict(phase=phase+".container_lifetime", startedAt=state.get("StartedAt"), finishedAt=state.get("FinishedAt")))
            result["exit_code"] = state["ExitCode"]
            result["oom_killed"] = state["OOMKilled"]
            return result
        finally:
            # Exact per-attempt name only: never prune or touch another project's containers.
            self.timings.call(phase+".container_remove", docker, "rm", "--force", name)

    def judge(self, source, problem):
        self.timings = Timings()
        self.state_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        # Existing serial Runners use the same exclusive gate. Functional work also
        # holds one of two host-wide slots; no caller can bypass the physical cap.
        with ExitStack() as locks:
            slot = self.timings.call("host_lock_wait", locks.enter_context, execution_lock(self.execution_mode))
            report = self._judge(source, problem)
            # Separate from result.json: diagnostics must not alter immutable verdict evidence.
            performance = self.timings.snapshot() | {
                "attemptToken": self.attempt, "sourceSha256": report["source_sha256"],
                "problemSha256": report["problem_sha256"], "runtimeImage": self.image,
                "requestedTests": len(problem["tests"]), "executedTests": len(report["tests"]),
                "compileCacheHit": report.get("compile", {}).get("cache_hit"), "verdict": report["verdict"],
                "dockerControl": os.environ.get("GAMJAOJ_DOCKER_CONTROL", "cli"),
                "executionMode": self.execution_mode, "functionalSlot": slot}
            try:
                (self.state_dir / "runs" / report["run_id"] / "performance.json").write_text(json.dumps(performance))
            except OSError:
                pass  # Diagnostic storage failure must not cause a re-execution.
            return report

    def _judge(self, source, problem):
        validate_problem(problem)
        if len(source) > SOURCE_LIMIT:
            raise ValueError("Source exceeds 64 KiB")
        run_id = uuid.uuid4().hex
        run_dir = self.state_dir / "runs" / run_id
        run_dir.mkdir(parents=True, mode=0o700)
        problem_bytes = json.dumps(problem, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode()
        (run_dir / self.profile["sourceFile"]).write_bytes(source)
        (run_dir / "problem.json").write_bytes(problem_bytes)
        custom = problem["output_policy"] == "RUN_ONLY"
        report = {"run_id": run_id, "policy": (runtime_policies(self.image)[1 if custom else 0] if self.image in [p['image'] for p in LANGUAGES.values()] + [(ROOT / 'runner/java21-image.txt').read_text().strip()] else POLICY), "image": self.image,
                  "source_sha256": hashlib.sha256(source).hexdigest(),
                  "problem_sha256": hashlib.sha256(problem_bytes).hexdigest(),
                  "runner_environment": {"contract": EXECUTION_CONTRACT,
                      "dockerControl": os.environ.get("GAMJAOJ_DOCKER_CONTROL", "cli")},
                  "language": self.profile["language"], "execution_profile": self.profile,
                  "problem_version": problem["version"], "execution_mode": self.execution_mode, "verdict": "IE", "tests": []}
        try:
            self.timings.call("image_inspect", docker, "image", "inspect", self.image)
            with workspace(self.timings) as directory:
                preparation_started = time.monotonic()
                work = Path(directory)
                work.chmod(0o755)
                (work / self.profile["sourceFile"]).write_bytes(source)
                (work / self.profile["sourceFile"]).chmod(0o444)
                self.timings.segments.append(dict(phase="workspace_prepare", elapsedMs=round((time.monotonic()-preparation_started)*1000,3)))
                cache_key = self.compile_cache.key(problem["version"], self.image, source) if self.compile_cache is not None else None
                result = self.timings.call("compile_cache_lookup", self.compile_cache.get, cache_key) if cache_key is not None else None
                cache_hit = result is not None
                if result is None:
                    result = self.timings.call("compile_total", self.sandbox, work, self.profile["compileCommand"], compile_phase=True)
                report["compile"] = evidence(result)
                report["compile"]["cache_hit"] = cache_hit
                # wall_ms remains original build evidence, not current request latency.
                if (result["exit_code"] in (137, 143) and not result["limit"]
                        and not result["oom_killed"]):
                    raise InfrastructureError("Unattributed compiler termination")
                if result["limit"] or result["exit_code"]:
                    report["verdict"] = "CE"
                else:
                    classes = work / "compiled"
                    classes.mkdir(mode=0o755)
                    classes.chmod(0o755)  # Worker uses umask 077; sandbox UID still needs traversal.
                    self.timings.call("artifact_unpack", unpack_classes, result["stdout"], classes, self.profile["artifact"])
                    if self.compile_cache is not None and not cache_hit:
                        self.compile_cache.put(cache_key, result)
                    for test in problem["tests"]:
                        result = self.timings.call("test_total", self.sandbox, classes, self.profile['testCommand'], test["input"].encode())
                        verdict = classify(result, None if custom else test["output"].encode())
                        report["tests"].append({"id": test["id"], "verdict": verdict,
                                                **evidence(result), **({"stdout": result["stdout"][:16384].decode(errors="replace"),
                                                "stdout_truncated": len(result["stdout"]) > 16384 or result["limit"] == "OUTPUT_LIMIT"} if custom else {})})
                        report["verdict"] = verdict
                        if verdict not in ("AC", "OK"):
                            break
                    if report["verdict"] == "AC" and problem.get("generated"):
                        self._generated(problem, classes, report)
        except (InfrastructureError, OSError, tarfile.TarError) as exc:
            report["verdict"] = "IE"
            report["error"] = str(exc)[:2048]
        temporary = run_dir / "result.tmp"
        with self.timings.measure("report_save"):
            temporary.write_text(json.dumps(report, ensure_ascii=False, indent=2))
            temporary.replace(run_dir / "result.json")
        return report


    def _aux_classes(self, version, source, parent):
        """Compiles a trusted Java 8 helper (generator or reference) in its own sandboxed build."""
        helper = Runner(LANGUAGES["JAVA"]["image"], self.state_dir, self.attempt)
        helper.timings, helper.compile_cache, helper.execution_mode = self.timings, self.compile_cache, self.execution_mode
        work = Path(tempfile.mkdtemp(dir=parent))
        work.chmod(0o755)
        (work / "Main.java").write_bytes(source.encode())
        (work / "Main.java").chmod(0o444)
        cache_key = self.compile_cache.key(version, helper.image, source.encode()) if self.compile_cache is not None else None
        result = self.compile_cache.get(cache_key) if cache_key is not None else None
        cached = result is not None
        if result is None:
            result = helper.sandbox(work, helper.profile["compileCommand"], compile_phase=True)
        if result["limit"] or result["exit_code"]:
            raise InfrastructureError("Generated test helper failed to compile")
        classes = work / "compiled"
        classes.mkdir(mode=0o755)
        classes.chmod(0o755)
        unpack_classes(result["stdout"], classes, "class")
        if self.compile_cache is not None and not cached:
            self.compile_cache.put(cache_key, result)
        return helper, classes

    def _generated(self, problem, classes, report):
        """Large tests: a trusted generator builds the input inside the sandbox boundary and a trusted
        reference (or a fixed VALID expectation) supplies the answer. The input never leaves the Runner."""
        spec = problem["generated"]
        with tempfile.TemporaryDirectory(prefix="gamjaoj-generated-") as parent:
            Path(parent).chmod(0o755)
            generator = reference = None
            for test in spec["tests"]:
                key = GeneratedCache.key(problem["version"], spec["generator"], spec.get("reference", ""), test["seed"], test["expected"])
                data = self.generated_cache.get(key) if self.generated_cache is not None else None
                entry = {"id": test["id"], "kind": "generated", "cache_hit": data is not None}
                if data is None:
                    if generator is None:
                        generator = self._aux_classes(problem["version"], spec["generator"], parent)
                    produced = self.timings.call("generated.generator", generator[0].sandbox, generator[1], generator[0].profile["testCommand"],
                                                 (test["seed"] + "\n").encode(), output_limit=GENERATED_INPUT_LIMIT)
                    if produced["limit"] or produced["exit_code"] or produced["oom_killed"] or not produced["stdout"].strip():
                        raise InfrastructureError("Generated input could not be produced")
                    data = {"input": produced["stdout"], "generator_wall_ms": produced["wall_ms"]}
                    if test["expected"] == "VALID":
                        data["expected"] = b"VALID\n"
                    else:
                        if reference is None:
                            reference = self._aux_classes(problem["version"], spec["reference"], parent)
                        solved = self.timings.call("generated.reference", reference[0].sandbox, reference[1], reference[0].profile["testCommand"],
                                                   data["input"], output_limit=GENERATED_OUTPUT_LIMIT)
                        if solved["limit"] or solved["exit_code"] or solved["oom_killed"] or not solved["stdout"].strip():
                            raise InfrastructureError("Generated expected output could not be produced")
                        data["expected"], data["reference_wall_ms"] = solved["stdout"], solved["wall_ms"]
                    if self.generated_cache is not None:
                        self.generated_cache.put(key, data)
                result = self.timings.call("test_total", self.sandbox, classes, self.profile["testCommand"], data["input"], output_limit=GENERATED_OUTPUT_LIMIT)
                verdict = classify(result, data["expected"])
                entry |= {"verdict": verdict, **evidence(result), "input_sha256": hashlib.sha256(data["input"]).hexdigest(),
                          "input_bytes": len(data["input"]), "expected_sha256": hashlib.sha256(data["expected"]).hexdigest(),
                          "generator_wall_ms": data.get("generator_wall_ms"), "reference_wall_ms": data.get("reference_wall_ms")}
                report["tests"].append(entry)
                report["verdict"] = verdict
                if verdict != "AC":
                    break


def unpack_classes(archive, destination, artifact="class"):
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
            if not member.isfile() or (path.suffix != ".class" if artifact == "class" else member.name != ("main" if artifact == "binary" else "Main.py")) or count > 4096 or total > BUILD_LIMIT:
                raise InfrastructureError("Invalid compiler artifact")
            target = destination / path
            target.parent.mkdir(parents=True, exist_ok=True, mode=0o755)
            parent = target.parent
            while parent != destination:
                parent.chmod(0o755)
                parent = parent.parent
            target.write_bytes(package.extractfile(member).read())
            target.chmod(0o555 if artifact == "binary" else 0o444)


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
    generated = problem.get("generated")
    if generated is not None:
        if problem["output_policy"] != "TOKEN_EXACT" or not isinstance(generated, dict) or set(generated) - {"generator", "reference", "tests"}:
            raise ValueError("Generated tests require the judge policy and a known shape")
        cases = generated.get("tests")
        if not isinstance(generated.get("generator"), str) or not 0 < len(generated["generator"].encode()) <= SOURCE_LIMIT:
            raise ValueError("Generated tests need a bounded generator")
        if not isinstance(cases, list) or not 1 <= len(cases) <= 4:
            raise ValueError("Expected 1 to 4 generated tests")
        needs_reference = any(isinstance(c, dict) and c.get("expected") == "REFERENCE" for c in cases)
        if needs_reference and (not isinstance(generated.get("reference"), str) or not 0 < len(generated["reference"].encode()) <= SOURCE_LIMIT):
            raise ValueError("Generated reference answers need a bounded reference")
        for case in cases:
            if (not isinstance(case, dict) or set(case) != {"id", "seed", "expected"} or case["expected"] not in ("REFERENCE", "VALID")
                    or not isinstance(case["seed"], str) or not re.fullmatch(r"-?[0-9]{1,19}", case["seed"])
                    or not isinstance(case["id"], str) or not re.fullmatch(r"[a-z0-9-]{1,40}", case["id"])):
                raise ValueError("Generated test entries must be id, integer seed and expectation")
    identifiers = {case["id"] for case in generated["tests"]} if generated else set()
    if generated and len(identifiers) != len(generated["tests"]):
        raise ValueError("Test ids must be unique strings")
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
