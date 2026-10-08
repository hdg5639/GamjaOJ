package dev.gamjaoj.service.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gamjaoj.domain.JudgeScheduling;
import dev.gamjaoj.exception.AccountException;
import dev.gamjaoj.repository.generation.ExperimentalChecksRepository;
import dev.gamjaoj.support.JudgeJson;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;

/** Bounded differential evidence, never a publisher or a proof of the drafted semantics. */
@Service
public class ExperimentalChecks {
  private final ExperimentalChecksRepository repository;

  public ExperimentalChecks(ExperimentalChecksRepository repository) {
    this.repository = repository;
  }

  private String version(UUID id) {
    return "experimental-check-" + id;
  }

  private ObjectNode plan(UUID id, List<JsonNode> cases, boolean run) {
    var plan =
        JudgeJson.JSON
            .createObjectNode()
            .put("version", version(id))
            .put("output_policy", run ? "RUN_ONLY" : "TOKEN_EXACT");
    var tests = plan.putArray("tests");
    cases.forEach(tests::add);
    return plan;
  }

  private ObjectNode test(String id, String input, String output) {
    return JudgeJson.JSON
        .createObjectNode()
        .put("id", id)
        .put("input", input)
        .put("output", output);
  }

  private void execute(UUID id, String role, String source, List<JsonNode> cases, boolean run) {
    execute(id, role, source, cases, run, run ? "OK" : "AC");
  }

  public void execute(
      UUID id, String role, String source, List<JsonNode> cases, boolean run, String expected) {
    UUID submission = UUID.randomUUID();
    String json = JudgeJson.canonical(plan(id, cases, run));
    repository.executeSubmission(
        submission,
        version(id),
        source,
        JudgeJson.hash(source),
        submission,
        run ? "java8-run-v1" : "java8-judge-v1",
        "experimental-check",
        json,
        JudgeJson.hash(json),
        version(id),
        id);
    repository.executeJudgeJob(submission, JudgeScheduling.experimental(role));
    repository.executeGenerationSpecExecution(id, role, submission, expected);
  }

