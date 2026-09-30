"""Prepare the reviewed presentation-order example correction without overwriting history.

Reads a private v2 artifact; emits a private corrected artifact and transactional SQL.
Does not connect to the database. Existing versions/submissions remain intact; only
unattempted OPEN snapshots and the bank's next-session assignment are migrated.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
if __package__:
    from .audit_algo_examples import audit
else:
    from audit_algo_examples import audit

OLD = 'diagnostic-algo-mix-a-v2-safe-presentation-order-v1'
NEW = 'diagnostic-algo-mix-a-v2-safe-presentation-order-v2'
EXAMPLES = [
    {'id':'EX2', 'input':'3 1\n1 2\n', 'output':'2\n'},
    {'id':'EX3', 'input':'4 2\n1 2\n3 4\n', 'output':'8\n'},
]
NOTES = [
    '1번과 2번이 이웃할 수 없으므로 가능한 순서는 (1, 3, 2), (2, 3, 1)의 2가지이다. 첫 발표자와 마지막 발표자는 일렬 순서의 양 끝이므로 서로 이웃한 것으로 보지 않는다.',
    '1번과 2번, 3번과 4번이 각각 이웃할 수 없다. 전체 24가지에서 첫 금지 쌍이 이웃한 12가지와 둘째 금지 쌍이 이웃한 12가지를 빼고, 두 조건에 중복으로 포함된 8가지를 다시 더하면 8가지이다.',
]


def canonical(value):
    return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'))


def digest(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def quote(value):
    return "'"+value.replace("'","''")+"'"


def prepare(bank):
    assert bank['id']=='algo-mix-a-v2' and len(bank['items'])==20
    out=copy.deepcopy(bank)
    before=bank['items'][9]['problem']
    assert before['version']==OLD
    after=out['items'][9]['problem']
    assert [t['id'] for t in after['tests'][:3]]==['T01','EX2','EX3']
    after['version']=NEW
    after['tests'][1:3]=copy.deepcopy(EXAMPLES)
    statement=after['statement'].split('\n예제 2 설명:')[0]
    old='첫 줄에 N, M이 주어진다. 다음 M개 줄에 금지 쌍 A,B가 주어진다.'
    assert old in statement
    statement=statement.replace(old,old+' M=0이면 금지 쌍 입력 줄은 없으며 첫 줄만 입력한다.')
    after['statement']=statement+''.join(f'\n예제 {i} 설명: {note}' for i,note in enumerate(NOTES,2))
    # The corrected examples deliberately demonstrate the main rule even if they
    # also reject a diagnostic mutant. Clarity takes priority over hiding that rule.
    out['items'][9]['publicExamplesMayRejectMutant']=True
    report=audit(out)
    assert not report['failures'], report
    old_id,new_id=quote(OLD),quote(NEW)
    sql=f'''BEGIN;
-- Same owner lock as submission admission: a concurrent submission cannot race migration.
SELECT id FROM app_user WHERE id IN (
 SELECT s.user_id FROM diagnostic_session s JOIN diagnostic_item i ON i.session_id=s.id
 WHERE i.problem_version={old_id} AND i.status='OPEN'
) ORDER BY id FOR UPDATE;
DO $guard$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM problem_version WHERE id={old_id}
 AND package_sha256={quote(digest(before))} AND ready=true AND review_hold=false)
 THEN RAISE EXCEPTION 'Original package changed or is not ready'; END IF;
 IF (SELECT count(*) FROM diagnostic_bank_item WHERE bank_id='algo-mix-a-v2'
 AND position=9 AND problem_version={old_id})<>1
 THEN RAISE EXCEPTION 'Bank assignment changed'; END IF;
END $guard$;
INSERT INTO problem_version(id,package_json,package_sha256,runtime_image,runner_policy,
 ready,diagnostic_only,time_limits_json)
 SELECT {new_id},{quote(canonical(after))},{quote(digest(after))},runtime_image,runner_policy,
 ready,diagnostic_only,time_limits_json FROM problem_version WHERE id={old_id};
UPDATE diagnostic_bank_item SET problem_version={new_id}
 WHERE bank_id='algo-mix-a-v2' AND position=9 AND problem_version={old_id};
UPDATE diagnostic_item i SET problem_version=p.id,package_json=p.package_json,
 package_sha256=p.package_sha256 FROM problem_version p
 WHERE p.id={new_id} AND i.problem_version={old_id} AND i.status='OPEN'
 AND i.package_sha256={quote(digest(before))}
 AND NOT EXISTS (SELECT 1 FROM submission s WHERE s.diagnostic_item_id=i.id)
 AND EXISTS (SELECT 1 FROM diagnostic_session s WHERE s.id=i.session_id AND s.status IN ('ACTIVE','PAUSED'));
INSERT INTO diagnostic_exposure(user_id,content_sha256)
 SELECT DISTINCT s.user_id,{quote(digest({k:v for k,v in after.items() if k != 'version'}))}
 FROM diagnostic_session s JOIN diagnostic_item i ON i.session_id=s.id
 WHERE i.problem_version={new_id}
 ON CONFLICT (user_id,content_sha256) DO NOTHING;
COMMIT;
'''
    return out,sql,report


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--sql',type=Path,required=True)
    args=parser.parse_args()
    bank,sql,report=prepare(json.loads(args.source.read_text()))
    args.output.write_text(json.dumps(bank,ensure_ascii=False,indent=2)+'\n')
    args.sql.write_text(sql)
    print(json.dumps(report,ensure_ascii=False))
