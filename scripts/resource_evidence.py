"""Identity checks shared by private resource qualification and release staging."""
import hashlib
import json
import copy
import math


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))


def digest(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def audit_plan(job):
    """Build the same private measurement plan for calibration and release review."""
    plan = copy.deepcopy(job['problem'])
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
