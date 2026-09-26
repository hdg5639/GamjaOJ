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
    static Option option(JsonNode profile) {
        return new Option(profile.path("language").asText(),profile.path("label").asText(),profile.path("sourceFile").asText(),
                profile.path("testWallSeconds").asInt()*1000,profile.path("memoryMb").asInt());
    }
    static List<Option> options() { return List.of(option(profile("JAVA")),option(profile("CPP")),option(profile("PYTHON"))); }
}
