package dev.gamjaoj.domain;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamjaoj.exception.AccountException;
import java.util.List;

/** Server-owned, versioned execution profiles. Never accept commands or limits from a client. */
public final class LanguageProfiles {
  public record Option(
      String id, String label, String file, int timeLimitMs, int memoryMb, String timeMetric) {}

  public static String normalize(String language) {
    String value = language == null ? "JAVA" : language;
    if (!List.of("JAVA", "CPP", "PYTHON").contains(value))
      throw new AccountException(400, "지원하는 언어를 선택해 주세요: Java, C++, Python.");
    return value;
  }

  public static JsonNode profile(String language) {
    return RunnerEnvironment.expected().path("languages").path(normalize(language)).deepCopy();
  }

  public static JsonNode profile(String language, String limitsJson) {
    var p = (com.fasterxml.jackson.databind.node.ObjectNode) profile(language);
    var limits = ProblemTimeLimits.parse(limitsJson);
    if (limits != null) {
      var seconds = limits.path(normalize(language));
      if (seconds.isIntegralNumber()) p.put("testWallSeconds", seconds.asInt());
      else p.put("testWallSeconds", seconds.asDouble());
      if (limits.has("memory"))
        p.put("memoryMb", limits.path("memory").path(normalize(language)).asInt());
      if (limits.path("cpu").has(normalize(language)))
        p.put("testCpuSeconds", limits.path("cpu").path(normalize(language)).asDouble());
    }
    return p;
  }

  public static Option option(JsonNode profile) {
    return new Option(
        profile.path("language").asText(),
        profile.path("label").asText(),
        profile.path("sourceFile").asText(),
        (int)
            Math.round(
                profile
                        .path(profile.has("testCpuSeconds") ? "testCpuSeconds" : "testWallSeconds")
                        .asDouble()
                    * 1000),
        profile.path("memoryMb").asInt(),
        profile.has("testCpuSeconds") ? "CPU" : "WALL");
  }

  public static List<Option> options() {
    return List.of(option(profile("JAVA")), option(profile("CPP")), option(profile("PYTHON")));
  }

  public static List<Option> options(String limitsJson) {
    return ProblemTimeLimits.LANGUAGES.stream().map(l -> option(profile(l, limitsJson))).toList();
  }
}
