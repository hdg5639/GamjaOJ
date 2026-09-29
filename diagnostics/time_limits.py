"""Reviewed limits for exact diagnostic packages; no hidden test data in the policy file."""
import hashlib
import json
from pathlib import Path

POLICY = Path(__file__).with_name('algo-mix-time-limits-v1.json')


def limits_for(problem):
    for item in json.loads(POLICY.read_text())['items']:
        if problem['version'] in item['versions']:
            raw = json.dumps(problem, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()
            if hashlib.sha256(raw).hexdigest() != item['versions'][problem['version']]:
                raise ValueError('Diagnostic time-limit package hash mismatch')
            return dict(item['limits'])
    return None
