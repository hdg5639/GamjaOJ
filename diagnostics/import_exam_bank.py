"""Convert a private A/B authoring package using server-generated callable bundles.
No DB writes or approval; solutions, tests, images stay in ignored private artifacts.
"""
import argparse,hashlib,json,sys,uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
from runner.judge import validate_problem

def canonical(value):return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'))
def convert(root,output):
 output.mkdir(parents=True,exist_ok=True)
 presentation=json.loads((root/'presentation/manifest.json').read_text())
 images={p['id']:p for p in presentation['problems'] if p['illustrated']}
 for authoring in sorted((root/'banks').glob('*.authoring.json')):
  bank=json.loads(authoring.read_text());items=[]
  for entry in bank['items']:
   folder=root/entry['directory'];meta=json.loads((folder/'metadata.json').read_text())
   statement=(root/'presentation/problems'/entry['id']/'statement.md').read_text()
   image=None
   if entry['id'] in images:
    record=images[entry['id']];image_id=str(uuid.uuid5(uuid.NAMESPACE_URL,'gamjaoj:'+meta['version']+':diagram'))
    statement=statement.replace('](diagram.svg)','](/api/problem-images/'+image_id+')')
    image=dict(id=image_id,alt=record['alt'],caption='',file='images/'+entry['id']+'.png',width=800,height=320)
   tests=json.loads((folder/'tests/manifest.json').read_text());problem=dict(version=meta['version'],title=meta['title'],statement=statement,output_policy='TOKEN_EXACT')
   languages={}
   if meta['type']=='A':
    problem['tests']=[{k:t[k] for k in ('id','input','output')} for t in tests]
    for lang,name in [('JAVA','Main.java'),('CPP','Main.cpp'),('PYTHON','Main.py')]:languages[lang]={'correct':(folder/'reference'/name).read_text()}
   else:
    bundle=json.loads((root/'integration/callable'/f"{entry['id']}.json").read_text());problem['api']=bundle
    problem['tests']=[dict(id=t['id'],input=canonical([t['calls']])+'\n',output=t['output']) for t in tests]
    languages['JAVA']={'correct':(folder/'reference/UserSolution.java').read_text()}
    statement=statement.replace('Java 8 `UserSolution.java`','Java 8 `UserSolution.java`')
   validate_problem(problem)
   mutants=[p.read_text() for p in sorted((folder/'mutants').glob('*/Main.py'))];assert len(mutants)==2
   item=dict(id=entry['id'],category=entry['categoryId'],difficulty=entry['role'],problem=problem,rubric=json.loads((folder/'rubric.json').read_text()),thinking={**meta['thinking'],'rationale':meta['difficultyRationale'][:300]},languages=languages,logicMutants=mutants,logicProblem=dict(version=meta['version'],output_policy='TOKEN_EXACT',tests=[{k:t[k] for k in ('id','input','output')} for t in tests]))
   if image:item['image']=image
   items.append(item)
  candidate=dict(id=bank['bankId'],reviewed=False,examType=bank['examType'],setNumber=bank['setNumber'],allocationUnit='WHOLE_SET',items=items)
  assert len(items)==8 and {i['difficulty'] for i in items}=={'CORE','APPLIED'}
  (output/(bank['bankId']+'.json')).write_text(canonical(candidate)+'\n')
 print('Converted 8 private unreviewed banks / 64 problems; B Java callable remains Java-only')
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--package',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();convert(a.package,a.output)
