"""Bounded production read-concurrency smoke. Seeds only an isolated synthetic account; no AI calls."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import http.cookiejar
import importlib.util
import json
from pathlib import Path
import secrets
import time
import urllib.parse
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not args.execute:
        parser.error('--execute required')
    spec = importlib.util.spec_from_file_location('probe', Path(__file__).with_name('probe-rule-onboarding.py'))
    probe = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(probe)
    base = probe.ssh("sed -n 's/^PUBLIC_BASE_URL=//p' ~/gamjaoj/web/.env").rstrip('/')
    invite = probe.ssh("sed -n 's/^INVITE_CODE=//p' ~/gamjaoj/web/.env")
    jar = http.cookiejar.CookieJar()
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(jar))
    def call(path, body=None, form=False):
        headers = {'User-Agent': 'GamjaOJ-Smoke/1.0'}
        data = None
        if body is not None:
            csrf = call('/api/auth/csrf')
            headers[csrf['headerName']] = csrf['token']
            headers['Content-Type'] = 'application/x-www-form-urlencoded' if form else 'application/json'
            data = (urllib.parse.urlencode(body) if form else json.dumps(body)).encode()
        with client.open(urllib.request.Request(base+path, data=data, headers=headers), timeout=15) as r:
            raw = r.read()
            return json.loads(raw) if raw else None
    name = 'probe_pool_' + secrets.token_hex(5)
    password = secrets.token_urlsafe(24)
    job, rule, analysis, followup, round_id = [str(uuid.uuid4()) for _ in range(5)]
    owner = f"(SELECT id FROM app_user WHERE username='{name}')"
    results = []
    try:
        call('/api/auth/signup', dict(username=name, password=password, nickname='연결 풀 검증', inviteCode=invite))
        call('/api/auth/login', dict(username=name, password=password), form=True)
        probe.sql(f"""BEGIN;
        INSERT INTO generation_job(id,owner_id,template_id,status,model,effort,focus)
          VALUES ('{job}',{owner},'sequence-sum-v1','NEEDS_REVIEW','fixture','medium','basics');
        INSERT INTO hybrid_rule_onboarding(id,owner_id,request_json,request_sha256,status,budget_usd,created_at,updated_at,deadline_at)
          VALUES ('{rule}',{owner},'{{"request":"pool smoke","difficulty":"HARD"}}','{'0'*64}','HELD',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
        INSERT INTO ai_task(id,user_id,kind,cache_key,settings_json,input_json,status,result_json)
          VALUES ('{analysis}',{owner},'ANALYSIS','{secrets.token_hex(32)}','{{}}','{{}}','COMPLETED','{{}}');
        INSERT INTO practice_followup(id,user_id,analysis_id,step_index,goal,focus,source_version,round_id)
          VALUES ('{followup}',{owner},'{analysis}',0,'pool smoke','basics','total-v1','{round_id}');
        COMMIT;""")
        # Frozen authenticated cookie; each worker has an independent HTTP client.
        cookie = '; '.join(c.name+'='+c.value for c in jar)
        def read(path):
            start = time.monotonic()
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            request = urllib.request.Request(base+path, headers={'Cookie': cookie, 'User-Agent': 'GamjaOJ-Smoke/1.0'})
            with opener.open(request, timeout=15) as response:
                value = json.load(response)
            expected = {'/api/generation': job, '/api/rules/onboarding': rule, '/api/practice-followups': followup}
            if path in expected:
                assert any(v['id'] == expected[path] for v in value), path
            return {'path': path, 'seconds': round(time.monotonic()-start, 3)}
        for path in ['/api/generation', '/api/rules/onboarding', '/api/practice-followups']:
            with ThreadPoolExecutor(max_workers=5) as pool:
                results.extend(pool.map(read, [path]*15))
        with ThreadPoolExecutor(max_workers=6) as pool:
            results.extend(pool.map(read, ['/api/generation','/api/rules/onboarding','/api/practice-followups','/api/problems']*3))
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(results, indent=2))
        print(json.dumps({'passedRequests':len(results),'maxSeconds':max(r['seconds'] for r in results)}))
    finally:
        probe.sql(f"BEGIN; DELETE FROM generation_job WHERE owner_id IN {owner}; DELETE FROM spring_session WHERE principal_name='{name}'; DELETE FROM app_user WHERE username='{name}'; COMMIT;")
        print('Removed this probe account and its fixtures; no AI work requested.')


if __name__ == '__main__':
    main()
