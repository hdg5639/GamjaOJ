package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * A registered rule as data (engine PACKAGE_V1). Every field was produced during onboarding and qualified
 * in the Runner: tiny answers agree between an independent brute-force oracle and the reference; stress
 * answers are reference outputs within the resource margin; each mutant fails its recorded witness.
 * Nothing here is executed in the application process.
 */
record HybridRulePackage(String versionId,JsonNode contract,JsonNode rules,JsonNode catalog,String generator,String validator,
                         List<HybridFiniteProfile.Case> tiny,List<HybridFiniteProfile.Case> invalid,List<HybridFiniteProfile.Case> stress,
                         Map<String,String> mutantSources,Map<String,HybridFiniteProfile.Case> witnesses,
                         String oracleDomain,String enumeration,String authorGuidance,String teachingGuidance,String readerGuidance) {
    static final String ENGINE="PACKAGE_V1";
    static final int MIN_TINY=6,MAX_TINY=24,MAX_STRESS=3,MAX_INVALID=10;
    String policy(){return "pkg-"+versionId;}
    String supportVersion(){return "pkg-support:"+versionId;}
    String rulesVersion(){return "pkg-rules:"+versionId;}
    List<String> mutants(){return List.copyOf(new TreeSet<>(mutantSources.keySet()));}

    private static String text(JsonNode n,int max) {
        if(n==null||!n.isTextual()||n.asText().isBlank()||n.asText().getBytes(StandardCharsets.UTF_8).length>max)throw new HybridArtifacts.Invalid("RULE_PACKAGE_FIELD");
        return n.asText();
    }
    private static List<HybridFiniteProfile.Case> cases(JsonNode n,String prefix,int min,int max,int bytes,boolean answers) {
        if(n==null||!n.isArray()||n.size()<min||n.size()>max)throw new HybridArtifacts.Invalid("RULE_PACKAGE_CASES");
        var out=new ArrayList<HybridFiniteProfile.Case>();var seen=new HashSet<String>();
        for(var c:n) {
            String input=text(c.path("input"),bytes);if(!seen.add(input))throw new HybridArtifacts.Invalid("RULE_PACKAGE_DUPLICATE_INPUT");
            out.add(new HybridFiniteProfile.Case(prefix+out.size(),input,answers?text(c.path("output"),4096):"INVALID\n"));
        }
        return List.copyOf(out);
    }
    /** Parses a stored package; any structural deviation rejects the version rather than repairing it. */
    static HybridRulePackage parse(String versionId,JsonNode p) {
        HybridArtifacts.fields(p,"contract","rules","catalog","generator","validator","tiny","invalid","stress","mutants","oracleDomain","enumeration","guidance");
        var contract=HybridArtifacts.contract(p.path("contract"));
        // Exactly one Korean normative explanation per contract action, as the public snapshot requires.
        var rules=p.path("rules");var actions=new HashSet<String>();contract.path("actions").forEach(a->actions.add(a.path("id").asText()));
        if(!rules.isArray()||rules.size()!=actions.size())throw new HybridArtifacts.Invalid("RULE_PACKAGE_RULES");
        for(var r:rules){HybridArtifacts.fields(r,"id","text");text(r.path("text"),4000);if(!actions.remove(r.path("id").asText()))throw new HybridArtifacts.Invalid("RULE_PACKAGE_RULES");}
        var tiny=cases(p.path("tiny"),"tiny-",MIN_TINY,MAX_TINY,1024,true);
        var stress=cases(p.path("stress"),"stress-",1,MAX_STRESS,16384,true);
        var invalid=cases(p.path("invalid"),"invalid-",2,MAX_INVALID,1024,false);
        var tinyAnswers=new HashMap<String,String>();tiny.forEach(c->tinyAnswers.put(c.input(),c.output()));
        var sources=new TreeMap<String,String>();var witnesses=new TreeMap<String,HybridFiniteProfile.Case>();
        // Exactly two, like every built-in profile, so both scheduling paths keep their fixed check count.
        if(!p.path("mutants").isArray()||p.path("mutants").size()!=2)throw new HybridArtifacts.Invalid("RULE_PACKAGE_MUTANTS");
        for(var m:p.path("mutants")) {
            HybridArtifacts.fields(m,"id","source","witness");
            String id=text(m.path("id"),40);if(!id.matches("mutant-[a-z0-9-]{1,32}")||sources.containsKey(id))throw new HybridArtifacts.Invalid("RULE_PACKAGE_MUTANTS");
            String witness=text(m.path("witness"),1024);
            // A witness is always a tiny input whose answer came from the independent oracle.
            if(!tinyAnswers.containsKey(witness))throw new HybridArtifacts.Invalid("RULE_PACKAGE_WITNESS");
            sources.put(id,text(m.path("source"),65536));witnesses.put(id,new HybridFiniteProfile.Case("witness-"+id,witness,tinyAnswers.get(witness)));
        }
        var catalog=p.path("catalog");
        HybridArtifacts.fields(catalog,"label","description","category","tags","rules");
        text(catalog.path("label"),120);text(catalog.path("description"),600);text(catalog.path("category"),40);
        if(!catalog.path("tags").isArray()||catalog.path("tags").size()<1||catalog.path("tags").size()>6)throw new HybridArtifacts.Invalid("RULE_PACKAGE_CATALOG");
        for(var t:catalog.path("tags")){text(t,30);if(t.asText().contains(","))throw new HybridArtifacts.Invalid("RULE_PACKAGE_CATALOG");}
        if(!catalog.path("rules").isArray()||catalog.path("rules").size()<1||catalog.path("rules").size()>5)throw new HybridArtifacts.Invalid("RULE_PACKAGE_CATALOG");
        for(var r:catalog.path("rules"))text(r,300);
        var g=p.path("guidance");HybridArtifacts.fields(g,"author","teaching","reader");
        return new HybridRulePackage(versionId,contract,rules.deepCopy(),catalog.deepCopy(),text(p.path("generator"),65536),text(p.path("validator"),65536),
                tiny,invalid,stress,Collections.unmodifiableMap(sources),Collections.unmodifiableMap(witnesses),
                text(p.path("oracleDomain"),200),text(p.path("enumeration"),200),text(g.path("author"),1500),text(g.path("teaching"),1500),text(g.path("reader"),1500));
    }
    String hash(JsonNode stored){return JudgeJson.hash(ENGINE+"\n"+versionId+"\n"+JudgeJson.canonical(stored)+"\nwall<=4000;repeat=2;package<=20;answers=oracle-tiny,reference-stress");}
    String answer(String input) {
        for(var list:List.of(tiny,stress))for(var c:list)if(c.input().equals(input))return c.output();
        throw new IllegalArgumentException("PROFILE_INPUT_BOUND");
    }
    boolean tiny(String input){return tiny.stream().anyMatch(c->c.input().equals(input));}
    /** Four distinct qualified tiny inputs chosen by seed; no unqualified answers enter a package. */
    List<String> random(long seed) {
        var pool=new ArrayList<String>();tiny.forEach(c->pool.add(c.input()));
        var r=new SplittableRandom(seed);
        for(int i=pool.size()-1;i>0;i--){int j=r.nextInt(i+1);var t=pool.get(i);pool.set(i,pool.get(j));pool.set(j,t);}
        return List.copyOf(pool.subList(0,4));
    }
    List<JsonNode> tests(String role) {
        boolean validator=role.equals("domain-valid")||role.equals("stress-valid");
        List<HybridFiniteProfile.Case> cs;
        if(role.startsWith("mutant-")) {
            var w=witnesses.get(role);if(w==null)throw new IllegalArgumentException("UNKNOWN_MUTANT");cs=List.of(w);
        } else cs=switch(role) {
            case "domain-invalid"->invalid;
            case "stress-valid","stress-reference-0","stress-reference-1"->stress;
            case "domain-valid","domain-reference","domain-oracle"->tiny;
            default->throw new IllegalArgumentException("UNKNOWN_FINITE_ROLE");
        };
        return cs.stream().map(c->(JsonNode)JudgeJson.JSON.createObjectNode().put("id",c.id()).put("input",c.input()).put("output",validator?"VALID\n":c.output())).toList();
    }
    ObjectNode coverage(String profileHash) {
        return JudgeJson.JSON.createObjectNode().put("profileHash",profileHash).put("cases",tiny.size())
                .put("description","Qualified tiny inputs for the registered rule: "+oracleDomain)
                .put("entireContractExhausted",false).put("entireDeclaredOracleDomainExhausted",false)
                .put("expectedAnswers","independent onboarding oracle agreed with the qualified reference; stress answers are reference outputs");
    }
    ObjectNode maximumChecks() {
        return JudgeJson.JSON.createObjectNode().put("cases",stress.size()).put("repetitions",2).put("perTestMarginMs",4000)
                .put("executionMode","EXCLUSIVE").put("worstCaseForEveryAlgorithm",false);
    }
    String readerInstructions() {
        return " The server supports this registered rule. Use exactly inputDomain=\""+oracleDomain+"\" and enumeration=\""+enumeration
                +"\". Independently implement a Java 8 brute-force oracle for that small domain from the public snapshot only. Supply 1 to 8 small valid inputs inside that domain, exactly matching the public input format. No expected outputs, malformed inputs, ellipses or expanded maximum-size inputs. "+readerGuidance;
    }
}
