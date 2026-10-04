"""Build private, hash-fenced reference jobs from an operator inventory and authoring files."""
import argparse,hashlib,json,re
from pathlib import Path

def canonical(v):return json.dumps(v,ensure_ascii=False,sort_keys=True,separators=(',',':'))
def digest(v):return hashlib.sha256(canonical(v).encode()).hexdigest()
def write(path,value):
 temporary=path.with_suffix('.tmp');temporary.write_text(canonical(value)+'\n');temporary.replace(path)

def callable_learner_java(source):
 # Hybrid authoring artifacts are executable Main bundles; the pinned callable Runner
 # supplies its own Main.java. Preserve only the preceding learner implementation.
 marker=re.search(r'^public class Main\s*\{',source,re.MULTILINE)
 if marker:
  learner=source[:marker.start()].rstrip()+'\n'
  if not re.search(r'\bclass\s+UserSolution\b',learner):raise ValueError('callable artifact missing learner class')
  return learner
 return source

def prepare(root,inventory,output):
 refs={};slow={};intent={}
 for folder in (root/'docs/basic-pool-v1-production/problems').iterdir():
  if not (folder/'package.json').exists():continue
  pack=json.loads((folder/'package.json').read_text());v=pack['version'];meta=json.loads((folder/'metadata.json').read_text());intent[v]=meta
  refs[v]={l:(folder/'solutions'/l.lower()/name).read_text() for l,name in [('JAVA','Main.java'),('CPP','Main.cpp'),('PYTHON','Main.py')]}
  if (folder/'slow.json').exists():slow[v]={l:(folder/path).read_text() for l,path in json.loads((folder/'slow.json').read_text()).items()}
 for folder in (root/'.state/problemset-candidates').iterdir():
  if not (folder/'package.json').exists():continue
  pack=json.loads((folder/'package.json').read_text());v=pack['version'];refs[v]={'JAVA':(folder/'reference.java').read_text()};intent[v]=json.loads((folder/'metadata.json').read_text())
 for path in [*(root/'diagnostics').glob('*.json'),*(root/'diagnostics/private').glob('*.json'),*(root/'diagnostics/private/exam-ab-v2').glob('*.json')]:
  try:bank=json.loads(path.read_text())
  except ValueError:continue
  for item in bank.get('items',[]):
   if 'problem' not in item:continue
   v=item['problem']['version'];languages=item.get('languages',{});entry={l:x['correct'] for l,x in languages.items() if isinstance(x,dict) and 'correct' in x}
   if item.get('reference'):entry.setdefault('JAVA',item['reference'])
   if entry:refs[v]=entry
   if item.get('id','').startswith('B'):
    folder=root/'docs/GamjaOJ_Diagnostic_AB_64_v2/problems'/item['id']
    cpp=(folder/'stdio-equivalent/Main.cpp').read_text();py=(folder/'stdio-equivalent/Main.py').read_text()
    assert '\nint main()' in cpp and '\nSPECS=' in py
    refs[v]['CPP']=cpp.rsplit('\nint main()',1)[0]+'\n'
    refs[v]['PYTHON']=py.split('\nSPECS=',1)[0]+'\n'
   intent[v]=item.get('rubric',{})
   if isinstance(languages,dict):
    s={l:x['slow'] for l,x in languages.items() if isinstance(x,dict) and x.get('slow')}
    if s:slow[v]=s
 for version,folder in [('sum-v1',''),('total-v1','total'),('valid-parentheses-v1','valid-parentheses')]:
  refs[version]={'JAVA':(root/'examples'/folder/'Main.java').read_text()}
 refs['sum-v1'].update(CPP='#include <iostream>\nint main(){long long a,b;std::cin>>a>>b;std::cout<<a+b;}\n',PYTHON='a,b=map(int,input().split());print(a+b)\n')
 refs['total-v1'].update(CPP='#include <iostream>\nint main(){long long n,v,s=0;std::cin>>n;while(n--){std::cin>>v;s+=v;}std::cout<<s;}\n',PYTHON='import sys\na=list(map(int,sys.stdin.buffer.read().split()));print(sum(a[1:]))\n')
 refs['valid-parentheses-v1'].update(CPP='#include <iostream>\n#include <string>\nint main(){std::string s;std::cin>>s;int n=0;for(char c:s){n+=c==\'(\'?1:-1;if(n<0){std::cout<<"NO";return 0;}}std::cout<<(n==0?"YES":"NO");}\n',PYTHON='s=input().strip();n=0\nfor c in s:\n n+=1 if c=="(" else -1\n if n<0:break\nprint("YES" if n==0 else "NO")\n')
 output.mkdir(parents=True,exist_ok=True);jobs=[];missing={l:0 for l in ['JAVA','CPP','PYTHON']}
 for row in (json.loads(s) for s in inventory.open()):
  v=row['version'];pack=row['package'];assert digest(pack)==row['packageHash'],v
  sources=refs.get(v,{})
  if not sources and v=='diagnostic-algo-mix-a-v2-safe-presentation-order-v2':sources=refs.get('diagnostic-algo-mix-a-v2-smallest-valid-order-v1',{})
  if row.get('reference'):sources={**sources,'JAVA':row['reference']}
  authored={};override=output.parent/'reference-overrides'/(v+'.json')
  if override.exists():
   authored=json.loads(override.read_text())
   if authored.get('packageHash')!=row['packageHash']:raise ValueError('stale authored reference: '+v)
   additions=authored.get('references',{})
   if not additions or set(additions)-{'JAVA','CPP','PYTHON'} or any(not isinstance(s,str) or not s.strip() for s in additions.values()):raise ValueError('invalid authored references: '+v)
   sources={**sources,**additions}
  if row.get('slow'):slow[v]={**slow.get(v,{}),'JAVA':row['slow']}
  if authored.get('slowReferences'):
   witnesses=authored['slowReferences']
   if set(witnesses)-{'JAVA','CPP','PYTHON'} or any(not isinstance(s,str) or not s.strip() for s in witnesses.values()):raise ValueError('invalid inefficient references: '+v)
   slow[v]={**slow.get(v,{}),**witnesses}
  for l in missing:
   if l not in sources:missing[l]+=1
  job=dict(version=v,packageHash=row['packageHash'],problem=pack,oldLimits=row['limits'],diagnostic=row['diagnostic'],references=sources,slow=slow.get(v,{}),intent=intent.get(v,pack.get('semantics',{})))
  if 'api' in pack:
   job['references']={l:callable_learner_java(s) if l=='JAVA' else s for l,s in job['references'].items()}
   job['slow']={l:callable_learner_java(s) if l=='JAVA' else s for l,s in job['slow'].items()}
  if authored.get('allowedReferences'):job['allowedReferences']=authored['allowedReferences']
  witness=output.parent/'witness-overrides'/(v+'.json')
  if witness.exists():
   evidence=json.loads(witness.read_text())
   if evidence.get('packageHash')!=row['packageHash'] or not (evidence.get('tests') or evidence.get('generated')) or not evidence.get('review'):raise ValueError('stale or incomplete audit witnesses: '+v)
   job.update(auditTests=evidence.get('tests',[]),auditReview=evidence['review'])
   if evidence['review'].get('efficiencyRequired') is True:job['intent']['efficiencyRequired']=True
   if evidence.get('generated'):
    if pack.get('generated'):raise ValueError('audit generator cannot replace an existing problem generator: '+v)
    job['auditGenerated']=evidence['generated']
  write(output/(v+'.json'),job);jobs.append(dict(version=v,packageHash=row['packageHash'],languages=list(sources),generated=bool(pack.get('generated')),callable='api' in pack,diagnostic=row['diagnostic']))
 summary=dict(total=len(jobs),missing=missing,problems=jobs)
 write(output.parent/'coverage.json',summary);print(canonical(dict(total=len(jobs),missing=missing)))
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--inventory',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();prepare(Path(__file__).resolve().parents[1],a.inventory,a.output)
