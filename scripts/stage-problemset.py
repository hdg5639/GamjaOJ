#!/usr/bin/env python3
"""Stage all 358 attributed problems after exact-package Java Runner verification.

No database connection. Private packages/answers and SQL must remain outside Git.
Other languages use the standard STDIO contract; their timing budgets are estimates.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from runner.judge import validate_problem,LANGUAGES,checked_profile
from runner.execution_contract import contract
COMMIT='3da07507a3842458aa3576db4ec46cd97348d351'
FILES=('문제.txt','Solution.java','sample_input.txt','sample_output.txt','input.txt','output.txt')
CATEGORIES={'BFS':'너비 우선 탐색','DFS':'깊이 우선 탐색','DP':'동적 계획법','MST':'최소 신장 트리','그리디':'탐욕법','이진탐색':'이분 탐색','투포인터':'두 포인터','분할정복':'분할 정복','서로소집합':'서로소 집합','슬라이딩윈도우':'슬라이딩 윈도우','연결리스트':'연결 리스트','위상정렬':'위상 정렬','세그먼트트리':'세그먼트 트리','중복순열':'중복 순열','중복조합':'중복 조합','최단경로':'최단 경로','순서통계':'순서 통계','네트워크유량':'네트워크 유량','계산기하':'기하'}
def canonical(v):return json.dumps(v,ensure_ascii=False,sort_keys=True,separators=(',',':'))
def digest(v):return hashlib.sha256(canonical(v).encode()).hexdigest()
def quote(v):return "'"+v.replace("'","''")+"'"
def profile_check(p):
 if set(p)!={'layer','insight','implementation','edgeCases','rationale'}:raise ValueError('Profile shape')
 for key,limit in [('layer',9),('insight',5),('implementation',5),('edgeCases',5)]:
  if type(p[key]) is not int or not 1<=p[key]<=limit:raise ValueError('Profile range')
 if not isinstance(p['rationale'],str) or not 1<=len(p['rationale'].strip())<=300:raise ValueError('Profile rationale')
def load(directory,source,review):
 package=json.loads((directory/'package.json').read_text());meta=json.loads((directory/'metadata.json').read_text());v=package['version'];path=meta['upstreamPath'];validate_problem(package)
 if v!='iamywl-v1-'+hashlib.sha256(path.encode()).hexdigest()[:16] or meta['version']!=v or meta['upstreamCommit']!=COMMIT:raise ValueError('Frozen source identity')
 original=(source/path).resolve()
 if not original.is_relative_to(source.resolve()):raise ValueError('Unsafe source path')
 for name in FILES:
  if hashlib.sha256((original/name).read_bytes()).hexdigest()!=meta['sourceHashes'][name]:raise ValueError('Upstream source changed: '+path)
 if review['upstreamPath']!=path:raise ValueError('Review identity')
 profile_check(review['thinking'])
 evidence=json.loads((directory/'verification.json').read_text());report=evidence['report'];fingerprint=digest(package);reference=hashlib.sha256((directory/'reference.java').read_bytes()).hexdigest()
 if evidence['packageHash']!=fingerprint or evidence['referenceHash']!=reference or report['problem_sha256']!=fingerprint or report['source_sha256']!=reference:raise ValueError('Changed package or reference')
 if report['verdict']!='AC' or not report['judge_all'] or len(report['tests'])!=len(package['tests']) or any(t['verdict']!='AC' for t in report['tests']):raise ValueError('Incomplete passing evidence')
 expected=checked_profile(LANGUAGES['JAVA']|{'testWallSeconds':10},'JAVA',LANGUAGES['JAVA']['image'])
 if report['execution_profile']!=expected or report['runner_environment']['contract']!=contract():raise ValueError('Runner contract/budgets changed')
 if [t['id'] for t in report['tests']]!=[t['id'] for t in package['tests']]:raise ValueError('Test identity mismatch')
 if len(canonical(package).encode())>3*1024*1024:raise ValueError('Package exceeds worker transport budget')
 if package['output_policy']!='TOKEN_EXACT' or package.get('generated') or package.get('api'):raise ValueError('Only frozen STDIO supported')
 if len(package['samples'])!=1 or any(package['samples'][0][k]!=package['tests'][0][k] for k in ('input','output')):raise ValueError('Public sample mismatch')
 if package['tests'][0]['input'].split()!=(original/'sample_input.txt').read_text().split():raise ValueError('Supplied sample input omitted')
 hidden=(original/'input.txt').read_text().split()
 retained=any(t['input'].split()==hidden for t in package['tests'][1:])
 if not retained:
  split=[t for t in package['tests'] if t['id'].startswith('upstream-hidden-')]
  if len(split)!=10 or any(t['input'].split()[0]!='1' for t in split) or ['10']+[x for t in split for x in t['input'].split()[1:]]!=hidden:raise ValueError('Supplied hidden input omitted')
 for name,key in [('sample_output.txt','samples'),('output.txt','hidden')]:
  count=len(re.findall(r'^#\d+(?:\s|$)',(original/name).read_text(),re.M))
  if count!=meta['sourceCaseCounts'][key] or count!={'samples':2,'hidden':10}[key]:raise ValueError('Missing supplied cases')
 category=CATEGORIES.get(meta['originalCategory'],meta['originalCategory'])
 if not category or re.search('[A-Za-z]',category):raise ValueError('Non-Korean category')
 if 'https://github.com/iamywl/problemset/tree/'+COMMIT+'/'+path not in package['statement']:raise ValueError('Missing attribution')
 return package,meta,review['thinking'],category,evidence

def stage(root,source,profiles):
 directories=sorted(p for p in root.iterdir() if (p/'package.json').is_file())
 if len(directories)!=358 or len(profiles)!=358:raise ValueError('Entire 358-problem reviewed release required')
 lines=['BEGIN;',"SELECT pg_advisory_xact_lock(hashtext('iamywl-problemset-v1-release')); "]
 manifest=[];seen=set();image=LANGUAGES['JAVA']['image']
 for directory in directories:
  version=directory.name;package,meta,p,category,evidence=load(directory,source,profiles[version]);version=package['version']
  if version in seen:raise ValueError('Duplicate version')
  seen.add(version);layer=p['layer'];difficulty='EASY' if layer<=2 else 'MEDIUM' if layer<=5 else 'HARD' if layer<=7 else 'EXPERT'
  limits={'JAVA':10,'CPP':10,'PYTHON':20,'analysis':'Java reference measured against supplied case batches in pinned Runner at 10 seconds per batch; C++ and Python budgets are conservative estimates, not measured reference evidence.'}
  values=dict(id=version,package_json=canonical(package),package_sha256=digest(package),runtime_image=image,runner_policy='java8-judge-v1',catalog_category=category,catalog_tags=category,catalog_difficulty=difficulty,time_limits_json=canonical(limits))
  same=' AND '.join(k+' IS NOT DISTINCT FROM '+quote(v) for k,v in values.items())+' AND owner_id IS NULL AND ready=true AND diagnostic_only=false AND review_hold=false AND shared=true'
  lines.append('DO $corpus$ BEGIN IF EXISTS (SELECT 1 FROM problem_version WHERE id='+quote(version)+') AND NOT EXISTS (SELECT 1 FROM problem_version WHERE '+same+") THEN RAISE EXCEPTION 'Existing imported problem differs'; END IF; END $corpus$;")
  lines.append('INSERT INTO problem_version('+','.join(values)+',ready,diagnostic_only,shared) SELECT '+','.join(quote(v) for v in values.values())+',true,false,true WHERE NOT EXISTS(SELECT 1 FROM problem_version WHERE id='+quote(version)+');')
  numbers=','.join(str(p[key]) for key in ('layer','insight','implementation','edgeCases'))
  criteria='problem_version='+quote(version)+' AND package_sha256='+quote(values['package_sha256'])+' AND layer='+str(layer)+' AND insight='+str(p['insight'])+' AND implementation='+str(p['implementation'])+' AND edge_cases='+str(p['edgeCases'])+' AND rationale='+quote(p['rationale'])+" AND source='CURATED_ESTIMATE' AND assessment_kind='IMPORT'"
  lines.append('DO $corpus$ BEGIN IF EXISTS (SELECT 1 FROM problem_thinking_profile WHERE problem_version='+quote(version)+') AND NOT EXISTS (SELECT 1 FROM problem_thinking_profile WHERE '+criteria+") THEN RAISE EXCEPTION 'Existing difficulty differs'; END IF; END $corpus$;")
  lines.append('INSERT INTO problem_thinking_profile(problem_version,package_sha256,layer,insight,implementation,edge_cases,rationale,source,assessment_kind) SELECT id,package_sha256,'+numbers+','+quote(p['rationale'])+",'CURATED_ESTIMATE','IMPORT' FROM problem_version WHERE id="+quote(version)+' AND NOT EXISTS(SELECT 1 FROM problem_thinking_profile WHERE problem_version='+quote(version)+');')
  manifest.append(dict(version=version,title=package['title'],category=category,upstreamPath=meta['upstreamPath'],upstreamCommit=COMMIT,packageSha256=values['package_sha256'],referenceSha256=evidence['referenceHash'],thinking=p,adaptations=meta['adaptations']))
 lines.append('COMMIT;');return '\n'.join(lines)+'\n',manifest
if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('root',type=Path);parser.add_argument('--source',type=Path,required=True);parser.add_argument('--profiles',type=Path,required=True);parser.add_argument('--output',type=Path,required=True);parser.add_argument('--manifest',type=Path,required=True);a=parser.parse_args();sql,manifest=stage(a.root,a.source,json.loads(a.profiles.read_text()));a.output.write_text(sql);a.output.chmod(0o600);a.manifest.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n');print('358 verified packages staged; no database changed')
