// Representative diagnostic categories (assignment unit: one EASY + one MEDIUM item each).
export const categoryLabels={
  'implementation':'구현','arrays-strings':'배열·문자열','basic-data-structures':'기초 자료구조','basic-search':'기초 탐색',
  'bfs':'너비 우선 탐색(BFS)','dfs':'깊이 우선 탐색(DFS)','backtracking':'백트래킹·순열·조합','dp':'동적 계획법',
  'binary-search':'이분 탐색','greedy':'그리디','graph':'그래프·최단 경로','mst':'최소 신장 트리',
  'exam-a-implementation':'조건 구현·경계 처리','exam-a-simulation':'시뮬레이션·상태 전이','exam-a-combinatorial-search':'조합·최적화','exam-a-state-search':'상태 탐색',
  'exam-b-indexed-structures':'인덱스 기반 자료구조','exam-b-priority-order':'우선순위·정렬','exam-b-dynamic-queries':'동적 조회·구간 집계','exam-b-combined-design':'복합 설계·관계 경로'
};
export function bankTitle(id){if(id?.startsWith('exam-a-'))return 'A형 목표 진단'+(id.includes('-set-')?' · 세트 '+Number(id.split('-')[3]):'');if(id?.startsWith('exam-b-'))return 'B형 목표 진단'+(id.includes('-set-')?' · 세트 '+Number(id.split('-')[3]):'');return id.startsWith('core-a-')?'핵심 시범 진단 A':id.startsWith('algo-mix-a-')?'핵심 혼합 알고리즘 진단 A':'분야별 진단';}

export const diagnosticRoleLabels={EASY:'하',MEDIUM:'중',CORE:'기본',APPLIED:'응용'};
