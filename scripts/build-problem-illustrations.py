#!/usr/bin/env python3
"""Build reviewed, public rule diagrams. Input is a public-statement inventory, never judge packages."""
import argparse, csv, hashlib, html, json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
def sha(s): return hashlib.sha256(s.encode()).hexdigest()
def text(x,y,s,size=18,color='#243443'): return f'<text x="{x}" y="{y}" font-size="{size}" fill="{color}">{html.escape(s)}</text>'
def rect(x,y,w,h,color='#e8eff3'): return f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="7" fill="{color}"/>'
def arrow(x,y,xx,yy): return f'<path d="M{x},{y} L{xx},{yy}" stroke="#35766d" stroke-width="3" fill="none" marker-end="url(#arrow)"/>'
def node(x,y,s): return f'<circle cx="{x}" cy="{y}" r="26" fill="#e3f1eb" stroke="#35766d"/>'+text(x-9,y+6,s)
def cells(x,y,values,colors=None):
 return ''.join(rect(x+i*51,y,46,46,(colors or {}).get(v,'#e8eff3'))+text(x+i*51+13,y+30,str(v)) for i,v in enumerate(values))
def grid(x,y,rows):
 return ''.join(cells(x,y+i*51,list(row),{'#':'#aab9c3','1':'#b9dfb2','2':'#edc597','-1':'#d3dce2'}) for i,row in enumerate(rows))
def four(x,y): return ''.join(arrow(x+dx*9,y+dy*9,x+dx*53,y+dy*53) for dx,dy in [(1,0),(-1,0),(0,1),(0,-1)])

FIGURES=[]
def add(version,alt,caption,body,kind='STRUCTURE'):
 FIGURES.append(dict(version=version,alt=alt,caption=caption,body=body,kind=kind))
add('basic-pool-v1-arrays-strings-easy-02-v1','두 필름을 같은 칸에 겹치는 방식',
 'B 전체를 A 안에 놓습니다. 같은 칸의 서로 다른 글자만 충돌하며, 물음표는 충돌하지 않습니다.',
 text(42,42,'두 줄의 같은 위치를 비교해요')+text(42,104,'A')+cells(95,75,'A?BACA')+text(42,164,'B')+cells(197,135,'B?A')+text(460,106,'같은 글자 → 충돌 없음')+text(460,146,'? 포함 → 충돌 없음')+text(460,186,'서로 다른 글자 → 충돌')+text(42,270,'위 그림은 겹치는 방식만 보여줍니다. 가장 좋은 위치를 나타내지는 않습니다.',16))
add('basic-pool-v1-bfs-easy-01-v1','격자에서 가능한 네 방향 이동과 벽',
 '한 번에 상하좌우로 한 칸 이동합니다. 벽과 격자 밖으로는 이동할 수 없습니다.',
 grid(45,50,['....','..#.','....','.#..'])+four(400,150)+text(480,100,'.  이동할 수 있는 칸')+text(480,142,'#  벽')+text(480,184,'대각선 이동 없음')+text(45,295,'시작 칸도 방문 가능한 칸의 수에 포함됩니다.',17))
add('basic-pool-v1-bfs-hard-02-v1','하나의 명령을 함께 받는 두 로봇',
 '같은 방향 명령에도 벽에 막힌 로봇은 머무르고 다른 로봇은 움직입니다. 같은 칸에 도착하거나 서로 위치를 교환할 수 없습니다.',
 text(42,40,'공통 명령: 오른쪽으로 이동')+text(42,100,'로봇 A')+cells(155,70,['A','#','.'])+text(370,101,'→ A는 벽 앞에 머무름')+text(42,177,'로봇 B')+cells(155,147,['B','.','.'])+arrow(177,211,227,211)+text(370,178,'→ B는 한 칸 이동')+text(42,285,'두 로봇의 이동 결과를 함께 확인합니다. 같은 칸 도착·자리 교환은 금지됩니다.',16))
add('basic-pool-v1-bfs-hard-04-v2','한 번의 이동 거리와 벽으로 끊기는 이동 범위',
 '한 번에 같은 행 또는 열로 1~K칸 이동합니다. 지나가는 모든 칸이 비어 있어야 하며 벽을 넘을 수 없습니다.',
 text(42,42,'이동 규칙을 보여주기 위한 K = 3 상황')+cells(70,90,['S','.','.','#','.','.'])+arrow(92,165,143,165)+arrow(92,191,194,191)+text(390,125,'벽 뒤의 칸은 한 번에 갈 수 없음')+text(70,241,'한 칸 또는 두 칸 이동 가능 · 세 번째 칸은 벽')+text(70,281,'상하좌우 모두 같은 규칙이 적용됩니다.',17))
add('basic-pool-v1-graph-hard-01-v1','방향이 있는 길과 길드 도장의 관계',
 '길에는 이동 비용과 길드 번호가 있습니다. 같은 길드의 가입세는 경로에서 처음 이용할 때만 내며, 길드 0에는 가입세가 없습니다.',
 arrow(130,110,310,110)+arrow(355,120,535,200)+arrow(135,132,525,225)+node(100,110,'A')+node(335,110,'B')+node(565,220,'C')+text(170,85,'비용 w · 길드 1',16)+text(405,128,'비용 w · 길드 1',16)+text(235,223,'비용 w · 길드 0',16)+text(42,290,'길드 1: 첫 이용 때 가입세 + 이동 비용 / 이후 이용은 이동 비용만',17))
