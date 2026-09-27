"""Synthetic DB fixtures -> deployed queue -> real Runner. No model calls.

Proves scheduler/worker behavior, not model quality or generation admission.
Never stops the live worker or changes existing submissions.
"""
import os
import hashlib
import json
from pathlib import Path
import subprocess
import time
import uuid


def ssh(host, command, data=None):
    return subprocess.run(['ssh','-o','BatchMode=yes',host,command],input=data,text=True,
                          capture_output=True,check=True,timeout=30).stdout.strip()


def sql(statement):
    return ssh(os.environ['GAMJAOJ_APP_SSH_TARGET'],'docker exec -i gamjaoj-postgres-1 psql -U gamjaoj -d gamjaoj -At -v ON_ERROR_STOP=1',statement)


def quote(value):
    return "'" + str(value).replace("'", "''") + "'"


def main():
    # Expired generation checks can never be claimed again; everything else must be idle.
    assert sql("""SELECT count(*) FROM judge_job j JOIN submission s ON s.id=j.submission_id WHERE j.status<>'FINISHED' AND (j.status='RUNNING' OR s.hybrid_branch_id IS NULL
OR EXISTS (SELECT 1 FROM hybrid_branch b JOIN hybrid_generation g ON g.id=b.generation_id WHERE b.id=s.hybrid_branch_id AND g.deadline_at>CURRENT_TIMESTAMP))""") == '0', 'Wait for an idle queue'
    slots=int(os.environ.get('RUNNER_FUNCTIONAL_SLOTS','2'))
    functional=[f'functional-{i}' for i in range(slots)]
    owner=str(uuid.uuid4())
    ids={name:str(uuid.uuid4()) for name in functional+['resource','later-functional','user']}
    all_ids=','.join(quote(value) for value in ids.values())
    def insert(name, mode, priority, delay):
        code='public class Main { public static void main(String[] a) throws Exception { Thread.sleep('+str(delay)+'); System.out.println(3); }}'
        plan=dict(version='sum-v1',output_policy='TOKEN_EXACT',tests=[dict(id='sample',input='',output='3\n')])
        package=json.dumps(plan,sort_keys=True,ensure_ascii=False,separators=(',',':'))
        source_hash=hashlib.sha256(code.encode()).hexdigest();plan_hash=hashlib.sha256(package.encode()).hexdigest()
        return f"""INSERT INTO submission(id,user_id,problem_version,source_code,source_sha256,idempotency_key,runtime_image,runner_policy,run_input,run_package,run_package_sha256)
SELECT {quote(ids[name])},{quote(owner)},'sum-v1',{quote(code)},{quote(source_hash)},{quote(ids[name])},runtime_image,runner_policy,'fixture',{quote(package)},{quote(plan_hash)} FROM problem_version WHERE id='sum-v1';
INSERT INTO judge_job(submission_id,priority,execution_mode,created_at) VALUES ({quote(ids[name])},{priority},{quote(mode)},CURRENT_TIMESTAMP + INTERVAL '{list(ids).index(name)} milliseconds');"""
    def rows():
        return json.loads(sql(f"""SELECT coalesce(json_agg(row_to_json(x)),'[]'::json) FROM (
SELECT j.submission_id AS id,j.status,j.verdict,j.execution_mode AS mode,j.attempt,j.worker_id,j.token,
a.started_at,a.finished_at,j.result_json::json->>'execution_mode' AS report_mode
FROM judge_job j LEFT JOIN judge_attempt a ON a.submission_id=j.submission_id AND a.attempt=j.attempt
WHERE j.submission_id IN ({all_ids})) x"""))
    def wait(predicate, seconds=90):
        deadline=time.monotonic()+seconds
        while time.monotonic()<deadline:
            result=rows()
            if predicate(result):return result
            time.sleep(.15)
        raise AssertionError('Scheduling probe timed out')
    try:
        sql(f"INSERT INTO app_user(id,username,password_hash,nickname) VALUES ({quote(owner)},{quote('parallel_'+owner[:12])},'not-a-login','Runner scheduling')")
        sql('BEGIN;'+''.join(insert(name,'FUNCTIONAL',1,3000) for name in functional)
            +insert('resource','EXCLUSIVE',1,100)+insert('later-functional','FUNCTIONAL',1,100)+'COMMIT;')
        active=wait(lambda items:sum(row['status']=='RUNNING' for row in items)==slots)
        assert {row['id'] for row in active if row['status']=='RUNNING'}=={ids[name] for name in functional},active
        assert len({row['worker_id'] for row in active if row['status']=='RUNNING'})==slots
        sql('BEGIN;'+insert('user','EXCLUSIVE',0,100)+'COMMIT;')
        done=wait(lambda items:len(items)==slots+3 and all(row['status']=='FINISHED' for row in items))
        by_id={row['id']:row for row in done}
        assert all(row['verdict']=='AC' and row['attempt']==1 and row['mode']==row['report_mode'] for row in done),done
        parallel=[by_id[ids[name]] for name in functional]
        assert max(row['started_at'] for row in parallel)<min(row['finished_at'] for row in parallel)
        user,resource,later=[by_id[ids[name]] for name in ('user','resource','later-functional')]
        assert user['started_at']>=max(row['finished_at'] for row in parallel)
        assert resource['started_at']>=user['finished_at']
        assert later['started_at']>=resource['finished_at']
        # Fetch diagnostics only for our exact tokens, across all persistent slots.
        tokens=[str(uuid.UUID(row['token'])) for row in done]
        program='''import json,sys
from pathlib import Path
root=Path.home()/'gamjaoj-worker/state'
tokens=json.loads(sys.stdin.read())
rows=[]
for token in tokens:
 paths=list((root/'attempts'/token).glob('runs/*/performance.json'))+list(root.glob('slots/*/attempts/'+token+'/runs/*/performance.json'))
 assert len(paths)==1
 rows.append(json.loads(paths[0].read_text()))
print(json.dumps(rows))
'''
        # The program is fixed trusted text; shell quote it, never interpolate fixture data into shell code.
        import shlex
        timings=json.loads(ssh(os.environ['GAMJAOJ_RUNNER_SSH_TARGET'],'python3 -c '+shlex.quote(program),json.dumps(tokens)))
        from datetime import datetime
        def interval(timing):
            span=next(s for s in timing['segments'] if s['phase']=='test.container_lifetime')
            def parse(value):
                import re
                return datetime.fromisoformat(re.sub(r'(\.\d{6})\d+',r'\1',value).replace('Z','+00:00'))
            return parse(span['startedAt']),parse(span['finishedAt'])
        by_token={t['attemptToken']:t for t in timings}
        spans=[interval(by_token[row['token']]) for row in parallel]
        assert max(start for start,_ in spans)<min(end for _,end in spans),'DB overlap did not produce container overlap'
        assert len({by_token[row['token']]['functionalSlot'] for row in parallel})==slots
        for row,(start,end) in zip(parallel,spans):
            print(f"slot {by_token[row['token']]['functionalSlot']}: container {(end-start).total_seconds():.2f}s")
        output=dict(mode='synthetic-scheduling-real-runner',jobs=done,timings=timings,
                    functionalSlots=slots,functionalOverlap=True,userBeforeQueuedGeneration=True,resourcesExclusive=True)
        Path('.state/runner-parallel-live.json').write_text(json.dumps(output,indent=2))
        print(f'PASS: {slots} real functional containers overlap; user drains all slots; resource then later validation; {slots+3} AC/attempt1/mode-matched reports')
    finally:
        # Leave active evidence intact on a timeout; never delete jobs still executing.
        remaining=int(sql(f"SELECT count(*) FROM judge_job WHERE submission_id IN ({all_ids}) AND status<>'FINISHED'"))
        if remaining==0:
            sql(f"DELETE FROM app_user WHERE id={quote(owner)}")
            print('Synthetic account and job evidence cleaned; local diagnostic snapshot retained')
        else:
            raise RuntimeError('Active fixture preserved for recovery: '+owner)


if __name__=='__main__':main()
