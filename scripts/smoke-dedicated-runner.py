"""Exercise HTTP -> PostgreSQL -> persistent runner-serv, without running code on ocr-serv."""
import argparse
import hashlib
import http.cookiejar
import json
import os
from pathlib import Path
import secrets
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def ssh(host, command, data=None):
    return subprocess.run(["ssh", "-o", "BatchMode=yes", host, command], input=data,
                          text=True, capture_output=True, check=True, timeout=45).stdout.strip()


def sql(statement):
    return ssh("ocr-serv", "docker exec -i gamjaoj-postgres-1 psql -U gamjaoj -d gamjaoj -At -v ON_ERROR_STOP=1", statement)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--restart-worker", action="store_true", help="Pre-opening only: kill an active synthetic attempt and verify automatic recovery")
    args = parser.parse_args()
    # Only the invitation is needed locally; worker/DB credentials remain on their hosts.
    invitation = ssh("ocr-serv", "sed -n 's/^INVITE_CODE=//p' ~/gamjaoj/web/.env")
    base = (os.environ.get("GAMJAOJ_BASE_URL") or ssh("ocr-serv", "sed -n 's/^PUBLIC_BASE_URL=//p' ~/gamjaoj/web/.env") or "http://192.168.0.210:18081").rstrip("/")
    client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    client.addheaders = [("User-Agent", "GamjaOJ-Smoke/1.0")]

    def call(path, method="GET", body=None, key=None, form=False):
        headers = {}
        if method != "GET":
            _, csrf = call("/api/auth/csrf")
            headers[csrf["headerName"]] = csrf["token"]
        if key:
            headers["Idempotency-Key"] = key
        if body is not None:
            headers["Content-Type"] = "application/x-www-form-urlencoded" if form else "application/json"
            body = (urllib.parse.urlencode(body) if form else json.dumps(body)).encode()
        try:
            response = client.open(urllib.request.Request(base + path, data=body, headers=headers, method=method), timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None

    username, password = "runner_" + secrets.token_hex(5), secrets.token_urlsafe(24)
    worker_id = json.loads(ssh("runner-serv", "cat ~/gamjaoj-worker/state/identity.json"))["workerId"]
    uuid.UUID(worker_id)
    assert ssh("runner-serv", "systemctl --user is-active gamjaoj-worker") == "active"
    try:
        assert call("/api/auth/signup", "POST", dict(username=username, password=password, nickname="Runner 검증", inviteCode=invitation))[0] == 201
        assert call("/api/auth/login", "POST", dict(username=username, password=password), form=True)[0] == 204
        opened = call("/api/problems")[1][0]["submissionsEnabled"]
        if args.restart_worker:
            assert not opened, "Fault injection is only allowed before general submissions open"
            assert sql("SELECT count(*) FROM judge_job WHERE status <> 'FINISHED'") == "0"
        sources = {
            "AC": (Path(__file__).resolve().parent.parent / "examples/Main.java").read_text(),
            "WA": "public class Main {public static void main(String[] a) {System.out.println(42);}}",
            "CE": "public class Main { invalid java; }",
            "TLE": "public class Main {public static void main(String[] a) {while(true) {}}}",
        }
        for verdict, source in sources.items():
            if not opened:
                digest = hashlib.sha256(source.encode()).hexdigest()
                sql(f"INSERT INTO execution_grant SELECT id,'{digest}' FROM app_user WHERE username='{username}'")
            key = str(uuid.uuid4())
            payload = dict(problemVersion="sum-v1", source=source)
            status, submission = call("/api/submissions", "POST", payload, key)
            assert status == 202, submission
            job = str(uuid.UUID(submission["id"]))
            assert call("/api/submissions", "POST", payload, key)[1]["id"] == job
            if args.restart_worker and verdict == "AC":
                deadline = time.monotonic() + 30
                while time.monotonic() < deadline:
                    token = sql(f"SELECT coalesce(token::text,'') FROM judge_job WHERE submission_id='{job}'")
                    if token:
                        uuid.UUID(token)
                        active = ssh("runner-serv", f"docker ps -q --filter label=com.gamjaoj.attempt={token}")
                        if active:
                            ssh("runner-serv", "systemctl --user kill --signal=SIGKILL gamjaoj-worker.service")
                            print("Injected coordinator failure during a real sandbox execution", flush=True)
                            break
                    time.sleep(.2)
                else:
                    raise AssertionError("No active sandbox observed for fault injection")
            deadline = time.monotonic() + 90
            while time.monotonic() < deadline:
                done = call("/api/submissions/" + job)[1]
                if done["status"] == "FINISHED":
                    break
                time.sleep(1)
            else:
                raise AssertionError("Persistent worker did not finish submission")
            assert done["verdict"] == verdict, done
            assert done["source"] == source
            assert sql(f"SELECT worker_id::text||'|'||attempt FROM judge_job WHERE submission_id='{job}'") == worker_id + "|1"
            assert sql(f"SELECT count(*) FROM judge_attempt WHERE submission_id='{job}' AND status='COMPLETED'") == "1"
            assert sql(f"SELECT count(*) FROM submission WHERE user_id=(SELECT id FROM app_user WHERE username='{username}') AND idempotency_key='{key}'") == "1"
            print(f"PASS: {verdict} on runner-serv, same-key replay, one completed attempt, persisted source", flush=True)
        if opened:
            assert sql(f"SELECT count(*) FROM execution_grant WHERE user_id=(SELECT id FROM app_user WHERE username='{username}')") == "0"
            print("PASS: ordinary account submission without execution grants", flush=True)
            cases = [
                ("OK", sources["AC"], "17 25\n", "42\n"),
                ("OK", 'public class Main {public static void main(String[] a) {System.out.println(System.getProperty("java.specification.version"));}}', "", "1.8\n"),
                ("CE", 'public class Main {public static void main(String[] a) {System.out.println(java.util.List.of(1));}}', "", ""),
                ("CE", sources["CE"], "", ""),
                ("RE", 'public class Main {public static void main(String[] a) {throw new RuntimeException("custom error");}}', "", ""),
                ("TLE", sources["TLE"], "", ""),
                ("OLE", 'public class Main {public static void main(String[] a) {while(true) System.out.print(new String(new char[8192]).replace("\\0", "x"));}}', "", None),
            ]
            for verdict, source, stdin, output in cases:
                key = str(uuid.uuid4())
                payload = dict(problemVersion="sum-v1", source=source, input=stdin)
                status, saved = call("/api/runs", "POST", payload, key)
                assert status == 202, saved
                job = str(uuid.UUID(saved["id"]))
                assert call("/api/runs", "POST", payload, key)[1]["id"] == job
                assert call("/api/runs", "POST", dict(payload,input=stdin+"different"), key)[0] == 409
                assert call("/api/submissions/"+job)[0] == 404
                deadline = time.monotonic() + 90
                while time.monotonic() < deadline:
                    done = call("/api/runs/"+job)[1]
                    if done["status"] == "FINISHED": break
                    time.sleep(1)
                else: raise AssertionError("Custom execution did not finish")
                assert done["verdict"] == verdict and done["input"] == stdin, done
                if output is not None: assert done["stdout"] == output
                else: assert done["outputTruncated"] and len(done["stdout"]) <= 16384
                if verdict == "RE": assert "custom error" in done["stderr"]
                assert sql(f"SELECT worker_id::text FROM judge_job WHERE submission_id='{job}'") == worker_id
                assert sql(f"SELECT count(*) FROM judge_attempt WHERE submission_id='{job}' AND status='COMPLETED'") == "1"
                print(f"PASS: custom {verdict}, input/output preserved, one completion on runner-serv", flush=True)
            assert len(call("/api/submissions")[1]) == 4
            assert len(call("/api/runs")[1]) == len(cases)
    finally:
        sql(f"DELETE FROM spring_session WHERE principal_name='{username}'; DELETE FROM app_user WHERE username='{username}';")


if __name__ == "__main__":
    main()
