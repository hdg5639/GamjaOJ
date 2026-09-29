#!/usr/bin/env python3
"""Build private algo-mix-a-v2 from private algo-mix-a-v1: same items, rubrics and hidden tests, plus two
longer public examples per item (tests EX2, EX3 right after T01) and per-example explanations.

Expected outputs come from each item's Python reference and must agree with its Java and C++ references
(checked on the Runner by verify_bank_runner.py). Both artifacts stay in the git-ignored private directory.
"""
import argparse
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

from algo_mix_examples import EXAMPLES

OLD, NEW = 'algo-mix-a-v1', 'algo-mix-a-v2'


def run_python(source, text):
    with tempfile.NamedTemporaryFile('w', suffix='.py', delete=False) as f:
        f.write(source)
    result = subprocess.run([sys.executable, f.name], input=text, capture_output=True, text=True, timeout=30, check=True)
    Path(f.name).unlink()
    return result.stdout


def build(bank):
    if bank['id'] != OLD or len(bank['items']) != len(EXAMPLES):
        raise ValueError('Expected the private algo-mix-a-v1 artifact')
    out = json.loads(json.dumps(bank))
    out.update(id=NEW, reviewed=False, reviewStatus='CANDIDATE_AUTOMATED_CHECKS_ONLY')
    for index, item in enumerate(out['items']):
        problem = item['problem']
        if OLD not in problem['version']:
            raise ValueError('Unexpected version ' + problem['version'])
        problem['version'] = problem['version'].replace(OLD, NEW)
        tests = problem['tests']
        first = tests[0]
        if first['id'] != 'T01' or run_python(item['languages']['PYTHON']['correct'], first['input']).split() != first['output'].split():
            raise ValueError(f'Item {index}: reference disagrees with the first example')
        examples = []
        for number, (text, note) in enumerate(EXAMPLES[index], start=2):
            output = run_python(item['languages']['PYTHON']['correct'], text)
            if run_python(item['languages']['PYTHON']['wrong'], text).split() != output.split():
                raise ValueError(f'Item {index} example {number}: exposes the targeted wrong solution')
            examples.append({'id': f'EX{number}', 'input': text, 'output': output.strip() + '\n'})
        if len(tests) + len(examples) > 20:
            raise ValueError(f'Item {index}: more than 20 tests')
        problem['tests'] = [first, *examples, *tests[1:]]
        statement, count = re.subn(r'\n예제 설명: ', '\n예제 1 설명: ', problem['statement'])
        if count != 1:
            raise ValueError(f'Item {index}: expected one example explanation')
        problem['statement'] = statement.rstrip('\n') + ''.join(
            f'\n예제 {number} 설명: {note}' for number, (_, note) in enumerate(EXAMPLES[index], start=2))
    return out


if __name__ == '__main__':
    root = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=root / 'private/algo-mix-a-v1.json')
    parser.add_argument('--output', type=Path, default=root / 'private/algo-mix-a-v2.json')
    args = parser.parse_args()
    result = build(json.loads(args.source.read_text()))
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(f'{NEW}: {len(result["items"])} items written to {args.output}')
