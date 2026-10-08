package dev.gamjaoj.generation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gamjaoj.ai.config.AiSettings;
import dev.gamjaoj.generation.repository.GenerationResourcesRepository;
import dev.gamjaoj.judge.domain.LanguageProfiles;
import dev.gamjaoj.problem.domain.ProblemTimeLimits;
import dev.gamjaoj.shared.domain.ArtifactValidation;
import dev.gamjaoj.shared.exception.AccountException;
import dev.gamjaoj.shared.support.JudgeJson;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Same frozen package, actual three-language measurements, ordinary alternative and budget replays.
 */
@org.springframework.stereotype.Service
public class GenerationResources {
  private final GenerationDraftRecovery generationDraftRecovery;
  private final GenerationResourcesRepository repository;

  public GenerationResources(
      GenerationResourcesRepository repository, GenerationDraftRecovery generationDraftRecovery) {
    this.generationDraftRecovery = generationDraftRecovery;
    this.repository = repository;
  }

  public boolean enabled(String pipeline, UUID id) {
    String table =
        switch (pipeline) {
          case "TAG" -> "generation_job";
          case "DIRECT" -> "generation_spec_draft";
          case "RULE" -> "hybrid_generation";
          default -> throw new IllegalArgumentException();
        };
    return repository.enabledRow(table, id);
  }

  public static JsonNode resourceDefinition(String pipeline, JsonNode original) {
    var definition = (ObjectNode) original.deepCopy();
    definition.remove(
        List.of(
            "validationPolicy",
            "theme",
            "themeDomain",
            "recentStories",
            "learnerFeedback",
            "learningFocus"));
    if ("TAG".equals(pipeline))
      definition.remove(List.of("generatorContract", "inputLayoutPolicy"));
    definition.put(
        "maximumInputContract",
        "Main reads one integer seed 0..3 and emits ONE complete legal problem input at the public"
            + " bounds. This is separate from the preliminary small-case/transport generator"
            + " contract; do not emit four transport records.");
    return definition;
  }

