"""Real-Runner verification of a diagnostic candidate bank (Docker; production images and judge code).

For every item and language (Java 8, C++17, Python 3):
  - the correct solution is AC on all fixed tests and generated large tests; large-test wall time is below
    4000 ms (Java, Python) or 3000 ms (C++);
  - every wrong solution passes the public examples and ends with WA (not RE/TLE/MLE) on a private fixed test;
  - slow solutions (items with generated tests) are AC on small inputs and TLE on a generated test. With
    --package, small inputs are the package QA's 12 random cases per item (its random_case/oracle, seed
    99173 + problem id); without it, fixed tests of at most 4096 bytes are used as an approximation;
  - large-input generators in all three languages emit byte-identical input for each seed.
Writes a JSON evidence report. Never contacts the service or database.
"""
import argparse
import concurrent.futures
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from runner.judge import LANGUAGES, Runner, GeneratedCache  # noqa: E402

LIMIT_MS = {'JAVA': 4000, 'CPP': 3000, 'PYTHON': 4000}


INFRASTRUCTURE = []


def public(test_id):
    """T01 and the EX-prefixed examples that follow it are shown to learners (Diagnostics.examples)."""
    return test_id == 'T01' or test_id.startswith('EX')


def judge(language, source, problem):
    """An IE is an infrastructure fault, not a program verdict: record it and retry exactly once."""
    for attempt in range(2):
        with tempfile.TemporaryDirectory() as directory:
            runner = Runner(LANGUAGES[language]['image'], directory)
            runner.generated_cache = SHARED
            report = runner.judge(source.encode(), problem)
        if report['verdict'] != 'IE':
            return report
        INFRASTRUCTURE.append({'version': problem['version'], 'language': language, 'attempt': attempt, 'error': str(report.get('error'))[:300]})
    return report


SHARED = GeneratedCache()
ORACLES = None


def small_cases(item, problem):
    if ORACLES is None:
        return [t for t in problem['tests'] if len(t['input'].encode()) <= 4096]
    import random
    rng = random.Random(99173 + item['oracleId'])
    cases = []
    for index in range(12):
        text = ORACLES.random_case(item['oracleId'], rng)
        ORACLES.validate_input(item['oracleId'], text)
        cases.append({'id': f'R{index + 1:02d}', 'input': text, 'output': ORACLES.oracle(item['oracleId'], text)})
    return cases


def fixed_only(problem):
    return {k: v for k, v in problem.items() if k != 'generated'}


def generator_output(language, source, seed):
    image = LANGUAGES[language]['image']
    with tempfile.TemporaryDirectory() as directory:
        name = {'JAVA': 'Main.java', 'CPP': 'main.cpp', 'PYTHON': 'main.py'}[language]
        Path(directory, name).write_text(source)
        command = {'JAVA': 'mkdir -p /tmp/c && javac -encoding UTF-8 -d /tmp/c /w/Main.java && java -Xmx384m -cp /tmp/c Main',
                   'CPP': 'g++ -std=c++17 -O2 -pipe -o /tmp/g /w/main.cpp && /tmp/g',
                   'PYTHON': 'python3 -I -B /w/main.py'}[language]
        result = subprocess.run(['docker', 'run', '--rm', '-i', '--network', 'none', '-v', f'{directory}:/w:ro', image, 'sh', '-c', command],
                                input=(seed + '\n').encode(), capture_output=True, timeout=300)
        if result.returncode:
            raise RuntimeError(f'{language} generator failed: {result.stderr[-300:]!r}')
        return result.stdout


