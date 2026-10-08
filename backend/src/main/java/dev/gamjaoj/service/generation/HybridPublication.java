package dev.gamjaoj.service.generation;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamjaoj.domain.ArtifactValidation;
import dev.gamjaoj.domain.ProblemCategories;
import dev.gamjaoj.domain.ProblemTimeLimits;
import dev.gamjaoj.repository.generation.HybridPublicationRepository;
import dev.gamjaoj.service.problem.ThinkingAssessmentPublication;
import dev.gamjaoj.support.JudgeJson;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Final content review consumes a reserved call; publication consumes exactly its frozen input. */
@Service
public class HybridPublication {
  private final GenerationResources generationResources;
  private final ThinkingAssessmentPublication thinkingAssessmentPublication;
  private final HybridPublicationRepository repository;
  private final HybridRunnerChecks checks;
  private final ApplicationEventPublisher events;
  private final HybridRuleRegistry registry;

  public HybridPublication(
      HybridPublicationRepository repository,
      HybridRunnerChecks checks,
      ApplicationEventPublisher events,
      HybridRuleRegistry registry,
      GenerationResources generationResources,
      ThinkingAssessmentPublication thinkingAssessmentPublication) {
    this.generationResources = generationResources;
    this.thinkingAssessmentPublication = thinkingAssessmentPublication;
    this.repository = repository;
    this.checks = checks;
    this.events = events;
    this.registry = registry;
  }

  /**
   * A reused implementation must still be qualified at the publication fence, not only at
   * admission.
   */
  private void requireReferenceQualified(UUID id, int revision) {
    var completion = repository.requireReferenceQualifiedHybridBranch(id, revision);
    if (completion.isEmpty()
        || !HybridRuleRegistry.REUSED_EXECUTOR.equals(
            JudgeJson.parse(completion.get()).path("usage").path("executor").asText())) return;
    var artifact = repository.requireReferenceQualifiedHybridPublicRequest(id);
    HybridArtifacts.require(
        artifact.isPresent() && registry.stillQualified(artifact.get()),
        "REFERENCE_ARTIFACT_REVOKED");
  }

  private static String hash(JsonNode n) {
    return JudgeJson.hash(JudgeJson.canonical(n));
  }

  private record State(
      UUID id,
      UUID owner,
      int revision,
      boolean shared,
      String contract,
      String publicHash,
      UUID validation) {}

