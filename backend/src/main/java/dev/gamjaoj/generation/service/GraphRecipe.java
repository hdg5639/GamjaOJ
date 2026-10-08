package dev.gamjaoj.generation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gamjaoj.problem.service.ProblemContract;
import dev.gamjaoj.shared.exception.AccountException;
import dev.gamjaoj.shared.support.JudgeJson;
import java.util.*;

/** A single bounded graph contract family, independent of generated Java implementations. */
public final class GraphRecipe implements ProblemContract {
  public static final String PREFIX = "graph-recipe-v1-";
  public static final long INF = Long.MAX_VALUE / 4;

  public enum Query {
    DISTANCE,
    COUNT,
    MAX
  }

  public final boolean directed, weighted;
  public final Query query;

  public GraphRecipe(boolean directed, boolean weighted, Query query) {
    this.directed = directed;
    this.weighted = weighted;
    this.query = query;
  }

  public static GraphRecipe parse(String id) {
    try {
      String[] parts = id.substring(PREFIX.length()).split("-", -1);
      if (parts.length != 3
          || !List.of("D", "U").contains(parts[0])
          || !List.of("W", "UNIT").contains(parts[1])) throw new IllegalArgumentException();
      return new GraphRecipe(parts[0].equals("D"), parts[1].equals("W"), Query.valueOf(parts[2]));
    } catch (RuntimeException e) {
      throw new AccountException(400, "지원하지 않는 그래프 계약이에요.");
    }
  }

  public String id() {
    return PREFIX + (directed ? "D" : "U") + "-" + (weighted ? "W" : "UNIT") + "-" + query;
  }

  public String categoryId() {
    return "graphs";
  }

  public String categoryLabel() {
    return "그래프·경로";
  }

  public String title() {
    return (directed ? "방향" : "무방향")
        + " 그래프 · "
        + (weighted ? "가중 " : "")
        + switch (query) {
          case DISTANCE -> "최단 거리";
          case COUNT -> "도달 정점 수";
          case MAX -> "최대 최단 거리";
        };
  }

  public List<String> operationTags() {
    var tags = new ArrayList<String>();
    if (directed) tags.add("directed");
    if (weighted) tags.add("weighted");
    if (query == Query.COUNT) tags.add("reachable-count");
    if (query == Query.MAX) tags.add("max-distance");
    return tags;
  }

  public String statement() {
    return "첫째 줄에 N M S T가 주어진다. 정점 번호는 1부터 N까지이며 1 ≤ N ≤ 60, 0 ≤ M ≤ 200, 1 ≤ S,T ≤ N이다. 다음 M줄에 간선"
        + " u v w가 주어진다. "
        + (directed ? "간선은 u에서 v로만 이동할 수 있다. " : "간선은 u와 v 사이를 양방향으로 이동할 수 있다. ")
        + (weighted ? "이동 비용 w는 0 이상 1000000000 이하인 정수이다. " : "이동 비용 w는 반드시 1이다. ")
        + "자기 자신으로 향하는 간선, 중복 간선, 연결되지 않은 정점을 허용한다. 출발점에서 자기 자신까지 거리는 0이며 비용 합은 64비트 정수로 계산한다. "
        + switch (query) {
          case DISTANCE -> "S에서 T까지의 최소 비용을 출력한다. 도달할 수 없으면 -1을 출력한다.";
          case COUNT -> "S에서 도달 가능한 정점의 개수를 S 자신을 포함하여 출력한다. T는 이 질의에서 사용하지 않는다.";
          case MAX ->
              "S에서 도달 가능한 정점들까지의 최단 거리 중 최댓값을 출력한다. 도달 불가능한 정점은 제외한다. S 자신만 도달 가능하면 0이다. T는 이 질의에서"
                  + " 사용하지 않는다.";
        };
  }

  public ObjectNode spec() {
    var spec =
        JudgeJson.JSON
            .createObjectNode()
            .put("templateId", id())
            .put("statement", statement())
            .put("runtime", "Java 8 / Main / STDIO")
            .put("contractFamily", "graph-recipe-v1")
            .put("sourceKind", "ORIGINAL_DECLARATIVE")
            .put("permissionNote", "GamjaOJ original graph contract; do not copy external problems")
            .put(
                "inputLayoutPolicy",
                "canonical-lines-v1: generator lines are transport records only; server restores"
                    + " the exact statement line layout before execution and publication")
            .put(
                "generatorContract",
                "Read one long seed; output exactly FOUR lines. Each line is one COMPLETE graph"
                    + " input flattened to whitespace tokens: N M S T followed by M triples u v w."
                    + " No answers. Respect the declared direction/weight/query rules, N<=60,"
                    + " M<=200. Deterministic. Include disconnected/reverse reachability, duplicate"
                    + " edges/self loops, boundary weights (if weighted) and seeded randomness.")
            .put(
                "validatorContract",
                "Validate exactly 4+3*M integer tokens, N=1..60, M=0..200, S/T/u/v in 1..N and"
                    + " weights per declaration. Reject missing/extra/malformed/oversized tokens"
                    + " without exceptions. Disconnected graphs, self loops and duplicate edges are"
                    + " VALID input. Do not validate the answer or require connectivity.");
    spec.putObject("recipe")
        .put("directed", directed)
        .put("weighted", weighted)
        .put("query", query.name())
        .put("maxNodes", 60)
        .put("maxEdges", 200);
    spec.set("sample", sample());
    return spec;
  }

