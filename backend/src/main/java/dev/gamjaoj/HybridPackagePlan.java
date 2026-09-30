package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Trusted input parser/answer producer for the exact supported profile, never a generic judge oracle. */
final class HybridPackagePlan {
    record Input(int capacity,int[] costs,int[] values) {
        boolean tiny(){return costs.length<=4&&capacity<=8&&Arrays.stream(costs).allMatch(v->v<=3)&&Arrays.stream(values).allMatch(v->v<=3);}
        String answer(){
            int[] best=new int[capacity+1];
            for(int i=0;i<costs.length;i++)for(int w=capacity;w>=costs[i];w--)best[w]=Math.max(best[w],best[w-costs[i]]+values[i]);
            return best[capacity]+"\n";
        }
    }
    static Input parse(String text) {
        if(text==null||text.isBlank()||text.getBytes(StandardCharsets.UTF_8).length>4096)throw new IllegalArgumentException("PROFILE_INPUT_BOUND");
        try {
            String[] tokens=text.strip().split("(?U)\\s+");
            if(tokens.length<2)throw new IllegalArgumentException();
            for(String token:tokens)if(!token.matches("[+-]?[0-9]+"))throw new IllegalArgumentException();
            int n=Integer.parseInt(tokens[0]),w=Integer.parseInt(tokens[1]);
            if(n<1||n>100||w<1||w>1000||tokens.length!=2+2*n)throw new IllegalArgumentException();
            int[] costs=new int[n],values=new int[n];
            for(int i=0;i<n;i++) {
                costs[i]=Integer.parseInt(tokens[2+2*i]);values[i]=Integer.parseInt(tokens[3+2*i]);
                if(costs[i]<1||costs[i]>1000||values[i]<1||values[i]>10000)throw new IllegalArgumentException();
            }
            return new Input(w,costs,values);
        }catch(IllegalArgumentException e){throw new IllegalArgumentException("PROFILE_INPUT_BOUND");}
    }
    static List<String> random(long seed) {
        var random=new SplittableRandom(seed);var result=new LinkedHashSet<String>();
        for(int attempt=0;result.size()<4&&attempt<32;attempt++) {
            int n=random.nextInt(3,5),w=random.nextInt(1,9);var input=new StringBuilder().append(n).append(' ').append(w).append('\n');
            for(int i=0;i<n;i++)input.append(random.nextInt(1,4)).append(' ').append(random.nextInt(1,4)).append('\n');
            result.add(input.toString());
        }
        if(result.size()!=4)throw new IllegalArgumentException("RANDOM_INPUT_DIVERSITY");return List.copyOf(result);
    }
    static List<JsonNode> candidates(String generated,JsonNode reader,long seed) {return candidates(generated,reader,seed,HybridProfiles.KNAPSACK);}
    static List<JsonNode> candidates(String generated,JsonNode reader,long seed,HybridProfiles.Definition profile) {
        JsonNode envelope;try{envelope=JudgeJson.JSON.readTree(generated);}catch(Exception e){throw new IllegalArgumentException("INVALID_GENERATOR_ENVELOPE");}
        if(envelope==null||!envelope.isArray()||envelope.size()!=4)throw new IllegalArgumentException("INVALID_GENERATOR_ENVELOPE");
        var all=new LinkedHashMap<String,JsonNode>();
        for(String role:profile.mutants()) {
            var witness=profile.tests(role).get(0);add(profile,all,role,witness.path("input").asText());
        }
        for(var stress:profile.stress())add(profile,all,stress.id(),stress.input());
        var distinct=new HashSet<String>();int i=0;
        for(var value:envelope) {
            if(!value.isTextual()||!distinct.add(value.asText()))throw new IllegalArgumentException("INVALID_GENERATOR_DIVERSITY");
            // A registered package has no trusted answer engine for fresh generator output: validate it only.
            if(profile.pkg()!=null)check(all,"check-generated-"+i++,value.asText());else add(profile,all,"generated-"+i++,value.asText());
        }
        i=0;for(String input:profile.random(seed))add(profile,all,"random-"+i++,input);
        i=0;for(var item:reader.path("adversarialInputs")) {
            if(profile.pkg()!=null&&!profile.tiny(item.path("input").asText()))check(all,"check-reader-"+i++,item.path("input").asText());
            else add(profile,all,"reader-"+i++,item.path("input").asText());
        }
        if(all.size()>20)throw new IllegalArgumentException("FINAL_PACKAGE_TEST_CAP");
        return List.copyOf(all.values());
    }
    /** Validation-only candidate: checked by the input validator, never used as an answer-bearing test. */
    private static void check(Map<String,JsonNode> all,String id,String input) {
        all.putIfAbsent(input,JudgeJson.JSON.createObjectNode().put("id",id).put("input",input).put("output","VALID\n"));
    }
    static boolean checkOnly(JsonNode c){return c.path("id").asText().startsWith("check-");}
    private static void add(HybridProfiles.Definition profile,Map<String,JsonNode> all,String id,String input) {
        var answer=profile.answer(input);
        all.putIfAbsent(input,JudgeJson.JSON.createObjectNode().put("id",id).put("input",input).put("output",answer));
    }
    static List<JsonNode> tests(List<JsonNode> candidates,String role) {return tests(candidates,role,HybridProfiles.KNAPSACK);}
    static List<JsonNode> tests(List<JsonNode> candidates,String role,HybridProfiles.Definition profile) {
        if(role.equals("batch-valid"))return candidates.stream().map(c->(JsonNode)((ObjectNode)c.deepCopy()).put("output","VALID\n")).toList();
        if(role.equals("batch-oracle"))return candidates.stream().filter(c->!checkOnly(c)&&profile.tiny(c.path("input").asText())).toList();
        if(!role.equals("batch-reference"))throw new IllegalArgumentException("UNKNOWN_PACKAGE_ROLE");
        return candidates.stream().filter(c->!checkOnly(c)).toList();
    }
    static ObjectNode pack(String version,List<JsonNode> candidates,JsonNode presentation) {return pack(version,candidates,presentation,HybridProfiles.KNAPSACK);}
    static ObjectNode pack(String version,List<JsonNode> candidates,JsonNode presentation,HybridProfiles.Definition profile) {return pack(version,candidates,presentation,profile,null);}
    /** Registered packages with large tests carry them into every learner judgement; reference answers stay in the Runner. */
    static int generatedCount(HybridProfiles.Definition profile){return profile.pkg()!=null&&profile.pkg().hasLarge()?profile.pkg().largeSeeds().size():0;}
    static ObjectNode pack(String version,List<JsonNode> candidates,JsonNode presentation,HybridProfiles.Definition profile,String reference) {
        var p=JudgeJson.JSON.createObjectNode().put("version",version).put("output_policy","TOKEN_EXACT")
                .put("title",presentation.path("title").asText()).put("mode","HYBRID_V1");
        var sem=presentation.path("semantics");var statement=new StringBuilder(presentation.path("context").asText());
        statement.append("\n\n규칙\n");for(var rule:presentation.path("ruleExplanations"))statement.append(rule.path("text").asText()).append('\n');
        if(presentation.has("sections")) {
            // Learner prose checked for Korean wording and contract bounds; the contract itself stays for review only.
            var s=presentation.path("sections");
            statement.append("\n입력\n").append(s.path("input").asText().strip()).append("\n\n출력\n").append(s.path("output").asText().strip())
                    .append("\n\n제한\n").append(s.path("limits").asText().strip());
        } else {
            statement.append("\n\n대상과 상태\n").append(sem.path("domain").path("entities").asText()).append('\n')
                    .append(sem.path("domain").path("types").asText()).append('\n').append(sem.path("domain").path("relationships").asText())
                    .append("\n초기 상태: ").append(sem.path("state").path("initial").asText()).append("\n변경되는 상태: ").append(sem.path("state").path("mutable").asText());
            for(var action:sem.path("actions"))statement.append("\n행동 조건: ").append(action.path("preconditions").asText())
                    .append("\n상태 변화: ").append(action.path("transition").asText()).append("\n재사용: ").append(action.path("reuse").asText())
                    .append("\n자원 조건: ").append(action.path("resources").asText());
            statement.append("\n\n목표\n").append(sem.path("goal").path("definition").asText()).append("\n종료 조건: ").append(sem.path("termination").asText());
            statement.append("\n입력\n").append(sem.path("input").path("format").asText())
                    .append("\n인덱스: ").append(sem.path("input").path("indexing").asText()).append("\n테스트 케이스: ").append(sem.path("input").path("caseCount").asText());
            statement.append("\n\n출력\n").append(sem.path("output").path("format").asText())
                    .append("\n동점: ").append(sem.path("output").path("ties").asText()).append("\n선택 없음: ").append(sem.path("output").path("empty").asText())
                    .append("\n불가능한 경우: ").append(sem.path("output").path("impossible").asText())
                    .append("\n출력 수의 범위: ").append(sem.path("output").path("numericRange").asText());
            statement.append("\n\n제약\n").append(sem.path("limits").path("maxInputSize").asText()).append('\n').append(sem.path("limits").path("executionConstraints").asText());
        }
        p.put("statement",statement.toString());p.set("semantics",sem.deepCopy());
        if(sem.has("callable"))p.set("api",CallablePrograms.bundle(sem.path("callable")));
        var tests=p.putArray("tests");candidates.stream().filter(c->!checkOnly(c)).forEach(tests::add);
        if(generatedCount(profile)>0) {
            if(reference==null||reference.isBlank())throw new IllegalArgumentException("MISSING_GENERATED_REFERENCE");
            p.set("generated",profile.pkg().generated(reference,"REFERENCE"));
        }
        // Mechanically sourced, already checked by both implementations; never guessed by a writer.
        var samples=p.putArray("samples");
        for(var c:samples(candidates,profile)) {
            var sample=samples.addObject();sample.set("input",c.path("input"));sample.set("output",c.path("output"));
        }
        return p;
    }
    /**
     * Up to three public examples from answer-bearing tiny tests. Mutant witnesses and fixed stress cases are
     * minimal trap inputs, so random, reader and generated inputs come first; among them the richest input
     * (longest, with a non-trivial answer, distinct answers first). Shown shortest first.
     */
    static List<JsonNode> samples(List<JsonNode> candidates,HybridProfiles.Definition profile) {
        var pool=candidates.stream().filter(c->!checkOnly(c)&&profile.tiny(c.path("input").asText())
                &&c.path("input").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=1200).toList();
        java.util.function.Predicate<JsonNode> illustrative=c->c.path("id").asText().matches("(random|reader|generated)-\\d+");
        java.util.function.Predicate<JsonNode> trivial=c->Set.of("","0","-1","NO","IMPOSSIBLE").contains(c.path("output").asText().strip());
        var ranked=pool.stream().sorted(java.util.Comparator.<JsonNode,Boolean>comparing(c->!illustrative.test(c))
                .thenComparing(c->trivial.test(c)).thenComparing(c->-c.path("input").asText().length())
                .thenComparing(c->c.path("id").asText())).toList();
        var chosen=new java.util.ArrayList<JsonNode>();var outputs=new HashSet<String>();
        for(var c:ranked)if(chosen.size()<3&&outputs.add(c.path("output").asText().strip()))chosen.add(c);
        for(var c:ranked)if(chosen.size()<3&&!chosen.contains(c))chosen.add(c);
        chosen.sort(java.util.Comparator.comparingInt(c->c.path("input").asText().length()));
        return chosen;
    }
}
