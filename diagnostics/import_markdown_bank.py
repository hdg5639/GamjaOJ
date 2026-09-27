"""Convert an authored Markdown bank (docs/GamjaOJ_Diagnostic_Question_Authoring_Guide.md format)
into the private diagnostics/<bank>.json candidate used by stage-diagnostic-bank.py.

Fixed tests are taken from the Markdown and must match the package's test files byte for byte.
Verification-only material (other languages, slow solutions, generators) stays in the candidate
file and is never staged: staging reads only problem, category, difficulty and rubric.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re

EVIDENCE_POLICY = 'Link claims to submission IDs, code spans and test classes; verdict alone is insufficient.'
FILES = {'JAVA': 'Main.java', 'CPP': 'main.cpp', 'PYTHON': 'main.py'}


def fences(text):
    """Returns [(language, body)] for fenced blocks in order."""
    return [(m.group(1), m.group(2)) for m in re.finditer(r'^```([A-Za-z0-9+]*)\n(.*?)^```\s*$', text, re.S | re.M)]


def sections(text, level):
    parts = re.split(rf'^{"#" * level} (.+)$', text, flags=re.M)
    return {parts[i].strip(): parts[i + 1] for i in range(1, len(parts), 2)}, parts[0]


def bullets(text):
    """Top-level '- key: value' bullets with nested '  - item' lists."""
    result, key = {}, None
    for line in text.splitlines():
        top = re.match(r'^- ([^:]+):\s*(.*)$', line)
        if top:
            key = top.group(1).strip()
            result[key] = top.group(2).strip() or []
            continue
        nested = re.match(r'^\s{2,}- (.+)$', line)
        if nested and key is not None:
            if not isinstance(result[key], list):
                result[key] = [result[key]]
            result[key].append(nested.group(1).strip())
    return result


def plain(text):
    return re.sub(r'`([^`]*)`', r'\1', text.replace('**', '')).strip()


def statement(body, example):
    lines, skipping = [], False
    for line in body.strip('\n').splitlines():
        if line.startswith('**언어별 제한'):
            skipping = True
            continue
        if skipping:
            if line.startswith('|') or not line.strip():
                continue
            skipping = False
        lines.append(line)
    text = plain('\n'.join(lines))
    explanation = re.search(r'^예제 설명:\s*(.+)$', example, re.M)
    if explanation:
        text += '\n\n예제 설명: ' + plain(explanation.group(1))
    # Learners copy output values from statements; U+2212 would never match the ASCII '-' in expected outputs.
    return re.sub(r'\n{3,}', '\n\n', text.replace('\u2212', '-'))


def io_pair(block):
    """'입력:' fence then optional '출력:' fence."""
    found = re.search(r'입력:\s*\n```[a-z]*\n(.*?)^```', block, re.S | re.M)
    out = re.search(r'출력:\s*\n```[a-z]*\n(.*?)^```', block, re.S | re.M)
    if not found or not out:
        raise ValueError('Every test needs explicit input and output fences')
    return found.group(1), out.group(1)


def convert(bank_md, package):
    text = bank_md.read_text()
    header, first = text.split('\n## 문항: ', 1)[0], None
    meta = bullets(header)
    bank_id = re.search(r'^# 진단 묶음: (\S+)', header, re.M).group(1)
    manifest = {p['slug']: p for p in json.loads((package / 'manifest.json').read_text())['problems']}
    items = []
    for chunk in text.split('\n## 문항: ')[1:]:
        slug, rest = chunk.split('\n', 1)
        slug = slug.strip()
        info = bullets(rest.split('\n### ', 1)[0])
        parts, _ = sections(rest, 3)
        example = parts['예제']
        sample_in, sample_out = io_pair(example)
        tests = [{'id': 'T01', 'input': sample_in, 'output': sample_out}]
        hidden, _ = sections(parts['비공개 테스트'], 4)
        for title, block in hidden.items():
            test_id = title.split(' ', 1)[0]
            inp, out = io_pair(block)
            tests.append({'id': test_id, 'input': inp, 'output': out})
        # The Markdown must agree with the package's test files and manifest digests.
        entry = manifest[slug]
        if [t['id'] for t in entry['tests']] != [t['id'] for t in tests]:
            raise ValueError(f'{slug}: test ids differ from manifest')
        for test, recorded in zip(tests, entry['tests']):
            for field in ('input', 'output'):
                data = test[field].encode()
                if hashlib.sha256(data).hexdigest() != recorded[field + '_sha256'] or data != (package / recorded[field]).read_bytes():
                    raise ValueError(f'{slug} {test["id"]} {field}: Markdown differs from package file')
        reference = next(body for lang, body in fences(parts['정답 코드']) if lang == 'java')
        wrong, _ = sections(parts['오답 코드'], 4)
        mutants = {name: next(body for lang, body in fences(block) if lang == 'java') for name, block in wrong.items()}
        rubric_src = bullets(parts['평가 기준'].split('**테스트별 관찰 지점**')[0])
        as_list = lambda key: [plain(v) for v in (rubric_src.get(key) or [])] if isinstance(rubric_src.get(key), list) else [plain(rubric_src[key])] if rubric_src.get(key) else []
        rows = re.findall(r'^\| (T\d+|G\d+ / seed \d+) \| (.*?) \| (.*?) \|$', parts['평가 기준'], re.M)
        rubric = {
            'version': '1',
            'skills': as_list('관찰 기술'),
            'positiveEvidence': as_list('잘했을 때 근거'),
            'negativeEvidence': as_list('부족할 때 근거'),
            'unobservable': as_list('관찰할 수 없는 것'),
            'evidencePolicy': EVIDENCE_POLICY,
            'testClasses': [f'{r[0]}: {r[1]}' for r in rows],
            'interpretationRules': as_list('진단 해석 규칙'),
            'habitSignals': as_list('코드 습관 관찰 포인트'),
            'riskFamilies': [v.strip() for v in plain(str(rubric_src.get('위험 분류', ''))).split(',') if v.strip()],
            'includedSkills': plain(str(rubric_src.get('포함 기술', ''))),
            'skillTags': entry.get('skill_tag_ids') or [t.strip(' `') for t in re.findall(r'`([^`]+)`', str(rubric_src.get('보조 기술 태그 ID', '')))],
            'languageNotes': as_list('언어별 관찰 유의점'),
            'approach': as_list('풀이 원리와 선택 근거'),
            'alternatives': as_list('허용되는 다른 풀이'),
            'efficiencyScope': as_list('효율 평가 범위'),
        }
        for field in ('skills', 'positiveEvidence', 'negativeEvidence', 'unobservable'):
            if not rubric[field]:
                raise ValueError(f'{slug}: rubric field {field} is empty')
        problem = {'version': f'diagnostic-{bank_id}-{slug}-v1', 'title': info['제목'], 'statement': statement(parts['본문'], example),
                   'output_policy': 'TOKEN_EXACT', 'tests': tests}
        item = {'category': info['분류'], 'difficulty': info['난이도'], 'problem': problem, 'reference': reference,
                'mutant': next(iter(mutants.values())), 'mutants': mutants, 'rubric': rubric}
        if '대형 입력 생성기' in parts:
            seeds = [s.strip() for s in re.search(r'- seed:\s*([0-9,\s]+)', parts['대형 입력 생성기']).group(1).split(',') if s.strip()]
            generator = next(body for lang, body in fences(parts['대형 입력 생성기']) if lang == 'java')
            problem['generated'] = {'generator': generator, 'reference': reference,
                                    'tests': [{'id': f'g{i + 1:02d}-seed-{seed}', 'seed': seed, 'expected': 'REFERENCE'} for i, seed in enumerate(seeds)]}
            item['slow'] = {lang: (package / 'slow' / slug / lang.lower() / name).read_text() for lang, name in FILES.items()}
            item['generators'] = {lang: (package / 'generators' / slug / lang.lower() / name).read_text() for lang, name in FILES.items()}
        # Other-language solutions for real-Runner verification only (never staged).
        item['languages'] = {lang: {kind: (package / 'solutions' / slug / lang.lower() / kind / name).read_text() for kind in ('correct', 'wrong')}
                             for lang, name in FILES.items()}
        if item['languages']['JAVA']['correct'] != reference:
            raise ValueError(f'{slug}: Java reference differs between Markdown and solutions/')
        item['oracleId'] = entry['id']
        items.append(item)
    return {'id': bank_id, 'title': meta.get('묶음 이름', bank_id), 'reviewed': False,
            'reviewStatus': 'CANDIDATE_AUTOMATED_CHECKS_ONLY', 'source': 'docs/diagnostic-questions/banks/' + bank_md.name, 'items': items}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--markdown', type=Path, required=True)
    parser.add_argument('--package', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    bank = convert(args.markdown, args.package)
    args.output.write_text(json.dumps(bank, ensure_ascii=False, indent=1) + '\n')
    print(f"{bank['id']}: {len(bank['items'])} items, {sum(len(i['problem']['tests']) for i in bank['items'])} fixed tests, "
          f"{sum(len(i['problem'].get('generated', {}).get('tests', [])) for i in bank['items'])} generated")
