package dev.gamjaoj.generation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gamjaoj.generation.repository.ExperimentalReviewRepository;
import dev.gamjaoj.problem.service.ThinkingDifficulty;
import dev.gamjaoj.shared.support.JudgeJson;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;

/** Independent proposed counterexamples only become evidence after execution by the Runner. */
@Service
public class ExperimentalReview {
  private final ExperimentalReviewRepository repository;
  private final ExperimentalChecks checks;

  public ExperimentalReview(ExperimentalReviewRepository repository, ExperimentalChecks checks) {
    this.repository = repository;
    this.checks = checks;
  }

  public static void validate(JsonNode value) {
    validate(value, false);
  }

  public static void validate(JsonNode value, boolean requirements) {
    validate(value, requirements, false);
  }

  public static void validate(JsonNode value, boolean requirements, boolean thinking) {
    if (value == null || !value.isObject()) throw new IllegalArgumentException();
    if ((thinking && value.path("verdict").asText().equals("ACCEPT")) || value.has("thinking"))
      ThinkingDifficulty.validate(value.path("thinking"));
    if (requirements || value.has("requirementsReview"))
      GenerationRequirements.validate(
          value.path("requirementsReview"), value.path("verdict").asText().equals("ACCEPT"));
    var shape = (ObjectNode) value.deepCopy();
    shape.remove("requirementsReview");
    shape.remove("thinking");
    shape.remove("failureScope");
    if (value.has("failureScope")
        && !Set.of("PROSE", "IMPLEMENTATION", "CONTRACT", "REVIEW")
            .contains(value.path("failureScope").asText())) throw new IllegalArgumentException();
    if (value.path("verdict").asText().equals("ACCEPT")
        && value.has("failureScope")
        && !value.path("failureScope").asText().equals("REVIEW"))
      throw new IllegalArgumentException();
    object(shape, Set.of("verdict", "issues", "validCases", "invalidCases", "mutants"));
    if (!List.of("ACCEPT", "REVISE").contains(value.path("verdict").asText()))
      throw new IllegalArgumentException();
    array(value.path("issues"), 0, 8);
    for (var issue : value.path("issues")) text(issue, 2000, false);
    array(value.path("validCases"), 0, 8);
    array(value.path("invalidCases"), 0, 8);
    array(value.path("mutants"), 0, 2);
    for (var test : value.path("validCases")) test(test);
    for (var input : value.path("invalidCases")) text(input, 4096, true);
    for (var mutant : value.path("mutants")) {
      object(mutant, Set.of("source", "witness", "explanation"));
      text(mutant.path("source"), 65536, false);
      text(mutant.path("explanation"), 2000, false);
      test(mutant.path("witness"));
    }
    if (value.path("verdict").asText().equals("ACCEPT")) {
      if (!value.path("issues").isEmpty()
          || value.path("validCases").size() < 2
          || value.path("invalidCases").size() < 2
          || value.path("mutants").size() != 2) throw new IllegalArgumentException();
      if (value
          .path("mutants")
          .get(0)
          .path("source")
          .equals(value.path("mutants").get(1).path("source")))
        throw new IllegalArgumentException();
    } else if (value.path("issues").isEmpty()) throw new IllegalArgumentException();
  }

  private static void test(JsonNode value) {
    object(value, Set.of("input", "output", "reason"));
    text(value.path("input"), 4096, false);
    text(value.path("output"), 4096, false);
    text(value.path("reason"), 2000, false);
  }

  private static void object(JsonNode value, Set<String> keys) {
    if (value == null || !value.isObject() || value.size() != keys.size())
      throw new IllegalArgumentException();
    value
        .fieldNames()
        .forEachRemaining(
            k -> {
              if (!keys.contains(k)) throw new IllegalArgumentException();
            });
  }

  private static void array(JsonNode value, int min, int max) {
    if (!value.isArray() || value.size() < min || value.size() > max)
      throw new IllegalArgumentException();
  }

  private static void text(JsonNode value, int bytes, boolean empty) {
    if (!value.isTextual()
        || (!empty && value.asText().isBlank())
        || value.asText().getBytes(StandardCharsets.UTF_8).length > bytes)
      throw new IllegalArgumentException();
  }

  private ObjectNode test(String id, JsonNode value) {
    return JudgeJson.JSON
        .createObjectNode()
        .put("id", id)
        .put("input", value.path("input").asText())
        .put("output", value.path("output").asText());
  }

  private String[] snapshot(UUID id) {
    return repository.snapshotGenerationSpecDraft(
        id,
        (r, n) ->
            new String[] {
              r.getString(1),
              r.getString(2),
              r.getString(3),
              r.getString(4),
              r.getString(5),
              r.getString(6),
              r.getString(7),
              r.getString(8)
            });
  }

