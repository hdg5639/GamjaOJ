"""Reproducible candidate A bank. Never marks pedagogical review complete."""
import itertools
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent

def source(body):
    return 'import java.util.*; public class Main { public static void main(String[] args) { Scanner s=new Scanner(System.in); '+body+' } }\n'

def make(slug, category, difficulty, title, statement, cases, body, skill, negative, mutant):
    tests=[{'id':'sample' if i==0 else f'case-{i}', 'input':a, 'output':str(b)+'\n'} for i,(a,b) in enumerate(cases)]
    return {'category':category,'difficulty':difficulty,
            'problem':{'version':'diagnostic-core-a-'+slug+'-v1','title':title,'statement':statement,'output_policy':'TOKEN_EXACT','tests':tests},
            'reference':source(body), 'mutant':source(mutant),
            'rubric':{'version':'1','skills':[skill],
                      'positiveEvidence':['Correct judge results support only the tested input domain.', 'Inspect submitted code and changes before interpreting implementation strategy.'],
                      'negativeEvidence':[negative],
                      'unobservable':['One item cannot establish a general weakness or stable habit.', 'Unsubmitted or skipped work does not establish algorithm knowledge.', 'AC does not identify which algorithm the user understood.'],
                      'evidencePolicy':'Link claims to submission IDs, code spans and test classes; verdict alone is insufficient.',
                      'testClasses':['sample','minimum','boundary','adversarial']}}

