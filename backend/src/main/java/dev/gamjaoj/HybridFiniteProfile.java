package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Exact, server-owned semantics. Never infer this profile from tags or rewrite a fresh contract. */
final class HybridFiniteProfile {
    static final String POLICY="hybrid-zero-one-items-v1";
    static final String EXTENDED_POLICY="hybrid-zero-one-items-v2";
    static final String PACKAGE_POLICY="hybrid-zero-one-items-v3";
    private static final JsonNode CONTRACT=load();
    static final String HASH=JudgeJson.hash(POLICY+"\n"+JudgeJson.canonical(CONTRACT)+"\n"
            +JudgeJson.canonical(JudgeJson.JSON.valueToTree(valid()))+"\n"+JudgeJson.canonical(JudgeJson.JSON.valueToTree(invalid())));
    record Case(String id,String input,String output) {}
    private static JsonNode load() {
        try(var stream=HybridFiniteProfile.class.getResourceAsStream("/hybrid/zero-one-items-v1.json")) {
            if(stream==null)throw new IllegalStateException("Missing finite profile");
            return HybridArtifacts.contract(JudgeJson.parse(new String(stream.readAllBytes(),StandardCharsets.UTF_8)));
        }catch(IOException e){throw new IllegalStateException("Cannot load finite profile",e);}
    }
    static JsonNode contract(){return CONTRACT.deepCopy();}
    static void requireSupported(JsonNode contract,JsonNode reader) {
        if(!CONTRACT.equals(contract))throw new IllegalArgumentException("UNSUPPORTED_FINITE_CONTRACT");
        // A deliberately exact supported reader declaration; arbitrary prose is not parsed as a bound.
        var domain=reader.path("oracleDomain");
        if(!domain.path("inputDomain").asText().equals("N <= 4, W <= 8")
                ||!domain.path("enumeration").asText().equals("all subsets"))
            throw new IllegalArgumentException("UNSUPPORTED_ORACLE_DOMAIN");
    }
    static List<Case> valid() {
        var cases=new ArrayList<Case>();
        // Exhaust TWO explicitly described slices, not the whole contract or whole declared oracle domain.
        for(int capacity=1;capacity<=2;capacity++)for(int cost=1;cost<=2;cost++)for(int value=1;value<=2;value++)
            add(cases,capacity,new int[]{cost},new int[]{value});
        for(int capacity=1;capacity<=2;capacity++)for(int a=1;a<=2;a++)for(int b=1;b<=2;b++)
            add(cases,capacity,new int[]{a,b},new int[]{1,1});
        return List.copyOf(cases);
    }
    private static void add(List<Case> cases,int capacity,int[] costs,int[] values) {
        var input=new StringBuilder().append(costs.length).append(' ').append(capacity).append('\n');
        for(int i=0;i<costs.length;i++)input.append(costs[i]).append(' ').append(values[i]).append('\n');
        int best=0;
        for(int mask=0;mask<(1<<costs.length);mask++) {
            int cost=0,value=0;
            for(int i=0;i<costs.length;i++)if((mask&(1<<i))!=0){cost+=costs[i];value+=values[i];}
            if(cost<=capacity)best=Math.max(best,value);
        }
        cases.add(new Case("finite-"+cases.size(),input.toString(),best+"\n"));
    }
    static List<Case> invalid() {
        return List.of(new Case("zero-items","0 1\n","INVALID\n"),new Case("negative-capacity","1 -1\n1 1\n","INVALID\n"),
                new Case("zero-cost","1 1\n0 1\n","INVALID\n"),new Case("zero-value","1 1\n1 0\n","INVALID\n"),
                new Case("extra-token","1 1\n1 1\n9\n","INVALID\n"));
    }
    static boolean supports(String policy){return POLICY.equals(policy)||EXTENDED_POLICY.equals(policy)||PACKAGE_POLICY.equals(policy);}
    static String hash(String policy) {
        if(PACKAGE_POLICY.equals(policy))return JudgeJson.hash(PACKAGE_POLICY+"\n"+hash(EXTENDED_POLICY)+"\ntrusted-parser-v1;dp-answers-v1;random=4;generator=4-distinct;all-reader-inputs;package<=20;repeat=2;package-wall<=40000ms");
        if(POLICY.equals(policy))return HASH;
        if(!EXTENDED_POLICY.equals(policy))throw new IllegalArgumentException("UNKNOWN_FINITE_POLICY");
        return JudgeJson.hash(EXTENDED_POLICY+"\n"+HASH+"\n"+mutant("mutant-unbounded")+"\n"+mutant("mutant-strict-fit")
                +"\n"+JudgeJson.canonical(JudgeJson.JSON.valueToTree(stress()))
                +"\n"+JudgeJson.canonical(JudgeJson.JSON.valueToTree(tests("mutant-unbounded")))
                +"\n"+JudgeJson.canonical(JudgeJson.JSON.valueToTree(tests("mutant-strict-fit")))+"\nwall<=4000ms;repeat=2;exclusive");
    }
    static String mutant(String role) {
        String loop=switch(role) {
            case "mutant-unbounded" -> "for(int j=c;j<=w;j++)";
            case "mutant-strict-fit" -> "for(int j=w;j>c;j--)";
            default -> throw new IllegalArgumentException("UNKNOWN_MUTANT");
        };
        return "public class Main {public static void main(String[] args) {"
                +"java.util.Scanner s=new java.util.Scanner(System.in);int n=s.nextInt(),w=s.nextInt();int[] d=new int[w+1];"
                +"for(int i=0;i<n;i++){int c=s.nextInt(),v=s.nextInt();"+loop+"d[j]=Math.max(d[j],d[j-c]+v);}"
                +"System.out.println(d[w]);}}";
    }
    static List<Case> stress() {
        return List.of(new Case("maximum-transitions","100 1000\n"+"1 10000\n".repeat(100),"1000000\n"),
                new Case("maximum-cost","100 1000\n"+"1000 10000\n".repeat(100),"10000\n"));
    }
    static Set<String> roles(boolean extended) {
        var roles=new HashSet<>(Set.of("domain-valid","domain-invalid","domain-reference","domain-oracle"));
        if(extended)roles.addAll(Set.of("mutant-unbounded","mutant-strict-fit","stress-valid","stress-reference-0","stress-reference-1"));
        return Set.copyOf(roles);
    }
    static Set<String> roles(String policy) {
        var roles=new HashSet<>(roles(!POLICY.equals(policy)));
        if(PACKAGE_POLICY.equals(policy))roles.addAll(Set.of("package-generator","batch-valid","batch-reference","batch-oracle","package-final-0","package-final-1"));
        return Set.copyOf(roles);
    }
    static List<JsonNode> tests(String role) {
        if(!roles(true).contains(role))throw new IllegalArgumentException("UNKNOWN_FINITE_ROLE");
        boolean validator=role.equals("domain-valid")||role.equals("stress-valid");
        List<Case> cases=switch(role) {
            case "domain-invalid" -> invalid();
            case "mutant-unbounded" -> List.of(valid().get(4));
            case "mutant-strict-fit" -> List.of(valid().get(0));
            case "stress-valid","stress-reference-0","stress-reference-1" -> stress();
            default -> valid();
        };
        return cases.stream().map(c->(JsonNode)JudgeJson.JSON.createObjectNode()
                .put("id",c.id()).put("input",c.input()).put("output",validator?"VALID\n":c.output())).toList();
    }
    static JsonNode coverage() {
        return JudgeJson.JSON.createObjectNode().put("profileHash",HASH).put("cases",16)
                .put("description","Slice A: N=1, W/cost/value independently in 1..2 (8). Slice B: N=2, W and both costs independently in 1..2, both values fixed at 1 (8).")
                .put("entireContractExhausted",false).put("entireDeclaredOracleDomainExhausted",false)
                .put("expectedAnswers","server subset enumeration; not model-provided answers");
    }
}
