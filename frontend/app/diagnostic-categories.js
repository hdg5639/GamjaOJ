// Representative diagnostic categories (assignment unit: one EASY + one MEDIUM item each).
export const categoryLabels={
  'implementation':'구현','arrays-strings':'배열·문자열','basic-data-structures':'기초 자료구조','basic-search':'기초 탐색',
  'bfs':'너비 우선 탐색(BFS)','dfs':'깊이 우선 탐색(DFS)','backtracking':'백트래킹·순열·조합','dp':'동적 계획법',
  'binary-search':'이분 탐색','greedy':'그리디','graph':'그래프·최단 경로','mst':'최소 신장 트리'
};
export function bankTitle(id){return id.startsWith('core-a-')?'핵심 시범 진단 A':id.startsWith('algo-mix-a-')?'핵심 혼합 알고리즘 진단 A':'분야별 진단';}
