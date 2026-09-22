"""Real PostgreSQL + HTTP + Docker judge smoke, with source-hash-scoped synthetic grants."""
import argparse
import hashlib
import http.cookiejar
import json
import os
from pathlib import Path
import secrets
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--compose", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    config = dict(line.split("=", 1) for line in args.env_file.read_text().splitlines() if line and not line.startswith("#"))
    if config.get("SUBMISSIONS_ENABLED", "false").lower() == "true":
        raise SystemExit("Shared-VM execution smoke is disabled after general submissions are opened; verify on the dedicated Runner VM.")
    base = config.get("PUBLIC_BASE_URL", "http://" + config["BIND_ADDRESS"] + ":" + config["HTTP_PORT"]).rstrip("/")
    compose = ["docker", "compose", "--env-file", str(args.env_file), "-f", str(args.compose)]
    names = ["judge_" + secrets.token_hex(5) for _ in range(2)]
    password = secrets.token_urlsafe(24)
    correct = (root / "examples/Main.java").read_text()
    wrong = 'public class Main { public static void main(String[] args) { System.out.println(42); } }'
    state = root / ".state" / ("worker-" + names[0])
    worker_env = dict(os.environ, GAMJAOJ_API_URL=base, WORKER_TOKEN=config["WORKER_TOKEN"])
    worker_command = [sys.executable, "-m", "runner.worker", "--state-dir", str(state), "--once"]

    def sql(statement):
        return subprocess.run(compose + ["exec", "-T", "postgres", "psql", "-v", "ON_ERROR_STOP=1", "-U", "gamjaoj", "-d", "gamjaoj", "-At", "-c", statement],
            capture_output=True, text=True, check=True, stdin=subprocess.DEVNULL).stdout.strip()

    class Browser:
        def __init__(self):
            self.client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
            self.client.addheaders = [("User-Agent", "GamjaOJ-Smoke/1.0")]
        def call(self, path, method="GET", body=None, key=None, form=False):
            headers = {}
            if method != "GET":
                _, token = self.call("/api/auth/csrf")
                headers[token["headerName"]] = token["token"]
            if key: headers["Idempotency-Key"] = key
            data = None
            if body is not None:
                headers["Content-Type"] = "application/x-www-form-urlencoded" if form else "application/json"
                data = (urllib.parse.urlencode(body) if form else json.dumps(body)).encode()
            try: response = self.client.open(urllib.request.Request(base+path, data=data, headers=headers, method=method), timeout=15)
            except urllib.error.HTTPError as error: response = error
            payload = response.read()
            return response.status, json.loads(payload) if payload else None

    def worker_api(path, body):
        request = urllib.request.Request(base+"/internal/judge"+path, data=json.dumps(body).encode(),
            headers={"Content-Type":"application/json", "Authorization":"Bearer "+config["WORKER_TOKEN"]})
        try: response = urllib.request.urlopen(request, timeout=15)
        except urllib.error.HTTPError as error: response = error
        payload = response.read()
        return response.status, json.loads(payload) if payload else None

    def run_worker(command=None, expected=0):
        result = subprocess.run(command or worker_command, cwd=root, env=worker_env,
            stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=180)
        assert result.returncode == expected, result.stderr[-2000:]

    a, b = Browser(), Browser()
    try:
        for browser, name in zip([a,b],names):
            assert browser.call("/api/auth/signup", "POST", {"username":name,"password":password,"nickname":"채점 검증","inviteCode":config["INVITE_CODE"]})[0] == 201
            assert browser.call("/api/auth/login", "POST", {"username":name,"password":password}, form=True)[0] == 204
        for source in [correct, wrong]:
            digest = hashlib.sha256(source.encode()).hexdigest()
            sql("INSERT INTO execution_grant (user_id,source_sha256) SELECT id,'"+digest+"' FROM app_user WHERE username='"+names[0]+"'")
        public = a.call("/api/problems")[1]
        assert "tests" not in json.dumps(public) and "positive-boundary" not in json.dumps(public)
        key = str(uuid.uuid4())
        payload = {"problemVersion":"sum-v1","source":correct}
        status, first = a.call("/api/submissions", "POST", payload, key)
        assert status == 202, first
        job = first["id"]
        assert a.call("/api/submissions", "POST", payload, key)[1]["id"] == job
        assert a.call("/api/submissions", "POST", dict(payload,source=wrong), key)[0] == 409
        assert b.call("/api/submissions/"+job)[0] == 404
        assert b.call("/api/submissions")[1] == []
        assert sql("SELECT count(*) FROM submission s JOIN judge_job j ON j.submission_id=s.id WHERE s.id='"+job+"'") == "1"
        assert sql("SELECT source_sha256 FROM submission WHERE id='"+job+"'") == hashlib.sha256(correct.encode()).hexdigest()
        print("PASS: atomic immutable submission/job, idempotency replay/conflict, owner-only history and public problem projection")

        subprocess.run(compose+["restart","application"],check=True,stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL)
        for _ in range(90):
            try:
                if a.call("/api/submissions/"+job)[0] == 200: break
            except OSError: pass
            time.sleep(1)
        else: raise AssertionError("Submission not available after restart")
        assert a.call("/api/submissions/"+job)[1]["source"] == correct

        process = subprocess.Popen(worker_command,cwd=root,env=worker_env,stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        try:
            for _ in range(100):
                token = sql("SELECT coalesce(token::text,'') FROM judge_job WHERE submission_id='"+job+"'")
                if token:
                    active = subprocess.run(["docker","ps","-q","--filter","label=com.gamjaoj.attempt="+token],capture_output=True,text=True,check=True).stdout.strip()
                    if active: break
                time.sleep(.2)
            else: raise AssertionError("Runner did not start")
            process.kill(); process.wait(timeout=5)
        finally:
            if process.poll() is None: process.kill(); process.wait(timeout=5)
        run_worker()
        done = a.call("/api/submissions/"+job)[1]
        assert done["verdict"] == "AC", done
        assert sql("SELECT attempt FROM judge_job WHERE submission_id='"+job+"'") == "1"
        assert sql("SELECT count(*) FROM judge_attempt WHERE submission_id='"+job+"' AND status='COMPLETED'") == "1"
        print("PASS: app restart preserves queued code; killed Docker worker resumes same attempt and completes all real tests as AC")

        status, second = a.call("/api/submissions","POST",dict(payload,source=wrong),str(uuid.uuid4()))
        assert status == 202
        fault = '''import os,sys
from runner.worker import Api,Worker
class LostResponse(Api):
 def post(self,path,body):
  result=super().post(path,body)
  if path.endswith('/result'): raise OSError('simulated response loss after actual commit')
  return result
try: Worker(LostResponse(os.environ['GAMJAOJ_API_URL'],os.environ['WORKER_TOKEN']),sys.argv[1]).once()
except OSError: sys.exit(7)
'''
        run_worker([sys.executable,"-c",fault,str(state)],expected=7)
        assert a.call("/api/submissions/"+second["id"])[1]["verdict"] == "WA"
        count = len(list(state.glob("attempts/*/runs/*/result.json")))
        run_worker()
        assert len(list(state.glob("attempts/*/runs/*/result.json"))) == count
        assert not list((state/"pending").glob("*.json"))
        print("PASS: result committed before lost HTTP response; restart replays saved report without rerunning Java")

        status, third = a.call("/api/submissions","POST",payload,str(uuid.uuid4()))
        assert status == 202
        old = worker_api("/claim",{"workerId":str(uuid.uuid4())})[1]
        assert old["submissionId"] == third["id"]
        sql("UPDATE judge_job SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 minute' WHERE submission_id='"+third["id"]+"'")
        run_worker()
        report = json.loads(sql("SELECT result_json FROM judge_job WHERE submission_id='"+third["id"]+"'"))
        assert worker_api("/"+third["id"]+"/result",{"token":old["token"],"report":report})[0] == 409
        assert worker_api("/"+third["id"]+"/heartbeat",{"token":old["token"]})[0] == 409
        assert sql("SELECT attempt FROM judge_job WHERE submission_id='"+third["id"]+"'") == "2"
        assert a.call("/api/submissions/"+third["id"])[1]["verdict"] == "AC"
        assert sql("SELECT count(*) FROM judge_attempt WHERE submission_id='"+third["id"]+"' AND status='COMPLETED'") == "1"
        print("PASS: expired attempt fenced from heartbeat/result; real replacement attempt commits once")
    finally:
        selected = "('"+"','".join(names)+"')"
        sql("DELETE FROM spring_session WHERE principal_name IN "+selected)
        sql("DELETE FROM app_user WHERE username IN "+selected)


if __name__ == "__main__":
    main()
