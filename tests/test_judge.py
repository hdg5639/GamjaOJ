import io
import json
import os
from pathlib import Path
import tarfile
import tempfile
import unittest

from runner.judge import InfrastructureError, ROOT, Runner, classify, unpack_classes, validate_problem


class ContractTests(unittest.TestCase):
    def test_run_contract_is_not_a_judge_verdict(self):
        result = {"stdout": b"anything", "limit": None, "exit_code": 0, "oom_killed": False}
        self.assertEqual("OK", classify(result, None))
        for tests in [[], [{"id": "custom-input", "input": "x" * 16385, "output": ""}],
                      [{"id": "custom-input", "input": "", "output": "secret"}]]:
            with self.assertRaises(ValueError):
                validate_problem({"version": "v1", "output_policy": "RUN_ONLY", "tests": tests})

    def test_comparison_and_failure_precedence(self):
        result = {"stdout": b"  3\n", "limit": None, "exit_code": 0, "oom_killed": False}
        self.assertEqual("AC", classify(result, b"3"))
        self.assertEqual("WA", classify(result, b"03"))
        self.assertEqual("OLE", classify(result | {"limit": "OUTPUT_LIMIT"}, b"3"))
        self.assertEqual("MLE", classify(result | {"oom_killed": True, "exit_code": 137}, b"3"))
        with self.assertRaises(InfrastructureError):
            classify(result | {"exit_code": 137}, b"3")

    def test_empty_suite_cannot_pass(self):
        with self.assertRaises(ValueError):
            validate_problem({"version": "x", "output_policy": "TOKEN_EXACT", "tests": []})

    def test_compiler_artifact_cannot_escape_or_link(self):
        for name, kind in [("../escape.class", tarfile.REGTYPE),
                           ("/escape.class", tarfile.REGTYPE),
                           ("Main.class", tarfile.SYMTYPE)]:
            with self.subTest(name=name, kind=kind), tempfile.TemporaryDirectory() as directory:
                archive = io.BytesIO()
                with tarfile.open(fileobj=archive, mode="w") as package:
                    entry = tarfile.TarInfo(name)
                    entry.type = kind
                    entry.linkname = "/etc/passwd" if kind == tarfile.SYMTYPE else ""
                    package.addfile(entry)
                with self.assertRaises(InfrastructureError):
                    unpack_classes(archive.getvalue(), Path(directory))


