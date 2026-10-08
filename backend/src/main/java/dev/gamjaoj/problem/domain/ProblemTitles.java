package dev.gamjaoj.problem.domain;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamjaoj.shared.support.JudgeJson;
import java.nio.charset.StandardCharsets;

/** Cosmetic repairs never mutate frozen judge packages or their thinking profiles. */
public final class ProblemTitles {
  private static final JsonNode OVERRIDES = load();

  private static JsonNode load() {
    try (var input = ProblemTitles.class.getResourceAsStream("/problem-title-overrides.json")) {
      return JudgeJson.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException("Missing title repair metadata", e);
    }
  }

  public static String display(JsonNode problem) {
    String title = problem.path("title").asText("").strip();
    if (!title.isEmpty()) return title;
    String version = problem.path("version").asText("미분류");
    var repair = OVERRIDES.path(version);
    if (repair.path("packageSha256").asText().equals(JudgeJson.hash(JudgeJson.canonical(problem))))
      return repair.path("title").asText();
    return "연습 문제 · " + version;
  }
}
