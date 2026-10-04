#!/usr/bin/env python3
"""Emit a private, unreviewed bank import transaction. Does not connect to any database.

Run automated content/Runner checks separately before staging. SQL intentionally cannot
publish the bank: pedagogical review and a separate explicit release are still required.
"""
import argparse
import hashlib
import json
from pathlib import Path
import sys
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from runner.judge import validate_problem
from diagnostics.time_limits import limits_for

def canonical(value):
    return json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=False)
def quote(value):
    return "'"+value.replace("'","''")+"'"

def stage(data):
    if data.get('reviewed') is not False:raise ValueError('Staging accepts unreviewed candidates only')
    if not isinstance(data.get('id'),str) or not 1<=len(data['id'])<=80:raise ValueError('Invalid bank ID')
    groups={};versions=set()
    for item in data['items']:
        validate_problem(item['problem'])
        if item['problem']['output_policy']!='TOKEN_EXACT':raise ValueError('Diagnostic questions require formal judging')
        if not item['problem'].get('title') or not item['problem'].get('statement'):raise ValueError('Missing public statement')
        if not isinstance(item.get('category'),str) or not 1<=len(item['category'])<=80:raise ValueError('Invalid category')
        version=item['problem']['version']
        if len(version)>80 or version in versions:raise ValueError('Invalid/duplicate version')
        versions.add(version)
        groups.setdefault(item['category'],[]).append(item['difficulty'])
        for field in ('version','skills','positiveEvidence','negativeEvidence','unobservable','evidencePolicy'):
            if not item['rubric'].get(field):raise ValueError('Missing rubric evidence field: '+field)
    if not groups or any(sorted(v) not in [['EASY','MEDIUM'],['APPLIED','CORE']] for v in groups.values()):raise ValueError('Complete unique legacy or core/applied pairs required')
    if data.get('allocationUnit')=='WHOLE_SET' and (len(data['items'])!=8 or len(groups)!=4 or any(sorted(v)!=['APPLIED','CORE'] for v in groups.values())):raise ValueError('Exam diagnostics require four core/applied pairs')
    image=(ROOT/'runner/java-image.txt').read_text().strip()
    lines=['BEGIN;',f"INSERT INTO diagnostic_bank(id,reviewed) VALUES ({quote(data['id'])},false);"]
    for pos,item in enumerate(data['items']):
        p=item['problem'];raw=canonical(p)
        values=[p['version'],raw,hashlib.sha256(raw.encode()).hexdigest(),image,'java8-judge-v1']
        lines.append('INSERT INTO problem_version(id,package_json,package_sha256,runtime_image,runner_policy,ready,diagnostic_only) VALUES ('+','.join(map(quote,values))+',true,true);')
        limits=limits_for(p)
        if limits is not None:
            if (set(limits)!={'JAVA','CPP','PYTHON','analysis'} or any(type(limits[l]) is not int or not 1<=limits[l]<=20 for l in ('JAVA','CPP','PYTHON')) or not isinstance(limits['analysis'],str) or not 1<=len(limits['analysis'])<=6000):raise ValueError('Invalid server time-limit contract')
            lines.append('UPDATE problem_version SET time_limits_json='+quote(canonical(limits))+' WHERE id='+quote(p['version'])+';')
        values=[data['id'],str(pos),item['category'],item['difficulty'],p['version'],canonical(item['rubric'])]
        lines.append('INSERT INTO diagnostic_bank_item(bank_id,position,category,difficulty,problem_version,rubric_json) VALUES ('+','.join(map(quote,values))+');')
    lines.append('COMMIT;')
    return '\n'.join(lines)+'\n'

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bank',type=Path,default=ROOT/'diagnostics/core-a-v1.json')
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    args.output.write_text(stage(json.loads(args.bank.read_text())))
    print('Unreviewed staging SQL written; no database changed.')