  private JsonNode input(State s, boolean requirements, boolean thinking) {
    var data = checks.checkedPackage(s.validation);
    var contract = data.get("CONTRACT");
    var presentation = data.get("PRESENTATION");
    var core = data.get("CORE");
    var snapshot = HybridArtifacts.publicSnapshot(presentation);
    HybridArtifacts.require(
        hash(contract).equals(s.contract) && hash(snapshot).equals(s.publicHash),
        "PUBLICATION_ARTIFACT_FENCE");
    String version = "hybrid-check-" + s.validation;
    var p =
        repository.inputProblemVersion(
            version,
            (r, n) ->
                new Object[] {
                  r.getString(1),
                  r.getString(2),
                  r.getString(3),
                  r.getString(4),
                  r.getString(5),
                  r.getObject(6, UUID.class),
                  r.getBoolean(7),
                  r.getBoolean(8),
                  r.getBoolean(9)
                });
    HybridArtifacts.require(
        s.owner.equals(p[5])
            && Boolean.FALSE.equals(p[6])
            && Boolean.FALSE.equals(p[7])
            && Boolean.FALSE.equals(p[8]),
        "PUBLICATION_VISIBILITY_FENCE");
    var pack = JudgeJson.parse((String) p[0]);
    var teaching = JudgeJson.parse((String) p[2]);
    var validation =
        repository.inputHybridBranch(
            s.validation, (r, n) -> new String[] {r.getString(1), r.getString(2)});
    var runtime =
        JudgeJson.JSON.createObjectNode().put("image", (String) p[3]).put("policy", (String) p[4]);
    // Every completed job must have used the frozen runtime. The generator is RUN_ONLY.
    var runtimes =
        repository.inputHybridExecutionCheck(
            s.validation,
            (r, n) ->
                new String[] {
                  r.getString(1),
                  r.getString(2),
                  r.getString(3),
                  r.getString(4),
                  r.getString(5),
                  r.getString(6),
                  r.getString(7)
                });
    String scheduling = repository.inputHybridValidationProfile(s.validation);
    String validationPolicy = repository.inputHybridValidationProfile2(s.validation);
    var registered =
        HybridProfiles.all().stream()
            .filter(pf -> pf.pkg() != null && pf.policy().equals(validationPolicy))
            .findFirst();
    int qualifiedSeconds = registered.map(pf -> pf.pkg().qualifiedJavaSeconds()).orElse(0);
    String expectedProfile =
        qualifiedSeconds > 0 ? ProblemTimeLimits.javaProfile(qualifiedSeconds) : null;
    long referenceMs = 0;
    for (var row : runtimes) {
      var report = JudgeJson.parse(row[6]);
      if (row[0].contains("reference") || row[0].startsWith("package-final-"))
        referenceMs = Math.max(referenceMs, ProblemTimeLimits.maximum(report));
      String expectedMode = HybridRunnerChecks.executionMode(scheduling, row[0]);
      HybridArtifacts.require(
          Objects.equals(row[1], p[3])
              && row[2].equals(row[0].equals("package-generator") ? "java8-run-v1" : p[4])
              && "JAVA".equals(row[3])
              && Objects.equals(expectedProfile, row[4])
              && expectedMode.equals(row[5])
              && (expectedProfile == null
                  || JudgeJson.parse(expectedProfile).equals(report.path("execution_profile")))
              && row[1].equals(report.path("image").asText())
              && row[2].equals(report.path("policy").asText())
              && expectedMode.equals(report.path("execution_mode").asText()),
          "PUBLICATION_RUNTIME_FENCE");
    }
    var bindings =
        JudgeJson.JSON
            .createObjectNode()
            .put("generationId", s.id.toString())
            .put("revision", s.revision)
            .put("ownerId", s.owner.toString())
            .put("shared", s.shared)
            .put("validationBranchId", s.validation.toString())
            .put("manifestHash", validation[0])
            .put("executionReportHash", JudgeJson.hash(validation[1]))
            .put("contractHash", s.contract)
            .put("publicHash", s.publicHash)
            .put("packageHash", (String) p[1])
            .put("teachingHash", hash(teaching))
            .put("runtimeHash", hash(runtime));
    var result = JudgeJson.JSON.createObjectNode();
    result.set("bindings", bindings);
    result.set("contract", contract);
    result.set("publicSnapshot", snapshot);
    result.put("reference", core.path("reference").asText());
    result.set("authorNotes", core.path("authorNotes"));
    result.put("statement", pack.path("statement").asText());
    result.set("samples", pack.path("samples"));
    result.set("teaching", teaching);
    if (requirements) {
      var saved =
          repository.inputHybridPublicRequest(
              s.id, (r, n) -> new String[] {r.getString(1), r.getString(2)});
      if (saved.isPresent() && saved.get()[0] != null) {
        HybridArtifacts.require(
            JudgeJson.hash(saved.get()[0]).equals(saved.get()[1]), "REQUIREMENTS_SNAPSHOT_FENCE");
        result.set("requirements", JudgeJson.parse(saved.get()[0]));
      } else {
        // Compatibility requests have their own immutable original input; never infer intent from a
        // new model output.
        result.set("requirements", JudgeJson.parse(repository.inputHybridGeneration(s.id)));
      }
      ((com.fasterxml.jackson.databind.node.ObjectNode) result.path("requirements"))
          .putObject("timeEvidence")
          .put("javaMaxWallMs", referenceMs)
          .put("otherLanguagesMeasured", false)
          .put(
              "javaMaxAllowedSeconds",
              registered.isPresent() ? (qualifiedSeconds > 0 ? qualifiedSeconds : 5) : 20)
          .put(
              "scope",
              "checked reference inputs on the pinned Runner; not a worst-case proof. Registered"
                  + " rules retain the budget used to requalify their slow witnesses; legacy rules"
                  + " retain five seconds.");
    }
    if (requirements && qualifiedSeconds > 0)
      ((com.fasterxml.jackson.databind.node.ObjectNode)
              result.path("requirements").path("timeEvidence"))
          .put("javaQualifiedSeconds", qualifiedSeconds);
    if (thinking) result.put("thinkingRubric", "v1");
    return HybridArtifacts.bounded(result);
  }

