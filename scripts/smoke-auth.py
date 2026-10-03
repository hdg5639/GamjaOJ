"""Exercise the deployed HTTP/DB contract; remove only this run's synthetic accounts."""
import argparse
import http.cookiejar
import json
from pathlib import Path
import secrets
import socket
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--compose", type=Path, required=True)
    parser.add_argument("--ipv4", action="store_true", help="Use IPv4 for probes on hosts without working IPv6 egress")
    args = parser.parse_args()
    if args.ipv4:
        resolve = socket.getaddrinfo
        def ipv4_address(host, port, family=0, type=0, proto=0, flags=0):
            return resolve(host, port, socket.AF_INET, type, proto, flags)
        socket.getaddrinfo = ipv4_address
    config = dict(line.split("=", 1) for line in args.env_file.read_text().splitlines()
                  if line and not line.startswith("#"))
    base = config.get("PUBLIC_BASE_URL", "http://" + config["BIND_ADDRESS"] + ":" + config["HTTP_PORT"]).rstrip("/")
    compose = ["docker", "compose", "--env-file", str(args.env_file), "-f", str(args.compose)]
    names = ["probe_" + secrets.token_hex(5) for _ in range(2)]
    password = secrets.token_urlsafe(24)

    def sql(statement):
        return subprocess.run(compose + ["exec", "-T", "postgres", "psql", "-v", "ON_ERROR_STOP=1",
            "-U", "gamjaoj", "-d", "gamjaoj", "-At", "-c", statement],
            check=True, capture_output=True, text=True, stdin=subprocess.DEVNULL).stdout.strip()

    class Browser:
        def __init__(self):
            self.jar = http.cookiejar.CookieJar()
            self.client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))
            self.client.addheaders = [("User-Agent", "GamjaOJ-Smoke/1.0")]

        def call(self, path, method="GET", data=None, csrf=True, form=False, key=None):
            headers = {}
            if key is not None:
                headers["Idempotency-Key"] = str(key)
            if method != "GET" and csrf:
                status, token, _ = self.call("/api/auth/csrf")
                assert status == 200
                headers[token["headerName"]] = token["token"]
            if data is not None:
                headers["Content-Type"] = "application/x-www-form-urlencoded" if form else "application/json"
                data = (urllib.parse.urlencode(data) if form else json.dumps(data)).encode()
            request = urllib.request.Request(base + path, data=data, headers=headers, method=method)
            try:
                response = self.client.open(request, timeout=15)
            except urllib.error.HTTPError as error:
                response = error
            body = response.read()
            return response.status, json.loads(body) if body else None, response.headers

    a, b = Browser(), Browser()
    try:
        assert a.call("/api/me")[0] == 401
        assert a.call("/api/auth/login", "POST", {}, csrf=False, form=True)[0] == 403
        for browser, name in zip([a, b], names):
            assert browser.call("/api/auth/signup", "POST", {
                "username": name, "password": password, "nickname": "검증 계정",
                "inviteCode": config["INVITE_CODE"]})[0] == 201
            assert browser.call("/api/auth/login", "POST", {
                "username": name, "password": "wrong-password"}, form=True)[0] == 401
            assert browser.call("/api/auth/login", "POST", {
                "username": name, "password": password}, form=True)[0] == 204
        first, second = a.call("/api/me")[1], b.call("/api/me")[1]
        assert first["id"] != second["id"]
        assert a.call("/api/me", "PATCH", {"nickname": "검증 A", "trainingGoal": "DFS 복원"})[0] == 200
        assert b.call("/api/me")[1]["trainingGoal"] == ""
        assert sql("SELECT count(*) FROM app_user WHERE username IN ('" + "','".join(names)
                   + "') AND password_hash LIKE '$2a$12$%'") == "2"
        assert sql("SELECT count(*) FROM spring_session WHERE principal_name IN ('" + "','".join(names) + "')") == "2"
        print("PASS: signup/login, CSRF, BCrypt, independent user IDs and stored preferences/sessions")
        training_id = str(uuid.uuid4())
        start = {"problemVersion":"sum-v1", "goal":"재시작 후 훈련 이어가기"}
        assert a.call("/api/training-sessions", "POST", start, key=training_id)[0] == 200
        assert b.call("/api/training-sessions/"+training_id)[0] == 404
        status, catalog, _ = a.call("/api/training-courses")
        assert status == 200 and len(catalog) == 8
        ready_course = next((c for c in catalog if c["available"] == len(c["steps"])), None) if config.get("SUBMISSIONS_ENABLED", "false").lower() == "true" else None
        enrolled_course = None
        if ready_course:
            enroll_key = str(uuid.uuid4())
            enrollment = {"courseId": ready_course["course"]["id"], "revision": ready_course["course"]["revision"]}
            assert a.call("/api/training-courses/enrollments", "POST", enrollment, csrf=False, key=enroll_key)[0] == 403
            status, enrolled_course, _ = a.call("/api/training-courses/enrollments", "POST", enrollment, key=enroll_key)
            assert status == 200
            assert a.call("/api/training-courses/enrollments", "POST", enrollment, key=enroll_key)[1]["enrollmentId"] == enrolled_course["enrollmentId"]
            assert b.call("/api/training-courses/enrollments")[1] == []
            assert a.call("/api/training-courses/enrollments/" + enrolled_course["enrollmentId"] + "/start", "POST",
                          {"position": 0, "activeSessionId": None, "note": ""}, key=uuid.uuid4())[0] == 409
        subprocess.run(compose + ["restart", "application"], check=True,
                       stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL)
        for attempt in range(90):
            try:
                with urllib.request.urlopen(urllib.request.Request(base + "/healthz",
                        headers={"User-Agent": "GamjaOJ-Smoke/1.0"}), timeout=2) as response:
                    if response.status == 200:
                        break
            except (OSError, urllib.error.URLError):
                pass
            time.sleep(1)
        else:
            raise AssertionError("Application did not recover after restart")
        assert a.call("/api/me")[1]["id"] == first["id"]
        assert a.call("/api/me")[1]["trainingGoal"] == "DFS 복원"
        assert b.call("/api/me")[1]["id"] == second["id"]
        print("PASS: application restart preserves login and account-specific preferences")
        restored = a.call("/api/training-sessions/"+training_id)[1]["session"]
        assert restored["id"] == training_id and restored["status"] == "ACTIVE" and restored["goal"] == start["goal"]
        assert a.call("/api/training-sessions", "POST", start, key=training_id)[1]["id"] == training_id
        assert a.call("/api/training-sessions/"+training_id+"/end", "POST", {"note":"재시작 검증 완료"})[1]["status"] == "ENDED"
        print("PASS: training session and goal survive app restart, start replay is idempotent, owner can finish")
        if enrolled_course:
            restored_courses = a.call("/api/training-courses/enrollments")[1]
            assert restored_courses[0]["enrollmentId"] == enrolled_course["enrollmentId"]
            assert restored_courses[0]["course"] == enrolled_course["course"]
            course_path = "/api/training-courses/enrollments/" + enrolled_course["enrollmentId"] + "/start"
            start_key = str(uuid.uuid4())
            first_step = {"position": 0, "activeSessionId": None, "note": ""}
            assert b.call(course_path, "POST", first_step, key=uuid.uuid4())[0] == 404
            status, course_session, _ = a.call(course_path, "POST", first_step, key=start_key)
            assert status == 200 and course_session["status"] == "ACTIVE"
            assert course_session["problemVersion"] == enrolled_course["steps"][0]["version"]
            assert a.call(course_path, "POST", first_step, key=start_key)[1]["id"] == start_key
            switch_key = str(uuid.uuid4())
            next_step = {"position": 1, "activeSessionId": start_key, "note": "코스 단계 전환 검증"}
            status, switched, _ = a.call(course_path, "POST", next_step, key=switch_key)
            assert status == 200 and switched["id"] == switch_key
            previous = a.call("/api/training-sessions/" + start_key)[1]["session"]
            assert previous["status"] == "ENDED" and previous["note"] == next_step["note"]
            assert a.call(course_path, "POST", next_step, key=switch_key)[1]["id"] == switch_key
            assert a.call(course_path, "POST", {**next_step, "note": "다른 내용"}, key=switch_key)[0] == 409
            assert a.call(course_path, "POST", first_step, key=uuid.uuid4())[0] == 409
            assert a.call("/api/training-sessions/" + switch_key + "/end", "POST", {"note": "코스 검증 완료"})[0] == 200
            assert a.call(course_path, "POST", next_step, key=switch_key)[1]["status"] == "ENDED"
            assert len(a.call("/api/training-sessions")[1]) == 3
            assert a.call("/api/training-courses/enrollments")[1][0]["steps"][1]["sessionId"] == switch_key
            assert sql("SELECT count(*) FROM ai_task WHERE user_id='" + first["id"] + "'") == "0"
            print("PASS: curated course snapshot survives restart; owned real-problem training, atomic switch, stale fence and ended replay")
        else:
            print("SKIP: curated course execution unavailable or disabled; catalog contract only")
        old_cookie = next(c for c in a.jar if c.name == "GAMJAOJ_SESSION").value
        assert a.call("/api/auth/logout", "POST")[0] == 204
        assert a.call("/api/me")[0] == 401
        replay = urllib.request.Request(base + "/api/me", headers={"Cookie": "GAMJAOJ_SESSION=" + old_cookie,
                                                                 "User-Agent": "GamjaOJ-Smoke/1.0"})
        try:
            urllib.request.urlopen(replay, timeout=5)
            raise AssertionError("Logged-out session was replayed")
        except urllib.error.HTTPError as error:
            assert error.code == 401
        assert b.call("/api/me")[0] == 200
        assert sql("SELECT count(*) FROM spring_session WHERE principal_name = '" + names[0] + "'") == "0"
        assert b.call("/api/auth/logout", "POST")[0] == 204
        print("PASS: logout revokes stored session, replay denied, other account unaffected")
    finally:
        selected = "('" + "','".join(names) + "')"
        sql("DELETE FROM spring_session WHERE principal_name IN " + selected)
        sql("DELETE FROM app_user WHERE username IN " + selected)


if __name__ == "__main__":
    main()