def verify_item(item):
    problem, version = item['problem'], item['problem']['version']
    record = {'version': version, 'category': item['category'], 'difficulty': item['difficulty'], 'failures': [], 'languages': {}}
    fail = record['failures'].append
    for language, sources in item['languages'].items():
        entry = record['languages'].setdefault(language, {})
        report = judge(language, sources['correct'], problem)
        large = [t for t in report['tests'] if t.get('kind') == 'generated']
        entry['correct'] = {'verdict': report['verdict'], 'fixedMaxWallMs': max((t['wall_ms'] for t in report['tests'] if t.get('kind') != 'generated'), default=None),
                            'largeWallMs': [t['wall_ms'] for t in large], 'largeInputBytes': [t.get('input_bytes') for t in large]}
        if report['verdict'] != 'AC':
            last = report['tests'][-1] if report['tests'] else {}
            fail(f'{language} correct {report["verdict"]} at {last.get("id")} {report.get("error", "")[:200]}')
        for t in large:
            if t['wall_ms'] >= LIMIT_MS[language] + (1 if language == 'CPP' else 0):
                fail(f'{language} correct large {t["id"]} {t["wall_ms"]} ms exceeds {LIMIT_MS[language]} ms gate')
        wrong = judge(language, sources['wrong'], fixed_only(problem))
        tests = wrong['tests']
        entry['wrong'] = {'verdict': wrong['verdict'], 'failedAt': tests[-1]['id'] if tests else None}
        if wrong['verdict'] != 'WA':
            fail(f'{language} wrong verdict {wrong["verdict"]} (expected WA)')
        elif not tests or tests[0]['id'] != 'T01' or any(t['verdict'] != 'AC' for t in tests[:-1]) or public(tests[-1]['id']):
            fail(f'{language} wrong must pass the public examples and fail a private test (failed at {tests[-1]["id"] if tests else None})')
        if 'slow' in item:
            # Handoff criterion: exact on small inputs, TLE on the generated large inputs.
            small = dict(fixed_only(problem), tests=small_cases(item, problem))
            exact = judge(language, item['slow'][language], small)
            large = dict(problem, tests=[problem['tests'][0]])
            slow = judge(language, item['slow'][language], large)
            last = slow['tests'][-1] if slow['tests'] else {}
            entry['slow'] = {'smallVerdict': exact['verdict'], 'smallTests': len(small['tests']), 'largeVerdict': slow['verdict'],
                             'at': last.get('id'), 'kind': last.get('kind'), 'wallMs': last.get('wall_ms')}
            if exact['verdict'] != 'AC':
                fail(f'{language} slow {exact["verdict"]} on small fixed tests (expected AC)')
            if slow['verdict'] != 'TLE' or last.get('kind') != 'generated':
                fail(f'{language} slow {slow["verdict"]} at {last.get("id")} (expected TLE on a generated test)')
    if 'generators' in item:
        identity = {}
        for test in problem['generated']['tests']:
            digests = {language: hashlib.sha256(generator_output(language, source, test['seed'])).hexdigest()
                       for language, source in item['generators'].items()}
            identity[test['seed']] = digests
            if len(set(digests.values())) != 1:
                fail(f'generators differ for seed {test["seed"]}: {digests}')
        record['generatorIdentity'] = identity
    return record


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bank', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--workers', type=int, default=1)
    parser.add_argument('--only', nargs='*')
    parser.add_argument('--package', type=Path, help='Authoring package with qa/oracles.py for slow-solution small cases')
    args = parser.parse_args()
    if args.package:
        sys.path.insert(0, str(args.package / 'qa'))
        import oracles as ORACLES  # noqa: E402
    bank = json.loads(args.bank.read_text())
    items = [i for i in bank['items'] if not args.only or any(s in i['problem']['version'] for s in args.only)]
    # Exclusive timing checks stay serial by default; the physical Runner lock also serializes them.
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        records = list(pool.map(verify_item, items))
    for r in records:
        print(('PASS ' if not r['failures'] else 'FAIL ') + r['version'], *r['failures'], sep='\n  ', flush=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({'bank': bank['id'], 'bankSha256': hashlib.sha256(args.bank.read_bytes()).hexdigest(),
                                       'contract': 'runner/judge.py with pinned language images', 'infrastructureRetries': INFRASTRUCTURE,
                                       'items': records}, ensure_ascii=False, indent=1))
    print('infrastructure IE retries:', len(INFRASTRUCTURE))
    sys.exit(1 if any(r['failures'] for r in records) else 0)
