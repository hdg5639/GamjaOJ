"""Fixed B pilot: related observable skills, different tasks; no calibrated equivalence claim."""
import json
from pathlib import Path
from diagnostics.build_core_bank import make

def bank():
    items=[]
    def add(slug,category,difficulty,title,statement,cases,body,skill,negative,mutant,extra):
        item=make(slug,category,difficulty,title,statement,cases,body,skill,negative,mutant)
        item['problem']['version']='diagnostic-core-b-'+slug+'-v1'
        item['additionalMutant']=item['reference'].replace(*extra)
        item['rubric']['unobservable']+=['A/B correspondence does not prove equivalent difficulty or improvement.']
        items.append(item)
    body='int t=s.nextInt()*60+s.nextInt(),d=s.nextInt();int v=((t-d)%1440+1440)%1440;System.out.println((v/60)+" "+(v%60));'
    add('before','implementation','EASY','알림을 울린 시각','현재 시각 H M보다 D분 전의 시각을 24시간제로 출력하세요. 날짜는 출력하지 않습니다.\n입력: H M D (0≤H≤23, 0≤M≤59, 0≤D≤100000).\n출력: 시와 분을 공백으로 구분하세요.',
        [(f'{h} {m} {d}\n',f'{((h*60+m-d)%1440)//60} {(h*60+m-d)%60}') for h,m,d in [(10,30,45),(0,0,1),(0,0,0),(23,59,1440),(2,10,100000),(0,10,20)]],body,'Unit conversion and backward day normalization','Inspect negative remainder handling; new subtraction requirement is not difficulty-equated with A.',body.replace('((t-d)%1440+1440)%1440','(t+d)%1440'),('((t-d)%1440+1440)%1440','(t-d)%1440'))
    cases=[]
    for cap,initial,changes in [(5,2,[3,1,-4,-2,2]),(1,0,[-1,1,1,-1]),(10,10,[-10,10]),(3,1,[5,-1,1,-5,2]),(1000,500,[1]*1000),(10,0,[10,-10,10])]:
        x=initial
        for delta in changes:
            if 0<=x+delta<=cap:x+=delta
        cases.append((f'{cap} {initial} {len(changes)}\n'+ ' '.join(map(str,changes))+'\n',x))
    body='int c=s.nextInt(),x=s.nextInt(),n=s.nextInt();while(n-->0){int d=s.nextInt();if(x+d>=0&&x+d<=c)x+=d;}System.out.println(x);'
    add('storage','implementation','MEDIUM','보관함의 재고','최대 C개를 보관할 수 있는 보관함에 처음 X개가 있습니다. N개의 수량 변화 D를 순서대로 적용합니다. 적용 결과가 0 미만이거나 C를 넘으면 그 변화 전체를 무시하고 다음 변화로 넘어갑니다.\n입력: C X N, 다음 줄 N개의 D. 1≤C,N≤1000, 0≤X≤C, -1000≤D≤1000.\n출력: 마지막 수량.',cases,body,'Sequential state with rejected transitions','Check ignoring the whole invalid update rather than clamping or terminating.',body.replace('if(x+d>=0&&x+d<=c)x+=d;','x=Math.max(0,Math.min(c,x+d));'),('x+d<=c','x+d<c'))
    arrays=[[1,2,2,3,4],[7],[5,4,3],[1]*1000,list(range(1000)),[-5,-2,0,-1,1,2,3],[1,9,2,3,4]]
    cases=[]
    for a in arrays:
        best=max(j-i+1 for i in range(len(a)) for j in range(i,len(a)) if all(a[k]>a[k-1] for k in range(i+1,j+1))) if len(a)<20 else (1000 if len(set(a))==1000 else 1)
        cases.append((str(len(a))+'\n'+' '.join(map(str,a))+'\n',best))
    body='int n=s.nextInt(),prev=s.nextInt(),cur=1,best=1;while(--n>0){int x=s.nextInt();cur=x>prev?cur+1:1;best=Math.max(best,cur);prev=x;}System.out.println(best);'
    add('rising','arrays-strings','EASY','연속해서 증가하는 구간','정수 수열에서 오른쪽으로 갈 때 매번 값이 엄격히 증가하는 연속 구간의 최대 길이를 출력하세요. 같은 값은 증가가 아닙니다.\n입력: N(1≤N≤1000), 다음 줄 N개의 정수(-1000000000~1000000000).\n출력: 최대 길이. 원소 하나의 길이는 1입니다.',cases,body,'Contiguous state and reset handling','Distinguish contiguous runs from subsequences and strict from non-strict comparison.',body.replace('x>prev','x>=prev'),('cur+1:1','cur+1:cur'))
    cases=[]
    for a,k in [([1,5,2,4],2),([-9,-3,-7],2),([8],1),([10**9]*1000,1000),([-10**9]*1000,999),([7,-10,8,9],2),([1,2,20],1)]:
        cases.append((f'{len(a)} {k}\n'+' '.join(map(str,a))+'\n',max(sum(a[i:i+k]) for i in range(len(a)-k+1))))
    body='int n=s.nextInt(),k=s.nextInt();long[]a=new long[n];for(int i=0;i<n;i++)a[i]=s.nextLong();long best=Long.MIN_VALUE;for(int i=0;i+k<=n;i++){long sum=0;for(int j=i;j<i+k;j++)sum+=a[j];best=Math.max(best,sum);}System.out.println(best);'
    add('window','arrays-strings','MEDIUM','연속 K개의 최대 합','수열에서 연속한 K개의 원소를 반드시 골라 그 합의 최댓값을 출력하세요. 빈 구간은 고를 수 없습니다.\n입력: N K(1≤K≤N≤1000), 다음 줄 N개의 정수(-1000000000~1000000000).\n출력: 최대 합. 합은 32비트 정수 범위를 넘을 수 있습니다.',cases,body,'Inclusive window bounds and integer range','Negative-only inputs and final window matter; O(NK) is allowed, no sliding-window mastery claim.',body.replace('best=Long.MIN_VALUE','best=0'),('i+k<=n','i+k<n'))
    arrays=[[1,2,1,3,2],[7],[1,1,2,2],[0]*1000,list(range(1000)),[-5,5,-5,0,5]]
    body='int n=s.nextInt();Map<Integer,Integer>m=new HashMap<>();while(n-->0){int x=s.nextInt();m.put(x,m.getOrDefault(x,0)+1);}int count=0;for(int v:m.values())if(v==1)count++;System.out.println(count);'
    add('singletons','basic-data-structures','EASY','한 번만 나온 번호','입력된 번호 중 정확히 한 번만 등장한 값의 개수를 출력하세요.\n입력: N(1≤N≤1000), 다음 줄 N개의 정수(-1000000000~1000000000).\n출력: 정확히 한 번 등장한 값의 개수.',[(str(len(a))+'\n'+' '.join(map(str,a))+'\n',sum(a.count(v)==1 for v in set(a))) for a in arrays],body,'Duplicate and frequency handling with equivalent implementations','Frequency matters; set size alone does not answer this task.',body.replace('if(v==1)','if(v>=1)'),('if(v==1)','if(v==2)'))
    logs=[[1,2,-2,-1],[1,2,-1,-2],[-1],[1],[1,-1,2,-2],[1,1,-1,-1],[1]*500+[-1]*500,[1,-2]]
    cases=[]
    for a in logs:
        work=list(a)
        while True:
            index=next((i for i in range(len(work)-1) if work[i]>0 and work[i+1]==-work[i]),None)
            if index is None:break
            del work[index:index+2]
        cases.append((str(len(a))+'\n'+' '.join(map(str,a))+'\n','YES' if not work else 'NO'))
    body='int n=s.nextInt();Deque<Integer>d=new ArrayDeque<>();boolean ok=true;while(n-->0){int x=s.nextInt();if(x>0)d.push(x);else if(d.isEmpty()||d.pop()!=-x)ok=false;}System.out.println(ok&&d.isEmpty()?"YES":"NO");'
    add('nested','basic-data-structures','MEDIUM','작업 시작과 종료 기록','양수 T는 종류 T의 작업 시작, 음수 -T는 종료입니다. 종료는 가장 최근에 시작한 미종료 작업의 종류와 같아야 합니다. 같은 종류를 중첩해서 시작할 수 있습니다. 모든 종료가 올바르고 마지막에 미종료 작업이 없어야 올바른 기록입니다.\n입력: N(1≤N≤1000), 다음 줄 N개의 정수(1≤절댓값≤1000).\n출력: 올바르면 YES, 아니면 NO.',cases,body,'LIFO nesting and type matching','Check unmatched starts, early closes and crossing types; repeated task types are legal.',body.replace('ok&&d.isEmpty()','ok'),('d.pop()!=-x','d.removeLast()!=-x'))
    graphs=[(4,[(1,2),(2,4)],1,4),(1,[],1,1),(4,[(1,2),(3,4)],1,4),(4,[(2,1),(3,2),(4,3)],1,4),(5,[(1,2),(2,3),(3,4),(4,5),(1,5)],1,5),(5,[(1,2),(2,3),(3,1),(3,4),(4,5)],2,5),(20,[(i,i+1) for i in range(1,20)],20,1)]
    graphs.append((20,[(a,b) for a in range(1,21) for b in range(a+1,21)],20,1))
    bfs='int n=s.nextInt(),m=s.nextInt(),start=s.nextInt()-1,end=s.nextInt()-1;boolean[][]g=new boolean[n][n];while(m-->0){int a=s.nextInt()-1,b=s.nextInt()-1;g[a][b]=g[b][a]=true;}int[]d=new int[n];Arrays.fill(d,-1);Queue<Integer>q=new ArrayDeque<>();q.add(start);d[start]=0;while(!q.isEmpty()){int a=q.remove();for(int b=0;b<n;b++)if(g[a][b]&&d[b]<0){d[b]=d[a]+1;q.add(b);}}'
    for medium in [False,True]:
        cases=[]
        for n,edges,start,end in graphs:
            dist=[[0 if i==j else 999 for j in range(n)] for i in range(n)]
            for a,b in edges:dist[a-1][b-1]=dist[b-1][a-1]=1
            for k in range(n):
                for i in range(n):
                    for j in range(n):dist[i][j]=min(dist[i][j],dist[i][k]+dist[k][j])
            value=dist[start-1][end-1];value=value if value<999 else -1
            cases.append((f'{n} {len(edges)} {start} {end}\n'+''.join(f'{a} {b}\n' for a,b in edges),value if medium else 'YES' if value>=0 else 'NO'))
        body=bfs+('System.out.println(d[end]);' if medium else 'System.out.println(d[end]>=0?"YES":"NO");')
        add('hops' if medium else 'connected','basic-search','MEDIUM' if medium else 'EASY','연결을 몇 번 건너야 할까' if medium else '두 지점의 연결',
            '1~N번 지점 사이에 M개의 양방향 연결이 있습니다. S에서 T로 이동하려고 합니다. 같은 연결이나 자기 자신으로의 연결은 주어지지 않습니다.\n입력: N M S T (1≤N≤20, 0≤M≤N(N-1)/2, 1≤S,T≤N), 이후 M줄에 연결된 두 지점.\n출력: '+('최소 연결 이동 횟수, 도달 불가능하면 -1. S=T이면 0.' if medium else '도달 가능하면 YES, 아니면 NO. S=T이면 YES.'),cases,body,'Unweighted shortest hops' if medium else 'Reachability and visited state','Representation differs from A grids; related B graph tasks are dependent observations.',body.replace('g[a][b]=g[b][a]=true','g[a][b]=true'),('d[start]=0','d[start]=1') if medium else ('d[end]>=0','d[end]>0'))
    return {'id':'core-b-v1','title':'핵심 시범 재평가 B','reviewed':False,'reviewStatus':'CANDIDATE_AUTOMATED_CHECKS_ONLY','items':items}
if __name__=='__main__':
    (Path(__file__).parent/'core-b-v1.json').write_text(json.dumps(bank(),ensure_ascii=False,indent=2)+'\n')
