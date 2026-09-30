"""Verify Java callable HTTP admission and dedicated Runner with private, disposable fixtures.

Run CallableProgramsTest first to export the deterministic bundles, then load .env.ops.
No model call or public problem is created. Deletes only its own account/problems.
"""
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


def ssh(command, data=None):
    return subprocess.run(['ssh', '-o', 'BatchMode=yes', os.environ['GAMJAOJ_APP_SSH_TARGET'], command],
                          input=data, text=True, capture_output=True, check=True, timeout=45).stdout.strip()


def sql(statement):
    return ssh('docker exec -i gamjaoj-postgres-1 psql -U gamjaoj -d gamjaoj -At -v ON_ERROR_STOP=1', statement)


def literal(value):
    return "'" + value.replace("'", "''") + "'"


def main():
    fixture = Path('backend/target/callable-fixtures')
    multi = json.loads((fixture/'api.json').read_text())
    single = json.loads((fixture/'single-api.json').read_text())
    base = (os.environ.get('GAMJAOJ_BASE_URL') or ssh("sed -n 's/^PUBLIC_BASE_URL=//p' ~/gamjaoj/web/.env")).rstrip('/')
    invitation = ssh("sed -n 's/^INVITE_CODE=//p' ~/gamjaoj/web/.env")
    client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    client.addheaders = [('User-Agent', 'GamjaOJ-Smoke/1.0')]

    def call(path, method='GET', body=None, key=None, form=False):
        headers = {}
        if method != 'GET':
            _, csrf = call('/api/auth/csrf')
            headers[csrf['headerName']] = csrf['token']
        if key: headers['Idempotency-Key'] = key
        if body is not None:
            headers['Content-Type'] = 'application/x-www-form-urlencoded' if form else 'application/json'
            body = (urllib.parse.urlencode(body) if form else json.dumps(body)).encode()
        try:
            response = client.open(urllib.request.Request(base+path, data=body, headers=headers, method=method), timeout=20)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None

    username, password = 'callable_'+secrets.token_hex(5), secrets.token_urlsafe(24)
    versions, evidence = [], []
    contract = json.loads(Path('backend/src/main/resources/runner-execution-contract.json').read_text())
    cases = [[["init", c], *[["add", j] for j in range(150)], ["query"], ["init", 0], ["query"]] for c in range(25)]
    multi_output = ''.join(str(c+sum(range(150)))+'\n0\n' for c in range(25))
    inputs = [(multi, (fixture/'UserSolution.java').read_text(), json.dumps(cases), multi_output),
              (single, (fixture/'SingleSolution.java').read_text(), '[[["solution",[7],["a b\\n한글"],true]],[["solution",[-2],[""],false]]]', '["a\\u0020b\\u000a한글","7","true"]\n["","-2","false"]\n')]
    try:
        assert call('/api/auth/signup', 'POST', dict(username=username, password=password, nickname='API 검증', inviteCode=invitation))[0] == 201
        assert call('/api/auth/login', 'POST', dict(username=username, password=password), form=True)[0] == 204
        for bundle, source, stdin, expected in inputs:
            version = 'callable-smoke-'+str(uuid.uuid4())
            package = dict(version=version, title='비공개 API 검증', statement='검증용 임시 문제', output_policy='TOKEN_EXACT',
                           tests=[dict(id='all-cases', input=stdin, output=expected)], api=bundle)
            encoded = json.dumps(package, ensure_ascii=False, separators=(',', ':'))
            sql(f"INSERT INTO problem_version(id,package_json,package_sha256,runtime_image,runner_policy,owner_id) SELECT {literal(version)},{literal(encoded)},{literal(hashlib.sha256(encoded.encode()).hexdigest())},p.runtime_image,p.runner_policy,u.id FROM problem_version p CROSS JOIN app_user u WHERE p.id='total-v1' AND u.username={literal(username)}")
            versions.append(version)
            public = next(p for p in call('/api/problems')[1] if p['version']==version)
            assert public['api']==bundle and [l['id'] for l in public['languages']]==['JAVA']
            assert public['submissionsEnabled'] and not public['shared']
            assert 'tests' not in public and 'package' not in public
            bad = dict(problemVersion=version, source=source, language='CPP')
            assert call('/api/submissions', 'POST', bad, str(uuid.uuid4()))[0] == 400
            wrong = source.replace('return total;', 'return total+1;') if bundle['api']['mode']=='MULTI_API' else source.replace('labels[0]', '"wrong"')
            for endpoint, expected_verdict, code in [('/api/submissions','AC',source),('/api/submissions','WA',wrong),('/api/submissions','CE',source+'\nclass Main {}'),('/api/runs','OK',source)]:
                payload = dict(problemVersion=version, source=code, language='JAVA')
                # Run-only input has a separate 16 KiB admission bound; formal input exercises all 25 cases.
                if endpoint=='/api/runs': payload['input'] = '[[["init",2],["add",3],["query"]]]' if bundle['api']['mode']=='MULTI_API' else stdin
                key = str(uuid.uuid4())
                status, saved = call(endpoint, 'POST', payload, key)
                assert status==202, saved
                job = str(uuid.UUID(saved['id']))
                assert call(endpoint, 'POST', payload, key)[1]['id']==job
                deadline = time.monotonic()+90
                while time.monotonic()<deadline:
                    status, done = call(endpoint+'/'+job)
                    if done['status']=='FINISHED': break
                    time.sleep(1)
                else: raise AssertionError('Runner did not finish')
                assert done['verdict']==expected_verdict, done
                assert done['source']==code
                if endpoint=='/api/runs': assert done['stdout']==('5\n' if bundle['api']['mode']=='MULTI_API' else expected), done
                record = json.loads(sql(f"SELECT json_build_object('expected',a.execution_environment_json::json,'result',j.result_json::json,'worker',j.worker_id,'completed',(SELECT count(*) FROM judge_attempt WHERE submission_id=j.submission_id AND status='COMPLETED')) FROM judge_job j JOIN judge_attempt a ON a.submission_id=j.submission_id AND a.attempt=j.attempt WHERE j.submission_id='{job}'"))
                assert record['expected']==contract and record['result']['runner_environment']['contract']==contract
                assert record['result']['runner_environment']['dockerControl']=='engine'
                assert record['completed']==1 and record['worker']
                evidence.append(dict(mode=bundle['api']['mode'],verdict=expected_verdict,submissionId=job,**record))
                print('PASS:', bundle['api']['mode'], expected_verdict, 'HTTP -> persistent Runner; replay, environment, source verified', flush=True)
        Path('.state').mkdir(exist_ok=True)
        Path('.state/callable-live.json').write_text(json.dumps(evidence, ensure_ascii=False, indent=2))
    finally:
        sql(f"DELETE FROM spring_session WHERE principal_name={literal(username)}; DELETE FROM submission WHERE user_id=(SELECT id FROM app_user WHERE username={literal(username)});")
        for version in versions: sql(f"DELETE FROM problem_version WHERE id={literal(version)};")
        sql(f"DELETE FROM app_user WHERE username={literal(username)};")


if __name__=='__main__':
    main()
