package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Exact simple undirected, unit-edge shortest-path contract. Answers use Floyd-Warshall. */
final class HybridBfsProfile {
    static final String ID="bfs-shortest-path-v1",POLICY="hybrid-bfs-shortest-path-v1";
    private static JsonNode loadContract() {
        try(var in=HybridBfsProfile.class.getResourceAsStream("/hybrid/"+ID+".json")) {
            return HybridArtifacts.contract(JudgeJson.parse(new String(in.readAllBytes(),StandardCharsets.UTF_8)));
        }catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
    private static final JsonNode CONTRACT=loadContract();
    static JsonNode contract(){return CONTRACT.deepCopy();}
    record Input(int n,int s,int t,List<int[]> edges) {
        String answer() {
            int[][] d=new int[n][n];for(int[] row:d)Arrays.fill(row,1000);
            for(int i=0;i<n;i++)d[i][i]=0;
            for(var e:edges)d[e[0]-1][e[1]-1]=d[e[1]-1][e[0]-1]=1;
            for(int k=0;k<n;k++)for(int i=0;i<n;i++)for(int j=0;j<n;j++)d[i][j]=Math.min(d[i][j],d[i][k]+d[k][j]);
            return (d[s-1][t-1]>=1000?-1:d[s-1][t-1])+"\n";
        }
    }
    static Input parse(String text) {
        try {
            if(text==null||text.isBlank()||text.getBytes(StandardCharsets.UTF_8).length>4096)throw new IllegalArgumentException();
            String[] t=text.strip().split("(?U)\\s+");for(String x:t)if(!x.matches("[+-]?[0-9]+"))throw new IllegalArgumentException();
            if(t.length<4)throw new IllegalArgumentException();
            int n=Integer.parseInt(t[0]),m=Integer.parseInt(t[1]),s=Integer.parseInt(t[2]),end=Integer.parseInt(t[3]);
            if(n<1||n>100||m<0||m>Math.min(200,n*(n-1)/2)||s<1||s>n||end<1||end>n||t.length!=4+2*m)throw new IllegalArgumentException();
            var seen=new HashSet<Integer>();var edges=new ArrayList<int[]>();
            for(int i=0;i<m;i++) {
                int u=Integer.parseInt(t[4+2*i]),v=Integer.parseInt(t[5+2*i]);
                if(u<1||v<1||u>n||v>n||u==v||!seen.add(Math.min(u,v)*101+Math.max(u,v)))throw new IllegalArgumentException();
                edges.add(new int[]{u,v});
            }
            return new Input(n,s,end,edges);
        }catch(IllegalArgumentException e){throw new IllegalArgumentException("PROFILE_INPUT_BOUND");}
    }
    static String input(int n,int s,int t,List<int[]> edges) {
        var b=new StringBuilder().append(n).append(' ').append(edges.size()).append(' ').append(s).append(' ').append(t).append('\n');
        for(var e:edges)b.append(e[0]).append(' ').append(e[1]).append('\n');return b.toString();
    }
    static List<HybridFiniteProfile.Case> valid() {
        var result=new ArrayList<HybridFiniteProfile.Case>();
        for(int mask=0;mask<8;mask++) {
            var edges=new ArrayList<int[]>();int bit=0;
            for(int u=1;u<=3;u++)for(int v=u+1;v<=3;v++,bit++)if((mask&(1<<bit))!=0)edges.add(new int[]{u,v});
            for(int s:new int[]{1,3}) {String in=input(3,s,3,edges);result.add(new HybridFiniteProfile.Case("finite-"+result.size(),in,parse(in).answer()));}
        }
        return result;
    }
    static List<HybridFiniteProfile.Case> invalid() {
        var inputs=List.of("0 0 1 1\n","3 1 1 3\n2 2\n","3 2 1 3\n1 2\n2 1\n","3 1 1 3\n1 4\n","3 1 1 3\n","3 0 1 3\n1 2\n");
        var out=new ArrayList<HybridFiniteProfile.Case>();for(int i=0;i<inputs.size();i++)out.add(new HybridFiniteProfile.Case("invalid-"+i,inputs.get(i),"INVALID\n"));return out;
    }
    static List<HybridFiniteProfile.Case> stress() {
        var chain=new ArrayList<int[]>();for(int i=1;i<100;i++)chain.add(new int[]{i,i+1});
        var dense=new ArrayList<int[]>();for(int i=1;i<=100&&dense.size()<200;i++)for(int j=i+1;j<=100&&dense.size()<200;j++)dense.add(new int[]{i,j});
        String a=input(100,1,100,chain),b=input(100,100,99,dense);
        return List.of(new HybridFiniteProfile.Case("maximum-chain",a,parse(a).answer()),new HybridFiniteProfile.Case("maximum-edges",b,parse(b).answer()));
    }
    static List<String> random(long seed) {
        var r=new SplittableRandom(seed);var out=new LinkedHashSet<String>();
        for(int tries=0;out.size()<4&&tries<128;tries++) {
            var e=new ArrayList<int[]>();for(int u=1;u<=4;u++)for(int v=u+1;v<=4;v++)if(r.nextBoolean())e.add(new int[]{u,v});
            out.add(input(4,r.nextInt(1,5),r.nextInt(1,5),e));
        }
        if(out.size()!=4)throw new IllegalArgumentException("RANDOM_INPUT_DIVERSITY");return List.copyOf(out);
    }
    static List<String> mutants(){return List.of("mutant-directed","mutant-unreachable");}
    static String mutant(String role) {
        if(!mutants().contains(role))throw new IllegalArgumentException("UNKNOWN_MUTANT");
        return "import java.util.*;public class Main{public static void main(String[]z){Scanner q=new Scanner(System.in);int n=q.nextInt(),m=q.nextInt(),s=q.nextInt()-1,t=q.nextInt()-1;boolean[][]a=new boolean[n][n];for(int i=0;i<m;i++){int u=q.nextInt()-1,v=q.nextInt()-1;a[u][v]=true;"
            +(role.equals("mutant-directed")?"":"a[v][u]=true;")+"}int[]d=new int[n];Arrays.fill(d,-1);d[s]=0;ArrayDeque<Integer>k=new ArrayDeque<>();k.add(s);while(!k.isEmpty()){int u=k.remove();for(int v=0;v<n;v++)if(a[u][v]&&d[v]<0){d[v]=d[u]+1;k.add(v);}}System.out.println("
            +(role.equals("mutant-unreachable")?"Math.max(0,d[t])":"d[t]")+");}}";
    }
    static List<JsonNode> tests(String role) {
        boolean validator=role.equals("domain-valid")||role.equals("stress-valid");
        List<HybridFiniteProfile.Case> cs=switch(role) {
            case "domain-invalid"->invalid();
            case "mutant-directed"->List.of(new HybridFiniteProfile.Case("reverse-edge","2 1 2 1\n1 2\n","1\n"));
            case "mutant-unreachable"->List.of(new HybridFiniteProfile.Case("disconnected","2 0 1 2\n","-1\n"));
            case "stress-valid","stress-reference-0","stress-reference-1"->stress();
            default->valid();
        };
        return cs.stream().map(c->(JsonNode)JudgeJson.JSON.createObjectNode().put("id",c.id()).put("input",c.input()).put("output",validator?"VALID\n":c.output())).toList();
    }
    private static class Hash {static final String VALUE=computeHash();}
    static String hash(){return Hash.VALUE;}
    private static String computeHash() {
        return JudgeJson.hash(POLICY+"\n"+JudgeJson.canonical(contract())+"\n"+JudgeJson.canonical(JudgeJson.JSON.valueToTree(valid()))+"\n"+JudgeJson.canonical(JudgeJson.JSON.valueToTree(invalid()))+"\n"+JudgeJson.canonical(JudgeJson.JSON.valueToTree(stress()))+"\n"+mutant("mutant-directed")+"\n"+mutant("mutant-unreachable")+"\n"+JudgeJson.canonical(JudgeJson.JSON.valueToTree(tests("mutant-directed")))+"\n"+JudgeJson.canonical(JudgeJson.JSON.valueToTree(tests("mutant-unreachable")))+"\nparser-v1;floyd-v1;random=4;generator=4;all-reader;package<=20;repeat=2;wall<=4000;total<=40000");
    }
}
