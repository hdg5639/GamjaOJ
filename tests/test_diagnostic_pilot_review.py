"""Independent fixed-case and release-manifest checks; not learner calibration."""
import hashlib
import json
import unittest
from collections import deque
from pathlib import Path
from diagnostics.build_core_bank import pilot_bank

ROOT=Path(__file__).resolve().parents[1]
class DiagnosticPilotReviewTests(unittest.TestCase):
    def test_reviewed_artifact_and_all_fixed_answers(self):
        path=ROOT/'diagnostics/core-a-v2.json'
        data=json.loads(path.read_text());self.assertEqual(data,pilot_bank())
        for item in data['items']:
            slug=item['problem']['version'].removeprefix('diagnostic-core-a-').removesuffix('-v2')
            for case in item['problem']['tests']:
                tokens=case['input'].split();expected=case['output'].split()
                if slug=='clock':
                    h,m,d=map(int,tokens);self.assertTrue(0<=h<24 and 0<=m<60 and 0<=d<=100000)
                    answer=list(map(str,divmod((h*60+m+d)%1440,60)))
                elif slug=='robot':
                    n=int(tokens[0]);r=c=1;self.assertTrue(1<=n<=50 and 1<=len(tokens[1])<=1000)
                    for command in tokens[1]:
                        self.assertIn(command,'UDLR')
                        if command=='U':r=max(1,r-1)
                        if command=='D':r=min(n,r+1)
                        if command=='L':c=max(1,c-1)
                        if command=='R':c=min(n,c+1)
                    answer=[str(r),str(c)]
                elif slug=='runs':
                    word=tokens[0];self.assertTrue(1<=len(word)<=1000 and all('a'<=c<='z' for c in word))
                    best=0
                    for start in range(len(word)):
                        end=start
                        while end<len(word) and word[end]==word[start]:end+=1
                        best=max(best,end-start)
                    answer=[str(best)]
                elif slug=='ranges':
                    values=list(map(int,tokens));n,q=values[:2];self.assertTrue(1<=n<=1000 and 1<=q<=1000)
                    self.assertEqual(len(values),2+n+2*q);a=values[2:2+n];self.assertTrue(all(abs(x)<=10**9 for x in a))
                    prefix=[0]
                    for x in a:prefix.append(prefix[-1]+x)
                    answer=[]
                    for j in range(2+n,len(values),2):
                        l,r=values[j:j+2];self.assertTrue(1<=l<=r<=n);answer.append(str(prefix[r]-prefix[l-1]))
                elif slug=='distinct':
                    values=list(map(int,tokens));n=values[0];a=sorted(values[1:]);self.assertEqual(n,len(a));self.assertTrue(1<=n<=1000 and all(abs(x)<=10**9 for x in a))
                    answer=[str(1+sum(a[j]!=a[j-1] for j in range(1,n)))]
                elif slug=='brackets':
                    word=tokens[0];self.assertTrue(1<=len(word)<=1000 and all(c in '()[]' for c in word));stack=[];ok=True
                    for c in word:
                        if c in '([':stack.append(c)
                        elif not stack or stack.pop()!={')':'(',']':'['}[c]:ok=False;break
                    answer=['YES' if ok and not stack else 'NO']
                else:
                    h,w=map(int,tokens[:2]);grid=tokens[2:];self.assertTrue(1<=h<=20 and 1<=w<=20);self.assertEqual(len(grid),h)
                    self.assertTrue(all(len(row)==w and set(row)<=set('.#') for row in grid));self.assertEqual(grid[0][0]+grid[-1][-1],'..')
                    queue=deque([(0,0,0)]);seen={(0,0)};distance=-1
                    while queue:
                        r,c,d=queue.popleft()
                        if (r,c)==(h-1,w-1):distance=d;break
                        for x,y in [(r+1,c),(r-1,c),(r,c+1),(r,c-1)]:
                            if 0<=x<h and 0<=y<w and grid[x][y]=='.' and (x,y) not in seen:seen.add((x,y));queue.append((x,y,d+1))
                    answer=[str(distance) if slug=='shortest' else 'YES' if distance>=0 else 'NO']
                self.assertEqual(answer,expected,(slug,case['id']))

    def test_release_requires_exact_reviewed_artifact(self):
        import importlib.util
        spec=importlib.util.spec_from_file_location('release_bank',ROOT/'scripts/release-diagnostic-bank.py')
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        artifact=(ROOT/'diagnostics/core-a-v2.json').read_bytes()
        review=json.loads((ROOT/'diagnostics/core-a-v2-review.json').read_text())
        sql=module.release(artifact,review)
        self.assertIn('LOCK TABLE',sql);self.assertIn("reviewed=true WHERE id='core-a-v2'",sql)
        with self.assertRaises(ValueError):module.release(artifact+b' ',review)
        with self.assertRaises(ValueError):module.release(artifact,{**review,'decision':'CANDIDATE'})
