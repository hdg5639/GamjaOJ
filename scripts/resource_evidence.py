"""Identity checks shared by private resource qualification and release staging."""
import hashlib
import json
import copy
import math
from pathlib import Path


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))


def digest(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def audit_plan(job):
    """Build the same private measurement plan for calibration and release review."""
    plan = copy.deepcopy(job['problem'])
    # Diagnostics publishes exactly the first three original tests as examples.
    # Operators must explicitly identify those displayed cases; never infer
    # examples from hidden/audit positions or replace an existing sample contract.
    published = job.get('auditPublicExampleTestIds')
    if published is not None:
        examples = job['problem'].get('tests', [])[:3]
        if (job.get('diagnostic') is not True or 'api' not in job['problem']
                or plan.get('samples') or len(examples) != 3
                or type(published) is not list
                or published != [test['id'] for test in examples]
                or len(set(published)) != 3):
            raise ValueError('diagnostic callable examples must match the three displayed original tests')
        plan['samples'] = [dict(input=test['input'], output=test['output']) for test in examples]
    plan['tests'].extend(copy.deepcopy(job.get('auditTests', [])))
    generated = job.get('auditGenerated')
    if generated:
        if 'generated' in plan:
            raise ValueError('audit generator cannot replace an existing problem generator')
        plan['generated'] = copy.deepcopy(generated)
    return plan


def public_example_plan(plan):
    """Check a slow control on actual published examples, never positional tests."""
    samples = plan.get('samples', [])
    if not samples:
        raise ValueError('published examples required for inefficient reference control')
    result = copy.deepcopy(plan)
    result.pop('generated', None)
    result['tests'] = [dict(id='resource-public-example-' + str(i),
                            input=sample['input'], output=sample['output'])
                       for i, sample in enumerate(samples, 1)]
    return result


def profiling_seconds(job, language):
    """Longer operator probes need an explicit, language-local reviewed window."""
    windows = job.get('auditProfilingSeconds', {})
    if not isinstance(windows, dict) or set(windows) - {'JAVA', 'CPP', 'PYTHON'}:
        raise ValueError('invalid audit profiling windows')
    if any(type(seconds) is not int or not 20 <= seconds <= 180 for seconds in windows.values()):
        raise ValueError('audit profiling window must be bounded20..180 seconds')
    return windows.get(language, 20)


def compatible_execution_evidence(record, target):
    """Retain original provenance for explicitly approved validation-only widenings.

    No source/settings/runtime migration is inferred. The approved hash pair has
    identical images, commands, sandbox settings, and all files except a literal
    checked_profile ceiling. Reports retain their original contract and must stay within the original ceiling.
    """
    source = record.get('executionContract')
    if source == target:
        return True
    if not isinstance(source, dict) or not isinstance(target, dict):
        return False
    migrations = json.loads((Path(__file__).with_name('resource-contract-compatibility.json')).read_text())['migrations']
    approved = next((m for m in migrations if m['from'] == digest(source)
                     and m['to'] == digest(target)
                     and m['kind'] in ('test-wall-ceiling-only-20-to-60','test-wall-ceiling-only-20-to-180','test-wall-ceiling-only-60-to-180','large-input-opt-in-defaults-preserved')), None)
    if not approved or source.get('format') != target.get('format'):
        return False
    old_ceiling=approved.get('maximumLegacySeconds')
    new_ceiling=approved.get('maximumCurrentSeconds',60)
    if source.get('languages') != target.get('languages') or source.get('profile') != target.get('profile'):
        return False
    old_files, new_files = source.get('files', {}), target.get('files', {})
    changed = {k for k in old_files if old_files[k] != new_files.get(k)}
    if set(old_files) != set(new_files):
        return False
    if approved['kind'] == 'large-input-opt-in-defaults-preserved':
        if old_ceiling not in (20,60,180) or new_ceiling != 180 or approved.get('maximumLegacyGeneratedInput') != 8388608:
            return False
        if changed != {'runner/judge.py','runner/scheduling.py'}:
            return False
        rewrites=json.loads(Path(__file__).with_name('large-input-compatibility-rewrites.json').read_text())
        if set(rewrites) != changed:
            return False
        for name, chunks in rewrites.items():
            current=(Path(__file__).resolve().parents[1]/name).read_bytes()
            if new_files[name] != approved['newFileHashes'][name] or old_files[name] != approved['oldFileHashes'][name] or hashlib.sha256(current).hexdigest() != new_files[name]:
                return False
            for chunk in chunks:
                text=chunk['current'].encode()
                if current.count(text) != 1:
                    return False
                current=current.replace(text,chunk['previous'].encode(),1)
            if name == 'runner/judge.py':
                literal=b'0.1 <= seconds <= 180'
                if current.count(literal) != 1:
                    return False
                current=current.replace(literal,f'0.1 <= seconds <= {old_ceiling}'.encode(),1)
            if hashlib.sha256(current).hexdigest() != old_files[name]:
                return False
    else:
        if (old_ceiling,new_ceiling) not in ((20,60),(20,180),(60,180)) or approved['kind'] != f'test-wall-ceiling-only-{old_ceiling}-to-{new_ceiling}' or changed != {'runner/judge.py'}:
            return False
        current_judge = (Path(__file__).resolve().parents[1] / 'runner/judge.py').read_bytes()
        current_literal=f'0.1 <= seconds <= {new_ceiling}'.encode()
        previous_literal=f'0.1 <= seconds <= {old_ceiling}'.encode()
        if (old_files['runner/judge.py'] != approved['oldJudgeHash'] or new_files['runner/judge.py'] != approved['newJudgeHash']
                or current_judge.count(current_literal) != 1
                or hashlib.sha256(current_judge).hexdigest() != approved['newJudgeHash']
                or hashlib.sha256(current_judge.replace(current_literal,previous_literal,1)).hexdigest() != approved['oldJudgeHash']):
            return False
    reports = list(record.get('reports', [])) + [record.get('qualified', {})]
    reports += [a.get('qualified', {}) for a in record.get('qualifiedAlternates', [])]
    slow = record.get('slow', {})
    reports += [slow[k] for k in ('small', 'large') if k in slow]
    for report in reports:
        seconds = report.get('execution_profile', {}).get('testWallSeconds')
        if type(seconds) not in (int, float) or not math.isfinite(seconds) or not 0.1 <= seconds <= approved['maximumLegacySeconds']:
            return False
        observed = report.get('runner_environment', {}).get('contract')
        if observed is not None and observed != source:
            return False
        if approved['kind'] == 'large-input-opt-in-defaults-preserved':
            for test in report.get('tests', []):
                if test.get('kind') == 'generated' and (type(test.get('input_bytes')) is not int or not 0 < test['input_bytes'] <= 8388608):
                    return False
    return bool(record.get('reports'))


def complete_qualification(qualified, language, source_hash, plan, profile):
    expected = {test['id'] for test in plan['tests']} | {
        test['id'] for test in plan.get('generated', {}).get('tests', [])}
    actual = qualified.get('tests', [])
    return (
        qualified.get('verdict') == 'AC'
        and qualified.get('problem_sha256') == digest(plan)
        and qualified.get('language') == language
        and qualified.get('source_sha256') == source_hash
        and qualified.get('execution_mode') == 'FUNCTIONAL'
        and qualified.get('judge_all') is True
        and qualified.get('execution_profile') == profile
        and {test.get('id') for test in actual} == expected
        and len(actual) == len(expected)
        and all(test.get('verdict') == 'AC'
                and test.get('memory_measurement') == 'cgroup-peak-observed'
                and test.get('memory_peak_bytes', 0) > 0 for test in actual))


def worst_plan(plan, report):
    result = copy.deepcopy(plan)
    ranked = sorted(report['tests'], key=lambda test: test['wall_ms'], reverse=True)
    ids = {test['id'] for test in ranked[:3]}
    # Startup can dominate small tests; always repeat the largest generated input.
    generated = [test for test in ranked if test.get('kind') == 'generated']
    if generated:
        ids.add(max(generated, key=lambda test: test.get('input_bytes', 0))['id'])
    if ranked:
        ids.add(max(ranked, key=lambda test: test.get('memory_peak_bytes', 0))['id'])
    result['tests'] = [test for test in result['tests'] if test['id'] in ids]
    if result.get('generated'):
        result['generated']['tests'] = [test for test in result['generated']['tests'] if test['id'] in ids]
        if not result['generated']['tests']:
            result.pop('generated')
    # Runner validation still requires one fixed test when the longest is generated.
    if not result['tests']:
        result['tests'] = plan['tests'][:1]
    return result


def complete_measurement(record, language, sources, plan, broad_profile):
    """Require the full broad corpus and both current worst-case replays per source.
    This prevents an older measurement with a missing peak-memory replay from being
    reused or released merely because its final qualified verdict was AC.
    """
    reports = record.get('reports', [])
    if len(reports) != 3 * len(sources):
        return False
    observed = []
    for index, source in enumerate(sources):
        source_hash = hashlib.sha256(source.encode()).hexdigest()
        broad, first, second = reports[index * 3:index * 3 + 3]
        if any(type(test.get('wall_ms')) not in (int, float)
               or not math.isfinite(test['wall_ms']) or test['wall_ms'] <= 0
               or type(test.get('memory_peak_bytes')) is not int
               or test['memory_peak_bytes'] <= 0 for report in (broad, first, second)
               for test in report.get('tests', [])):
            return False
        if not complete_qualification(broad, language, source_hash, plan, broad_profile):
            return False
        replay = worst_plan(plan, broad)
        if any(not complete_qualification(report, language, source_hash, replay, broad_profile)
               for report in (first, second)):
            return False
        observed.extend(test for report in (broad, first, second) for test in report['tests'])
    if not observed or any(type(test.get('wall_ms')) not in (int, float)
                           or not math.isfinite(test['wall_ms']) or test['wall_ms'] <= 0
                           or type(test.get('memory_peak_bytes')) is not int
                           or test['memory_peak_bytes'] <= 0 for test in observed):
        return False
    return (record.get('maxWallMs') == max(test['wall_ms'] for test in observed)
            and record.get('maxMemoryBytes') == max(test['memory_peak_bytes'] for test in observed))
