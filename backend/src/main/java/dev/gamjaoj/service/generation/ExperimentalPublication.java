package dev.gamjaoj.service.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gamjaoj.domain.ArtifactValidation;
import dev.gamjaoj.domain.ProblemCategories;
import dev.gamjaoj.domain.ProblemTimeLimits;
import dev.gamjaoj.repository.generation.ExperimentalPublicationRepository;
import dev.gamjaoj.service.problem.ThinkingAssessmentPublication;
import dev.gamjaoj.support.JudgeJson;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;

/** Enumerates the declared finite domain itself; generated code never decides publication. */
@Service
public class ExperimentalPublication {
  private final GenerationResources generationResources;
  private final ThinkingAssessmentPublication thinkingAssessmentPublication;
  private final ExperimentalPublicationRepository repository;
  private final ExperimentalChecks checks;
  private final ExperimentalReview reviews;

  public ExperimentalPublication(
      ExperimentalPublicationRepository repository,
      ExperimentalChecks checks,
      ExperimentalReview reviews,
      GenerationResources generationResources,
      ThinkingAssessmentPublication thinkingAssessmentPublication) {
    this.generationResources = generationResources;
    this.thinkingAssessmentPublication = thinkingAssessmentPublication;
    this.repository = repository;
    this.checks = checks;
    this.reviews = reviews;
  }

  private String field(UUID id, String field) {
    return repository.fieldGenerationSpecDraft(field, id).orElse(null);
  }

  private JsonNode json(UUID id, String field) {
    return JudgeJson.parse(field(id, field));
  }

  private static void text(JsonNode node, int max, boolean empty) {
    if (!node.isTextual()
        || (!empty && node.asText().isBlank())
        || node.asText().getBytes(StandardCharsets.UTF_8).length > max)
      throw new IllegalArgumentException();
  }

