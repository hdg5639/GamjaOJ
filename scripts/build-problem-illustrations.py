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

# Inline figures are anchored to a reviewed, complete Markdown paragraph.
def add_inline(version,alt,caption,body,anchor_prefix,explanation):
 add(version,alt,caption,body)
 FIGURES[-1].update(anchorPrefix=anchor_prefix,explanation=explanation)

add_inline('basic-pool-v1-bfs-medium-02-v1','문 번호와 진입 가능한 박자의 관계','숫자는 문을 통과할 수 있는 박자를 나타냅니다.',
 text(55,50,'도착 시각의 나머지')+cells(65,95,['0','1','2'])+arrow(243,116,332,116)+text(370,121,'다시 0, 1, 2 순서로 반복')+text(65,215,'문 0 → t mod 3 = 0일 때 진입')+text(65,251,'문 1 → t mod 3 = 1일 때 진입')+text(65,287,'문 2 → t mod 3 = 2일 때 진입'),
 '격자는 .,#,0,1,2로 구성된다.', '그림의 0·1·2는 들어가려는 문의 번호입니다. 이동하거나 기다리면 시각이 1 증가하므로 도착 시각을 기준으로 문을 확인합니다. 이미 문 칸에 있다면 문이 닫혀도 그 칸에서 기다릴 수 있습니다.')
add_inline('basic-pool-v1-bfs-medium-03-v1','중간 칸과 착지 칸이 다른 도약 규칙','도약은 정확히 두 칸이며 이동 한 번으로 셉니다.',
 text(50,42,'중간 칸은 벽이어도 지나갈 수 있어요')+cells(65,72,['S','#','.'])+arrow(87,138,189,138)+text(340,112,'착지 칸이 빈 칸 → 가능')+text(50,206,'착지할 칸은 비어 있어야 해요')+cells(65,231,['S','.','#'])+arrow(87,297,189,297)+text(340,271,'착지 칸이 벽 → 불가능'),
 '평소에는 상하좌우 한 칸 이동한다.', '위 그림에서는 중간 벽을 건너 빈 칸에 착지할 수 있습니다. 아래 그림에서는 두 번째 칸이 벽이므로 도약할 수 없습니다. 이 도약은 전체 이동 중 최대 한 번만 사용할 수 있습니다.')
add_inline('basic-pool-v1-dfs-easy-03-v1','붙어 있는 유리 칸과 밖에 드러난 변','변을 공유한 칸만 연결되며 바깥에 드러난 변이 둘레입니다.',
 grid(55,55,['##..','.#..','..#.','....'])+f'<path d="M55,55 H152 V157 H106 V106 H55 Z" fill="none" stroke="#c47b40" stroke-width="4"/>'+text(330,106,'상하좌우로 붙은 #은 연결')+text(330,157,'대각선으로만 닿은 #은 별개')+text(330,208,'주황 선: 밖에 드러난 변')+text(330,260,'칸 사이의 공유한 변은 둘레에서 제외',16),
 '격자의 # 칸들을 상하좌우 연결로 묶는다.', '위쪽 유리 칸들은 변을 공유하므로 연결되지만, 오른쪽 아래 칸은 대각선으로만 닿아 별개의 묶음입니다. 둘레를 셀 때는 같은 묶음의 두 칸 사이에 있는 변을 포함하지 않습니다.')
add_inline('basic-pool-v1-mst-easy-02-v1','기존 나무 모양 다리와 추가된 다리','실선은 기존 다리, 점선은 새로 추가된 다리입니다.',
 f'<path d="M110,100 L325,80 L325,250 L600,210" fill="none" stroke="#91a5b3" stroke-width="3"/>'+f'<path d="M110,100 L325,250" fill="none" stroke="#c47b40" stroke-width="3" stroke-dasharray="8 6"/>'+''.join(node(x,y,s) for x,y,s in [(110,100,'1'),(325,80,'2'),(325,250,'3'),(600,210,'4')])+text(70,220,'새 다리',16)+text(460,100,'기존 다리는 연결된 트리',16),
 'N정점의 비용 있는 트리와 추가 간선', '그림에서 기존 다리만 보면 섬들이 나무 모양으로 연결되어 있습니다. 새 다리가 후보에 더해져도 기존 다리를 모두 유지할 필요는 없으며, 모든 섬이 연결되도록 사용할 다리를 선택합니다.')
add_inline('iamywl-v1-2c6b7f10a64c0952','정사각형 종이를 같은 크기의 네 영역으로 나누는 방식','가로와 세로를 각각 절반으로 나눕니다.',
 rect(65,45,260,260,'#e8eff3')+f'<path d="M195,45 V305 M65,175 H325" fill="none" stroke="#35766d" stroke-width="3"/>'+text(90,120,'좌상단')+text(220,120,'우상단')+text(90,245,'좌하단')+text(220,245,'우하단')+text(400,113,'각 영역의 크기: N/2 × N/2')+text(400,170,'같은 색이면 더 나누지 않음')+text(400,227,'색이 섞였으면 다시 네 영역으로 나눔',17),
 'N x N 크기의 정사각형 색종이가 주어집니다.', '그림의 네 영역은 서로 겹치지 않고 원래 종이 전체를 덮습니다. 각 영역에서도 같은 규칙을 적용하며, 이 문제에서 세는 대상은 최종적으로 남은 파란색 종이입니다.')