  public @Transactional void advance() {
    repository.advanceAiBudgetLock();
    var ids = repository.advanceHybridGeneration();
    for (UUID id : ids) {
      var state =
          repository.advanceHybridGeneration2(
              id,
              (r, n) ->
                  new State(
                      id,
                      r.getObject(1, UUID.class),
                      r.getInt(2),
                      r.getBoolean(3),
                      r.getString(4),
                      r.getString(5),
                      r.getObject(6, UUID.class)));
      if (state.isEmpty()) continue;
      var s = state.get();
      var review =
          repository.advanceHybridBranch(
              id,
              s.revision,
              (r, n) ->
                  new String[] {
                    r.getString(1),
                    r.getString(2),
                    r.getString(3),
                    r.getString(4),
                    r.getString(5),
                    r.getString(6)
                  });
      int nextAttempt = review.isEmpty() ? 0 : Integer.parseInt(review.get()[5]) + 1;
      // Only an explicitly reserved retry may create a fresh review over repaired validation
      // evidence.
      nextAttempt = Math.max(nextAttempt, repository.advanceHybridApiReservation(id, s.revision));
      boolean fresh =
          review.isEmpty()
              || (review.get()[1].equals("FAILED")
                  && repository.advanceHybridApiReservation2(id, s.revision, nextAttempt) == 1);
      if (review.isPresent() && !review.get()[1].equals("SUCCEEDED") && !fresh) continue;
      if (review.isEmpty() && repository.advanceHybridApiReservation3(id, s.revision) != 1)
        continue;
      try {
        var input =
            input(
                s,
                fresh || JudgeJson.parse(review.get()[2]).has("requirements"),
                fresh || JudgeJson.parse(review.get()[2]).has("thinkingRubric"));
        String raw = JudgeJson.canonical(input), hash = JudgeJson.hash(raw);
        if (fresh) {
          repository.advanceHybridBranch2(
              UUID.randomUUID(), id, s.revision, nextAttempt, raw, hash, s.contract, s.publicHash);
          repository.advanceHybridGeneration3(id);
          events.publishEvent(new HybridExecution.Wakeup());
          continue;
        }
        var r = review.get();
        HybridArtifacts.require(
            repository.advanceHybridApiReservation4(UUID.fromString(r[0]), id, s.revision) == 1,
            "CONTENT_REVIEW_ACCOUNTING_FENCE");
        HybridArtifacts.require(raw.equals(r[2]) && hash.equals(r[3]), "CONTENT_REVIEW_STALE");
        var artifact =
            repository.advanceHybridArtifact(
                UUID.fromString(r[0]),
                (row, n) -> new String[] {row.getString(1), row.getString(2)});
        HybridArtifacts.require(
            JudgeJson.hash(artifact[0]).equals(artifact[1]) && artifact[1].equals(r[4]),
            "CONTENT_REVIEW_ARTIFACT_FENCE");
        HybridArtifacts.contentReview(
            JudgeJson.parse(artifact[0]),
            hash,
            input.has("requirements"),
            input.has("thinkingRubric"));
        String version = "hybrid-check-" + s.validation;
        // The budget/generation locks serialize cancellation, provider completion and publication.
        HybridArtifacts.require(
            OffsetDateTime.now(ZoneOffset.UTC).isBefore(repository.advanceHybridGeneration4(id)),
            "PUBLICATION_DEADLINE");
        requireReferenceQualified(id, s.revision);
        String limits =
            input.has("requirements")
                ? ProblemTimeLimits.reviewed(
                    JudgeJson.parse(artifact[0]).path("requirementsReview"),
                    input.path("requirements").path("timeEvidence").path("javaMaxWallMs").asLong(),
                    input
                        .path("requirements")
                        .path("timeEvidence")
                        .path("javaMaxAllowedSeconds")
                        .asInt(20))
                : null;
        int qualified =
            input.path("requirements").path("timeEvidence").path("javaQualifiedSeconds").asInt();
        if (qualified > 0 && !generationResources.enabled("RULE", id))
          HybridArtifacts.require(
              JudgeJson.parse(limits).path("JAVA").asInt() == qualified,
              "TIME_LIMIT_QUALIFICATION_FENCE");
        var core = repository.advanceHybridArtifact2(id, s.revision);
        var corePayload = JudgeJson.parse(core);
        limits =
            generationResources.ensure(
                "RULE",
                id,
                version,
                input.path("contract"),
                corePayload.path("reference").asText(),
                corePayload.path("inputValidator").asText(),
                limits);
        if (limits == null) continue;
        repository.advanceProblemVersion(s.shared, limits, version);
        thinkingAssessmentPublication.publish(
            version, JudgeJson.parse(artifact[0]).path("thinking"), "MODEL");
        var ruleVersion = repository.advanceHybridPublicRequest(id);
        if (ruleVersion.isPresent()) {
          var profile =
              HybridProfiles.byPolicy(repository.advanceHybridValidationProfile(s.validation));
          var catalog =
              registry.version(ruleVersion.get()).filter(v -> v.catalog().has("category"));
          repository.advanceProblemVersion2(
              ProblemCategories.display(
                  catalog.map(HybridRuleRegistry.Version::category).orElse(profile.category())),
              catalog.map(HybridRuleRegistry.Version::tags).orElse(profile.tags()),
              version);
          // Preserve the internal legacy authoring band. A requested thinking layer is never copied
          // as a reviewed public rating.
          repository
              .advanceHybridRuleOnboarding(ruleVersion.get())
              .map(requestJson -> JudgeJson.parse(requestJson).path("difficulty").asText(""))
              .filter(HybridRuleOnboarding.DIFFICULTIES::contains)
              .ifPresent(d -> repository.advanceProblemVersion3(d, version));
        }
        repository.advanceHybridGeneration5(version, id);
        registry.qualify(id);
      } catch (ArtifactValidation.Invalid
          | IllegalArgumentException
          | IllegalStateException
          | org.springframework.dao.IncorrectResultSizeDataAccessException invalid) {
        repository.advanceHybridGeneration6(
            invalid.getMessage() != null && invalid.getMessage().matches("[A-Z][A-Z0-9_]{0,79}")
                ? invalid.getMessage()
                : "INVALID_PUBLICATION_EVIDENCE",
            id);
      }
    }
  }
}
