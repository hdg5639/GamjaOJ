"""Live rule-onboarding probe: real model calls and Runner work under a synthetic account.

Registers each request sequentially, optionally generates one problem from every qualified rule, records
outcome/timing/cost evidence under --output, and always deletes the synthetic account and its data.
"""
import argparse
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

REQUESTS = [
    "무방향 그래프에서 연결 요소의 개수를 구하는 문제. 정점 N과 간선 M은 최대 10만이다.",
    "N개의 물건(무게, 가치)과 가방 용량 W가 주어질 때 각 물건을 최대 한 번 넣어 얻는 최대 가치를 구하는 0/1 배낭 문제. N은 최대 100, W는 최대 10만이다.",
    "오름차순 정렬된 정수 배열과 Q개의 질의 x가 주어질 때 각 x 이상인 첫 원소의 1부터 시작하는 위치를 출력하고 없으면 N+1을 출력하는 문제. N과 Q는 최대 20만이다.",
    "여는 괄호와 닫는 괄호로만 이루어진 길이 최대 100만의 문자열이 올바른 괄호 문자열인지 판별하는 문제.",
]
TERMINAL = {"ACTIVE", "HELD", "FAILED", "CANCELLED", "DEADLINE_EXCEEDED"}


def ssh(command, data=None):
    result = subprocess.run(['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', os.environ['GAMJAOJ_APP_SSH_TARGET'], command],
                            input=data, text=True, capture_output=True, timeout=60)
    if result.returncode:
        raise RuntimeError('SSH operation failed: ' + result.stderr[-300:])
    return result.stdout.strip()


def sql(query):
    return ssh('docker exec -i gamjaoj-postgres-1 psql -U gamjaoj -d gamjaoj -At -v ON_ERROR_STOP=1', query)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--generate', action='store_true', help='Also generate one problem from each qualified rule')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--only', type=int, nargs='*', help='Request indexes to run')
    args = parser.parse_args()
    if not args.execute:
        parser.error('--execute is required: this spends API budget and Runner time')
    base = ssh("sed -n 's/^PUBLIC_BASE_URL=//p' ~/gamjaoj/web/.env").rstrip('/')
    invite = ssh("sed -n 's/^INVITE_CODE=//p' ~/gamjaoj/web/.env")
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    client.addheaders = [('User-Agent', 'GamjaOJ-Smoke/1.0')]

    def call(path, method='GET', body=None, key=None, form=False):
        headers = {}
        if method != 'GET':
            csrf = call('/api/auth/csrf')
            headers[csrf['headerName']] = csrf['token']
        if key:
            headers['Idempotency-Key'] = key
        data = None
        if body is not None:
            if form:
                data = urllib.parse.urlencode(body).encode()
                headers['Content-Type'] = 'application/x-www-form-urlencoded'
            else:
                data = json.dumps(body).encode()
                headers['Content-Type'] = 'application/json'
        request = urllib.request.Request(base + path, data=data, headers=headers, method=method)
        try:
            with client.open(request, timeout=30) as response:
                raw = response.read()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            raise RuntimeError(f'{method} {path} -> {error.code} {error.read()[:300]!r}')

    username = 'probe_' + secrets.token_hex(5)
    password = secrets.token_urlsafe(24)
    results = []
    try:
        call('/api/auth/signup', 'POST', dict(username=username, password=password, nickname='규칙 등록 측정', inviteCode=invite))
        call('/api/auth/login', 'POST', dict(username=username, password=password), form=True)
        indexes = args.only if args.only else range(len(REQUESTS))
        for index in indexes:
            request = REQUESTS[index]
            started = time.monotonic()
            onboarding = call('/api/rules/onboarding', 'POST', {'request': request}, str(uuid.uuid4()))
            while onboarding['status'] not in TERMINAL and time.monotonic() - started < 25 * 60:
                time.sleep(10)
                onboarding = next(v for v in call('/api/rules/onboarding') if v['id'] == onboarding['id'])
            record = {'index': index, 'request': request, 'status': onboarding['status'], 'error': onboarding['error'],
                      'label': onboarding['label'], 'versionId': onboarding['versionId'], 'spentUsd': onboarding['spentUsd'],
                      'seconds': round(time.monotonic() - started, 1), 'checks': onboarding['checks']}
            print(json.dumps({k: record[k] for k in ('index', 'status', 'error', 'label', 'spentUsd', 'seconds')}, ensure_ascii=False), flush=True)
            if onboarding['status'] == 'ACTIVE':
                timing = sql("SELECT json_agg(json_build_object('role',e.role,'verdict',j.verdict,'generated',(SELECT json_agg(json_build_object('wall',t->>'wall_ms','bytes',t->>'input_bytes','verdict',t->>'verdict')) FROM json_array_elements(j.result_json::json->'tests') t WHERE t->>'kind'='generated'))) "
                             "FROM hybrid_execution_check e JOIN hybrid_branch b ON b.id=e.branch_id JOIN judge_job j ON j.submission_id=e.submission_id "
                             f"JOIN hybrid_rule_onboarding o ON o.carrier_generation_id=b.generation_id WHERE o.id='{uuid.UUID(onboarding['id'])}' AND e.role IN ('q-slow','q-large-reference-0','q-large-valid')")
                record['largeEvidence'] = json.loads(timing) if timing else None
            if onboarding['status'] == 'ACTIVE' and args.generate:
                started = time.monotonic()
                key = str(uuid.uuid4())
                job = call('/api/generation/hybrid', 'POST', {'profileId': onboarding['versionId'], 'shared': False, 'publishOnSuccess': True}, key)
                active = {'QUEUED', 'DESIGNING', 'BUILDING', 'VALIDATING', 'REVIEWING'}
                while (job['status'] in active or (job['status'] == 'HELD' and job['error'] in ('VALIDATION_ADAPTER_NOT_CONNECTED', 'CONTENT_REVIEW_REQUIRED'))) and time.monotonic() - started < 300:
                    time.sleep(5)
                    job = call('/api/generation/hybrid/' + job['id'])
                record['generation'] = {'status': job['status'], 'error': job['error'], 'seconds': round(time.monotonic() - started, 1)}
                if job['status'] == 'HELD' and job['error'] == 'CONTENT_REVIEW_REJECTED':
                    issues = sql("SELECT coalesce(b.completion_json::json->'payload'->>'issues','') FROM hybrid_branch b WHERE b.generation_id='"
                                 + str(uuid.UUID(job['id'])) + "' AND b.role='CONTENT_REVIEW'")
                    record['generation']['issues'] = issues[:2000]
                print(json.dumps({'index': index, 'generation': record['generation']}, ensure_ascii=False), flush=True)
            if onboarding['status'] != 'ACTIVE':
                author = sql(f"SELECT coalesce(left(author_json,6000),'') FROM hybrid_rule_onboarding WHERE id='{uuid.UUID(onboarding['id'])}'")
                record['authorExcerpt'] = author
            results.append(record)
    finally:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(results, ensure_ascii=False, indent=2))
        owner = f"(SELECT id FROM app_user WHERE username='{username}')"
        sql('BEGIN; '
            f'DELETE FROM hybrid_generation WHERE owner_id IN {owner}; '
            f'DELETE FROM submission WHERE user_id IN {owner}; '
            f'DELETE FROM problem_version WHERE owner_id IN {owner}; '
            f"DELETE FROM spring_session WHERE principal_name='{username}'; "
            f"DELETE FROM app_user WHERE username='{username}'; COMMIT;")
        print('cleanup: synthetic account and its rules, problems and checks removed', flush=True)


if __name__ == '__main__':
    main()
