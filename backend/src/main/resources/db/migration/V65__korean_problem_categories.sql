-- Display metadata only; immutable judge packages and signed model artifacts remain unchanged.
UPDATE problem_version SET catalog_category=CASE LOWER(TRIM(catalog_category))
 WHEN 'graph' THEN '그래프'
 WHEN 'graphs' THEN '그래프'
 WHEN 'bfs' THEN '너비 우선 탐색'
 WHEN 'dfs' THEN '깊이 우선 탐색'
 WHEN 'basic-data-structures' THEN '기초 자료구조'
 WHEN 'data-structures' THEN '자료구조'
 WHEN 'dynamic-tree' THEN '동적 트리'
 WHEN 'dynamic-programming' THEN '동적 계획법'
 WHEN 'dp' THEN '동적 계획법'
 WHEN 'shortest-path' THEN '최단 경로'
 WHEN 'math' THEN '수학'
 WHEN 'string' THEN '문자열'
 WHEN 'tree' THEN '트리'
 ELSE catalog_category END
WHERE catalog_category IS NOT NULL;
