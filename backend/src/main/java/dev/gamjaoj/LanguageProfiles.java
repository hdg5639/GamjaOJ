package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Server-owned, versioned execution profiles. Never accept commands or limits from a client. */
final class LanguageProfiles {
    public record Option(String id, String label, String file, int timeLimitMs, int memoryMb) {}
    static String normalize(String language) {
        String value=language==null?"JAVA":language;
        if(!List.of("JAVA","CPP","PYTHON").contains(value))
            throw new AccountException(400,"지원하는 언어를 선택해 주세요: Java, C++, Python.");
        return value;
    }
    static JsonNode profile(String language) { return RunnerEnvironment.expected().path("languages").path(normalize(language)).deepCopy(); }
    static JsonNode profile(String language,String limitsJson) {
        var p=(com.fasterxml.jackson.databind.node.ObjectNode)profile(language);
        var limits=ProblemTimeLimits.parse(limitsJson);
        if(limits!=null) {
            var seconds=limits.path(normalize(language));
            if(seconds.isIntegralNumber())p.put("testWallSeconds",seconds.asInt());else p.put("testWallSeconds",seconds.asDouble());
            if(limits.has("memory"))p.put("memoryMb",limits.path("memory").path(normalize(language)).asInt());
        }
        return p;
    }
    static Option option(JsonNode profile) {
        return new Option(profile.path("language").asText(),profile.path("label").asText(),profile.path("sourceFile").asText(),
                (int)Math.round(profile.path("testWallSeconds").asDouble()*1000),profile.path("memoryMb").asInt());
    }
    static List<Option> options() { return List.of(option(profile("JAVA")),option(profile("CPP")),option(profile("PYTHON"))); }
    static List<Option> options(String limitsJson) {
        return ProblemTimeLimits.LANGUAGES.stream().map(l->option(profile(l,limitsJson))).toList();
    }
}
