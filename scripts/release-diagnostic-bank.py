#!/usr/bin/env python3
"""Emit a separate, hash-bound pilot release transaction after recorded content review.
Never connects to a database. Import through stage-diagnostic-bank.py first.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('stage_bank',ROOT/'scripts/stage-diagnostic-bank.py')
staging=importlib.util.module_from_spec(spec);spec.loader.exec_module(staging)

def release(artifact,review):
    data=json.loads(artifact)
    if review.get('decision')!='APPROVED_LIMITED_PILOT' or review.get('reviewerType')!='AI_ASSISTED_CONTENT_REVIEW':raise ValueError('Explicit limited-pilot content review required')
    if review.get('bankId')!=data['id'] or review.get('artifactSha256')!=hashlib.sha256(artifact).hexdigest():raise ValueError('Reviewed artifact mismatch')
    if not review.get('checks') or not review.get('limitations'):raise ValueError('Review evidence and limitations required')
    staging.stage(data) # Validate private staging contract without approving arbitrary content.
    q=staging.quote;bank=q(data['id']);image=q((ROOT/'runner/java-image.txt').read_text().strip())
    checks=[f'(SELECT count(*) FROM diagnostic_bank WHERE id={bank})=1',f'(SELECT count(*) FROM diagnostic_bank_item WHERE bank_id={bank})={len(data["items"])}']
    for position,item in enumerate(data['items']):
        p=item['problem'];digest=q(hashlib.sha256(staging.canonical(p).encode()).hexdigest())
        checks.append(f"(SELECT count(*) FROM diagnostic_bank_item b JOIN problem_version p ON p.id=b.problem_version WHERE b.bank_id={bank} AND b.position={position} AND b.category={q(item['category'])} AND b.difficulty={q(item['difficulty'])} AND b.problem_version={q(p['version'])} AND b.rubric_json={q(staging.canonical(item['rubric']))} AND p.package_sha256={digest} AND p.package_json={q(staging.canonical(p))} AND p.runtime_image={image} AND p.runner_policy='java8-judge-v1' AND p.ready=true AND p.review_hold=false AND p.diagnostic_only=true AND p.owner_id IS NULL)=1")
    return 'BEGIN;\nLOCK TABLE diagnostic_bank,diagnostic_bank_item,problem_version IN SHARE ROW EXCLUSIVE MODE;\nDO $review$ BEGIN IF NOT ('+' AND '.join(checks)+") THEN RAISE EXCEPTION 'Reviewed bank content mismatch'; END IF; END $review$;\nUPDATE diagnostic_bank SET reviewed=true WHERE id="+bank+';\nCOMMIT;\n'

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bank',type=Path,required=True);parser.add_argument('--review',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();args.output.write_text(release(args.bank.read_bytes(),json.loads(args.review.read_text())))
    print('Hash-bound pilot release SQL written; no database changed.')
