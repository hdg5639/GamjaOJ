"""Normalize private audit provenance without exporting answers, tests or source code."""
import argparse, hashlib, json, re
from collections import Counter
from pathlib import Path


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))


def normalize(value):
    return re.sub(r'[\s·_-]+', '', value).casefold()


def profiles_for(policy, values):
    raw = [v.casefold() for v in values if isinstance(v, str)]
    tokens = [normalize(v) for v in raw]
    def matches(alias):
        if re.fullmatch(r'[a-zA-Z0-9_-]+', alias):
            parts = re.split(r'[-_]', alias.casefold())
            pattern = r'(?<![a-z0-9])' + r'[-_\s]*'.join(map(re.escape, parts)) + r'(?![a-z0-9])'
            return any(re.search(pattern, value) for value in raw)
        key = normalize(alias)
        return any(key == token or (len(key) >= 3 and key in token) for token in tokens)
    found = [profile['id'] for profile in policy['profiles'] if any(matches(alias) for alias in profile['aliases'])]
    return sorted(set(['input-contract'] + found))


def strings(value):
    if isinstance(value, str):
        return [value]
    if isinstance(value, list):
        return [s for item in value for s in strings(item)]
    if isinstance(value, dict):
        return [s for item in value.values() for s in strings(item)]
    return []


def normalize_inventory(root, policy):
    rows = []
    for path in sorted((root / 'jobs').glob('*.json')):
        job = json.loads(path.read_text())
        certificate_path = root / 'intent-certificates' / path.name
        certificate = json.loads(certificate_path.read_text()) if certificate_path.exists() else None
        if certificate is not None and certificate.get('packageHash') != job['packageHash']:
            raise ValueError('certificate package fence mismatch: ' + job['version'])
        intent = job.get('intent', {})
        values = strings({k: intent.get(k) for k in ('category', 'originalCategory', 'skills', 'skillTags', 'includedSkills', 'approach', 'testClasses')})
        # Version family is metadata, never source code or private input/output.
        version = job['version']
        if version.startswith('diagnostic-exam-b-') or job['problem'].get('api'):
            values += ['command']
        if 'basic-pool-v1-' in version:
            values += [version.split('basic-pool-v1-', 1)[1].rsplit('-', 3)[0]]
        selected = profiles_for(policy, values)
        evidence = certificate.get('languageEvidence', {}) if certificate else {}
        rows.append({'version': version, 'packageHash': job['packageHash'], 'profileIds': selected,
                     'auditJobHash': hashlib.sha256(path.read_bytes()).hexdigest(),
                     'certificateHash': hashlib.sha256(certificate_path.read_bytes()).hexdigest() if certificate else None,
                     'maximumInputsReviewed': bool(certificate and certificate.get('maximumInputsReviewed')),
                     'languageEvidence': {language: {'measurementHash': evidence.get(language, {}).get('measurementHash'),
                         'maximumWitnesses': evidence.get(language, {}).get('maximumWitnesses', []),
                         'qualifiedVariants': evidence.get(language, {}).get('qualifiedVariants', [])}
                         for language in ('CPP', 'JAVA', 'PYTHON')},
                     'status': 'AUDIT_CERTIFICATE_PRESENT' if certificate else 'PENDING_REVIEW'})
    if not rows:
        raise ValueError('no audit jobs')
    return {'policyVersion': policy['version'], 'policyHash': hashlib.sha256(canonical(policy).encode()).hexdigest(),
            'scope': 'typed audit provenance; certificates do not qualify newly generated semantics',
            'count': len(rows), 'profileCounts': dict(sorted(Counter(p for row in rows for p in row['profileIds']).items())),
            'pending': sum(row['status'] == 'PENDING_REVIEW' for row in rows), 'problems': rows}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--audit-root', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--policy', type=Path, default=Path('generation/resource-profiles-v1.json'))
    args = parser.parse_args()
    result = normalize_inventory(args.audit_root, json.loads(args.policy.read_text()))
    args.output.write_text(canonical(result) + '\n')
    print(canonical({key: result[key] for key in ('count', 'profileCounts', 'pending')}))
