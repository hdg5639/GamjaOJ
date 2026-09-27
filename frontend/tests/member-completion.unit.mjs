// node --test tests/member-completion.unit.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { memberCompletions } from '../app/member-completion.mjs';

const at = (language, text) => {
  const pos = text.indexOf('|');
  return memberCompletions(language, text.replace('|', ''), pos);
};
const labels = (language, text) => at(language, text)?.options.map(o => o.label) ?? null;

test('Java: BufferedReader, StringTokenizer and chained return types', () => {
  const head = 'import java.io.*;\nclass Main{public static void main(String[] a) throws IOException{\nBufferedReader br = new BufferedReader(new InputStreamReader(System.in));\nStringTokenizer st = new StringTokenizer(br.readLine());\n';
  assert.ok(labels('JAVA', head + 'br.|').includes('readLine'));
  assert.ok(labels('JAVA', head + 'br.re|').includes('readLine'));
  assert.equal(at('JAVA', head + 'br.re|').from, (head + 'br.').length);
  assert.ok(labels('JAVA', head + 'st.|').includes('nextToken'));
  assert.ok(labels('JAVA', head + 'br.readLine().|').includes('split'));
  assert.ok(labels('JAVA', head + 'br.readLine().trim().|').includes('charAt'));
  assert.equal(labels('JAVA', head + 'br.readLine.|'), null);
});

test('Java: generic collections follow element types', () => {
  const code = 'List<Integer> list = new ArrayList<>();\nMap<String, List<Integer>> graph = new HashMap<>();\nArrayDeque<int[]> q = new ArrayDeque<>();\nPriorityQueue<Long> pq = new PriorityQueue<>();\nTreeMap<Integer,Integer> tm = new TreeMap<>();\nList<Integer>[] adj = new ArrayList[5];\n';
  assert.ok(labels('JAVA', code + 'list.|').includes('get'));
  assert.ok(labels('JAVA', code + 'list.get(0).|').includes('intValue'));
  assert.ok(labels('JAVA', code + 'graph.get("a").|').includes('add'));
  assert.ok(labels('JAVA', code + 'q.|').includes('pollFirst'));
  assert.ok(labels('JAVA', code + 'q.poll().|').includes('length'));
  assert.ok(labels('JAVA', code + 'pq.|').includes('offer'));
  assert.ok(labels('JAVA', code + 'tm.|').includes('floorKey'));
  assert.ok(labels('JAVA', code + 'tm.firstEntry().|').includes('getValue'));
  assert.ok(labels('JAVA', code + 'for (Map.Entry<Integer,Integer> e : tm.entrySet()) e.|').includes('getKey'));
  assert.ok(labels('JAVA', code + 'adj[0].|').includes('add'));
});

test('Java: statics, arrays, strings and unknown receivers', () => {
  assert.ok(labels('JAVA', 'Math.|').includes('max'));
  assert.ok(labels('JAVA', 'Integer.|').includes('parseInt'));
  assert.ok(labels('JAVA', 'System.out.|').includes('println'));
  assert.ok(labels('JAVA', 'int[] arr = new int[3];\narr.|').includes('length'));
  assert.ok(labels('JAVA', 'int arr[] = new int[3];\narr.|').includes('length'));
  assert.ok(labels('JAVA', 'String s = "x";\ns.|').includes('substring'));
  assert.ok(labels('JAVA', '"a,b".|').includes('split'));
  assert.ok(labels('JAVA', 'StringBuilder sb = new StringBuilder();\nsb.append(1).|').includes('reverse'));
  assert.equal(labels('JAVA', 'Foo foo = new Foo();\nfoo.|'), null);
  assert.equal(labels('JAVA', 'int n = 3;\nn.|'), null);
  assert.equal(labels('JAVA', 'double d = 1.|'), null);
  assert.equal(labels('JAVA', 'return br;\nbr.|'), null);
});

test('C++: containers, pairs, nested vectors and streams', () => {
  const code = '#include <bits/stdc++.h>\nusing namespace std;\nint main(){\nvector<int> v(5);\nvector<vector<int>> g(3);\nstring s;\nmap<string,int> m;\npriority_queue<pair<int,int>, vector<pair<int,int>>, greater<pair<int,int>>> pq;\nset<int> st;\nint a, b;\n';
  assert.ok(labels('CPP', code + 'v.|').includes('push_back'));
  assert.ok(labels('CPP', code + 'g[0].|').includes('push_back'));
  assert.ok(labels('CPP', code + 's.|').includes('substr'));
  assert.ok(labels('CPP', code + 'm.|').includes('lower_bound'));
  assert.ok(labels('CPP', code + 'pq.|').includes('top'));
  assert.ok(labels('CPP', code + 'pq.top().|').includes('second'));
  assert.ok(labels('CPP', code + 'st.|').includes('upper_bound'));
  assert.ok(labels('CPP', code + 'cin.|').includes('tie'));
  assert.ok(labels('CPP', code + 'std::cin.|').includes('tie'));
  assert.equal(labels('CPP', code + 'a.|'), null);
  assert.equal(labels('CPP', code + 'auto it = st.begin();\nit.|'), null);
});

test('Python: inferred assignments, modules and chains', () => {
  const code = 'import sys, heapq\nfrom collections import deque, Counter\ninput = sys.stdin.readline\nn = int(input())\narr = list(map(int, input().split()))\nwords = input().split()\nq = deque()\nd = {}\nseen = set()\ncnt = Counter(arr)\nname = input().strip()\n';
  assert.ok(labels('PYTHON', code + 'arr.|').includes('append'));
  assert.ok(labels('PYTHON', code + 'words.|').includes('sort'));
  assert.ok(labels('PYTHON', code + 'q.|').includes('popleft'));
  assert.ok(labels('PYTHON', code + 'd.|').includes('items'));
  assert.ok(labels('PYTHON', code + 'seen.|').includes('add'));
  assert.ok(labels('PYTHON', code + 'cnt.|').includes('most_common'));
  assert.ok(labels('PYTHON', code + 'name.|').includes('startswith'));
  assert.ok(labels('PYTHON', code + 'input().|').includes('split'));
  assert.ok(labels('PYTHON', code + 'sys.|').includes('setrecursionlimit'));
  assert.ok(labels('PYTHON', code + 'sys.stdin.|').includes('readline'));
  assert.ok(labels('PYTHON', code + 'heapq.|').includes('heappush'));
  assert.ok(labels('PYTHON', code + '" ".|').includes('join'));
  assert.ok(labels('PYTHON', code + 'n.|').includes('bit_length'));
  assert.equal(labels('PYTHON', code + 'unknown.|'), null);
  assert.equal(labels('PYTHON', 'x = foo()\nx.|'), null);
});