@unittest.skipUnless(os.environ.get("GAMJAOJ_DOCKER_TESTS") == "1", "real Docker opt-in")
class DockerTests(unittest.TestCase):
    def test_java8_runtime_and_newer_api_rejection(self):
        self.check('if (!System.getProperty("java.specification.version").equals("1.8")) throw new RuntimeException("Wrong Java"); System.out.println(3);', 'AC')
        self.check('System.out.println(java.util.List.of(3));', 'CE')

    def test_legacy_java21_plan_remains_executable(self):
        self.runner.image = (ROOT / 'runner/java21-image.txt').read_text().strip()
        report = self.check('System.out.println(java.util.List.of(3).get(0));', 'AC')
        self.assertEqual('java21-m0-v2', report['policy'])
    def test_custom_input_returns_output_without_answer_comparison(self):
        problem = {"version": "test-v1", "output_policy": "RUN_ONLY", "tests": [
            {"id": "custom-input", "input": "17 25\n", "output": ""}]}
        report = self.runner.judge((ROOT / "examples/Main.java").read_bytes(), problem)
        self.assertEqual("OK", report["verdict"], report)
        self.assertEqual("java8-run-v1", report["policy"])
        self.assertEqual("42\n", report["tests"][0]["stdout"])
        self.assertFalse(report["tests"][0]["stdout_truncated"])

    def test_custom_output_is_bounded_and_regular_judge_does_not_expose_stdout(self):
        problem = {"version": "test-v1", "output_policy": "RUN_ONLY", "tests": [
            {"id": "custom-input", "input": "", "output": ""}]}
        source = b'public class Main {public static void main(String[] a) {System.out.print(new String(new char[20000]).replace("\\0", "x"));}}'
        report = self.runner.judge(source, problem)
        self.assertEqual("OK", report["verdict"], report)
        self.assertEqual(16384, len(report["tests"][0]["stdout"]))
        self.assertTrue(report["tests"][0]["stdout_truncated"])
        regular = self.check('System.out.println(3);', 'AC')
        self.assertNotIn("stdout", regular["tests"][0])

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.runner = Runner((ROOT / "runner/java-image.txt").read_text().strip(), self.directory.name)
        self.problem = {"version": "test-v1", "output_policy": "TOKEN_EXACT",
                        "tests": [{"id": "one", "input": "1 2\n", "output": "3\n"}]}

    def check(self, body, expected):
        source = ("public class Main { public static void main(String[] args) throws Exception {"
                  + body + "}}").encode()
        report = self.runner.judge(source, self.problem)
        self.assertEqual(expected, report["verdict"], report)
        run_dir = Path(self.directory.name) / "runs" / report["run_id"]
        self.assertEqual(report, json.loads((run_dir / "result.json").read_text()))
        self.assertEqual(source, (run_dir / "Main.java").read_bytes())
        self.assertEqual(self.problem, json.loads((run_dir / "problem.json").read_text()))
        return report

    def test_all_manual_sum_cases(self):
        report = self.runner.judge((ROOT / "examples/Main.java").read_bytes(),
                                  json.loads((ROOT / "problems/sum-v1.json").read_text()))
        self.assertEqual("AC", report["verdict"], report)
        self.assertEqual(5, len(report["tests"]))

    def test_wa(self):
        self.check('System.out.println(4);', "WA")

    def test_private_worker_umask_does_not_hide_compiled_artifacts(self):
        previous = os.umask(0o077)
        try:
            self.check('System.out.println(3);', "AC")
        finally:
            os.umask(previous)

    def test_ce(self):
        self.check('not valid java;', "CE")

    def test_re(self):
        self.check('throw new RuntimeException("expected");', "RE")

    def test_tle(self):
        self.check('while (true) {}', "TLE")

    def test_ole(self):
        self.check('while (true) System.out.println(new String(new char[8192]).replace("\\0", "x"));', "OLE")

    def test_stderr_ole(self):
        self.check('while (true) System.err.println(new String(new char[8192]).replace("\\0", "x"));', "OLE")

    def test_infrastructure_error_is_persisted(self):
        self.runner.image = "eclipse-temurin@sha256:" + "0" * 64
        self.check('System.out.println(3);', "IE")

    def test_secret_network_and_readonly_boundary(self):
        self.check('''
            for (String name : new String[]{"/var/run/docker.sock", "/root/.ssh",
                    "/root/.codex/auth.json", "/work/problem.json", "/work/.env",
                    "/work/Main.java", "/private/tests"}) {
                if (java.nio.file.Files.exists(java.nio.file.Paths.get(name)))
                    throw new RuntimeException("unexpected file: " + name);
            }
            if (System.getenv("OPENAI_API_KEY") != null) throw new RuntimeException("key exposed");
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                if (!interfaces.nextElement().isLoopback()) throw new RuntimeException("network");
            }
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress("1.1.1.1", 443), 250);
                throw new RuntimeException("outbound allowed");
            } catch (java.io.IOException expected) {}
            try {
                java.nio.file.Files.write(java.nio.file.Paths.get("/work/tamper"), "bad".getBytes("UTF-8"));
                throw new RuntimeException("writable classes");
            } catch (java.io.IOException expected) {}
            System.out.println(3);
        ''', "AC")

    def test_each_case_has_fresh_writable_state(self):
        self.problem["tests"].append({"id": "two", "input": "", "output": "3"})
        self.check('''
            java.nio.file.Path marker = java.nio.file.Paths.get("/tmp/previous-case");
            if (java.nio.file.Files.exists(marker)) throw new RuntimeException("shared state");
            java.nio.file.Files.write(marker, "written".getBytes("UTF-8"));
            System.out.println(3);
        ''', "AC")

    def test_child_process_cleanup(self):
        self.check('new ProcessBuilder("/bin/sleep", "60").start(); System.out.println(3);', "AC")

    def test_kernel_limits_and_nonroot_are_effective(self):
        self.check('''
            String memory = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/sys/fs/cgroup/memory.max")), "UTF-8").trim();
            String pids = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/sys/fs/cgroup/pids.max")), "UTF-8").trim();
            String cpu = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/sys/fs/cgroup/cpu.max")), "UTF-8").trim();
            String status = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/proc/self/status")), "UTF-8");
            if (!memory.equals("402653184") || !pids.equals("128") || !cpu.equals("50000 100000"))
                throw new RuntimeException("resource controls missing");
            if (!status.contains("NoNewPrivs:\\t1") || !status.contains("Uid:\\t65534"))
                throw new RuntimeException("identity controls missing");
            System.out.println(3);
        ''', "AC")

    def test_heap_exhaustion_is_not_claimed_as_kernel_mle(self):
        self.check('java.util.ArrayList<byte[]> xs = new java.util.ArrayList<byte[]>(); while(true) xs.add(new byte[1048576]);', "RE")


if __name__ == "__main__":
    unittest.main()