  public boolean intact(UUID id) {
    var s = snapshot(id);
    if (s[0] == null || s[2] == null || s[3] == null || s[5] == null) return false;
    var report = JudgeJson.parse(s[5]);
    return JudgeJson.hash(s[0]).equals(s[1])
        && JudgeJson.hash(s[2] + "\n" + s[3]).equals(s[4])
        && s[1].equals(report.path("specHash").asText())
        && s[4].equals(report.path("artifactHash").asText())
        && (s[6] == null || JudgeJson.hash(s[6]).equals(s[7]));
  }

  private ObjectNode summary(UUID id, JsonNode review) {
    var s = snapshot(id);
    var report =
        JudgeJson.JSON
            .createObjectNode()
            .put("policy", "experimental-independent-review-v1")
            .put("specHash", s[1])
            .put("artifactHash", s[4])
            .put("reviewHash", s[7])
            .put("verdict", review.path("verdict").asText())
            .put("publishable", false)
            .put("executions", 0);
    report.set("issues", review.path("issues").deepCopy());
    if (review.has("thinking")) report.set("thinking", review.path("thinking").deepCopy());
    if (review.has("requirementsReview"))
      report.set("requirementsReview", review.path("requirementsReview").deepCopy());
    report
        .put("boundaryCases", review.path("validCases").size())
        .put("invalidCases", review.path("invalidCases").size())
        .put("mutants", review.path("mutants").size());
    report
        .putArray("remaining")
        .add("bounded exhaustive domain")
        .add("resource envelope")
        .add("independent final seed");
    return report;
  }

  public void start(UUID id, JsonNode review) {
    if (!intact(id)) {
      fail(id, "REVIEW_ARTIFACT_FENCE_MISMATCH");
      return;
    }
    var report = summary(id, review);
    repository.startGenerationSpecDraft(
        report.toString(),
        review.path("verdict").asText().equals("ACCEPT") ? "REVIEW_CHECKING" : "REVIEW_REJECTED",
        id);
    if (!review.path("verdict").asText().equals("ACCEPT")) return;
    var s = snapshot(id);
    var artifacts = JudgeJson.parse(s[2]);
    var oracle = JudgeJson.parse(s[3]);
    var valid = new ArrayList<JsonNode>();
    int index = 0;
    for (var item : review.path("validCases")) valid.add(test("boundary-" + index++, item));
    index = 0;
    for (var mutant : review.path("mutants"))
      valid.add(test("witness-" + index++, mutant.path("witness")));
    checks.execute(
        id, "review-reference", artifacts.path("reference").asText(), valid, false, "AC");
    checks.execute(id, "review-oracle", oracle.path("source").asText(), valid, false, "AC");
    checks.execute(
        id,
        "review-valid-inputs",
        artifacts.path("inputValidator").asText(),
        valid.stream()
            .map(t -> (JsonNode) ((ObjectNode) t.deepCopy()).put("output", "VALID\n"))
            .toList(),
        false,
        "AC");
    var invalid = new ArrayList<JsonNode>();
    index = 0;
    for (var input : review.path("invalidCases"))
      invalid.add(
          JudgeJson.JSON
              .createObjectNode()
              .put("id", "invalid-" + index++)
              .put("input", input.asText())
              .put("output", "INVALID\n"));
    checks.execute(
        id,
        "review-invalid-inputs",
        artifacts.path("inputValidator").asText(),
        invalid,
        false,
        "AC");
    index = 0;
    for (var mutant : review.path("mutants")) {
      checks.execute(
          id,
          "review-mutant-" + index,
          mutant.path("source").asText(),
          List.of(test("witness-" + index, mutant.path("witness"))),
          false,
          "WA");
      index++;
    }
  }

  private void fail(UUID id, String error) {
    repository.failGenerationSpecDraft(error, id);
  }

  public void advance() {
    for (UUID id : repository.advanceGenerationSpecDraft()) {
      var rows =
          repository.advanceGenerationSpecExecution(
              id,
              (r, n) ->
                  new String[] {
                    r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5)
                  });
      if (rows.stream().anyMatch(r -> !r[2].equals("FINISHED"))) continue;
      if (rows.size() != 6) {
        fail(id, "INCOMPLETE_REVIEW_CHECKS");
        continue;
      }
      if (!intact(id)) {
        fail(id, "REVIEW_ARTIFACT_FENCE_MISMATCH");
        continue;
      }
      var s = snapshot(id);
      var report = summary(id, JudgeJson.parse(s[6])).put("executions", 6);
      var results = report.putArray("results");
      for (var row : rows)
        results
            .addObject()
            .put("role", row[0])
            .put("expected", row[1])
            .put("verdict", row[3])
            .put("reportHash", JudgeJson.hash(row[4]));
      repository.advanceGenerationSpecDraft2(report.toString(), id);
      var failed = rows.stream().filter(r -> !r[1].equals(r[3])).findFirst();
      if (failed.isPresent()) {
        fail(id, "REVIEW_" + failed.get()[0] + "_" + failed.get()[3]);
        continue;
      }
      repository.advanceGenerationSpecDraft3(id);
    }
  }
}
