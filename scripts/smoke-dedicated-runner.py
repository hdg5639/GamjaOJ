"""Exercise HTTP -> PostgreSQL -> persistent the dedicated Runner host, without running code on the application host."""
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
    return ssh(os.environ["GAMJAOJ_APP_SSH_TARGET"], "docker exec -i gamjaoj-postgres-1 psql -U gamjaoj -d gamjaoj -At -v ON_ERROR_STOP=1", statement)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--catalog-only", action="store_true", help="Verify new catalog references and representative wrong answers on the persistent worker")
    mode.add_argument("--restart-worker", action="store_true", help="Pre-opening only: kill an active synthetic attempt and verify automatic recovery")
    mode.add_argument("--memory-only", action="store_true", help="Verify memory enforcement and measured runs/formal submissions in all three languages")
    args = parser.parse_args()
    # Only the invitation is needed locally; worker/DB credentials remain on their hosts.
    invitation = ssh(os.environ["GAMJAOJ_APP_SSH_TARGET"], "sed -n 's/^INVITE_CODE=//p' ~/gamjaoj/web/.env")
    base = (os.environ.get("GAMJAOJ_BASE_URL") or ssh(os.environ["GAMJAOJ_APP_SSH_TARGET"], "sed -n 's/^PUBLIC_BASE_URL=//p' ~/gamjaoj/web/.env")).rstrip("/")
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
    worker_ids = set(json.loads(ssh(os.environ["GAMJAOJ_RUNNER_SSH_TARGET"], "python3 -c 'import json,pathlib; p=pathlib.Path.home()/\"gamjaoj-worker/state\"; print(json.dumps([json.loads(f.read_text())[\"workerId\"] for f in [p/\"identity.json\",*p.glob(\"slots/*/identity.json\")]]))'")))
    environment_evidence=[]
    expected_environment=json.loads(Path('backend/src/main/resources/runner-execution-contract.json').read_text())
    def verify_environment(job):
        value=json.loads(sql(f"SELECT json_build_object('expected',a.execution_environment_json::json,'actual',j.result_json::json->'runner_environment') FROM judge_job j JOIN judge_attempt a ON a.submission_id=j.submission_id AND a.attempt=j.attempt WHERE j.submission_id='{job}'"))
        assert value['expected']==expected_environment
        assert value['actual']['contract']==value['expected']
        assert value['actual']['dockerControl']=='engine'
        environment_evidence.append(dict(submissionId=job,environment=value['actual']))
    for worker_id in worker_ids: uuid.UUID(worker_id)
    assert ssh(os.environ["GAMJAOJ_RUNNER_SSH_TARGET"], "systemctl --user is-active gamjaoj-worker") == "active"
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
        jobs = [("sum-v1", verdict, source) for verdict, source in sources.items()]
        if args.catalog_only:
            root = Path(__file__).resolve().parent.parent
            total = (root / "examples/total/Main.java").read_text()
            parens = (root / "examples/valid-parentheses/Main.java").read_text()
            jobs = [("total-v1", "AC", total),
                    ("total-v1", "WA", total.replace("long sum", "int sum")),
                    ("total-v1", "WA", total.replace("i < n;", "i < n - 1;")),
                    ("valid-parentheses-v1", "AC", parens),
                    ("valid-parentheses-v1", "WA", parens.replace("if (depth < 0)", "if (false)"))]
            public = call("/api/problems")[1]
            # The catalog now also lists shared member problems; base problems must remain and no private field may leak.
            assert {"sum-v1", "total-v1", "valid-parentheses-v1"} <= {item["version"] for item in public}
            assert not any(key in item for item in public for key in ("tests", "package", "generated", "reference", "teaching"))
        if args.memory_only:
            assert opened, "Memory smoke requires ordinary execution admission"
            jobs=[]
        for version, verdict, source in jobs:
            if not opened:
                digest = hashlib.sha256(source.encode()).hexdigest()
                sql(f"INSERT INTO execution_grant SELECT id,'{digest}' FROM app_user WHERE username='{username}'")
            key = str(uuid.uuid4())
            payload = dict(problemVersion=version, source=source)
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
                        active = ssh(os.environ["GAMJAOJ_RUNNER_SSH_TARGET"], f"docker ps -q --filter label=com.gamjaoj.attempt={token}")
                        if active:
                            ssh(os.environ["GAMJAOJ_RUNNER_SSH_TARGET"], "systemctl --user kill --signal=SIGKILL gamjaoj-worker.service")
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
            assert done["problemVersion"] == version and done["runnerPolicy"] == "java8-judge-v1"
            assert sql(f"SELECT worker_id::text||'|'||attempt FROM judge_job WHERE submission_id='{job}'") in {worker_id + "|1" for worker_id in worker_ids}
            assert sql(f"SELECT count(*) FROM judge_attempt WHERE submission_id='{job}' AND status='COMPLETED'") == "1"
            assert sql(f"SELECT count(*) FROM submission WHERE user_id=(SELECT id FROM app_user WHERE username='{username}') AND idempotency_key='{key}'") == "1"
            verify_environment(job)
            print(f"PASS: {version} {verdict} on the dedicated Runner host, same-key replay, one completed attempt, persisted source", flush=True)
        if opened:
            assert sql(f"SELECT count(*) FROM execution_grant WHERE user_id=(SELECT id FROM app_user WHERE username='{username}')") == "0"
            print("PASS: ordinary account submission without execution grants", flush=True)
            if args.catalog_only:
                assert len(call("/api/submissions")[1]) == len(jobs)
                return
            cases = [
                ("OK", sources["AC"], "17 25\n", "42\n"),
                ("OK", 'public class Main {public static void main(String[] a) {System.out.println(System.getProperty("java.specification.version"));}}', "", "1.8\n"),
                ("CE", 'public class Main {public static void main(String[] a) {System.out.println(java.util.List.of(1));}}', "", ""),
                ("CE", sources["CE"], "", ""),
                ("RE", 'public class Main {public static void main(String[] a) {throw new RuntimeException("custom error");}}', "", ""),
                ("TLE", sources["TLE"], "", ""),
                ("OLE", 'public class Main {public static void main(String[] a) {while(true) System.out.print(new String(new char[8192]).replace("\\0", "x"));}}', "", None),
            ]
            memory_cases = [
                ("JAVA", "OK", 'public class Main {static byte[] a; public static void main(String[] args){a=new byte[32*1024*1024];for(int i=0;i<a.length;i+=4096)a[i]=1;System.out.println(a[0]);}}', "", "1\n"),
                ("JAVA", "RE", 'public class Main {static byte[] a; public static void main(String[] args){a=new byte[192*1024*1024];System.out.println(a.length);}}', "", None),
                ("CPP", "OK", '#include <iostream>\nstatic volatile unsigned char a[512ULL*1024*1024];int main(){std::cout<<int(a[0])<<"\\n";}', "", "0\n"),
                ("CPP", "MLE", '#include <iostream>\nstatic volatile unsigned char a[512ULL*1024*1024];int main(){for(unsigned long i=0;i<sizeof(a);i+=4096)a[i]=1;std::cout<<int(a[0]);}', "", None),
                ("PYTHON", "MLE", 'a=bytearray(512*1024*1024)\nprint(len(a))', "", None),
            ]
            selected=memory_cases if args.memory_only else [("JAVA", *case) for case in cases]
            memory_evidence=[]
            for language, verdict, source, stdin, output in selected:
                key = str(uuid.uuid4())
                payload = dict(problemVersion="sum-v1", source=source, input=stdin, language=language)
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
                elif verdict == "OLE": assert done["outputTruncated"] and len(done["stdout"]) <= 16384
                if verdict == "RE" and not args.memory_only: assert "custom error" in done["stderr"]
                if args.memory_only:
                    result=json.loads(sql(f"SELECT result_json FROM judge_job WHERE submission_id='{job}'"))
                    assert done.get('memoryPeakBytes') and done['memoryPeakBytes']>0, done
                    assert done['memoryPeakBytes']==max(t['memory_peak_bytes'] for t in result['tests'])
                    assert all(t['memory_measurement']=='cgroup-peak-observed' for t in result['tests'])
                    profile=result['execution_profile']
                    assert profile['language']==language
                    assert profile['memoryMb']==(384 if language=='JAVA' else 256)
                    assert all(t['oom_killed']==(verdict=='MLE') for t in result['tests'])
                    if language=='JAVA' and verdict=='RE':
                        assert 'OutOfMemoryError' in json.dumps(result)
                    memory_evidence.append(dict(language=language,expected=verdict,source=source,result=result))
                assert sql(f"SELECT worker_id::text FROM judge_job WHERE submission_id='{job}'") in worker_ids
                assert sql(f"SELECT count(*) FROM judge_attempt WHERE submission_id='{job}' AND status='COMPLETED'") == "1"
                verify_environment(job)
                print(f"PASS: custom {language} {verdict}, input/output preserved, one completion on the dedicated Runner host", flush=True)
            if args.memory_only:
                formal_sources = {
                    "JAVA": sources["AC"],
                    "CPP": '#include <iostream>\nint main(){long long a,b;std::cin>>a>>b;std::cout<<a+b<<"\\n";}',
                    "PYTHON": 'import sys\nprint(sum(map(int,sys.stdin.read().split())))',
                }
                for language, source in formal_sources.items():
                    status, saved = call("/api/submissions", "POST", dict(problemVersion="sum-v1", source=source, language=language), str(uuid.uuid4()))
                    assert status == 202, saved
                    job = str(uuid.UUID(saved["id"]))
                    deadline = time.monotonic() + 90
                    while time.monotonic() < deadline:
                        done = call("/api/submissions/" + job)[1]
                        if done["status"] == "FINISHED": break
                        time.sleep(1)
                    else: raise AssertionError("Formal execution did not finish")
                    assert done['verdict'] == 'AC', done
                    result = json.loads(sql(f"SELECT result_json FROM judge_job WHERE submission_id='{job}'"))
                    assert all(t.get('memory_peak_bytes', 0) > 0 and t['memory_measurement'] == 'cgroup-peak-observed' for t in result['tests'])
                    assert done['memoryPeakBytes'] == max(t['memory_peak_bytes'] for t in result['tests'])
                    assert done['wallMs'] == max(t['wall_ms'] for t in result['tests'])
                    assert [t['memoryPeakBytes'] for t in done['tests']] == [t['memory_peak_bytes'] for t in result['tests']]
                    assert all(not any(k in t for k in ('input', 'expected', 'stdout', 'stderr')) for t in done['tests'])
                    assert sql(f"SELECT worker_id::text FROM judge_job WHERE submission_id='{job}'") in worker_ids
                    assert sql(f"SELECT count(*) FROM judge_attempt WHERE submission_id='{job}' AND status='COMPLETED'") == "1"
                    verify_environment(job)
                    memory_evidence.append(dict(language=language,kind='SUBMISSION',expected='AC',public=done,result=result))
                    print(f"PASS: formal {language} AC, {done['wallMs']} ms, {done['memoryPeakBytes']/1048576:.2f} MiB, public test maxima match saved report", flush=True)
                Path('.state').mkdir(exist_ok=True)
                Path('.state/memory-limits-live.json').write_text(json.dumps(memory_evidence,indent=2))
            assert len(call("/api/submissions")[1]) == (3 if args.memory_only else 4)
            assert call("/api/runs")[1] == []  # Custom-run history listing is intentionally not exposed.
        Path('.state').mkdir(exist_ok=True)
        Path('.state/runner-environment-live.json').write_text(json.dumps(environment_evidence,indent=2))
        print(f"PASS: {len(environment_evidence)} saved attempt environments match actual Runner build and limits",flush=True)
    finally:
        sql(f"DELETE FROM spring_session WHERE principal_name='{username}'; DELETE FROM app_user WHERE username='{username}';")


if __name__ == "__main__":
    main()
