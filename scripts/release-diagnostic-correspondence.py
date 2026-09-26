#!/usr/bin/env python3
"""Emit atomic reviewed B release and A/B hash correspondence; never connects to a DB."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('release_bank',ROOT/'scripts/release-diagnostic-bank.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

def release(source_bytes,target_bytes,review):
    if review.get('sourceArtifactSha256')!=hashlib.sha256(source_bytes).hexdigest():raise ValueError('Source artifact review mismatch')
    source,target=json.loads(source_bytes),json.loads(target_bytes)
    if source['id']!=review.get('sourceBankId'):raise ValueError('Source bank mismatch')
    result=module.release(target_bytes,review).removesuffix('COMMIT;\n')
    q=module.staging.quote;canonical=module.staging.canonical
    a={(i['category'],i['difficulty']):i for i in source['items']}
    b={(i['category'],i['difficulty']):i for i in target['items']}
    if a.keys()!=b.keys():raise ValueError('Complete matching coverage required')
    result+='LOCK TABLE diagnostic_reassessment_pair IN SHARE ROW EXCLUSIVE MODE;\n'
    checks=[f"(SELECT count(*) FROM diagnostic_bank WHERE id={q(source['id'])} AND reviewed=true)=1"]
    inserts=[]
    for key,original in a.items():
        other=b[key];x,y=original['problem'],other['problem']
        hx,hy=hashlib.sha256(canonical(x).encode()).hexdigest(),hashlib.sha256(canonical(y).encode()).hexdigest()
        if x['statement']==y['statement'] or x['version']==y['version']:raise ValueError('Distinct reviewed task required')
        checks.append(f"(SELECT count(*) FROM diagnostic_bank_item b JOIN problem_version p ON p.id=b.problem_version WHERE b.bank_id={q(source['id'])} AND b.category={q(key[0])} AND b.difficulty={q(key[1])} AND b.problem_version={q(x['version'])} AND b.rubric_json={q(canonical(original['rubric']))} AND p.package_sha256={q(hx)} AND p.package_json={q(canonical(x))} AND p.ready=true AND p.review_hold=false AND p.diagnostic_only=true AND p.owner_id IS NULL)=1")
        inserts.append('INSERT INTO diagnostic_reassessment_pair(source_version,target_version,source_sha256,target_sha256,reviewed) VALUES ('+','.join(map(q,[x['version'],y['version'],hx,hy]))+',true);')
    result+='DO $source$ BEGIN IF NOT ('+' AND '.join(checks)+") THEN RAISE EXCEPTION 'Reviewed source correspondence mismatch'; END IF; END $source$;\n"
    return result+'\n'.join(inserts)+'\nCOMMIT;\n'
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source',type=Path,required=True);p.add_argument('--bank',type=Path,required=True);p.add_argument('--review',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    args=p.parse_args();args.output.write_text(release(args.source.read_bytes(),args.bank.read_bytes(),json.loads(args.review.read_text())))
    print('Hash-bound B/correspondence release SQL written; no DB changed.')
