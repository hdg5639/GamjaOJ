"""Validate Algo Mix public input contracts and answers independently of reference programs.

Only intended for small public examples (uses exhaustive enumeration for some tasks).
Private bank artifacts must remain outside version control.
"""
import argparse
from collections import deque
from functools import lru_cache
from itertools import combinations, permutations
import json
from pathlib import Path
import re


def check(number, text):
    rows = text.strip().splitlines()
    head = list(map(int, rows[0].split()))
    def ints(row, size, lo=None, hi=None):
        values = list(map(int, row.split()))
        assert len(values) == size, 'wrong number of integers'
        assert all((lo is None or lo <= v) and (hi is None or v <= hi) for v in values), 'integer out of bounds'
        return values
    def bounds(v, lo, hi):
        assert lo <= v <= hi, 'header out of bounds'
    def lines(size):
        assert len(rows) == size, 'wrong number of input lines'
    n = head[0]
    if number in (1, 2):
        assert len(head) == 2
        k = head[1]
        bounds(n, 1, 4000 if number == 1 else 1000000)
        bounds(k, 1, 1000 if number == 1 else 26)
        s = rows[1]
        assert len(s) == n, f'declared length {n}, actual {len(s)}'
        assert re.fullmatch('[a-z]+', s)
        if number == 2:
            lines(2)
            return [max((b-a for a in range(n) for b in range(a+1,n+1) if len(set(s[a:b])) <= k), default=0)]
        lines(k+2)
        out = []
        for row in rows[2:]:
            l, r, c = row.split(); l, r = int(l), int(r)
            assert 1 <= l <= r <= n and re.fullmatch('[a-z]', c)
            out.append(s[l-1:r].count(c))
        return out
    if number == 3:
        assert len(head) == 1
        bounds(n, 1, 2000); lines(n+1)
        stack, out = [], []
        for row in rows[1:]:
            words = row.split()
            if words[0] == 'ADD':
                assert len(words) == 2 and re.fullmatch('[a-z]{1,12}', words[1])
                stack.append(words[1])
            else:
                assert len(words) == 1 and words[0] in ('CANCEL', 'COUNT')
                if words[0] == 'COUNT': out.append(len(set(stack)))
                elif stack: stack.pop()
        assert out
        return out
    if number in (4, 9, 11, 14, 16):
        lines(2)
        assert len(head) == (3 if number == 9 else 1 if number == 11 else 2)
        maxima = {4:400000,9:16,11:4000,14:20000,16:3000}
        bounds(n,1,maxima[number])
        lo, hi = {4:(-10**9,10**9),9:(0,1023),11:(0,10**9),14:(1,10**9),16:(1,head[-1])}[number]
        a = ints(rows[1],n,lo,hi)
        k = head[1] if len(head)>1 else None
        if number == 4:
            bounds(k,1,n)
            return [sum(min(a[i:i+k]) for i in range(n-k+1))]
        if number == 9:
            bounds(k,0,n); bounds(head[2],0,1023)
            def union(xs):
                v=0
                for x in xs: v |= x
                return v
            return [sum(union(xs)==head[2] for xs in combinations(a,k))]
        if number == 11:
            @lru_cache(None)
            def cost(i):
                return 0 if i==0 else a[i-1]+min(cost(i-1),cost(i-2) if i>1 else float('inf'))
            return [cost(n)]
        bounds(k,1,10**9)
        if number == 14:
            t=0
            while sum(t//v for v in a)<k: t+=1
            return [t]
        @lru_cache(None)
        def boxes(mask):
            if not mask: return 0
            i=(mask & -mask).bit_length()-1; rest=mask ^ (1<<i)
            return 1+min([boxes(rest)]+[boxes(rest ^ (1<<j)) for j in range(i+1,n) if rest>>j&1 and a[i]+a[j]<=k])
        return [boxes((1<<n)-1)]
    if number in (5,6):
        assert len(head)==2
        h,w=head; bounds(h,1,60); bounds(w,1,60)
        lines(h+(2 if number==5 else 1)); grid=rows[1:h+1]
        assert all(len(r)==w and set(r)<=set('.#' if number==5 else '.#S') for r in grid)
        if number==5:
            sr,sc,tr,tc=ints(rows[-1],4)
            assert 1<=sr<=h and 1<=tr<=h and 1<=sc<=w and 1<=tc<=w
            assert grid[sr-1][sc-1]==grid[tr-1][tc-1]=='.'
            starts=[(sr-1,sc-1)]
        else:
            starts=[(r,c) for r in range(h) for c in range(w) if grid[r][c]=='S']; assert starts
        d={s:0 for s in starts}; q=deque(starts)
        while q:
            r,c=q.popleft()
            for rr,cc in ((r-1,c),(r+1,c),(r,c-1),(r,c+1)):
                if 0<=rr<h and 0<=cc<w and grid[rr][cc]!='#' and (rr,cc) not in d:
                    d[rr,cc]=d[r,c]+1; q.append((rr,cc))
        if number==5: return [d.get((tr-1,tc-1),-1)]
        return [max(d.values()) if len(d)==sum(r.count('.')+r.count('S') for r in grid) else -1]
    if number==8:
        assert len(head)==1
        bounds(n,1,4000); lines(3 if n>1 else 2)
        a=ints(rows[1],n,-10**9,10**9)
        parents=[0,0]+(ints(rows[2],n-1,1,n) if n>1 else [])
        out=[0]*n
        for v in range(1,n+1):
            seen=set(); u=v
            while u:
                assert u not in seen, 'parents contain a cycle'
                seen.add(u); out[u-1]+=a[v-1]; u=parents[u]
        return out
    if number==12:
        assert len(head)==2
        budget=head[1]; bounds(n,1,60); bounds(budget,0,10000); lines(n+1)
        items=[ints(r,2) for r in rows[1:]]
        assert all(1<=w<=10000 and 0<=v<=10**9 for w,v in items)
        return [max(sum(v for w,v in subset) for size in range(n+1) for subset in combinations(items,size) if sum(w for w,v in subset)<=budget)]
    if number==13:
        assert len(head)==2
        q=head[1]; bounds(n,1,2500); bounds(q,1,1000); lines(q+2)
        a=ints(rows[1],n,-10**9,10**9); out=[]
        for row in rows[2:]:
            l,r=ints(row,2,-10**9,10**9); assert l<=r
            out.append(sum(l<=v<=r for v in a))
        return out
    if number==20:
        assert len(head)==1
        bounds(n,1,1500); lines(n+1)
        coords=[ints(r,2,-10**6,10**6) for r in rows[1:]]
        edges=[(u+1,v+1,sum((a-b)**2 for a,b in zip(coords[u],coords[v]))) for u in range(n) for v in range(u+1,n)]
    else:
        assert number in (7,10,15,17,18,19)
        assert len(head)==(3 if number in (17,18) else 2)
        m=head[1]
        bounds(n,1,{7:200,10:8,15:200,17:35,18:3000,19:100}[number])
        bounds(m,0,{7:1000,10:n*(n-1)//2,15:1000,17:800,18:3000,19:3000}[number])
        q=head[2] if number==17 else 0
        if number==17: bounds(q,1,1000)
        if number==18: bounds(head[2],1,n)
        lines(1+m+q)
        edges=[ints(r,3 if number in (17,18,19) else 2) for r in rows[1:m+1]]
        assert all(1<=e[0]<=n and 1<=e[1]<=n for e in edges)
        if number in (7,10,15,19): assert all(e[0]!=e[1] for e in edges)
        if number in (17,18,19): assert all(0<=e[2]<=10**9 for e in edges)
    if number==10:
        assert all(a<b for a,b in edges) and len(set(map(tuple,edges)))==len(edges)
        banned={frozenset(e) for e in edges}
        return [sum(all(frozenset((a,b)) not in banned for a,b in zip(p,p[1:])) for p in permutations(range(1,n+1)))]
    if number==15:
        out=[]; remaining=set(range(1,n+1))
        while remaining:
            allowed=[v for v in remaining if all(u not in remaining for u,w in edges if w==v)]
            if not allowed: return [-1]
            v=min(allowed); out.append(v); remaining.remove(v)
        return out
    if number in (7,19,20):
        parent=list(range(n+1))
        def root(v):
            while parent[v]!=v: v=parent[v]
            return v
        cost=0
        for e in sorted(edges,key=lambda e:e[2] if len(e)==3 else 0):
            a,b=root(e[0]),root(e[1])
            if a!=b:
                parent[a]=b
                if len(e)==3: cost+=e[2]
        groups=[root(v) for v in range(1,n+1)]
        if number==7: return [len(set(groups)),max(groups.count(v) for v in groups)]
        return [cost if len(set(groups))==1 else -1]
    # Repeated edge relaxation, independent of the Floyd/Dijkstra references.
    def distances(start):
        d=[float('inf')]*(n+1); d[start]=0
        for _ in range(n-1):
            for u,v,c in edges: d[v]=min(d[v],d[u]+c)
        return [-1 if x==float('inf') else x for x in d]
    if number==18: return distances(head[2])[1:]
    out=[]
    for row in rows[m+1:]:
        s,t=ints(row,2,1,n); out.append(distances(s)[t])
    return out


def audit(bank):
    failures=[]; count=0
    for number,item in enumerate(bank['items'],1):
        for index,test in enumerate(item['problem']['tests']):
            if index and not test['id'].startswith('EX'): break
            count+=1
            try:
                output=check(number,test['input'])
                assert list(map(str,output))==test['output'].split(), 'answer mismatch'
            except (AssertionError,ValueError,IndexError) as e:
                failures.append(f'Question {number} {test["id"]}: {e}')
    return {'questions':len(bank['items']),'examples':count,'failures':failures}


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bank',type=Path,required=True)
    args=parser.parse_args()
    result=audit(json.loads(args.bank.read_text()))
    print(json.dumps(result,ensure_ascii=False,indent=2))
    raise SystemExit(bool(result['failures']))