add_inline('iamywl-v1-3d2b15cb2291497e','아파트 단지를 연결하는 상하좌우 관계','1은 집, 0은 빈 곳입니다.',
 grid(55,55,['1100','0100','0010','0000'])+four(395,160)+text(485,102,'변을 공유한 집은 같은 단지')+text(485,156,'대각선만 닿은 집은 연결되지 않음',16)+text(485,211,'빈 곳은 집을 연결하지 않음',17),
 '정사각형 모양의 N×N 지도에 아파트들이', '위쪽 집들은 상하좌우로 이어져 있습니다. 오른쪽 아래의 집은 위쪽 집과 대각선으로만 닿으므로 그 관계만으로 같은 단지가 되지 않습니다.')
add_inline('iamywl-v1-5b1320e876d2e6e9','트럭 대기열과 다리의 동시 진입 제한','동시에 올라간 트럭 수와 무게 합을 모두 확인합니다.',
 text(45,50,'대기 중')+cells(45,100,['a','b'])+arrow(162,124,245,124)+rect(270,90,390,95,'#e8eff3')+rect(300,115,90,45,'#c2ded4')+text(330,145,'p')+rect(460,115,90,45,'#c2ded4')+text(490,145,'q')+text(285,215,'다리 위 트럭 수 ≤ W')+text(285,258,'다리 위 트럭 무게의 합 ≤ L')+text(45,307,'a·b·p·q는 트럭을 구분하기 위한 기호입니다.',15),
 '[복합 제약조건 및 알고리즘 최적화]', '대기열의 트럭은 주어진 순서대로 다리에 들어갑니다. 다리 위 트럭 수에 여유가 있어도 무게 합이 L을 넘으면 새 트럭은 들어갈 수 없으며, 진입한 트럭이 빠져나오기까지는 W초가 걸립니다.')
add_inline('iamywl-v1-7d365588567be2ec','카메라의 직선 감시 범위와 벽','카메라는 빈 칸에 놓고, 벽을 만나면 그 방향의 감시가 끝납니다.',
 grid(55,50,['00000','00100','00C20','00000','00100'])+arrow(180,160,180,77)+arrow(180,175,180,274)+arrow(165,175,78,175)+arrow(188,175,203,175)+text(380,105,'C: 빈 칸에 설치한 카메라',17)+text(380,150,'1: 서버 / 2: 벽')+text(380,195,'상하좌우 직선 방향만 감시')+text(380,240,'벽 뒤·대각선은 감시하지 않음',16),
 'N x M 크기의 연구실에서 중요한 데이터 서버', '그림에서 카메라의 오른쪽에는 벽이 있으므로 그 뒤쪽까지 감시할 수 없습니다. 위아래와 왼쪽은 벽을 만나기 전까지 감시하며, 그림은 설치 규칙을 보여주는 임의의 배치입니다.')
add_inline('iamywl-v1-9ab31c8992ff67a4','사람과 불의 이동 방향 및 경계 밖 탈출','사람은 통로로 이동하고 불은 인접 통로 모두로 번집니다.',
 grid(55,55,['.J..','..#.','.F..','....'])+arrow(129,65,129,22)+four(390,150)+text(480,95,'J: 사람의 시작 위치')+text(480,138,'F: 불 / #: 벽 / .: 통로')+text(480,181,'상하좌우 이동과 확산')+text(480,225,'탈출은 격자의 경계 밖으로 이동',16)+text(55,304,'이미 불이 있거나 같은 시각에 불이 도달하는 칸에는 갈 수 없습니다.',16),
 '연구소에 불이 났다.', '위쪽 화살표는 격자의 가장자리에 있는 사람이 경계 밖으로 나가는 것을 나타냅니다. 불도 매초 함께 번지므로 사람이 이동할 칸에는 이미 불이 있거나 그 시각에 불이 도달해서는 안 됩니다.')
add_inline('iamywl-v1-f16065dcccde0087','파손된 칸이 있는 두 줄 벽면과 도미노 모양','도미노 한 개는 변을 공유한 정상 칸 두 개를 덮습니다.',
 grid(55,65,['..#...','......'])+text(55,220,'벽면: 2 × N / #은 파손된 칸')+rect(440,65,42,90,'#c2ded4')+rect(555,85,90,42,'#c2ded4')+text(410,205,'세로로 놓기',16)+text(545,205,'가로로 놓기',16)+text(390,265,'파손된 칸에 놓거나 블록을 겹칠 수 없음',16),
 '2 x N 크기의 직사각형 벽면을', '그림의 블록은 가로 또는 세로로 칸 두 개를 덮는 모양입니다. 파손된 칸은 그대로 남기고, 정상 칸들만 빈틈이나 겹침 없이 모두 채워야 합니다.')