  public String ensure(
      String pipeline,
      UUID job,
      String version,
      JsonNode definition,
      String reference,
      String validator,
      String existingLimits) {
    if (!enabled(pipeline, job)) return existingLimits;
    definition = resourceDefinition(pipeline, definition);
    var pack =
        repository.ensureProblemVersion(
            version, (r, n) -> new String[] {r.getString(1), r.getString(2)});
    HybridArtifacts.require(JudgeJson.hash(pack[0]).equals(pack[1]), "RESOURCE_PACKAGE_FENCE");
    var input =
        JudgeJson.JSON
            .createObjectNode()
            .put("phase", "RESOURCE_QUALIFICATION")
            .put("problemVersion", version)
            .put("packageHash", pack[1])
            .put("reference", reference)
            .put("validator", validator);
    input.set("definition", definition.deepCopy());
    input.set("package", JudgeJson.parse(pack[0]));
    GenerationValidationPolicy.attach(input, definition);
    String raw = JudgeJson.canonical(input), fence = JudgeJson.hash(raw);
    var saved =
        repository.ensureGenerationResourceCheck(
            pipeline,
            job,
            fence,
            (r, n) ->
                new String[] {r.getString(1), r.getString(2), r.getString(3), r.getString(4)});
    if (saved.isEmpty()) {
      repository.ensureGenerationResourceCheck2(pipeline, job, fence);
      if ("RULE".equals(pipeline))
        repository.ensureHybridGeneration(
            OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30),
            OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30),
            job);
      repository.ensureGenerationResourceCheck3(
          UUID.randomUUID(), pipeline, job, version, fence, raw);
      return null;
    }
    var row = saved.get();
    if ("PASSED".equals(row[1])) return row[2];
    if ("FAILED".equals(row[1]) && row[3] != null && row[3].startsWith("RESOURCE_REFERENCE_"))
      throw new ArtifactValidation.Invalid(row[3]);
    if ("FAILED".equals(row[1]) || "NEEDS_REVIEW".equals(row[1]))
      throw new ArtifactValidation.Invalid(
          "RESOURCE_RETRY_LIMIT_" + (row[3] == null ? "CHECK_FAILED" : row[3]));
    return null;
  }

  public JsonNode progress(String pipeline, UUID id) {
    return repository
        .progressGenerationResourceCheck(
            pipeline,
            id,
            (r, n) ->
                (JsonNode)
                    JudgeJson.JSON
                        .createObjectNode()
                        .put("status", r.getString(1))
                        .put("error", r.getString(2)))
        .orElse(null);
  }

  public boolean running() {
    return repository.runningGenerationResourceCheck() > 0;
  }

  public GenerationJobs.Assignment claim(AiSettings settings) {
    var row =
        repository.claimGenerationResourceCheck(
            (r, n) ->
                new String[] {r.getString(1), r.getString(2), r.getString(3), r.getString(4)});
    if (row.isEmpty()) return null;
    UUID id = UUID.fromString(row.get()[0]), token = UUID.randomUUID();
    var savedInput = JudgeJson.parse(row.get()[1]);
    String current = repository.claimProblemVersion(savedInput.path("problemVersion").asText());
    if (!current.equals(savedInput.path("packageHash").asText())) {
      repository.claimGenerationResourceCheck2(id);
      return null;
    }
    repository.claimGenerationResourceCheck3(
        token, OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(20), id);
    var spec = (ObjectNode) JudgeJson.parse(row.get()[1]);
    if (row.get()[2] != null)
      spec.put(
          "recoveryFeedback",
          "Resource-stage retry "
              + row.get()[3]
              + ": "
              + row.get()[2]
              + ". Preserve the supplied frozen definition and original reference; repair the"
              + " qualification sources/generator.");
    if (Integer.parseInt(row.get()[3]) > 0) {
      var previous =
          repository
              .claimGenerationResourceAttempt(id)
              .filter(Objects::nonNull)
              .map(JudgeJson::parse);
      if (previous.isPresent()) {
        spec.set("previousResourceArtifacts", previous.get().path("artifacts"));
        spec.set("previousMaximumIssues", previous.get().path("oracle").path("issues"));
      }
    }
    return new GenerationJobs.Assignment(
        id,
        token,
        Integer.parseInt(row.get()[3]),
        settings.value("CODEX_GENERATION_MODEL", "gpt-6.1-sol"),
        settings.value("CODEX_GENERATION_REASONING", "medium"),
        spec,
        null,
        null,
        null);
  }

  public boolean contains(UUID id) {
    return repository.containsGenerationResourceCheck(id) > 0;
  }

  public void complete(
      UUID id, UUID token, JsonNode artifacts, JsonNode oracle, JsonNode usage, String error) {
    String old = generationDraftRecovery.receipt(id, token);
    if (old != null) {
      var receipt = JudgeJson.JSON.createObjectNode();
      receipt.set("artifacts", artifacts);
      receipt.set("oracle", oracle);
      receipt.set("usage", usage);
      receipt.put("error", error);
      if (old.equals(JudgeJson.canonical(receipt))) return;
      throw new AccountException(409, "이전 자원 검증 결과와 달라요.");
    }
    var row =
        repository.completeGenerationResourceCheck(
            id,
            (r, n) ->
                new String[] {r.getString(1), r.getString(2), r.getString(3), r.getString(4)});
    if (!token.toString().equals(row[0])) throw new AccountException(409, "이전 자원 검증 작업이에요.");
    var envelope = JudgeJson.JSON.createObjectNode();
    envelope.set("artifacts", artifacts);
    envelope.set("oracle", oracle);
    envelope.set("usage", usage);
    envelope.put("error", error);
    String audit = JudgeJson.canonical(envelope);
    if (row[3] != null) {
      if (row[3].equals(audit)) return;
      throw new AccountException(409, "저장된 자원 검증 결과와 달라요.");
    }
    if ("SUPERSEDED".equals(row[1])) {
      repository.completeGenerationResourceCheck2(audit, id);
      return;
    }
    if (!"GENERATING".equals(row[1]) || repository.completeGenerationResourceCheck3(id) != 1)
      throw new AccountException(409, "자원 검증 작업 시간이 지났어요.");
    if (audit.length() > 600000) throw new AccountException(400, "자원 검증 산출물이 너무 커요.");
    repository.completeGenerationResourceCheck4(audit, id);
    if (error != null) {
      fail(id, error);
      return;
    }
    try {
      HybridArtifacts.fields(
          artifacts, "cpp", "python", "ordinaryJava", "maximumGenerator", "coverage");
      for (String key : List.of("cpp", "python", "ordinaryJava", "maximumGenerator"))
        HybridArtifacts.require(
            artifacts.path(key).isTextual()
                && !artifacts.path(key).asText().isBlank()
                && artifacts.path(key).asText().length() <= 65536,
            "INVALID_RESOURCE_SOURCE");
      HybridArtifacts.fields(oracle, "accepted", "issues");
      HybridArtifacts.require(
          oracle.path("accepted").isBoolean()
              && oracle.path("issues").isArray()
              && oracle.path("issues").size() <= 8,
          "INVALID_MAXIMUM_REVIEW");
      HybridArtifacts.require(
          oracle.path("accepted").asBoolean() && oracle.path("issues").isEmpty(),
          "MAXIMUM_COVERAGE_REJECTED");
      HybridArtifacts.require(
          artifacts.path("coverage").isArray() && artifacts.path("coverage").size() == 4,
          "INVALID_MAXIMUM_COVERAGE");
      var input = JudgeJson.parse(row[2]);
      var covered = new HashSet<String>();
      var seeds = new HashSet<Integer>();
      for (var item : artifacts.path("coverage")) {
        HybridArtifacts.fields(item, "seed", "checks", "reason");
        HybridArtifacts.text(item.path("reason"), 2000);
        HybridArtifacts.require(
            item.path("seed").isIntegralNumber()
                && item.path("seed").asInt() >= 0
                && item.path("seed").asInt() <= 3
                && seeds.add(item.path("seed").asInt()),
            "INVALID_MAXIMUM_SEEDS");
        HybridArtifacts.texts(item.path("checks"), 1, 64, 120);
        for (var check : item.path("checks")) covered.add(check.asText());
      }
      var required = new HashSet<String>();
      for (var check : input.path("validationPolicy").path("commonChecks"))
        required.add(check.asText());
      for (var profile : input.path("validationPolicy").path("profiles"))
        for (var check : profile.path("checks")) required.add(check.asText());
      HybridArtifacts.require(covered.containsAll(required), "MISSING_PROFILE_COVERAGE");
      HybridArtifacts.require(
          !artifacts.path("ordinaryJava").asText().equals(input.path("reference").asText()),
          "ORDINARY_REFERENCE_REQUIRED");
      repository.completeGenerationResourceCheck5(JudgeJson.canonical(artifacts), id);
      submit(id, "validator", input.path("validator").asText(), "JAVA", true, null);
      for (int repeat = 0; repeat < 2; repeat++)
        for (String language : ProblemTimeLimits.LANGUAGES)
          submit(
              id,
              "measure-" + language + "-" + repeat,
              source(input, artifacts, language),
              language,
              false,
              null);
      for (int repeat = 0; repeat < 2; repeat++)
        submit(
            id,
            "ordinary-JAVA-" + repeat,
            artifacts.path("ordinaryJava").asText(),
            "JAVA",
            false,
            null);
    } catch (ArtifactValidation.Invalid e) {
      fail(id, e.getMessage());
    }
  }

  private static String source(JsonNode input, JsonNode artifacts, String language) {
    return language.equals("JAVA")
        ? input.path("reference").asText()
        : artifacts.path(language.equals("CPP") ? "cpp" : "python").asText();
  }

  private void submit(
      UUID id, String role, String source, String language, boolean validator, String limits) {
    var row =
        repository.submitGenerationResourceCheck(
            id, (r, n) -> new String[] {r.getString(1), r.getString(2), r.getString(3)});
    var input = JudgeJson.parse(row[0]);
    var artifacts = JudgeJson.parse(row[1]);
    var plan = (ObjectNode) input.path("package").deepCopy();
    plan.remove("api");
    plan.remove("generated");
    if (validator) for (var test : plan.path("tests")) ((ObjectNode) test).put("output", "VALID\n");
    var original = input.path("package");
    String helperReference = input.path("reference").asText();
    if (original.has("api")) {
      var api = original.path("api").path("api");
      helperReference = CallablePrograms.executable(api, helperReference);
      if (!validator)
        plan.set("callable", NativeCallablePrograms.bundleForProblem(original, language, false));
    }
    plan.set(
        "generated",
        HybridRulePackage.generated(
            artifacts.path("maximumGenerator").asText(),
            List.of("0", "1", "2", "3"),
            helperReference,
            validator ? "VALID" : "REFERENCE"));
    String raw = JudgeJson.canonical(plan);
    UUID submission = UUID.randomUUID();
    var profile = (ObjectNode) LanguageProfiles.profile(language, limits);
    if (limits == null) profile.put("testWallSeconds", 180);
    repository.submitSubmission(
        submission,
        row[2],
        source,
        JudgeJson.hash(source),
        submission,
        profile.path("image").asText(),
        profile.path("policy").asText(),
        language,
        JudgeJson.canonical(profile),
        "resource-qualification",
        raw,
        JudgeJson.hash(raw),
        row[2]);
    var origin =
        repository.submitGenerationResourceCheck2(
            id, (r, n) -> new String[] {r.getString(1), r.getString(2)});
    String marker =
        origin[0].equals("TAG")
            ? "generation_job_id"
            : origin[0].equals("DIRECT") ? "spec_draft_id" : "hybrid_branch_id";
    UUID originId =
        origin[0].equals("RULE")
            ? UUID.fromString(row[2].substring("hybrid-check-".length()))
            : UUID.fromString(origin[1]);
    repository.submitSubmission2(marker, originId, submission);
    repository.submitJudgeJob(submission);
    repository.submitGenerationResourceExecution(id, role, submission);
  }

  public void advance() {
    repository.advanceGenerationResourceCheck();
    for (var id : repository.advanceGenerationResourceCheck2()) {
      var invalidProfiles =
          repository
              .advanceGenerationResourceExecution(
                  id,
                  (r, n) ->
                      new String[] {r.getString(1), r.getString(2), r.getString(3), r.getString(4)})
              .filter(
                  r -> {
                    var p = JudgeJson.parse(r[3]);
                    return !p.path("image").asText().equals(r[1])
                        || !p.path("policy").asText().equals(r[2]);
                  })
              .toList();
      if (!invalidProfiles.isEmpty()) {
        // These snapshots are rejected before a compatible worker starts a sandbox. Keep them as
        // superseded evidence.
        String report = "{\"verdict\":\"IE\",\"error\":\"resource execution profile superseded\"}";
        for (var invalid : invalidProfiles) {
          UUID submission = UUID.fromString(invalid[0]);
          repository.advanceJudgeAttempt(report, submission);
          repository.advanceJudgeJob(report, JudgeJson.hash(report), submission);
        }
        // Let valid in-flight jobs settle before beginning another exclusive measurement batch.
        if (repository.advanceGenerationResourceExecution2(id) == 0)
          fail(id, "RESOURCE_EXECUTION_PROFILE_MISMATCH");
        continue;
      }
      var rows =
          repository.advanceGenerationResourceExecution3(
              id,
              (r, n) ->
                  new String[] {r.getString(1), r.getString(2), r.getString(3), r.getString(4)});
      if (rows.isEmpty() || rows.stream().anyMatch(r -> !"FINISHED".equals(r[1]))) continue;
      try {
        for (var row : rows)
          HybridArtifacts.require("AC".equals(row[2]), "RESOURCE_" + row[0] + "_" + row[2]);
        String state = repository.advanceGenerationResourceCheck3(id);
        if (state.equals("MEASURING")) {
          HybridArtifacts.require(rows.size() == 9, "RESOURCE_INCOMPLETE_EVIDENCE");
          var limits = limits(rows);
          String raw = JudgeJson.canonical(limits);
          repository.advanceGenerationResourceCheck4(raw, id);
          var input = JudgeJson.parse(repository.advanceGenerationResourceCheck5(id));
          var artifacts = JudgeJson.parse(repository.advanceGenerationResourceCheck6(id));
          for (var language : ProblemTimeLimits.LANGUAGES)
            submit(
                id, "replay-" + language, source(input, artifacts, language), language, false, raw);
          submit(
              id,
              "replay-ordinary-JAVA",
              artifacts.path("ordinaryJava").asText(),
              "JAVA",
              false,
              raw);
        } else {
          HybridArtifacts.require(rows.size() == 13, "RESOURCE_INCOMPLETE_REPLAY");
          // Replays must also report actual positive cgroup observations, never zero/estimated
          // memory.
          limits(rows.stream().filter(r -> r[0].startsWith("replay-")).toList());
          var report =
              JudgeJson.JSON
                  .createObjectNode()
                  .put("policy", "GENERATION_VALIDATION_V1")
                  .put(
                      "scope",
                      "Bounded maximum-shape verification, not proof of worst-case completeness")
                  .put("threeLanguagesMeasured", true)
                  .put("ordinaryJavaMeasured", true);
          var frozen = JudgeJson.parse(repository.advanceGenerationResourceCheck7(id));
          report.set("coverage", frozen.path("coverage"));
          var evidence = report.putArray("results");
          for (var row : rows)
            evidence
                .addObject()
                .put("role", row[0])
                .put("verdict", row[2])
                .put("reportHash", JudgeJson.hash(row[3]));
          repository.advanceGenerationResourceCheck8(report.toString(), id);
        }
      } catch (ArtifactValidation.Invalid e) {
        fail(id, e.getMessage());
      }
    }
  }

  private static ObjectNode limits(List<String[]> rows) {
    var limits = JudgeJson.JSON.createObjectNode();
    var memory = limits.putObject("memory");
    for (var language : ProblemTimeLimits.LANGUAGES) {
      long maximum = 0, peak = 0;
      int observations = 0;
      for (var row : rows)
        if (!row[0].equals("validator") && row[0].contains(language))
          for (var test : JudgeJson.parse(row[3]).path("tests")) {
            HybridArtifacts.require(
                test.path("memory_peak_bytes").isIntegralNumber()
                    && test.path("memory_peak_bytes").asLong() > 0
                    && "cgroup-peak-observed".equals(test.path("memory_measurement").asText()),
                "RESOURCE_MEMORY_OBSERVATION_MISSING");
            maximum = Math.max(maximum, test.path("wall_ms").asLong());
            peak = Math.max(peak, test.path("memory_peak_bytes").asLong());
            observations++;
          }
      HybridArtifacts.require(observations > 0 && maximum > 0, "RESOURCE_TIME_OBSERVATION_MISSING");
      int floor = language.equals("CPP") ? 3 : language.equals("JAVA") ? 5 : 8;
      double seconds = Math.max(floor, Math.ceil((maximum * 3 + 1000) / 250.0) * .25);
      HybridArtifacts.require(seconds <= 180, "TIME_LIMIT_CAPACITY_EXCEEDED");
      limits.put(language, seconds);
      int memoryFloor = language.equals("JAVA") ? 96 : language.equals("PYTHON") ? 48 : 32;
      int mb = Math.max(memoryFloor, (int) (Math.ceil((peak / 1048576.0 * 1.2 + 8) / 16) * 16));
      HybridArtifacts.require(
          mb <= LanguageProfiles.profile(language).path("memoryMb").asInt(),
          "MEMORY_LIMIT_CAPACITY_EXCEEDED");
      memory.put(language, mb);
    }
    limits.put(
        "analysis",
        "Three-language cgroup measurements and ordinary Java alternative; GENERAL 3x + startup"
            + " allowance, replayed within final budgets.");
    ProblemTimeLimits.validate(limits);
    return limits;
  }

  private void fail(UUID id, String error) {
    var row =
        repository.failGenerationResourceCheck(
            id,
            (r, n) ->
                new String[] {r.getString(1), r.getString(2), r.getString(3), r.getString(4)});
    int attempts = Integer.parseInt(row[0]);
    if (error.matches("RESOURCE_(validator|measure-JAVA-[01]|replay-JAVA)_(RE|CE|TLE|MLE)")) {
      var history =
          JudgeJson.JSON
              .createObjectNode()
              .put("failure", error)
              .put("recoveryScope", "IMPLEMENTATION");
      var links = history.putArray("executions");
      repository
          .failGenerationResourceExecution(
              id,
              (r, n) ->
                  JudgeJson.JSON
                      .createObjectNode()
                      .put("role", r.getString(1))
                      .put("submissionId", r.getString(2)))
          .forEach(links::add);
      if (repository.failGenerationResourceAttempt(id, attempts) == 0)
        repository.failGenerationResourceAttempt2(id, attempts, row[1], row[2], history.toString());
      repository.failGenerationResourceCheck2(
          "RESOURCE_REFERENCE_" + error.substring("RESOURCE_".length()).toUpperCase(Locale.ROOT),
          id);
      return;
    }
    boolean telemetry =
        error.equals("RESOURCE_EXECUTION_PROFILE_MISMATCH")
            || error.equals("RESOURCE_MEMORY_OBSERVATION_MISSING")
            || error.equals("RESOURCE_TIME_OBSERVATION_MISSING");
    if (!GenerationDraftRecovery.stopped(error) && attempts < 2) {
      var history = JudgeJson.JSON.createObjectNode().put("failure", error);
      var links = history.putArray("executions");
      repository
          .failGenerationResourceExecution2(
              id,
              (r, n) ->
                  JudgeJson.JSON
                      .createObjectNode()
                      .put("role", r.getString(1))
                      .put("submissionId", r.getString(2)))
          .forEach(links::add);
      repository.failGenerationResourceAttempt3(id, attempts, row[1], row[2], history.toString());
      if (!telemetry
          && row[1] != null
          && row[3] != null
          && repository.failGenerationRecoveryReceipt(id, UUID.fromString(row[3])) == 0)
        repository.failGenerationRecoveryReceipt2(id, UUID.fromString(row[3]), row[1]);
      repository.failGenerationResourceExecution3(id);
      if (telemetry) {
        repository.failGenerationResourceCheck3(error, id);
        var input = JudgeJson.parse(repository.failGenerationResourceCheck4(id));
        var artifacts = JudgeJson.parse(row[2]);
        submit(id, "validator", input.path("validator").asText(), "JAVA", true, null);
        for (int repeat = 0; repeat < 2; repeat++)
          for (String language : ProblemTimeLimits.LANGUAGES)
            submit(
                id,
                "measure-" + language + "-" + repeat,
                source(input, artifacts, language),
                language,
                false,
                null);
        for (int repeat = 0; repeat < 2; repeat++)
          submit(
              id,
              "ordinary-JAVA-" + repeat,
              artifacts.path("ordinaryJava").asText(),
              "JAVA",
              false,
              null);
      } else repository.failGenerationResourceCheck5(error, id);
    } else
      repository.failGenerationResourceCheck6(
          error.substring(0, Math.min(100, error.length())), id);
  }
}