def bank():
    items=[]
    items.append(make('clock','implementation','EASY','하루 뒤의 시각',
        '현재 시각 H M에서 D분이 지난 시각을 24시간제로 출력하세요. 날짜는 출력하지 않습니다.\n입력: H M D (0≤H≤23, 0≤M≤59, 0≤D≤100000).\n출력: 시와 분을 공백으로 구분하세요.',
        [(f'{h} {m} {d}\n',f'{((h*60+m+d)//60)%24} {(h*60+m+d)%60}') for h,m,d in [(10,30,45),(0,0,0),(23,59,1),(0,0,1440),(23,59,100000),(1,59,1)]],
        'int h=s.nextInt(),m=s.nextInt(),d=s.nextInt(); int t=h*60+m+d; System.out.println((t/60%24)+" "+(t%60));',
        'Unit conversion and day-boundary normalization', 'A missing day modulo may explain failures only if supported by code and boundary evidence.',
        'int t=s.nextInt()*60+s.nextInt()+s.nextInt(); System.out.println((t/60)+" "+(t%60));'))
    robot_cases=[]
    for n,commands in [(3,'RDLU'),(1,'UDLR'),(2,'UURRDDLL'),(5,'RRRRDDDDLLLLUUUU'),(50,'R'*1000),(3,'LLDDRRUU')]:
        x=y=0
        for c in commands:
            dx,dy={'U':(-1,0),'D':(1,0),'L':(0,-1),'R':(0,1)}[c]
            if 0<=x+dx<n and 0<=y+dy<n:x+=dx;y+=dy
        robot_cases.append((f'{n}\n{commands}\n',f'{x+1} {y+1}'))
    robot='int n=s.nextInt(),r=1,c=1; String a=s.next(); for(char v:a.toCharArray()){int x=r,y=c; if(v==\'U\')x--;if(v==\'D\')x++;if(v==\'L\')y--;if(v==\'R\')y++; if(x>=1&&x<=n&&y>=1&&y<=n){r=x;c=y;}} System.out.println(r+" "+c);'
    items.append(make('robot','implementation','MEDIUM','격자 위 명령',
        'N×N 격자의 (1,1)에서 시작합니다. U,D,L,R은 행 감소, 행 증가, 열 감소, 열 증가입니다. 격자를 벗어나는 명령은 무시하고 다음 명령을 수행합니다.\n입력: 첫 줄 N(1≤N≤50), 다음 줄 명령 문자열(길이 1~1000).\n출력: 마지막 행과 열.',robot_cases,robot,
        'Sequential state updates with rejected transitions','Check whether an invalid move changes state or prevents later valid commands.',robot.replace('x>=1&&x<=n&&y>=1&&y<=n','true')))
    runs=['aabbbcc','a','aaaa','ababab','z'*1000,'ab'+'c'*998]
    items.append(make('runs','arrays-strings','EASY','가장 긴 연속 문자',
        '소문자 문자열에서 같은 문자가 연속해서 나타나는 구간의 최대 길이를 출력하세요.\n입력: 소문자 a~z로 된 문자열 한 줄(길이 1~1000).\n출력: 최대 연속 길이.',
        [(a+'\n',max(len(list(g)) for _,g in itertools.groupby(a))) for a in runs],
        'String a=s.next();int best=1,cur=1;for(int i=1;i<a.length();i++){cur=a.charAt(i)==a.charAt(i-1)?cur+1:1;best=Math.max(best,cur);}System.out.println(best);',
        'Contiguous-run state and reset handling','Distinguish a missing reset from computing global character frequency.',
        'String a=s.next();int[] c=new int[26];int best=0;for(char v:a.toCharArray())best=Math.max(best,++c[v-\'a\']);System.out.println(best);'))
    ranges=[]
    for a,qs in [([1,2,3,4],[(1,4),(2,3),(4,4)]),([-5],[(1,1)]),([10**9]*1000,[(1,1000),(500,1000)]),([-10**9]*1000,[(1,1000)]),([3,-3,0,7],[(1,2),(3,4),(2,2)]),([1]*1000,[(1,1000)]*1000)]:
        ranges.append((f'{len(a)} {len(qs)}\n'+ ' '.join(map(str,a))+'\n'+''.join(f'{l} {r}\n' for l,r in qs),'\n'.join(str(sum(a[l-1:r])) for l,r in qs)))
    prefix='int n=s.nextInt(),q=s.nextInt();long[] p=new long[n+1];for(int i=1;i<=n;i++)p[i]=p[i-1]+s.nextLong();while(q-->0){int l=s.nextInt(),r=s.nextInt();System.out.println(p[r]-p[l-1]);}'
    items.append(make('ranges','arrays-strings','MEDIUM','구간 합 질문',
        '수열에 대한 Q개의 질문에 답하세요. 각 질문 L R에 대해 L번째부터 R번째까지의 합을 출력합니다.\n입력: N Q, 다음 줄 N개의 정수, 이후 Q줄에 L R. 1≤N,Q≤1000, -1000000000≤각 원소≤1000000000, 1≤L≤R≤N.\n출력: 질문 순서대로 합을 한 줄씩 출력하세요. 합은 32비트 정수 범위를 넘을 수 있습니다.',ranges,prefix,
        'Inclusive range indexing and integer range selection','Code/test evidence may support off-by-one or overflow; these limits do not prove prefix-sum complexity.',prefix.replace('p[l-1]','p[l]')))
    arrays=[[1,2,1,3,2],[7],[0]*1000,list(range(1000)),[-5,5,-5,0]]
    items.append(make('distinct','basic-data-structures','EASY','서로 다른 번호',
        '주어진 정수 중 서로 다른 값의 개수를 출력하세요.\n입력: N(1≤N≤1000), 다음 줄 N개의 정수(-1000000000~1000000000).\n출력: 서로 다른 값의 개수.',
        [(str(len(a))+'\n'+' '.join(map(str,a))+'\n',len(set(a))) for a in arrays],
        'int n=s.nextInt();Set<Integer>a=new HashSet<>();while(n-->0)a.add(s.nextInt());System.out.println(a.size());',
        'Duplicate handling; set or equivalent correct strategy','Adjacent-only deduplication fails on separated duplicates; do not require a particular container.',
        'int n=s.nextInt(),p=s.nextInt(),c=1;while(--n>0){int v=s.nextInt();if(v!=p)c++;p=v;}System.out.println(c);'))
    brackets=['([])','([)]','(',']','()[]','['*500+']'*500,'][' , '(()[])']
    def balanced(a):
        while True:
            b=a.replace('()','').replace('[]','')
            if b==a:return 'YES' if not a else 'NO'
            a=b
    stack='String a=s.next();Deque<Character>d=new ArrayDeque<>();boolean ok=true;for(char c:a.toCharArray()){if(c==\'(\'||c==\'[\')d.push(c);else if(d.isEmpty()||d.pop()!=(c==\')\'?\'(\':\'[\')){ok=false;break;}}System.out.println(ok&&d.isEmpty()?"YES":"NO");'
    items.append(make('brackets','basic-data-structures','MEDIUM','두 종류의 괄호',
        '문자열이 올바르게 짝지어진 괄호인지 판단하세요. 괄호는 ()와 []이며 종류와 중첩 순서가 모두 맞아야 합니다.\n입력: (,),[,]만으로 된 문자열 한 줄(길이 1~1000).\n출력: 올바르면 YES, 아니면 NO.',[(a+'\n',balanced(a)) for a in brackets],stack,
        'LIFO nesting, type matching and empty-state handling','Balanced totals alone do not prove correct nesting.',
        'String a=s.next();int x=0,y=0;for(char c:a.toCharArray()){if(c==\'(\')x++;if(c==\')\')x--;if(c==\'[\')y++;if(c==\']\')y--;}System.out.println(x==0&&y==0?"YES":"NO");'))
    grids=[['..','..'],['.'],['.#.','###','...'],['....'],['.','.','.'],['.'*20]*20,['...','.#.','...'],['.#...','.#.#.','...#.','###..','.....']]
    # Independent all-pairs relaxation oracle, unlike reference queue traversal.
    def distance(g):
        h,w=len(g),len(g[0]);dist={(0,0):0}
        for _ in range(h*w):
            old=dict(dist)
            for r in range(h):
                for c in range(w):
                    if g[r][c]=='#':continue
                    ds=[old[(x,y)]+1 for x,y in [(r-1,c),(r+1,c),(r,c-1),(r,c+1)] if (x,y) in old]
                    if ds:dist[r,c]=min(dist.get((r,c),h*w+1),min(ds))
        return dist.get((h-1,w-1),-1)
    bfs='int h=s.nextInt(),w=s.nextInt();char[][]g=new char[h][];for(int i=0;i<h;i++)g[i]=s.next().toCharArray();int[][]d=new int[h][w];for(int[]r:d)Arrays.fill(r,-1);Queue<Integer>q=new ArrayDeque<>();q.add(0);d[0][0]=0;int[]dr={1,-1,0,0},dc={0,0,1,-1};while(!q.isEmpty()){int v=q.remove(),r=v/w,c=v%w;for(int k=0;k<4;k++){int x=r+dr[k],y=c+dc[k];if(x>=0&&x<h&&y>=0&&y<w&&g[x][y]==\'.\'&&d[x][y]<0){d[x][y]=d[r][c]+1;q.add(x*w+y);}}}'
    for medium in [False,True]:
        result='System.out.println(d[h-1][w-1]);' if medium else 'System.out.println(d[h-1][w-1]>=0?"YES":"NO");'
        cases=[(f'{len(g)} {len(g[0])}\n'+'\n'.join(g)+'\n',distance(g) if medium else ('YES' if distance(g)>=0 else 'NO')) for g in grids]
        items.append(make('shortest' if medium else 'reach','basic-search','MEDIUM' if medium else 'EASY','목적지까지 최소 이동' if medium else '목적지에 갈 수 있을까',
            'H×W 격자에서 .은 통로, #은 벽입니다. (1,1)에서 (H,W)로 상하좌우 한 칸씩 이동하며 벽은 통과할 수 없습니다. 시작과 끝은 통로입니다.\n입력: H W(1≤H,W≤20), 이후 H줄에 길이 W의 격자.\n출력: '+('최소 이동 횟수, 도달할 수 없으면 -1. 시작과 끝이 같으면 0.' if medium else '도달 가능하면 YES, 아니면 NO.'),cases,bfs+result,
            'Unweighted shortest path and distance initialization' if medium else 'Reachability, bounds and visited-state handling',
            'Do not infer all graph/DFS proficiency; inspect traversal and boundary evidence.',
            bfs+('System.out.println(d[h-1][w-1]+1);' if medium else 'System.out.println("YES");')))
    return {'id':'core-a-v1','title':'핵심 진단 A','reviewed':False,'reviewStatus':'CANDIDATE_AUTOMATED_CHECKS_ONLY','items':items}

def pilot_bank():
    # Preserve the v1 review artifact; first public pilot has distinct immutable version IDs.
    data=bank()
    data['id']='core-a-v2'
    data['title']='핵심 시범 진단 A'
    for item in data['items']:
        item['problem']['version']=item['problem']['version'].removesuffix('-v1')+'-v2'
    data['items'][0]['problem']['title']='분이 지난 뒤의 시각'
    return data

if __name__=='__main__':
    (ROOT/'core-a-v1.json').write_text(json.dumps(bank(),ensure_ascii=False,indent=2)+'\n')
    (ROOT/'core-a-v2.json').write_text(json.dumps(pilot_bank(),ensure_ascii=False,indent=2)+'\n')