add_inline('iamywl-v1-73b8cf3c4ff384f1','도착하는 칸에 따라 달라지는 벽 파괴 비용','0은 빈 칸, 1은 부술 수 있는 벽입니다.',
 text(55,50,'빈 칸으로 들어갈 때')+cells(65,82,['현재','0'])+arrow(192,105,300,105)+text(350,112,'추가 비용 0')+text(55,205,'벽이 있는 칸으로 들어갈 때')+cells(65,237,['현재','1'])+arrow(192,260,300,260)+text(350,267,'추가 비용 1 · 벽 한 개 파괴'),
 'R x C 크기의 미로 격자판이 있습니다.', '그림의 비용은 걸음 수가 아니라 부숴야 하는 벽의 수입니다. 빈 칸을 여러 번 지나도 그 이동 자체에는 추가 비용이 들지 않으며, 시작점과 도착점은 빈 칸입니다.')
add_inline('iamywl-v1-241bfb705697b488','여러 선행 작업이 합쳐지는 작업 관계 그래프','화살표는 먼저 끝내야 하는 작업에서 후속 작업을 향합니다.',
 arrow(142,85,310,155)+arrow(142,240,310,180)+arrow(142,240,325,260)+arrow(360,169,530,190)+arrow(377,255,530,214)+''.join(node(x,y,s) for x,y,s in [(115,75,'1'),(115,240,'2'),(335,165,'3'),(350,270,'4'),(560,200,'5')]),
 '1부터 N까지의 번호가 매겨진 N개의 작업과', '그림에서 3번 작업은 1번과 2번 작업이 모두 끝나야 시작할 수 있습니다. 5번 작업도 3번과 4번이 모두 끝나야 합니다. 화살표는 작업 사이의 선후 관계를 보여줍니다.')

def build(inventory):
 by={p['version']:p for p in inventory}; manifest=[]; selected=set();
 reviewed=json.loads((ROOT/'problems/illustrations/reviewed-statements-v1.json').read_text()); aliases=json.loads((ROOT/'problems/illustrations/approved-case-bound-aliases-v1.json').read_text()); public=ROOT/'frontend/public/problem-illustrations'; public.mkdir(parents=True,exist_ok=True)
 for f in FIGURES:
  p=by[f['version']]; selected.add(f['version'])
  accepted={reviewed[f['version']]};alias=aliases.get(f['version'])
  if alias:
   if alias['originalStatementSha256']!=reviewed[f['version']] or alias['change']!='ADD_APPROVED_T_BOUND_1_TO_10':raise ValueError('Invalid approved statement alias')
   accepted.add(alias['statementSha256'])
  if sha(p['statement']) not in accepted: raise ValueError('Statement changed: review rules again before drawing '+f['version'])
  svg='<svg xmlns="http://www.w3.org/2000/svg" width="800" height="340" viewBox="0 0 800 340" role="img"><title>'+html.escape(f['alt'])+'</title><desc>'+html.escape(f['caption'])+'</desc><defs><marker id="arrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10" fill="#35766d"/></marker></defs><rect width="800" height="340" rx="12" fill="#f8fafb"/><g font-family="Pretendard, Noto Sans KR, Apple SD Gothic Neo, sans-serif">'+f['body']+'</g></svg>\n'
  name=sha(svg)+'.svg';(public/name).write_text(svg)
  item={k:f[k] for k in ['version','alt','caption','kind']}|dict(statementSha256=sha(p['statement']),file=name,width=800,height=340)
  if f.get('anchorPrefix'):
   anchors=[part.strip() for part in p['statement'].split('\n\n') if part.strip().startswith(f['anchorPrefix'])]
   if len(anchors)!=1:raise ValueError('Ambiguous inline paragraph: '+f['version'])
   item.update(afterParagraph=anchors[0],explanation=f['explanation'])
  # Retain both identities during the staged statement release and later rebuilds.
  for statement_hash in sorted(accepted):manifest.append(item|dict(statementSha256=statement_hash))
 (ROOT/'backend/src/main/resources/problem-illustrations-v1.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
 # Inventory contains public metadata only. A candidate is not yet an approved drawing.
 with (ROOT/'problems/illustrations/review-v1.csv').open('w',newline='') as stream:
  writer=csv.writer(stream,lineterminator="\n");writer.writerow(['version','title','category','statement_sha256','status','reason'])
  for p in inventory:
   keywords=[w for w in ['격자','상하좌우','좌표','선분','선수과목','다리','커서','BOOK','쿼드트리','연결 관계'] if w in p['statement']]
   status='ILLUSTRATED' if p['version'] in selected else ('REVIEW_NEEDED' if keywords else 'TEXT_FIRST')
   writer.writerow([p['version'],p['title'],p['category'],sha(p['statement']),status,'규칙 검토 완료' if status=='ILLUSTRATED' else ' / '.join(keywords) if keywords else '그림 필요성을 추가 검토한 뒤 제작'])
 print(f'{len(inventory)} public problems reviewed; {len(manifest)} rule diagrams built')
if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('public_inventory',type=Path);args=parser.parse_args();build(json.loads(args.public_inventory.read_text()))