add('iamywl-v1-0c468772cccdbfa4','좌표 위의 점과 두 점 사이의 직선 거리',
 '점의 위치는 평면 좌표로 주어집니다. 두 점을 잇는 비용은 두 점 사이의 유클리드 거리입니다. 선택할 연결을 표시한 그림은 아닙니다.',
 arrow(95,265,700,265)+arrow(95,265,95,45)+f'<path d="M190,210 L480,90" stroke="#35766d" stroke-width="3"/>'+node(190,210,'P')+node(480,90,'Q')+text(215,242,'P(x₁, y₁)',16)+text(505,83,'Q(x₂, y₂)',16)+text(310,137,'두 점 사이의 거리',17)+text(708,270,'x')+text(85,35,'y'))
add('iamywl-v1-0d14a02045e8bf29','토마토의 상태와 상하좌우 전파 관계',
 '1은 익은 토마토, 0은 익지 않은 토마토, -1은 빈 칸입니다. 익은 토마토들은 동시에 상하좌우 이웃에 영향을 주며 대각선으로는 퍼지지 않습니다.',
 grid(42,55,[['0','0','0','0'],['0','1','0','0'],['0','0','-1','0'],['0','0','0','0']])+four(390,145)+text(480,90,'1  익은 토마토')+text(480,130,'0  익지 않은 토마토')+text(480,170,'−1  토마토가 없는 칸')+text(42,295,'여러 시작점이 있으면 모두 같은 날부터 퍼집니다. 소요 일수는 표시하지 않습니다.',16))
add('iamywl-v1-22b94e33d994aa40','교차에 포함되는 선분의 세 가지 만남',
 '서로 가로지르는 경우뿐 아니라 끝점이 닿거나 같은 직선 위에서 겹치는 경우도 교차에 포함됩니다.',
 text(42,42,'교차로 보는 관계')+f'<g fill="none" stroke="#35766d" stroke-width="4"><path d="M60,100 L210,230 M60,230 L210,100"/><path d="M290,220 L390,110 L480,220"/><path d="M550,130 L710,130"/><path d="M590,130 L750,130" stroke="#c47b40" stroke-width="2"/></g>'+text(80,270,'가로지름')+text(325,270,'끝점이 닿음')+text(566,270,'같은 직선에서 겹침',16)+text(548,302,'초록 선과 주황 선의 일부가 겹칩니다',11))
add('iamywl-v1-6bbe159f306f3c92','선수과목을 나타내는 방향 연결',
 'A → B는 A를 먼저 이수해야 B를 들을 수 있다는 뜻입니다. 두 과목을 같은 학기에 이수할 수 없으며, 한 학기의 수강 과목 수에는 제한이 없습니다.',
 arrow(135,110,310,110)+arrow(135,127,310,218)+arrow(357,110,535,160)+arrow(357,228,535,180)+node(105,110,'A')+node(335,110,'B')+node(335,230,'C')+node(565,170,'D')+text(170,83,'선수 관계',16)+text(42,295,'화살표 앞의 과목을 이전 학기에 이수해야 합니다.',17))
add('iamywl-v1-94d62cbe23c47c66','섬과 건설 가능한 다리 후보',
 '섬은 점, 건설할 수 있는 다리는 선으로 표시합니다. 숫자는 다리의 비용이며, 그림은 선택 전의 후보 연결을 보여줍니다.',
 f'<g fill="none" stroke="#91a5b3" stroke-width="3"><path d="M120,100 L350,80 L610,185 L120,100 L345,240 L350,80 M345,240 L610,185"/></g>'+''.join(node(x,y,s) for x,y,s in [(120,100,'1'),(350,80,'2'),(345,240,'3'),(610,185,'4')])+text(225,72,'5')+text(485,115,'7')+text(215,200,'3')+text(367,160,'4')+text(465,249,'6')+text(350,127,'8')+text(42,300,'모든 선은 후보입니다. 최소 비용의 다리를 골라 표시한 그림이 아닙니다.',16))
add('iamywl-v1-da2d5cb7ffae00f1','연구소 칸의 종류와 새로 세울 벽 세 개',
 '0은 빈 칸, 1은 기존 벽, 2는 바이러스입니다. 빈 칸에 새 벽을 정확히 세 개 세운 뒤 바이러스가 상하좌우로 퍼집니다.',
 grid(42,55,['0001','0200','1000','0002'])+text(320,90,'0  새 벽을 놓을 수 있는 빈 칸')+text(320,128,'1  기존 벽')+text(320,166,'2  바이러스')+cells(320,192,['벽','벽','벽'])+text(320,265,'위치가 아직 정해지지 않은 새 벽 세 개',16)+text(42,302,'새 벽의 정답 위치나 확산 결과는 표시하지 않습니다.',16))
