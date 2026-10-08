package dev.gamjaoj.problem.service;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamjaoj.generation.service.CallablePrograms;
import dev.gamjaoj.judge.service.Submissions;
import dev.gamjaoj.problem.domain.ProblemTitles;
import dev.gamjaoj.shared.support.JudgeJson;
import java.util.*;

/**
 * Bounded, package-hash-keyed public projection. Authorization and mutable metadata are never
 * cached.
 */
public final class PublicProblemPackages {
  public record Public(
      String title,
      String statement,
      String sampleInput,
      String sampleOutput,
      List<Submissions.Example> examples,
      JsonNode api) {}

  private final Map<String, Public> values = new LinkedHashMap<>();
  private int bytes;

  public synchronized Map<String, Public> snapshot() {
    return Map.copyOf(values);
  }

  public synchronized Public put(String hash, String json) {
    var existing = values.get(hash);
    if (existing != null) return existing;
    var data = JudgeJson.parse(json);
    var projection =
        new Public(
            ProblemTitles.display(data),
            data.path("statement").asText(),
            data.path("tests").get(0).path("input").asText(),
            data.path("tests").get(0).path("output").asText(),
            List.copyOf(Submissions.examples(data, null)),
            data.has("api") ? CallablePrograms.publicBundle(data.path("api")) : null);
    int weight = projection.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    if (weight > 16 * 1024 * 1024) return projection;
    if (values.size() >= 1024 || bytes + weight > 16 * 1024 * 1024) {
      values.clear();
      bytes = 0;
    }
    if (!values.containsKey(hash)) {
      values.put(hash, projection);
      bytes += weight;
    }
    return projection;
  }
}
