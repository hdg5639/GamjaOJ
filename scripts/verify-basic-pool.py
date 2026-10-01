#!/usr/bin/env python3
"""Verify a private ordinary problem candidate through the pinned Docker Runner.

Run on the dedicated Runner host. QA modules are trusted authoring tools requiring
code review before use; learner/solution code executes only inside Runner sandboxes.
"""
import argparse
import hashlib
import importlib.util
import json
import re
from pathlib import Path
import sys
import tempfile
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from runner.judge import LANGUAGES, Runner, checked_profile, validate_problem
from runner.execution_contract import contract


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))


def digest(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def judge(language, source, package, limits):
    with tempfile.TemporaryDirectory(prefix='gamja-basic-verify-') as state:
        runner = Runner(LANGUAGES[language]['image'], state)
        runner.profile = checked_profile(LANGUAGES[language] | {'testWallSeconds': limits[language]}, language, runner.image)
        runner.judge_all = True
        report = runner.judge(source.encode(), package)
    if report['verdict'] == 'IE':
        raise RuntimeError('Runner infrastructure failure: ' + str(report.get('error', ''))[:200])
    return report


def source(directory, relative):
    path = (directory / relative).resolve()
    if not path.is_relative_to(directory.resolve()):
        raise ValueError('Source path escapes candidate')
    return path.read_text()


def failure_summary(report):
    return str({'verdict': report.get('verdict'),
                'compileStderr': report.get('compile', {}).get('stderr', '')[:2000],
                'failedTests': [{k: test.get(k) for k in ('id', 'kind', 'verdict', 'wall_ms', 'stderr')}
                                for test in report.get('tests', []) if test.get('verdict') != 'AC']})[:4000]


def artifact_files(directory):
    return {str(path.relative_to(directory)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in sorted(directory.rglob('*')) if path.is_file()
            and path.name not in ('verification.json', 'review.json', 'verification-progress.json')
            and '__pycache__' not in path.parts and not path.name.endswith('.log')}


def verify(directory):
    package = json.loads((directory / 'package.json').read_text())
    meta = json.loads((directory / 'metadata.json').read_text())
    validate_problem(package)
    version = package['version']
    if (not re.fullmatch(r'basic-pool-v1-[a-z0-9-]{1,60}', version)
            or len(version) > 80 or meta.get('version') != version):
        raise ValueError('Invalid ordinary version identity or metadata mismatch')
    limits = meta['timeLimits']
    qa_path = directory / 'qa' / 'checks.py'
    spec = importlib.util.spec_from_file_location('basic_pool_qa', qa_path)
    qa = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(qa)
    for test in package['tests']:
        qa.validate(test['input'])
    small = list(qa.random_cases(20261002, 12))
    if len(small) != 12:
        raise ValueError('QA must supply twelve deterministic independent-oracle cases')
    cases = []
    for i, text in enumerate(small):
        qa.validate(text)
        answer = qa.oracle(text)
        if not isinstance(answer, str):
            raise ValueError('Oracle must emit textual expected output')
        cases.append({'id': f'oracle-{i+1:02d}', 'input': text, 'output': answer})
    oracle_package = dict(package, tests=cases)
    oracle_package.pop('generated', None)
    validate_problem(oracle_package)
    for test in package['samples']:
        if qa.oracle(test['input']).split() != test['output'].split():
            raise ValueError('Public sample disagrees with independent oracle')
    mutants = json.loads((directory / 'mutants.json').read_text())
    if set(mutants) != set(LANGUAGES) or len(mutants['JAVA']) < 2 or any(not mutants[k] for k in LANGUAGES):
        raise ValueError('Two Java mutants and at least one in each other language required')
    report = {'status': 'RUNNING', 'packageSha256': digest(package), 'metadataSha256': digest(meta),
              'executionContract': contract(), 'timeLimits': limits, 'languages': {}, 'issues': []}
    original_files = artifact_files(directory)
    progress_path = directory / 'verification-progress.json'
    if progress_path.exists():
        try:
            saved = json.loads(progress_path.read_text())
            if (saved.get('status') == 'RUNNING' and saved.get('files') == original_files
                    and all(saved.get(k) == report[k] for k in
                            ('packageSha256', 'metadataSha256', 'executionContract', 'timeLimits'))):
                report['languages'] = saved.get('languages', {})
        except (ValueError, OSError):
            pass
    for language in LANGUAGES:
        if language in report['languages']:
            print('REUSED PASS', package['version'], language, flush=True)
            continue
        name = {'JAVA': 'Main.java', 'CPP': 'Main.cpp', 'PYTHON': 'Main.py'}[language]
        reference = source(directory, 'solutions/' + language.lower() + '/' + name)
        correct = judge(language, reference, package, limits)
        if correct['verdict'] != 'AC':
            raise ValueError(language + ' correct failed: ' + failure_summary(correct))
        measured = correct['tests']
        if any(t.get('memory_measurement') != 'cgroup-peak-observed' or t.get('memory_peak_bytes', 0) <= 0 for t in measured):
            raise ValueError(language + ' trusted memory observation missing')
        maximum = max(t['wall_ms'] for t in measured)
        if maximum * 2 > limits[language] * 1000:
            raise ValueError(f'{language} reference timing headroom below 2x: maximum={maximum}ms, budget={limits[language]}s')
        compared = judge(language, reference, oracle_package, limits)
        if compared['verdict'] != 'AC':
            raise ValueError(language + ' independent oracle disagrees: ' + failure_summary(compared))
        mutant_evidence = []
        fixed = dict(package)
        fixed.pop('generated', None)
        for label, path in mutants[language].items():
            wrong = judge(language, source(directory, path), fixed, limits)
            tests = wrong['tests']
            if wrong['verdict'] != 'WA' or any(t['verdict'] != 'AC' for t in tests[:3]) or not any(t['verdict'] == 'WA' for t in tests[3:]):
                raise ValueError(language + ' mutant must pass examples and fail private test with WA: ' + label + ' ' + failure_summary(wrong))
            if any(t['verdict'] not in ('AC', 'WA') for t in tests):
                raise ValueError(language + ' mutant crashes or times out: ' + label)
            mutant_evidence.append({'name': label, 'tests': tests})
        entry = {'correctVerdict': 'AC', 'completedTests': len(measured), 'tests': measured,
                 'maxWallMs': maximum, 'maxMemoryBytes': max(t['memory_peak_bytes'] for t in measured),
                 'oracleComparedCases': len(cases), 'mutantsPublicPassPrivateWA': mutant_evidence}
        if meta.get('efficiencyRequired'):
            slow_paths = json.loads((directory / 'slow.json').read_text())
            slow_source = source(directory, slow_paths[language])
            tiny = judge(language, slow_source, oracle_package, limits)
            slow = judge(language, slow_source, package, limits)
            if tiny['verdict'] != 'AC' or slow['verdict'] != 'TLE' or not any(t.get('kind') == 'generated' and t['verdict'] == 'TLE' for t in slow['tests']):
                raise ValueError(language + ' slow solution must be correct on small cases and TLE on generated input; '
                                 + 'small=' + failure_summary(tiny) + '; large=' + failure_summary(slow))
            entry['slow'] = {'smallVerdict': tiny['verdict'], 'largeVerdict': slow['verdict'], 'tests': slow['tests']}
        report['languages'][language] = entry
        if artifact_files(directory) != original_files:
            raise ValueError('Candidate changed during Runner verification')
        report['files'] = original_files
        progress_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        print('PASS', package['version'], language, f'{maximum} ms', flush=True)
    if artifact_files(directory) != original_files:
        raise ValueError('Candidate changed during Runner verification')
    report['files'] = original_files
    report['status'] = 'PASS'
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directories', nargs='+', type=Path)
    args = parser.parse_args()
    failed = False
    for directory in args.directories:
        try:
            result = verify(directory)
        except Exception as error:
            result = {'status': 'FAIL', 'issues': [str(error)[:6000]]}
            failed = True
            print('FAIL', directory.name, str(error)[:400], flush=True)
        (directory / 'verification.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    sys.exit(1 if failed else 0)