  public void start(UUID id, JsonNode spec, JsonNode artifacts, JsonNode oracle) {
    var cases = new ArrayList<JsonNode>();
    int index = 0;
    for (var sample : spec.path("samples"))
      cases.add(
          test("sample-" + index++, sample.path("input").asText(), sample.path("output").asText()));
    String json =
        JudgeJson.canonical(
            plan(id, cases, false)
                .put("title", spec.path("title").asText())
                .put("statement", spec.path("statement").asText()));
    if (repository.startProblemVersion(version(id)) == 0) {
      repository.startProblemVersion2(version(id), json, JudgeJson.hash(json), id);
    } else if (repository.startProblemVersion3(json, JudgeJson.hash(json), version(id)) != 1)
      throw new AccountException(409, "이미 게시된 문제는 복구할 수 없어요.");
    execute(id, "reference-samples", artifacts.path("reference").asText(), cases, false);
    execute(id, "oracle-samples", oracle.path("source").asText(), cases, false);
    execute(
        id,
        "validator-samples",
        artifacts.path("inputValidator").asText(),
        cases.stream()
            .map(t -> (JsonNode) ((ObjectNode) t.deepCopy()).put("output", "VALID\n"))
            .toList(),
        false);
    execute(
        id,
        "generator",
        artifacts.path("generator").asText(),
        List.of(test("custom-input", new java.security.SecureRandom().nextLong() + "\n", "")),
        true);
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
      if (rows.isEmpty() || rows.stream().anyMatch(r -> !r[2].equals("FINISHED"))) continue;
      var failed = rows.stream().filter(r -> !r[1].equals(r[3])).findFirst();
      if (failed.isPresent()) {
        fail(id, "CHECK_" + failed.get()[0] + "_" + failed.get()[3]);
        continue;
      }
      var saved =
          repository.advanceGenerationSpecDraft2(
              id,
              (r, n) ->
                  new String[] {
                    r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5)
                  });
      if (!JudgeJson.hash(saved[0]).equals(saved[1])
          || !JudgeJson.hash(saved[2] + "\n" + saved[3]).equals(saved[4])) {
        fail(id, "ARTIFACT_FENCE_MISMATCH");
        continue;
      }
      if (rows.size() == 4) {
        try {
          var result =
              rows.stream().filter(r -> r[0].equals("generator")).findFirst().orElseThrow();
          var output = JudgeJson.parse(result[4]).path("tests").path(0);
          if (output.path("stdout_truncated").asBoolean()) throw new IllegalArgumentException();
          JsonNode inputs;
          try {
            inputs = JudgeJson.JSON.readTree(output.path("stdout").asText());
          } catch (Exception invalid) {
            throw new IllegalArgumentException(invalid);
          }
          if (inputs == null || !inputs.isArray() || inputs.size() != 4)
            throw new IllegalArgumentException();
          var checks = new ArrayList<JsonNode>();
          int index = 0;
          var artifacts = JudgeJson.parse(saved[2]);
          var oracle = JudgeJson.parse(saved[3]);
          for (var input : inputs) {
            if (!input.isTextual()
                || input.asText().isBlank()
                || input.asText().getBytes(StandardCharsets.UTF_8).length > 4096)
              throw new IllegalArgumentException();
            checks.add(test("generated-" + index++, input.asText(), "VALID\n"));
          }
          // Validate the entire generator envelope before queueing any dependent work.
          for (int i = 0; i < 4; i++) {
            var run = List.<JsonNode>of(test("custom-input", inputs.get(i).asText(), ""));
            execute(
                id, "reference-generated-" + i, artifacts.path("reference").asText(), run, true);
            execute(id, "oracle-generated-" + i, oracle.path("source").asText(), run, true);
          }
          execute(
              id, "validator-generated", artifacts.path("inputValidator").asText(), checks, false);
          repository.advanceGenerationSpecDraft3(inputs.toString(), id);
        } catch (IllegalArgumentException e) {
          fail(id, "INVALID_GENERATED_INPUT_ENVELOPE");
        }
      } else if (rows.size() == 13) {
        boolean matches = true;
        for (int i = 0; i < 4; i++) {
          final String suffix = "generated-" + i;
          var a =
              JudgeJson.parse(
                      rows.stream()
                          .filter(r -> r[0].equals("reference-" + suffix))
                          .findFirst()
                          .orElseThrow()[4])
                  .path("tests")
                  .path(0);
          var b =
              JudgeJson.parse(
                      rows.stream()
                          .filter(r -> r[0].equals("oracle-" + suffix))
                          .findFirst()
                          .orElseThrow()[4])
                  .path("tests")
                  .path(0);
          if (a.path("stdout_truncated").asBoolean()
              || b.path("stdout_truncated").asBoolean()
              || a.path("stdout").asText().isBlank()
              || !Arrays.equals(
                  a.path("stdout").asText().strip().split("(?U)\\s+"),
                  b.path("stdout").asText().strip().split("(?U)\\s+"))) matches = false;
        }
        if (!matches) {
          fail(id, "REFERENCE_ORACLE_DISAGREEMENT");
          continue;
        }
        var report =
            JudgeJson.JSON
                .createObjectNode()
                .put("policy", "experimental-examples-differential-v1")
                .put("specHash", saved[1])
                .put("artifactHash", saved[4])
                .put("executions", 13)
                .put("publishable", false);
        var results = report.putArray("results");
        for (var row : rows)
          results
              .addObject()
              .put("role", row[0])
              .put("verdict", row[3])
              .put("reportHash", JudgeJson.hash(row[4]));
        report
            .putArray("remaining")
            .add("independent semantic review")
            .add("bounded exhaustive domain")
            .add("invalid input rejection")
            .add("verified mutant witnesses")
            .add("resource envelope")
            .add("independent final seed");
        repository.advanceGenerationSpecDraft4(report.toString(), id);
      } else fail(id, "INCOMPLETE_EXPERIMENTAL_CHECKS");
    }
  }
}