  public static List<String> domain(JsonNode plan) {
    if (plan == null
        || !plan.isObject()
        || plan.size() != 4
        || !plan.has("domainDescription")
        || !plan.has("parts")
        || !plan.has("stressInput")
        || !plan.has("stressReason")) throw new IllegalArgumentException();
    text(plan.path("domainDescription"), 4000, false);
    text(plan.path("stressReason"), 4000, false);
    text(plan.path("stressInput"), 16384, false);
    var parts = plan.path("parts");
    if (!parts.isArray() || parts.size() < 1 || parts.size() > 16)
      throw new IllegalArgumentException();
    List<String> inputs = new ArrayList<>(List.of(""));
    int varying = 0;
    for (var choices : parts) {
      if (!choices.isArray()
          || choices.isEmpty()
          || choices.size() > 12
          || inputs.size() * choices.size() > 12) throw new IllegalArgumentException();
      if (choices.size() > 1) varying++;
      Set<String> distinct = new HashSet<>();
      for (var choice : choices) {
        text(choice, 4096, true);
        if (!distinct.add(choice.asText())) throw new IllegalArgumentException();
      }
      List<String> next = new ArrayList<>();
      for (String prefix : inputs)
        for (var choice : choices) {
          String input = prefix + choice.asText();
          if (input.getBytes(StandardCharsets.UTF_8).length > 4096)
            throw new IllegalArgumentException();
          next.add(input);
        }
      inputs = next;
    }
    if (varying < 2
        || inputs.size() < 4
        || new HashSet<>(inputs).size() != inputs.size()
        || inputs.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException();
    return inputs;
  }

  public static void validate(JsonNode plan, JsonNode review) {
    domain(plan);
    if (review == null
        || !review.isObject()
        || review.size() != 2
        || !review.path("accepted").isBoolean()
        || !review.path("issues").isArray()
        || review.path("issues").size() > 8) throw new IllegalArgumentException();
    for (var issue : review.path("issues")) text(issue, 2000, false);
    if (review.path("accepted").asBoolean() != review.path("issues").isEmpty())
      throw new IllegalArgumentException();
  }

  public boolean intact(UUID id) {
    if (!reviews.intact(id)) return false;
    var review = json(id, "review_report_json");
    if (review.path("executions").asInt() != 6
        || !review.path("reviewHash").asText().equals(field(id, "review_payload_sha256")))
      return false;
    String plan = field(id, "final_plan_json");
    return plan == null || JudgeJson.hash(plan).equals(field(id, "final_plan_sha256"));
  }

  private ObjectNode test(String name, String input, String output) {
    return JudgeJson.JSON
        .createObjectNode()
        .put("id", name)
        .put("input", input)
        .put("output", output);
  }

  private void run(UUID id, String role, String source, String input) {
    checks.execute(id, role, source, List.of(test("custom-input", input, "")), true, "OK");
  }

  private void validateInputs(UUID id, String role, String source, List<String> inputs) {
    var tests = new ArrayList<JsonNode>();
    for (int i = 0; i < inputs.size(); i++) tests.add(test("input-" + i, inputs.get(i), "VALID\n"));
    checks.execute(id, role, source, tests, false, "AC");
  }

  public void start(UUID id, JsonNode plan, JsonNode review) {
    if (!intact(id)) {
      fail(id, "FINAL_ARTIFACT_FENCE_MISMATCH");
      return;
    }
    if (!review.path("accepted").asBoolean()) {
      var report = JudgeJson.JSON.createObjectNode().put("publishable", false);
      report.set("issues", review.path("issues"));
      repository.startGenerationSpecDraft(report.toString(), id);
      return;
    }
    var artifacts = json(id, "build_artifacts_json");
    var oracle = json(id, "build_oracle_json");
    var inputs = domain(plan);
    for (int i = 0; i < inputs.size(); i++) {
      run(id, "final-small-ref-" + i, artifacts.path("reference").asText(), inputs.get(i));
      run(id, "final-small-oracle-" + i, oracle.path("source").asText(), inputs.get(i));
    }
    validateInputs(id, "final-small-validator", artifacts.path("inputValidator").asText(), inputs);
    String stress = plan.path("stressInput").asText();
    validateInputs(
        id, "final-stress-validator", artifacts.path("inputValidator").asText(), List.of(stress));
    for (int i = 0; i < 2; i++)
      run(id, "final-stress-" + i, artifacts.path("reference").asText(), stress);
    // Chosen after all authoring/review snapshots are fixed; never sent to a model.
    run(
        id,
        "final-generator",
        artifacts.path("generator").asText(),
        new java.security.SecureRandom().nextLong() + "\n");
    repository.startGenerationSpecDraft2(id);
  }

  private void fail(UUID id, String reason) {
    repository.failGenerationSpecDraft(reason, id);
  }

  private record Result(String role, String expected, String verdict, JsonNode report) {}

  private String output(Map<String, Result> results, String role) {
    var test = results.get(role).report().path("tests").path(0);
    if (test.path("stdout_truncated").asBoolean()
        || !test.path("stdout").isTextual()
        || test.path("stdout").asText().isBlank())
      throw new IllegalArgumentException("FINAL_INVALID_OUTPUT");
    return test.path("stdout").asText();
  }

  private void same(String a, String b) {
    if (!Arrays.equals(a.strip().split("(?U)\\s+"), b.strip().split("(?U)\\s+")))
      throw new IllegalArgumentException("FINAL_ORACLE_DISAGREEMENT");
  }

  private void resource(Result result) {
    long total = 0;
    for (var test : result.report().path("tests")) {
      if (!test.path("wall_ms").canConvertToLong()
          || test.path("wall_ms").asLong() < 0
          || test.path("wall_ms").asLong() > 4000)
        throw new IllegalArgumentException("FINAL_RESOURCE_MARGIN");
      total += test.path("wall_ms").asLong();
    }
    if (total > 40000) throw new IllegalArgumentException("FINAL_PACKAGE_TIME_BUDGET");
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
      try {
        if (!intact(id)) throw new IllegalArgumentException("FINAL_ARTIFACT_FENCE_MISMATCH");
        Map<String, Result> results = new LinkedHashMap<>();
        for (var row : rows) {
          if (!row[1].equals(row[3]))
            throw new IllegalArgumentException("FINAL_" + row[0] + "_" + row[3]);
          results.put(row[0], new Result(row[0], row[1], row[3], JudgeJson.parse(row[4])));
        }
        var plan = json(id, "final_plan_json");
        var small = domain(plan);
        var artifacts = json(id, "build_artifacts_json");
        var oracle = json(id, "build_oracle_json");
        int stage = Integer.parseInt(field(id, "final_stage"));
        int expected = 2 * small.size() + 5 + (stage >= 2 ? 9 : 0) + (stage >= 3 ? 2 : 0);
        if (rows.size() != expected) throw new IllegalArgumentException("FINAL_INCOMPLETE_CHECKS");
        for (int i = 0; i < small.size(); i++)
          same(output(results, "final-small-ref-" + i), output(results, "final-small-oracle-" + i));
        same(output(results, "final-stress-0"), output(results, "final-stress-1"));
        resource(results.get("final-stress-0"));
        resource(results.get("final-stress-1"));
        if (stage >= 2) {
          if (!json(id, "final_inputs_json")
              .equals(JudgeJson.parse(output(results, "final-generator"))))
            throw new IllegalArgumentException("FINAL_INPUT_FENCE");
          for (int i = 0; i < 4; i++)
            same(output(results, "final-seed-ref-" + i), output(results, "final-seed-oracle-" + i));
        }
        if (stage == 1) {
          JsonNode generated;
          try {
            generated = JudgeJson.JSON.readTree(output(results, "final-generator"));
          } catch (Exception invalid) {
            throw new IllegalArgumentException("FINAL_INVALID_GENERATOR");
          }
          if (generated == null || !generated.isArray() || generated.size() != 4)
            throw new IllegalArgumentException("FINAL_INVALID_GENERATOR");
          List<String> inputs = new ArrayList<>();
          for (var input : generated) {
            text(input, 4096, false);
            inputs.add(input.asText());
          }
          for (int i = 0; i < 4; i++) {
            run(id, "final-seed-ref-" + i, artifacts.path("reference").asText(), inputs.get(i));
            run(id, "final-seed-oracle-" + i, oracle.path("source").asText(), inputs.get(i));
          }
          validateInputs(
              id, "final-seed-validator", artifacts.path("inputValidator").asText(), inputs);
          repository.advanceGenerationSpecDraft2(generated.toString(), id);
        } else if (stage == 2) {
          var inputs = json(id, "final_inputs_json");
          for (int i = 0; i < 4; i++)
            same(output(results, "final-seed-ref-" + i), output(results, "final-seed-oracle-" + i));
          var spec = json(id, "spec_json");
          var review = json(id, "review_payload_json");
          var cases = new ArrayList<JsonNode>();
          for (var sample : spec.path("samples"))
            cases.add(
                test(
                    "sample-" + cases.size(),
                    sample.path("input").asText(),
                    sample.path("output").asText()));
          for (var mutant : review.path("mutants"))
            cases.add(
                test(
                    "witness-" + cases.size(),
                    mutant.path("witness").path("input").asText(),
                    mutant.path("witness").path("output").asText()));
          cases.add(
              test(
                  "resource",
                  plan.path("stressInput").asText(),
                  output(results, "final-stress-0")));
          for (int i = 0; i < 4; i++)
            cases.add(
                test("seed-" + i, inputs.get(i).asText(), output(results, "final-seed-ref-" + i)));
          for (var boundary : review.path("validCases")) {
            if (cases.size() >= 18) break;
            cases.add(
                test(
                    "boundary-" + cases.size(),
                    boundary.path("input").asText(),
                    boundary.path("output").asText()));
          }
          for (int i = 0; i < small.size() && cases.size() < 20; i++)
            cases.add(test("small-" + i, small.get(i), output(results, "final-small-ref-" + i)));
          var pack =
              JudgeJson.JSON
                  .createObjectNode()
                  .put("version", "experimental-check-" + id)
                  .put("output_policy", "TOKEN_EXACT")
                  .put("title", "[실험] " + spec.path("title").asText())
                  .put("mode", "EXPERIMENTAL")
                  .put(
                      "statement",
                      spec.path("statement").asText()
                          + "\n\n입력\n"
                          + spec.path("inputDefinition").asText()
                          + "\n\n출력\n"
                          + spec.path("outputDefinition").asText()
                          + "\n\n제약\n"
                          + spec.path("constraints").asText()
                          + "\n\n실험 문제: 제한된 작은 영역 전수·무작위 대조와 독립 검토를 통과했습니다.");
          var tests = pack.putArray("tests");
          cases.forEach(tests::add);
          String saved = JudgeJson.canonical(pack);
          repository.advanceProblemVersion(
              saved, JudgeJson.hash(saved), "experimental-check-" + id);
          for (int i = 0; i < 2; i++)
            checks.execute(
                id, "final-package-" + i, artifacts.path("reference").asText(), cases, false, "AC");
          repository.advanceGenerationSpecDraft3(id);
        } else if (stage == 3) {
          resource(results.get("final-package-0"));
          resource(results.get("final-package-1"));
          var pack =
              repository.advanceProblemVersion2(
                  "experimental-check-" + id,
                  (r, n) -> new String[] {r.getString(1), r.getString(2)});
          if (!JudgeJson.hash(pack[0]).equals(pack[1]))
            throw new IllegalArgumentException("FINAL_PACKAGE_FENCE");
          var packageTests = JudgeJson.parse(pack[0]).path("tests");
          for (int i = 0; i < 2; i++) {
            String role = "final-package-" + i;
            String executed = repository.advanceGenerationSpecExecution2(id, role);
            if (!JudgeJson.parse(executed).path("tests").equals(packageTests))
              throw new IllegalArgumentException("FINAL_PACKAGE_FENCE");
          }
          var report =
              JudgeJson.JSON
                  .createObjectNode()
                  .put("policy", "experimental-publication-v2")
                  .put("publishable", true)
                  .put("domainCases", small.size())
                  .put("domainDescription", plan.path("domainDescription").asText())
                  .put("executions", rows.size())
                  .put("planHash", field(id, "final_plan_sha256"))
                  .put("packageHash", pack[1])
                  .put("memoryLimitMiB", 384)
                  .put("perTestMarginMs", 4000)
                  .put("packageBudgetMs", 40000);
          var evidence = report.putArray("results");
          for (var result : results.values())
            evidence
                .addObject()
                .put("role", result.role())
                .put("verdict", result.verdict())
                .put("reportHash", JudgeJson.hash(JudgeJson.canonical(result.report())));
          var teaching =
              JudgeJson.JSON
                  .createObjectNode()
                  .put("editorial", artifacts.path("editorial").asText());
          teaching.set("hints", artifacts.path("hints"));
          long maximum =
              results.values().stream()
                  .filter(
                      r ->
                          r.role().startsWith("final-package-")
                              || r.role().startsWith("final-stress-")
                                  && !r.role().contains("validator"))
                  .mapToLong(r -> ProblemTimeLimits.maximum(r.report()))
                  .max()
                  .orElse(0);
          var assessment = json(id, "review_payload_json").path("requirementsReview");
          String limits =
              assessment.isMissingNode() ? null : ProblemTimeLimits.reviewed(assessment, maximum);
          limits =
              generationResources.ensure(
                  "DIRECT",
                  id,
                  "experimental-check-" + id,
                  json(id, "spec_json"),
                  artifacts.path("reference").asText(),
                  artifacts.path("inputValidator").asText(),
                  limits);
          if (limits == null) continue;
          report.set("timeLimits", JudgeJson.parse(limits));
          report.put("threeLanguagesMeasured", generationResources.enabled("DIRECT", id));
          repository.advanceProblemVersion3(
              limits,
              teaching.toString(),
              ProblemCategories.display(json(id, "spec_json").path("category").asText()),
              id,
              "experimental-check-" + id);
          thinkingAssessmentPublication.publish(
              "experimental-check-" + id,
              json(id, "review_payload_json").path("thinking"),
              "MODEL");
          repository.advanceGenerationSpecDraft4(report.toString(), id);
        } else throw new IllegalArgumentException("FINAL_INVALID_STAGE");
      } catch (IllegalArgumentException | ArtifactValidation.Invalid invalid) {
        fail(id, invalid.getMessage() == null ? "FINAL_INVALID_PLAN" : invalid.getMessage());
      }
    }
  }
}
