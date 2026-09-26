package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Structural acceptance only. Natural-language meaning still needs executable/profile evidence. */
final class HybridArtifacts {
    static final String VERSION="HYBRID_V1";
    static final List<String> PUBLIC_FIELDS=List.of("domain","input","state","actions","goal","termination","output","limits");
    static final int MAX_PAYLOAD_BYTES=256*1024;
    static class Invalid extends RuntimeException { Invalid(String code){super(code);} }
    static void require(boolean test,String code){if(!test)throw new Invalid(code);}
    static void fields(JsonNode node,String... names) {
        require(node!=null&&node.isObject(),"INVALID_OBJECT");
        var actual=new HashSet<String>();node.fieldNames().forEachRemaining(actual::add);
        require(actual.equals(Set.of(names)),"INVALID_FIELDS");
    }
    static void text(JsonNode node,int maximum) {
        require(node!=null&&node.isTextual()&&!node.asText().isBlank()&&node.asText().length()<=maximum,"INVALID_TEXT");
    }
    static void texts(JsonNode node,int min,int max,int length) {
        require(node!=null&&node.isArray()&&node.size()>=min&&node.size()<=max,"INVALID_LIST");
        node.forEach(v->text(v,length));
    }
    static JsonNode bounded(JsonNode payload) {
        require(payload!=null&&JudgeJson.canonical(payload).getBytes(StandardCharsets.UTF_8).length<=MAX_PAYLOAD_BYTES,"ARTIFACT_TOO_LARGE");
        return payload.deepCopy();
    }
    static void schema(JsonNode node){require(node.path("schemaVersion").asText().equals("1"),"UNSUPPORTED_SCHEMA");}
    static JsonNode contract(JsonNode candidate) {
        JsonNode c=bounded(candidate);
        fields(c,"schemaVersion","domain","input","state","actions","goal","termination","output","limits","obligations");schema(c);
        section(c.path("domain"),"entities","types","relationships");
        section(c.path("input"),"format","indexing","caseCount");
        section(c.path("state"),"initial","mutable");
        var actions=c.path("actions");require(actions.isArray()&&!actions.isEmpty()&&actions.size()<=16,"INCOMPLETE_ACTIONS");
        var ids=new HashSet<String>();
        for(var a:actions){section(a,"id","preconditions","transition","reuse","resources");
            require(a.path("id").asText().matches("[a-z][a-z0-9-]{0,39}")&&ids.add(a.path("id").asText()),"INVALID_RULE_ID");}
        section(c.path("goal"),"kind","definition");
        require(Set.of("FEASIBILITY","COUNT","MINIMIZE","MAXIMIZE","EXACT").contains(c.path("goal").path("kind").asText()),"INVALID_OBJECTIVE");
        text(c.path("termination"),4000);
        section(c.path("output"),"format","ties","empty","impossible","numericRange");
        section(c.path("limits"),"maxInputSize","executionConstraints");
        var obligations=c.path("obligations");fields(obligations,"smallDomain","boundaryCases","invalidCases","stress","mutants");
        text(obligations.path("smallDomain"),4000);text(obligations.path("stress"),4000);
        for(String name:List.of("boundaryCases","invalidCases","mutants"))texts(obligations.path(name),1,16,2000);
        return c;
    }
    private static void section(JsonNode node,String... keys){fields(node,keys);for(String key:keys)text(node.path(key),4000);}
    private static void source(JsonNode node) {
        text(node,65536);require(node.asText().getBytes(StandardCharsets.UTF_8).length<=65536,"SOURCE_TOO_LARGE");
    }
    static JsonNode core(JsonNode candidate) {
        var c=bounded(candidate);fields(c,"schemaVersion","reference","generator","inputValidator","authorNotes");schema(c);
        for(String field:List.of("reference","generator","inputValidator"))source(c.path(field));
        section(c.path("authorNotes"),"algorithm","complexity","edgeCases");return c;
    }
    static JsonNode presentation(JsonNode candidate,JsonNode contract) {
        var p=bounded(candidate);fields(p,"schemaVersion","title","context","semantics","ruleExplanations","hints","editorial");schema(p);
        text(p.path("title"),160);text(p.path("context"),8000);text(p.path("editorial"),12000);texts(p.path("hints"),3,3,2000);
        // Models cannot override canonical IO, limits or rule semantics in the public snapshot.
        require(p.path("semantics").equals(publicSemantics(contract)),"PUBLIC_CONTRACT_MISMATCH");
        var rules=p.path("ruleExplanations");require(rules.isArray()&&rules.size()==contract.path("actions").size(),"MISSING_RULE_COVERAGE");
        var expected=new HashSet<String>();contract.path("actions").forEach(a->expected.add(a.path("id").asText()));
        for(var rule:rules){section(rule,"id","text");require(expected.remove(rule.path("id").asText()),"INVALID_RULE_COVERAGE");}
        return p;
    }
    static ObjectNode publicSemantics(JsonNode contract) {
        var out=JudgeJson.JSON.createObjectNode();PUBLIC_FIELDS.forEach(k->out.set(k,contract.path(k).deepCopy()));return out;
    }
    static ObjectNode publicSnapshot(JsonNode presentation) {
        // Construct from an allowlist. Teaching, author notes, personal context and answers cannot enter.
        var out=JudgeJson.JSON.createObjectNode().put("schemaVersion","1");
        for(String key:List.of("title","context","semantics","ruleExplanations"))out.set(key,presentation.path(key).deepCopy());
        return out;
    }
    static JsonNode contentReview(JsonNode candidate,String inputHash) {
        var r=bounded(candidate);fields(r,"schemaVersion","inputHash","proseEquivalent","teachingCorrect","implementationAligned","issues","reasoning");schema(r);
        require(r.path("inputHash").asText().equals(inputHash),"CONTENT_REVIEW_INPUT_FENCE");
        texts(r.path("issues"),0,16,2000);text(r.path("reasoning"),12000);
        for(String gate:List.of("proseEquivalent","teachingCorrect","implementationAligned"))
            require(r.path(gate).isBoolean()&&r.path(gate).asBoolean(),"CONTENT_REVIEW_REJECTED");
        require(r.path("issues").isEmpty(),"CONTENT_REVIEW_REJECTED");return r;
    }
    static JsonNode reader(JsonNode candidate) {
        var r=bounded(candidate);fields(r,"schemaVersion","interpretedRules","ambiguities","oracleSource","oracleDomain","adversarialInputs","coverageNotes");schema(r);
        texts(r.path("interpretedRules"),1,32,4000);texts(r.path("ambiguities"),0,16,2000);
        source(r.path("oracleSource"));section(r.path("oracleDomain"),"inputDomain","enumeration","limitations");
        text(r.path("coverageNotes"),4000);
        var cases=r.path("adversarialInputs");require(cases.isArray()&&!cases.isEmpty()&&cases.size()<=16,"MISSING_ADVERSARIAL_INPUTS");
        for(var item:cases)section(item,"input","reason");
        require(r.path("ambiguities").isEmpty(),"READER_AMBIGUITY");return r;
    }
}
