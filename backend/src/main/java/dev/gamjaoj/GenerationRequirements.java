package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Shared author/reviewer policy; assessments are stored evidence, not correctness certificates. */
final class GenerationRequirements {
    static final String AUTHOR_VERSION="rule-author-requirements-v2";
    static final String AUTHOR=read("requirements-author.txt"), REVIEW=read("requirements-review.txt"), RULE_AUTHOR=read("requirements-rule-author.txt");
    private static String read(String name) {
        try(var in=GenerationRequirements.class.getResourceAsStream("/generation/"+name)) {
            if(in==null)throw new IllegalStateException("Missing generation policy: "+name);
            return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        } catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
    static boolean requiresAuthorReview(String version) {
        return AUTHOR_VERSION.equals(version)||"rule-author-requirements-v1".equals(version);
    }
    static ObjectNode schema() {
        return (ObjectNode)JudgeJson.parse(read("requirements-schema.json"));
    }
    static void addSchema(ObjectNode schema) {
        ((ObjectNode)schema.path("properties")).set("requirementsReview",schema());
        ((com.fasterxml.jackson.databind.node.ArrayNode)schema.path("required")).add("requirementsReview");
    }
    static void validate(JsonNode r,boolean mustPass) {
        HybridArtifacts.fields(r,"satisfied","coverage","complexity","shortcuts","issues","timeLimits");
        HybridArtifacts.require(r.path("satisfied").isBoolean(),"INVALID_REQUIREMENTS_REVIEW");
        ProblemTimeLimits.validate(r.path("timeLimits"));
        var coverage=r.path("coverage");
        HybridArtifacts.require(coverage.isArray()&&!coverage.isEmpty()&&coverage.size()<=32,"INVALID_REQUIREMENTS_REVIEW");
        for(var item:coverage) {
            HybridArtifacts.fields(item,"requirement","evidence");
            HybridArtifacts.text(item.path("requirement"),2000);HybridArtifacts.text(item.path("evidence"),4000);
        }
        for(String field:List.of("complexity","shortcuts"))HybridArtifacts.text(r.path(field),6000);
        HybridArtifacts.texts(r.path("issues"),0,16,2000);
        boolean passed=r.path("satisfied").asBoolean();
        HybridArtifacts.require(passed==r.path("issues").isEmpty(),"INVALID_REQUIREMENTS_REVIEW");
        HybridArtifacts.require(!mustPass||passed,"REQUIREMENTS_NOT_MET");
    }
}
