"""Explicit live generation latency probe; private fixtures, real model and Runner.

Retains timing/usage evidence under .state, without source, credentials or input data.
Existing generation settings and validation gates are unchanged.
"""
import os
import argparse
import datetime
import http.cookiejar
import json
from pathlib import Path
import secrets
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def ssh(command, data=None):
    result = subprocess.run(['ssh', '-o', 'BatchMode=yes', '-o', 'ControlPath=none',
                             '-o', 'ConnectTimeout=10', '-o', 'ServerAliveInterval=10',
                             '-o', 'ServerAliveCountMax=2', os.environ['GAMJAOJ_APP_SSH_TARGET'], command],
                            input=data, text=True, capture_output=True, timeout=50)
    if result.returncode:
        raise RuntimeError('SSH operation failed: ' + result.stderr[-500:])
    return result.stdout.strip()


def sql(query):
    return ssh('docker exec -i gamjaoj-postgres-1 psql -U gamjaoj -d gamjaoj -At -v ON_ERROR_STOP=1', query)


def query_json(query):
    return json.loads(sql(query))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--mode', choices=['tags', 'free'], required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not args.execute:
        parser.error('--execute is required for real model calls')
    base = ssh("sed -n 's/^PUBLIC_BASE_URL=//p' ~/gamjaoj/web/.env")
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
        if body is not None:
            headers['Content-Type'] = 'application/x-www-form-urlencoded' if form else 'application/json'
            body = (urllib.parse.urlencode(body) if form else json.dumps(body)).encode()
        try:
            with client.open(urllib.request.Request(base + path, data=body, headers=headers, method=method), timeout=30) as response:
                raw = response.read()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            raise RuntimeError(f'HTTP {error.code} at {path}') from None

    username = 'timing_' + secrets.token_hex(5)
    owner = "SELECT id FROM app_user WHERE username='" + username + "'"
    evidence = dict(mode=args.mode, startedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                    fixtureUsername=username, pollSeconds=1, runs=[], cleaned=False)

    def save():
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n')

    def runner_rows(key, free=False):
        table, column = ('generation_spec_execution', 'draft_id') if free else ('generation_execution', 'job_id')
        revision = 'NULL' if free else 'e.revision'
        return query_json(f"SELECT coalesce(json_agg(x),'[]') FROM (SELECT {revision} AS revision,e.expected_verdict,e.role,j.submission_id,j.created_at,j.finished_at,"
                          "j.result_json::jsonb->>'verdict' AS verdict,j.result_json::jsonb#>>'{compile,cache_hit}' AS cache_hit,"
                          "jsonb_array_length(j.result_json::jsonb->'tests') AS tests,"
                          "(SELECT json_agg(json_build_object('started_at',a.started_at,'finished_at',a.finished_at,'status',a.status)) "
                          "FROM judge_attempt a WHERE a.submission_id=e.submission_id) AS attempts "
                          f"FROM {table} e JOIN judge_job j ON j.submission_id=e.submission_id WHERE e.{column}='{key}' "
                          "ORDER BY j.created_at,j.submission_id) x")

    def snapshot(key, free=False):
        if free:
            return query_json("SELECT json_build_object('createdAt',created_at,'updatedAt',updated_at,'status',status,"
                              "'model',model,'effort',effort,'draftUsage',completion_json::jsonb->'usage',"
                              "'buildUsage',build_completion_json::jsonb->'usage','reviewUsage',review_completion_json::jsonb->'usage',"
                              "'finalUsage',final_completion_json::jsonb->'usage') "
                              f"FROM generation_spec_draft WHERE id='{key}'")
        return query_json("SELECT json_build_object('createdAt',g.created_at,'updatedAt',g.updated_at,'status',g.status,"
                          "'model',g.model,'effort',g.effort,'revision',g.revision,'template',g.template_id,"
                          "'reuse',g.structure_reuse_json::jsonb->>'sourceJobId',"
                          "'attempts',(SELECT json_agg(json_build_object('revision',a.revision,'createdAt',a.created_at,"
                          "'usage',a.result_json::jsonb->'usage')) FROM generation_attempt a WHERE a.job_id=g.id),"
                          "'theme',(SELECT json_agg(json_build_object('startedAt',a.started_at,'finishedAt',a.finished_at,"
                          "'actualUsd',a.actual_usd,'usage',a.usage_json::jsonb)) FROM ai_attempt a WHERE a.task_id=g.theme_task_id)) "
                          f"FROM generation_job g WHERE g.id='{key}'")

    def wait(key, run, free=False):
        deadline = time.monotonic() + 1800
        terminal = {'READY', 'FAILED', 'NEEDS_AUTH', 'NEEDS_REVIEW', 'THEME_FAILED', 'DRAFT_READY',
                    'CHECKED', 'REVIEW_CHECKED', 'PUBLISHED', 'REJECTED', 'BUILD_FAILED', 'REVIEW_REJECTED',
                    'FINAL_REJECTED', 'FINAL_FAILED', 'REVIEW_FAILED'}
        while time.monotonic() < deadline:
            value = call('/api/generation/spec-drafts/' + key) if free else next(j for j in call('/api/generation') if j['id'] == key)
            state = value['status']
            if not run['events'] or run['events'][-1]['status'] != state:
                run['events'].append(dict(status=state, observedAt=datetime.datetime.now(datetime.timezone.utc).isoformat()))
                save()
                print(run['kind'], state, flush=True)
            if state in terminal:
                return value
            if value.get('theme', {}).get('status', '') in ('HELD_BUDGET', 'HELD_DISABLED'):
                raise RuntimeError('Theme held; no settings changed')
            time.sleep(1)
        raise TimeoutError('Generation exceeded 30 minutes; fixture retained')

    try:
        password = secrets.token_urlsafe(24)
        call('/api/auth/signup', 'POST', dict(username=username, password=password, nickname='시간 측정', inviteCode=invite))
        call('/api/auth/login', 'POST', dict(username=username, password=password), form=True)
        count = 2 if args.mode == 'tags' else 1
        for index in range(count):
            free = args.mode == 'free'
            key = str(uuid.uuid4())
            run = dict(id=key, kind='free' if free else 'fresh' if index == 0 else 'reuse', events=[])
            evidence['runs'].append(run)
            request = (dict(request='동적 계획법으로 푸는 0/1 선택 문제를 만들어 주세요. 같은 항목을 중복 선택하는 습관이면 틀리는 문제이고, 창고가 아닌 탐사 장비 테마를 원합니다. 작은 N에서는 모든 부분집합을 열거하여 독립 검증할 수 있도록 해 주세요.', shared=False)
                       if free else dict(category='graphs', tags=['directed', 'max-distance', 'edge-cases'], shared=False))
            run['request'] = request
            started = time.monotonic()
            run['requestStartedAt'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
            path = '/api/generation/spec-drafts' if free else '/api/generation'
            call(path, 'POST', request, key=key)
            value = wait(key, run, free)
            if free:
                run['stages'] = []
                for action, expected in [('build', 'DRAFT_READY'), ('review', 'CHECKED'), ('publish', 'REVIEW_CHECKED')]:
                    run['stages'].append(snapshot(key, True))
                    save()
                    if value['status'] != expected:
                        break
                    call(path + '/' + key + '/' + action, 'POST', {'specHash': value['specHash']})
                    value = wait(key, run, True)
            run['clientSeconds'] = round(time.monotonic() - started, 3)
            run['db'] = snapshot(key, free)
            run['runner'] = runner_rows(key, free)
            run['status'] = value['status']
            run['error'] = value.get('error')
            run['structure'] = value.get('preview', {}).get('structure')
            run['validation'] = {k: value.get('validation', {}).get(k) for k in ('executions', 'policy')} if value.get('validation') else None
            save()
            print(run['kind'], 'client seconds', run['clientSeconds'], flush=True)
            if value['status'] != ('PUBLISHED' if free else 'READY'):
                raise RuntimeError('Probe reached ' + value['status'] + ': ' + str(value.get('error')))
            if not free and bool(run['db']['reuse']) != (index == 1):
                raise RuntimeError('Unexpected reuse selection')
            versions = call('/api/problems')
            run['playable'] = any(p['version'] == value['problemVersion'] and p.get('submissionsEnabled', True) for p in versions)
            if not run['playable']:
                raise RuntimeError('Published problem is not playable')
            save()
    finally:
        active = sql("SELECT (SELECT count(*) FROM generation_job WHERE owner_id IN (" + owner + ") AND status IN ('QUEUED','GENERATING','VALIDATING','AWAITING_REVIEW')) + "
                     "(SELECT count(*) FROM generation_spec_draft WHERE owner_id IN (" + owner + ") AND status IN ('QUEUED','GENERATING','BUILD_QUEUED','BUILD_GENERATING','CHECKING','REVIEW_QUEUED','REVIEW_GENERATING','REVIEW_CHECKING','FINAL_QUEUED','FINAL_GENERATING','FINAL_CHECKING')) + "
                     "(SELECT count(*) FROM judge_job j JOIN submission s ON s.id=j.submission_id WHERE s.user_id IN (" + owner + ") AND j.status<>'FINISHED')")
        if active == '0':
            jobs = 'SELECT id FROM generation_job WHERE owner_id IN (' + owner + ')'
            drafts = 'SELECT id FROM generation_spec_draft WHERE owner_id IN (' + owner + ')'
            sql('BEGIN; DELETE FROM generation_execution WHERE job_id IN (' + jobs + '); '
                'DELETE FROM generation_spec_execution WHERE draft_id IN (' + drafts + '); '
                'DELETE FROM submission WHERE user_id IN (' + owner + '); '
                'DELETE FROM generation_attempt WHERE job_id IN (' + jobs + '); '
                'DELETE FROM generation_job WHERE owner_id IN (' + owner + '); '
                'DELETE FROM problem_version WHERE owner_id IN (' + owner + '); '
                'DELETE FROM generation_spec_draft WHERE owner_id IN (' + owner + '); '
                "DELETE FROM spring_session WHERE principal_name='" + username + "'; "
                "DELETE FROM app_user WHERE username='" + username + "'; COMMIT;")
            evidence['cleaned'] = True
        save()
        print('Fixture cleaned:', evidence['cleaned'], flush=True)


if __name__ == '__main__':
    main()