  public static String input(int n, int s, int t, long[]... edges) {
    var text =
        new StringBuilder()
            .append(n)
            .append(' ')
            .append(edges.length)
            .append(' ')
            .append(s)
            .append(' ')
            .append(t)
            .append('\n');
    for (var edge : edges)
      text.append(edge[0]).append(' ').append(edge[1]).append(' ').append(edge[2]).append('\n');
    return text.toString();
  }

  public long w(long value) {
    return weighted ? value : 1;
  }

  public JsonNode sample() {
    return test(
        "sample",
        input(3, 1, 3, new long[] {1, 2, w(5)}, new long[] {2, 3, w(2)}, new long[] {1, 3, w(20)}));
  }

  public ObjectNode test(String id, String input) {
    try {
      String[] tokens = input.strip().split("\\s+");
      if (tokens.length < 4) throw new IllegalArgumentException();
      int n = Integer.parseInt(tokens[0]),
          m = Integer.parseInt(tokens[1]),
          s = Integer.parseInt(tokens[2]) - 1,
          t = Integer.parseInt(tokens[3]) - 1;
      if (n < 1
          || n > 60
          || m < 0
          || m > 200
          || tokens.length != 4 + 3 * m
          || s < 0
          || s >= n
          || t < 0
          || t >= n) throw new IllegalArgumentException();
      long[][] edges = new long[n][n];
      for (long[] row : edges) Arrays.fill(row, INF);
      for (int i = 0; i < m; i++) {
        int u = Integer.parseInt(tokens[4 + 3 * i]) - 1,
            v = Integer.parseInt(tokens[5 + 3 * i]) - 1;
        long cost = Long.parseLong(tokens[6 + 3 * i]);
        if (u < 0
            || u >= n
            || v < 0
            || v >= n
            || cost < 0
            || cost > 1_000_000_000L
            || (!weighted && cost != 1)) throw new IllegalArgumentException();
        edges[u][v] = Math.min(edges[u][v], cost);
        if (!directed) edges[v][u] = Math.min(edges[v][u], cost);
      }
      long[] distance = new long[n];
      Arrays.fill(distance, INF);
      distance[s] = 0;
      boolean[] done = new boolean[n];
      for (int step = 0; step < n; step++) {
        int u = -1;
        for (int v = 0; v < n; v++) if (!done[v] && (u < 0 || distance[v] < distance[u])) u = v;
        if (u < 0 || distance[u] == INF) break;
        done[u] = true;
        for (int v = 0; v < n; v++)
          if (edges[u][v] != INF) distance[v] = Math.min(distance[v], distance[u] + edges[u][v]);
      }
      long answer =
          switch (query) {
            case DISTANCE -> distance[t] == INF ? -1 : distance[t];
            case COUNT -> Arrays.stream(distance).filter(d -> d != INF).count();
            case MAX -> Arrays.stream(distance).filter(d -> d != INF).max().orElse(0);
          };
      return JudgeJson.JSON
          .createObjectNode()
          .put("id", id)
          .put("input", InputLayout.graph(input))
          .put("output", answer + "\n");
    } catch (RuntimeException e) {
      throw new AccountException(400, "입력이 선언된 그래프 계약 범위를 벗어났어요.");
    }
  }

