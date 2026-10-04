"""Identity checks shared by private resource qualification and release staging."""
import hashlib
import json


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))


def digest(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()


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
