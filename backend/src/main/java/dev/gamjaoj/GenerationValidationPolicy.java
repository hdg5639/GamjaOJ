package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.core.io.ClassPathResource;

/** Authored validation guidance, not certificates for a newly generated problem. */
final class GenerationValidationPolicy {
    private static final JsonNode POLICY=load();
    private static JsonNode load() {
        try(var stream=new ClassPathResource("generation/resource-profiles-v1.json").getInputStream()) {
            return JudgeJson.JSON.readTree(stream);
        } catch(java.io.IOException e){throw new IllegalStateException("Generation validation policy unavailable",e);}
    }
    private static String normalized(String value){return value.toLowerCase(Locale.ROOT).replaceAll("[\\s·_\\-]","");}
    static ObjectNode forProblem(JsonNode definition) {
        var result=JudgeJson.JSON.createObjectNode().put("version",POLICY.path("version").asText()).put("policyHash",JudgeJson.hash(JudgeJson.canonical(POLICY)))
            .put("scope","Required generation/review guidance. Historical certificates do not qualify a new problem.");
        result.set("timePolicy",POLICY.path("timePolicy").deepCopy());
        result.set("commonChecks",POLICY.path("commonChecks").deepCopy());
        var semantic=definition.deepCopy();if(semantic.isObject())((ObjectNode)semantic).remove(List.of("validationPolicy","theme","themeDomain","recentStories","learnerFeedback","learningFocus","generatorContract","inputLayoutPolicy","permissionNote","runtime"));
        String raw=JudgeJson.canonical(semantic).toLowerCase(Locale.ROOT), text=normalized(raw);
        var selected=result.putArray("profiles");
        for(var profile:POLICY.path("profiles")) {
            boolean match="input-contract".equals(profile.path("id").asText())||("command".equals(profile.path("id").asText())&&(definition.has("callable")||definition.has("api")));
            for(var alias:profile.path("aliases")) {
                String key=normalized(alias.asText());
                if(alias.asText().matches("[A-Za-z0-9_-]+")) {
                    String pattern=Arrays.stream(alias.asText().toLowerCase(Locale.ROOT).split("[-_]")).map(java.util.regex.Pattern::quote).collect(java.util.stream.Collectors.joining("[-_\\s]*"));
                    if(java.util.regex.Pattern.compile("(?<![a-z0-9])"+pattern+"(?![a-z0-9])").matcher(raw).find())match=true;
                } else if((key.length()>=3&&text.contains(key))||text.contains("\""+key+"\""))match=true;
            }
            if(match)selected.add(profile.deepCopy());
        }
        return result;
    }
    static void attach(ObjectNode task,JsonNode definition){task.set("validationPolicy",forProblem(definition));}
    private GenerationValidationPolicy(){}
}