add('iamywl-v1-ff911ece9cc1639f','커서를 기준으로 문자를 넣고 지우는 편집 명령',
 'TYPE은 커서 왼쪽에 문자를 넣고, DELETE는 커서 왼쪽 문자를 지웁니다. LEFT·RIGHT는 커서를 한 칸 이동하며 문서 밖으로 나가지 않습니다.',
 text(42,43,'편집 명령과 커서의 위치')+text(60,102,'ab│cd',30)+arrow(250,92,385,92)+text(275,68,'TYPE x',15)+text(430,102,'abx│cd',30)+text(60,199,'ab│cd',30)+arrow(250,189,385,189)+text(275,165,'DELETE',15)+text(430,199,'a│cd',30)+text(60,284,'│는 문자가 아닌 커서입니다. 왼쪽에 문자가 없으면 DELETE는 무시합니다.',16),'COMMAND_EXPLANATION')
add('basic-pool-v1-basic-data-structures-hard-02-v1','연속된 빈 객석 예약과 구간 해제 명령',
 'BOOK k는 가장 왼쪽의 연속된 빈 좌석 k개를 예약합니다. FREE l r은 양 끝을 포함해 해당 구간의 좌석을 모두 비웁니다.',
 text(42,42,'BOOK 2가 고르는 좌석')+cells(45,76,['빈','빈','예약','빈','빈','빈'])+f'<path d="M45,139 H142" stroke="#35766d" stroke-width="5"/>'+text(365,106,'가장 왼쪽 두 자리',16)+text(42,188,'FREE l r: 이미 비어 있는 칸도 함께 비워요')+cells(45,215,['빈','예약','예약','빈','예약'])+f'<path d="M96,275 H244" stroke="#35766d" stroke-width="5"/>'+text(365,247,'해제 구간: 양 끝 포함',16),'COMMAND_EXPLANATION')
add('basic-pool-v1-backtracking-medium-02-v1','스위치와 문이 연결되는 상태 규칙',
 '소문자 스위치에 들어가면 해당 비트가 반전됩니다. 대문자 문은 들어가기 직전 해당 비트가 1이어야 통과할 수 있습니다. 처음에는 모든 비트가 0입니다.',
 text(42,42,'같은 글자의 스위치와 문이 연결되어 있어요')+cells(60,88,['a'])+arrow(145,111,305,111)+cells(350,88,['A'])+text(470,105,'a에 들어갈 때: 0 ↔ 1',17)+text(470,144,'A에 들어갈 때: 비트 1 필요',17)+text(60,218,'한 경로에서 방문했던 칸은 다시 밟을 수 없습니다.')+text(60,264,'정확히 L번 이동해야 합니다. 목적지까지의 경로는 표시하지 않습니다.',16))

def build(inventory):
 by={p['version']:p for p in inventory}; manifest=[]; selected=set();
 reviewed=json.loads((ROOT/'problems/illustrations/reviewed-statements-v1.json').read_text()); public=ROOT/'frontend/public/problem-illustrations'; public.mkdir(parents=True,exist_ok=True)
 for f in FIGURES:
  p=by[f['version']]; selected.add(f['version'])
  if reviewed[f['version']] != sha(p['statement']): raise ValueError('Statement changed: review rules again before drawing '+f['version'])
  svg='<svg xmlns="http://www.w3.org/2000/svg" width="800" height="340" viewBox="0 0 800 340" role="img"><title>'+html.escape(f['alt'])+'</title><desc>'+html.escape(f['caption'])+'</desc><defs><marker id="arrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10" fill="#35766d"/></marker></defs><rect width="800" height="340" rx="12" fill="#f8fafb"/><g font-family="Pretendard, Noto Sans KR, Apple SD Gothic Neo, sans-serif">'+f['body']+'</g></svg>\n'
  name=sha(svg)+'.svg';(public/name).write_text(svg)
  manifest.append({k:f[k] for k in ['version','alt','caption','kind']}|dict(statementSha256=sha(p['statement']),file=name,width=800,height=340))
 (ROOT/'backend/src/main/resources/problem-illustrations-v1.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
 # Inventory contains public metadata only. A candidate is not yet an approved drawing.
 with (ROOT/'problems/illustrations/review-v1.csv').open('w',newline='') as stream:
  writer=csv.writer(stream);writer.writerow(['version','title','category','statement_sha256','status','reason'])
  for p in inventory:
   keywords=[w for w in ['격자','상하좌우','좌표','선분','선수과목','다리','커서','BOOK','쿼드트리','연결 관계'] if w in p['statement']]
   status='ILLUSTRATED' if p['version'] in selected else ('REVIEW_NEEDED' if keywords else 'TEXT_FIRST')
   writer.writerow([p['version'],p['title'],p['category'],sha(p['statement']),status,'규칙 검토 완료' if status=='ILLUSTRATED' else ' / '.join(keywords) if keywords else '그림 필요성을 추가 검토한 뒤 제작'])
 print(f'{len(inventory)} public problems reviewed; {len(manifest)} rule diagrams built')
if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('public_inventory',type=Path);args=parser.parse_args();build(json.loads(args.public_inventory.read_text()))