  public List<JsonNode> cases(long seed) {
    var result = new ArrayList<JsonNode>();
    result.add(sample());
    for (int n = 1; n <= (weighted ? 2 : 3); n++) {
      var pairs = new ArrayList<int[]>();
      for (int u = 1; u <= n; u++)
        for (int v = 1; v <= n; v++) if (u != v && (directed || u < v)) pairs.add(new int[] {u, v});
      int base = weighted ? 3 : 2, total = (int) Math.pow(base, pairs.size());
      for (int mask = 0; mask < total; mask++) {
        var edges = new ArrayList<long[]>();
        int code = mask;
        for (var pair : pairs) {
          int digit = code % base;
          code /= base;
          if (digit > 0) edges.add(new long[] {pair[0], pair[1], weighted ? digit - 1 : 1});
        }
        result.add(test("small-" + n + "-" + mask, input(n, 1, n, edges.toArray(long[][]::new))));
      }
    }
    result.add(test("reverse", input(3, 3, 1, new long[] {1, 2, w(3)}, new long[] {2, 3, w(4)})));
    result.add(test("disconnected", input(60, 1, 60)));
    result.add(test("same-node", input(60, 23, 23)));
    result.add(
        test(
            "parallel",
            input(
                3,
                1,
                3,
                new long[] {1, 2, w(2)},
                new long[] {1, 2, w(99)},
                new long[] {2, 3, w(3)})));
    result.add(test("self-loop", input(2, 1, 2, new long[] {1, 1, w(0)}, new long[] {1, 2, w(4)})));
    result.add(
        test(
            "zero-cycle",
            input(
                3,
                1,
                3,
                new long[] {1, 2, w(0)},
                new long[] {2, 1, w(0)},
                new long[] {2, 3, w(0)})));
    var chain = new ArrayList<long[]>();
    for (int i = 1; i < 60; i++) chain.add(new long[] {i, i + 1, w(1_000_000_000L)});
    result.add(test("long-distance", input(60, 1, 60, chain.toArray(long[][]::new))));
    long[][] dense = new long[200][];
    for (int i = 0; i < 200; i++) dense[i] = new long[] {i % 60 + 1, (i * 7 + 1) % 60 + 1, w(i)};
    result.add(test("max-edges", input(60, 1, 60, dense)));
    var random = new Random(seed);
    for (int i = 0; i < 5; i++) {
      int n = 1 + random.nextInt(60), m = random.nextInt(201);
      long[][] edges = new long[m][];
      for (int j = 0; j < m; j++)
        edges[j] =
            new long[] {
              1 + random.nextInt(n),
              1 + random.nextInt(n),
              weighted ? random.nextInt(1_000_000_001) : 1
            };
      result.add(test("seed-" + i, input(n, 1 + random.nextInt(n), 1 + random.nextInt(n), edges)));
    }
    return result;
  }

  public List<String> invalidInputs() {
    return List.of(
        "",
        "0 0 1 1",
        "61 0 1 1",
        "2 -1 1 2",
        "2 201 1 2",
        "2 0 0 2",
        "2 0 1 3",
        "2 1 1 2",
        "2 0 1 2 extra",
        "2 1 1 2 1 3 1",
        "2 1 1 2 1 2 -1",
        "2 1 1 2 1 2 " + (weighted ? "1000000001" : "0"),
        "2 1 1 2 1 2 999999999999999999999",
        "x 0 1 1");
  }

  public String smallDomain() {
    return "all simple "
        + (directed ? "directed" : "undirected")
        + " graphs N=1.."
        + (weighted ? 2 : 3)
        + (weighted ? " with absent/0/1 edges" : " with absent/1 edges")
        + ", S=1,T=N; self/parallel/other terminals in boundary suite";
  }

  public String mutantRole(boolean first) {
    return first ? "mutant-direction" : "mutant-off-by-one";
  }

  public List<JsonNode> mutantCases(List<JsonNode> cases) {
    return cases.stream()
        .filter(
            t ->
                List.of("sample", "reverse", "long-distance", "same-node")
                    .contains(t.path("id").asText()))
        .toList();
  }

  public String mutant(boolean first) {
    boolean useDirected = first ? !directed : directed;
    String result =
        switch (query) {
          case DISTANCE -> "long answer=d[t]==INF?-1:d[t];";
          case COUNT -> "long answer=0;for(long x:d)if(x<INF)answer++;";
          case MAX -> "long answer=0;for(long x:d)if(x<INF)answer=Math.max(answer,x);";
        };
    return "import java.util.*;public class Main{public static void main(String[]z){Scanner in=new"
        + " Scanner(System.in);int"
        + " n=in.nextInt(),m=in.nextInt(),s=in.nextInt()-1,t=in.nextInt()-1;long"
        + " INF=Long.MAX_VALUE/4;long[][] a=new"
        + " long[n][n];for(long[]r:a)Arrays.fill(r,INF);for(int i=0;i<m;i++){int"
        + " u=in.nextInt()-1,v=in.nextInt()-1;long"
        + " w=in.nextLong();a[u][v]=Math.min(a[u][v],w);"
        + (!useDirected ? "a[v][u]=Math.min(a[v][u],w);" : "")
        + "}long[]d=new long[n];Arrays.fill(d,INF);d[s]=0;boolean[]b=new boolean[n];for(int"
        + " k=0;k<n;k++){int u=-1;for(int"
        + " v=0;v<n;v++)if(!b[v]&&(u<0||d[v]<d[u]))u=v;if(u<0||d[u]==INF)break;b[u]=true;for(int"
        + " v=0;v<n;v++)if(a[u][v]<INF)d[v]=Math.min(d[v],d[u]+a[u][v]);}"
        + result
        + (first ? "" : query == Query.COUNT ? "answer--;" : "if(answer>=0)answer++;")
        + "System.out.println(answer);}}";
  }
}
