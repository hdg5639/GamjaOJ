package dev.gamjaoj;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gamjaoj.generation.service.HybridBfsProfile;
import dev.gamjaoj.generation.service.HybridCoreSupport;
import dev.gamjaoj.generation.service.HybridPackagePlan;
import dev.gamjaoj.generation.service.HybridProfiles;
import dev.gamjaoj.shared.support.JudgeJson;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HybridBfsProfileTest {
  @TempDir Path temp;

  static ObjectNode reader() {
    var r = new HybridGenerationIntegrationTest().reader();
    r.putArray("interpretedRules")
        .add("Unit-cost undirected shortest path; S=T is 0 and unreachable is -1.");
    r.putObject("oracleDomain")
        .put("inputDomain", "N <= 4")
        .put("enumeration", "Floyd-Warshall")
        .put("limitations", "N <= 4 only; not all public inputs");
    r.putArray("adversarialInputs")
        .addObject()
        .put("input", "1 0 1 1\n")
        .put("reason", "empty path");
    r.withArray("adversarialInputs")
        .addObject()
        .put("input", "4 4 1 4\n1 2\n2 3\n3 4\n1 4\n")
        .put("reason", "cycle and shorter alternative");
    r.put(
        "oracleSource",
        "import java.util.*; public class Main {public static void main(String[]args){Scanner x=new"
            + " Scanner(System.in);int"
            + " n=x.nextInt(),m=x.nextInt(),s=x.nextInt()-1,t=x.nextInt()-1;int[][]d=new"
            + " int[n][n];for(int i=0;i<n;i++){Arrays.fill(d[i],1000);d[i][i]=0;}for(int"
            + " i=0;i<m;i++){int u=x.nextInt()-1,v=x.nextInt()-1;d[u][v]=d[v][u]=1;}for(int"
            + " k=0;k<n;k++)for(int i=0;i<n;i++)for(int"
            + " j=0;j<n;j++)d[i][j]=Math.min(d[i][j],d[i][k]+d[k][j]);System.out.println(d[s][t]>=1000?-1:d[s][t]);}}");
    return r;
  }

  static ObjectNode prose() {
    var p = new HybridGenerationIntegrationTest().presentation();
    p.remove(List.of("semantics", "ruleExplanations"));
    p.put("title", "가까운 목적지")
        .put("context", "도시의 출발점에서 목적지까지 이동합니다.")
        .put("editorial", "BFS로 최단 거리를 구합니다. 시간 복잡도는 O(N+M)입니다.");
    p.putObject("sections")
        .put("input", "첫 줄에 N M S T가 주어지고, 다음 M줄에 간선의 두 끝점 u v가 주어집니다.")
        .put("output", "S에서 T까지의 최소 간선 수을 출력합니다. S와 T가 같으면 0, 도달할 수 없으면 `-1`을 출력합니다.")
        .put("limits", "1 ≤ N ≤ 100, 0 ≤ M ≤ 200이며 M은 N(N-1)/2 이하입니다.");
    p.putArray("hints").add("거리 순으로 탐색합니다.").add("큐를 사용합니다.").add("방문한 정점의 거리를 저장합니다.");
    return p;
  }

  static String generated() {
    return JudgeJson.canonical(
        JudgeJson.JSON.valueToTree(
            List.of(
                "1 0 1 1\n",
                "2 1 1 2\n1 2\n",
                "3 1 1 3\n1 2\n",
                HybridBfsProfile.stress().get(0).input())));
  }

  @Test
  void floydAnswersMatchIndependentBfsForEveryFourVertexGraphAndEndpointPair() {
    for (int mask = 0; mask < 64; mask++) {
      var es = new ArrayList<int[]>();
      int bit = 0;
      for (int u = 1; u <= 4; u++)
        for (int v = u + 1; v <= 4; v++, bit++)
          if ((mask & (1 << bit)) != 0) es.add(new int[] {u, v});
      for (int s = 1; s <= 4; s++)
        for (int t = 1; t <= 4; t++) {
          int[] d = new int[5];
          Arrays.fill(d, -1);
          d[s] = 0;
          var q = new ArrayDeque<Integer>();
          q.add(s);
          while (!q.isEmpty()) {
            int u = q.remove();
            for (var e : es) {
              int v = e[0] == u ? e[1] : e[1] == u ? e[0] : 0;
              if (v > 0 && d[v] < 0) {
                d[v] = d[u] + 1;
                q.add(v);
              }
            }
          }
          assertThat(HybridBfsProfile.parse(HybridBfsProfile.input(4, s, t, es)).answer())
              .isEqualTo(d[t] + "\n");
        }
    }
    for (var c : HybridBfsProfile.invalid())
      assertThatThrownBy(() -> HybridBfsProfile.parse(c.input())).hasMessage("PROFILE_INPUT_BOUND");
    assertThat(HybridBfsProfile.valid()).hasSize(16);
    var profile = HybridProfiles.BFS;
    profile.requireSupported(profile.contract(), reader());
    assertThatThrownBy(() -> HybridProfiles.KNAPSACK.requireSupported(profile.contract(), reader()))
        .isInstanceOf(IllegalArgumentException.class);
    var cases = HybridPackagePlan.candidates(generated(), reader(), 7, profile);
    assertThat(cases)
        .allMatch(c -> profile.answer(c.path("input").asText()).equals(c.path("output").asText()));
    assertThat(HybridPackagePlan.tests(cases, "batch-oracle", profile))
        .allMatch(c -> profile.tiny(c.path("input").asText()));
  }

  Path compile(String name, String source) throws Exception {
    var dir = Files.createDirectory(temp.resolve(name));
    var file = dir.resolve("Main.java");
    Files.writeString(file, source);
    assertThat(
            javax.tools.ToolProvider.getSystemJavaCompiler()
                .run(null, null, null, "--release", "8", file.toString()))
        .isZero();
    return dir;
  }

  @Test
  void realJavaSupportOracleAndMutantsRespectTheProfile() throws Exception {
    var helper = new HybridCoreSupportTest();
    var bundle = HybridCoreSupport.bundle(HybridProfiles.BFS);
    var gen = compile("generator", bundle.path("generator").asText());
    var validator = compile("validator", bundle.path("inputValidator").asText());
    var oracle = compile("oracle", reader().path("oracleSource").asText());
    for (long seed : new long[] {0, -1, Long.MIN_VALUE, Long.MAX_VALUE}) {
      String output = helper.run(gen, seed + "\n");
      assertThat(helper.run(gen, seed + "\n")).isEqualTo(output);
      var inputs = JudgeJson.parse(output);
      assertThat(inputs.size()).isEqualTo(4);
      var distinct = new HashSet<String>();
      for (var in : inputs) {
        distinct.add(in.asText());
        HybridBfsProfile.parse(in.asText());
        assertThat(helper.run(validator, in.asText())).isEqualTo("VALID\n");
      }
      assertThat(distinct).hasSize(4);
    }
    for (var c : HybridBfsProfile.valid())
      assertThat(helper.run(oracle, c.input())).isEqualTo(c.output());
    for (var c : HybridBfsProfile.stress())
      assertThat(helper.run(validator, c.input())).isEqualTo("VALID\n");
    for (var c : HybridBfsProfile.invalid())
      assertThat(helper.run(validator, c.input())).isEqualTo("INVALID\n");
    for (String role : HybridBfsProfile.mutants()) {
      var mutant = compile(role, HybridBfsProfile.mutant(role));
      var witness = HybridBfsProfile.tests(role).get(0);
      assertThat(helper.run(mutant, witness.path("input").asText()))
          .isNotEqualTo(witness.path("output").asText());
    }
  }
}
