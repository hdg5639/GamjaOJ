#!/usr/bin/env python3
"""Emit hash-guarded SQL for verified, ownerless ordinary practice problems.

Private packages stay outside Git. This tool never connects to a database.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from runner.judge import validate_problem
from runner.execution_contract import contract


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))


def digest(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def quote(value):
    return "'" + value.replace("'", "''") + "'"


def load_candidate(directory):
    package = json.loads((directory / 'package.json').read_text())
    meta = json.loads((directory / 'metadata.json').read_text())
    review = json.loads((directory / 'review.json').read_text())
    verification = json.loads((directory / 'verification.json').read_text())
    validate_problem(package)
    version = package['version']
    if not re.fullmatch(r'basic-pool-v1-[a-z0-9-]{1,60}', version) or len(version) > 80:
        raise ValueError('Invalid ordinary problem identity')
    if package['output_policy'] != 'TOKEN_EXACT' or package.get('api') or package.get('callable'):
        raise ValueError('This release requires three-language STDIO judging')
    if not package.get('title') or not package.get('statement'):
        raise ValueError('Missing public statement')
    samples = package.get('samples')
    if not isinstance(samples, list) or len(samples) != 3:
        raise ValueError('Three explicit public samples required')
    for sample, case in zip(samples, package['tests']):
        if set(sample) != {'input', 'output'} or any(sample[k] != case[k] for k in ('input', 'output')):
            raise ValueError('Public samples must match first fixed tests')
    if len(package['tests']) < 4 or meta.get('version') != version:
        raise ValueError('Private tests and matching metadata required')
    category, tags = meta.get('category', ''), meta.get('tags', [])
    if not 1 <= len(category) <= 80 or re.search('[A-Za-z]', category):
        raise ValueError('Public category must be Korean')
    if not isinstance(tags, list) or not 1 <= len(tags) <= 6 or len(set(tags)) != len(tags):
        raise ValueError('Invalid tags')
    if any(not isinstance(t, str) or not 1 <= len(t) <= 80 or ',' in t or re.search('[A-Za-z]', t) for t in tags):
        raise ValueError('Tags must use Korean and no commas')
    if meta.get('difficulty') not in ('EASY', 'MEDIUM', 'HARD'):
        raise ValueError('Invalid ordinary difficulty')
    limits = meta['timeLimits']
    if set(limits) != {'JAVA', 'CPP', 'PYTHON', 'analysis'} or not limits['analysis']:
        raise ValueError('Language-specific timing rationale required')
    if any(type(limits[k]) is not int or not 1 <= limits[k] <= 20 for k in ('JAVA', 'CPP', 'PYTHON')):
        raise ValueError('Invalid per-test time budgets')
    fingerprint = digest(package)
    if verification.get('status') != 'PASS' or verification.get('packageSha256') != fingerprint:
        raise ValueError('Current package has no passing Runner evidence')
    if verification.get('executionContract') != contract() or verification.get('timeLimits') != limits:
        raise ValueError('Runner contract or final budgets changed since verification')
    if verification.get('metadataSha256') != digest(meta):
        raise ValueError('Metadata changed since verification')
    files = verification.get('files', {})
    if not files or any(not (directory / name).resolve().is_relative_to(directory.resolve()) for name in files):
        raise ValueError('Missing or unsafe evidence file paths')
    for name, expected in files.items():
        if hashlib.sha256((directory / name).read_bytes()).hexdigest() != expected:
            raise ValueError('Verified source or QA artifact changed: ' + name)
    languages = verification.get('languages', {})
    if set(languages) != {'JAVA', 'CPP', 'PYTHON'}:
        raise ValueError('Three measured language reports required')
    for language, evidence in languages.items():
        if evidence.get('correctVerdict') != 'AC' or evidence.get('completedTests') != len(package['tests']) + len(package.get('generated', {}).get('tests', [])):
            raise ValueError('Correct solutions did not finish all tests: ' + language)
        if not evidence.get('oracleComparedCases') or not evidence.get('mutantsPublicPassPrivateWA'):
            raise ValueError('Independent answer and mutant evidence required: ' + language)
        if meta.get('efficiencyRequired') and (evidence.get('slow', {}).get('smallVerdict') != 'AC' or evidence.get('slow', {}).get('largeVerdict') != 'TLE'):
            raise ValueError('Natural slow solution timing evidence missing: ' + language)
        if not evidence.get('maxMemoryBytes') or not evidence.get('maxWallMs'):
            raise ValueError('Measured memory/time missing: ' + language)
        if evidence['maxWallMs'] * 2 > limits[language] * 1000:
            raise ValueError('Insufficient reference timing headroom: ' + language)
    if review.get('decision') != 'ACCEPT' or review.get('packageSha256') != fingerprint or not review.get('checks') or review.get('issues'):
        raise ValueError('Content review must accept this exact package without unresolved issues')
    teaching = json.loads((directory / 'teaching.json').read_text())
    if not isinstance(teaching.get('hints'), list) or len(teaching['hints']) != 3 or not all(teaching['hints']) or not teaching.get('editorial'):
        raise ValueError('Three hints and editorial required')
    return package, meta, teaching


def stage(directories):
    lines = ['BEGIN;', "SELECT pg_advisory_xact_lock(hashtext('basic-pool-v1-release')); "]
    thinking = json.loads((ROOT / 'generation/thinking-baseline-v1.json').read_text())
    seen = set()
    image = (ROOT / 'runner/java-image.txt').read_text().strip()
    for directory in directories:
        package, meta, teaching = load_candidate(directory)
        version = package['version']
        if version in seen:
            raise ValueError('Duplicate release identity')
        seen.add(version)
        values = {'id': version, 'package_json': canonical(package), 'package_sha256': digest(package),
                  'runtime_image': image, 'runner_policy': 'java8-judge-v1', 'catalog_category': meta['category'],
                  'catalog_tags': ','.join(meta['tags']), 'catalog_difficulty': meta['difficulty'],
                  'time_limits_json': canonical(meta['timeLimits']), 'teaching_json': canonical(teaching)}
        same = ' AND '.join(k + ' IS NOT DISTINCT FROM ' + quote(v) for k, v in values.items())
        same += ' AND owner_id IS NULL AND diagnostic_only=false AND ready=true AND review_hold=false AND shared=true'
        lines.append('DO $basic_pool$ BEGIN IF EXISTS (SELECT 1 FROM problem_version WHERE id=' + quote(version) + ') AND NOT EXISTS (SELECT 1 FROM problem_version WHERE ' + same + ") THEN RAISE EXCEPTION 'Existing basic problem differs'; END IF; END $basic_pool$;")
        columns = ','.join(values) + ',ready,diagnostic_only,shared'
        encoded = ','.join(quote(v) for v in values.values()) + ',true,false,true'
        lines.append('INSERT INTO problem_version (' + columns + ') SELECT ' + encoded + ' WHERE NOT EXISTS (SELECT 1 FROM problem_version WHERE id=' + quote(version) + ');')
        profile = thinking.get(version)
        if profile and profile['packageSha256'] == values['package_sha256']:
            # Only reviewed exact packages; do not invent levels or overwrite a subsequent review.
            numbers = ','.join(str(profile[key]) for key in ('layer', 'insight', 'implementation', 'edgeCases'))
            lines.append('INSERT INTO problem_thinking_profile(problem_version,package_sha256,layer,insight,implementation,edge_cases,rationale,source) '
                         'SELECT id,package_sha256,' + numbers + ',' + quote(profile['rationale']) + ",'CURATED_ESTIMATE' FROM problem_version WHERE id=" + quote(version)
                         + ' AND package_sha256=' + quote(profile['packageSha256']) + ' AND NOT EXISTS (SELECT 1 FROM problem_thinking_profile WHERE problem_version=' + quote(version) + ');')
    lines.append('COMMIT;')
    return '\n'.join(lines) + '\n'


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directories', nargs='+', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    sql = stage(args.directories)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(sql)
    args.output.chmod(0o600)
    print('Verified ordinary problem SQL written. No database changed.')
