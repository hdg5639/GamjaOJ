package dev.gamjaoj;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class HybridDijkstraProfileTest {
    @TempDir Path temp;
    static ObjectNode reader() {
        var r=new HybridGenerationIntegrationTest().reader();
        r.putArray("interpretedRules").add("Positive-weight undirected shortest path; S=T is 0 and unreachable is -1.");
        r.putObject("oracleDomain").put("inputDomain","N <= 4").put("enumeration","Floyd-Warshall").put("limitations","N <= 4 only; not all public inputs");
        r.putArray("adversarialInputs").addObject().put("input","1 0 1 1\n").put("reason","empty path");
        r.withArray("adversarialInputs").addObject().put("input","4 4 1 4\n1 2 1000000000\n2 3 1000000000\n3 4 1000000000\n1 4 9\n").put("reason","cycle and shorter alternative");
        r.put("oracleSource","import java.util.*; public class Main {public static void main(String[]args){Scanner x=new Scanner(System.in);int n=x.nextInt(),m=x.nextInt(),s=x.nextInt()-1,t=x.nextInt()-1;long[][]d=new long[n][n];for(int i=0;i<n;i++){Arrays.fill(d[i],Long.MAX_VALUE/4);d[i][i]=0;}for(int i=0;i<m;i++){int u=x.nextInt()-1,v=x.nextInt()-1;d[u][v]=d[v][u]=x.nextLong();}for(int k=0;k<n;k++)for(int i=0;i<n;i++)for(int j=0;j<n;j++)d[i][j]=Math.min(d[i][j],d[i][k]+d[k][j]);System.out.println(d[s][t]>=Long.MAX_VALUE/4?-1:d[s][t]);}}");return r;
    }
    static ObjectNode prose() {
        var p=new HybridGenerationIntegrationTest().presentation();p.remove(List.of("semantics","ruleExplanations"));
        p.put("title","가까운 목적지").put("context","도시의 출발점에서 목적지까지 이동합니다.").put("editorial","다익스트라로 최소 비용을 구합니다. long 거리와 우선순위 최소 우선순위 큐를 사용합니다.");
        p.putArray("hints").add("거리 순으로 탐색합니다.").add("최소 우선순위 큐를 사용합니다.").add("방문한 정점의 거리를 저장합니다.");return p;
    }
    static String generated(){return JudgeJson.canonical(JudgeJson.JSON.valueToTree(List.of("1 0 1 1\n","2 1 1 2\n1 2 7\n","3 1 1 3\n1 2 7\n",HybridDijkstraProfile.stress().get(0).input())));}
    static long enumerate(int u,int target,boolean[]seen,List<int[]>edges) {
        if(u==target)return 0;seen[u]=true;long best=Long.MAX_VALUE/4;
        for(var e:edges){int v=e[0]==u?e[1]:e[1]==u?e[0]:0;if(v>0&&!seen[v])best=Math.min(best,e[2]+enumerate(v,target,seen,edges));}
        seen[u]=false;return best;
    }
    @Test void floydMatchesIndependentSimplePathEnumerationWithMixedAndLargeWeights() {
        // Every four-vertex topology, three weight assignments and every endpoint pair.
        for(int mask=0;mask<64;mask++)for(int scale:new int[]{1,7,1000000000}) {
            var es=new ArrayList<int[]>();int bit=0;
            for(int u=1;u<=4;u++)for(int v=u+1;v<=4;v++,bit++)if((mask&(1<<bit))!=0)es.add(new int[]{u,v,scale==7?1+bit*7:scale});
            for(int start=1;start<=4;start++)for(int end=1;end<=4;end++) {
                long answer=enumerate(start,end,new boolean[5],es);
                assertThat(HybridDijkstraProfile.parse(HybridDijkstraProfile.input(4,start,end,es)).answer()).isEqualTo((answer>=Long.MAX_VALUE/4?-1:answer)+"\n");
            }
        }
        assertThat(HybridDijkstraProfile.stress().get(0).output()).isEqualTo("99000000000\n");
        for(var c:HybridDijkstraProfile.invalid())assertThatThrownBy(()->HybridDijkstraProfile.parse(c.input())).hasMessage("PROFILE_INPUT_BOUND");
        assertThat(HybridDijkstraProfile.valid()).hasSize(18);
        var profile=HybridProfiles.DIJKSTRA;profile.requireSupported(profile.contract(),reader());
        assertThatThrownBy(()->HybridProfiles.KNAPSACK.requireSupported(profile.contract(),reader())).isInstanceOf(IllegalArgumentException.class);
        var cases=HybridPackagePlan.candidates(generated(),reader(),7,profile);
        assertThat(cases).allMatch(c->profile.answer(c.path("input").asText()).equals(c.path("output").asText()));
        assertThat(HybridPackagePlan.tests(cases,"batch-oracle",profile)).allMatch(c->profile.tiny(c.path("input").asText()));
    }
    Path compile(String name,String source) throws Exception {
        var dir=Files.createDirectory(temp.resolve(name));var file=dir.resolve("Main.java");Files.writeString(file,source);
        assertThat(javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","8",file.toString())).isZero();return dir;
    }
    @Test void realJavaSupportOracleAndMutantsRespectTheProfile() throws Exception {
        var helper=new HybridCoreSupportTest();var bundle=HybridCoreSupport.bundle(HybridProfiles.DIJKSTRA);
        var gen=compile("generator",bundle.path("generator").asText());var validator=compile("validator",bundle.path("inputValidator").asText());
        var oracle=compile("oracle",reader().path("oracleSource").asText());
        for(long seed:new long[]{0,-1,Long.MIN_VALUE,Long.MAX_VALUE}) {
            String output=helper.run(gen,seed+"\n");assertThat(helper.run(gen,seed+"\n")).isEqualTo(output);
            var inputs=JudgeJson.parse(output);assertThat(inputs.size()).isEqualTo(4);var distinct=new HashSet<String>();
            for(var in:inputs){distinct.add(in.asText());HybridDijkstraProfile.parse(in.asText());assertThat(helper.run(validator,in.asText())).isEqualTo("VALID\n");}
            assertThat(distinct).hasSize(4);
        }
        for(var c:HybridDijkstraProfile.valid())assertThat(helper.run(oracle,c.input())).isEqualTo(c.output());
        for(var c:HybridDijkstraProfile.stress()){assertThat(helper.run(validator,c.input())).isEqualTo("VALID\n");assertThat(helper.run(oracle,c.input())).isEqualTo(c.output());}
        for(var c:HybridDijkstraProfile.invalid())assertThat(helper.run(validator,c.input())).isEqualTo("INVALID\n");
        for(String role:HybridDijkstraProfile.mutants()) {
            var mutant=compile(role,HybridDijkstraProfile.mutant(role));var witness=HybridDijkstraProfile.tests(role).get(0);
            assertThat(helper.run(mutant,witness.path("input").asText())).isNotEqualTo(witness.path("output").asText());
        }
    }
}
