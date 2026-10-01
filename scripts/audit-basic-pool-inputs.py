#!/usr/bin/env python3
"""Validate generated private inputs on the dedicated Runner without exporting their content.
This supplements full three-language verification; it does not replace verdict/resource evidence.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('pool_verify',ROOT/'scripts/verify-basic-pool.py')
verify=importlib.util.module_from_spec(spec);spec.loader.exec_module(verify)


class InputAudit:
    """Observe fresh generator output through Runner's existing get/put port; never cache data."""
    def __init__(self,validator):
        self.validator=validator
        self.records=[]

    def get(self,key):
        return None

    def put(self,key,data):
        self.validator(data['input'].decode('utf-8'))
        self.records.append({'inputSha256':hashlib.sha256(data['input']).hexdigest(),
                             'inputBytes':len(data['input'])})


def audit(directory):
    package=json.loads((directory/'package.json').read_text())
    meta=json.loads((directory/'metadata.json').read_text())
    qa_path=directory/'qa/checks.py'
    spec=importlib.util.spec_from_file_location('pool_input_validator',qa_path)
    qa=importlib.util.module_from_spec(spec);spec.loader.exec_module(qa)
    for case in package['tests']:qa.validate(case['input'])
    evidence={'status':'PASS','id':meta['id'],'packageSha256':verify.digest(package),
              'qaSha256':hashlib.sha256(qa_path.read_bytes()).hexdigest(),
              'executionContract':verify.contract(),'generated':[]}
    if not package.get('generated'):return evidence
    capture=InputAudit(qa.validate)
    probe=dict(package,tests=package['tests'][:1])
    source=(directory/'solutions/java/Main.java').read_bytes()
    with tempfile.TemporaryDirectory(prefix='gamja-basic-input-audit-') as state:
        runner=verify.Runner(verify.LANGUAGES['JAVA']['image'],state)
        runner.profile=verify.checked_profile(verify.LANGUAGES['JAVA']|{'testWallSeconds':meta['timeLimits']['JAVA']},'JAVA',runner.image)
        runner.judge_all=True
        runner.generated_cache=capture
        report=runner.judge(source,probe)
    if report['verdict']!='AC':raise ValueError('Input probe did not pass: '+verify.failure_summary(report))
    generated=[test for test in report['tests'] if test.get('kind')=='generated']
    if len(generated)!=len(capture.records) or len(generated)!=len(package['generated']['tests']):
        raise ValueError('Each generated input must be validated freshly')
    for test,record in zip(generated,capture.records):
        if record['inputSha256']!=test['input_sha256'] or record['inputBytes']!=test['input_bytes']:
            raise ValueError('Validated input differs from measured Runner input')
        evidence['generated'].append(dict(record,id=test['id']))
    return evidence


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directories',nargs='+',type=Path)
    parser.add_argument('--output',required=True,type=Path)
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True);failed=False
    for directory in args.directories:
        identifier=json.loads((directory/'metadata.json').read_text())['id']
        try:result=audit(directory)
        except Exception as error:
            failed=True;result={'status':'FAIL','id':identifier,'issues':[str(error)[:1000]]}
        (args.output/(identifier+'.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
        print(result['status'],identifier,'private fixed/generated input validation',result.get('issues',[]),flush=True)
    raise SystemExit(1 if failed else 0)
