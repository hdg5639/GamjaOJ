package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;

/** Sources are pinned in branch input before dispatch; recovery never loads replacement sources. */
final class HybridCoreSupport {
    static ObjectNode bundle() {return bundle(HybridProfiles.KNAPSACK);}
    static ObjectNode bundle(HybridProfiles.Definition profile) {
        var b=JudgeJson.JSON.createObjectNode().put("version",profile.supportVersion())
                .put("contractHash",JudgeJson.hash(JudgeJson.canonical(profile.contract())))
                .put("generator",source(profile,"generator")).put("inputValidator",source(profile,"validator"));
        b.put("sourcesHash",JudgeJson.hash(b.path("generator").asText()+"\n"+b.path("inputValidator").asText()));
        return b;
    }
    private static String source(HybridProfiles.Definition profile,String name) {
        if(profile.pkg()!=null)return name.equals("generator")?profile.pkg().generator():profile.pkg().validator();
        try(var in=HybridCoreSupport.class.getResourceAsStream("/hybrid/"+profile.supportVersion()+"/"+name+".java.txt")) {
            if(in==null)throw new IllegalStateException("Missing core support");
            return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
    static void validate(JsonNode input) {
        var b=input.path("serverSupport");
        HybridArtifacts.fields(b,"version","contractHash","generator","inputValidator","sourcesHash");
        var profile=HybridProfiles.byContract(input.path("contract"));
        HybridArtifacts.require(b.path("version").asText().equals(profile.supportVersion())
                &&b.path("contractHash").asText().equals(JudgeJson.hash(JudgeJson.canonical(input.path("contract"))))
                &&b.path("sourcesHash").asText().equals(JudgeJson.hash(b.path("generator").asText()+"\n"+b.path("inputValidator").asText())),"CORE_SUPPORT_FENCE");
    }
    static JsonNode assemble(JsonNode input,JsonNode payload) {
        if(!input.has("serverSupport"))return HybridArtifacts.core(payload);
        validate(input);
        HybridArtifacts.fields(payload,"schemaVersion","reference","authorNotes");
        var result=(ObjectNode)payload.deepCopy();
        result.set("generator",input.path("serverSupport").path("generator"));
        result.set("inputValidator",input.path("serverSupport").path("inputValidator"));
        return HybridArtifacts.core(result);
    }
}
