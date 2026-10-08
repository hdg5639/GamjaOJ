package dev.gamjaoj.service.generation;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamjaoj.config.AiSettings;
import dev.gamjaoj.domain.ArtifactValidation;
import dev.gamjaoj.domain.ProblemTimeLimits;
import dev.gamjaoj.exception.AccountException;
import dev.gamjaoj.repository.generation.GenerationSpecDraftsRepository;
import dev.gamjaoj.service.judge.Submissions;
import dev.gamjaoj.support.JudgeJson;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Private experimental definitions and preliminary checks; never publishes a ready problem. */
@Service
public class GenerationSpecDrafts {
  private final GenerationActivity generationActivity;
  private final GenerationDraftRecovery generationDraftRecovery;
  private final GenerationResources generationResources;

  public record View(
      UUID id,
      String status,
      String mode,
      String request,
      JsonNode spec,
      String specHash,
      String error,
      JsonNode checks,
      JsonNode review,
      JsonNode publication,
      String problemVersion,
      boolean problemHeld,
      String reviewReason,
      JsonNode recovery,
      JsonNode resources) {}

  private final ExperimentalChecks checks;
  private final ExperimentalReview reviews;
  private final ExperimentalPublication publication;
  private final GenerationSpecDraftsRepository repository;
  private final Submissions submissions;
  private final AiSettings settings;

  public GenerationSpecDrafts(
      GenerationSpecDraftsRepository repository,
      Submissions submissions,
      AiSettings settings,
      ExperimentalChecks checks,
      ExperimentalReview reviews,
      ExperimentalPublication publication,
      GenerationActivity generationActivity,
      GenerationDraftRecovery generationDraftRecovery,
      GenerationResources generationResources) {
    this.generationActivity = generationActivity;
    this.generationDraftRecovery = generationDraftRecovery;
    this.generationResources = generationResources;
    this.checks = checks;
    this.reviews = reviews;
    this.publication = publication;
    this.repository = repository;
    this.submissions = submissions;
    this.settings = settings;
  }

  private void lock() {
    repository.lockAiBudgetLock();
  }

  public boolean contains(UUID id) {
    return repository.containsGenerationSpecDraft(id) > 0;
  }

  public boolean active(UUID owner) {
    return repository.activeGenerationSpecDraft(owner) > 0;
  }

  public void expire() {
    repository.expireGenerationSpecDraft();
  }

  public boolean running() {
    return repository.runningGenerationSpecDraft() > 0;
  }

  @Transactional
  public View create(String username, UUID id, String request) {
    return create(username, id, request, false);
  }

  @Transactional
  public View create(String username, UUID id, String request, boolean shared) {
    UUID owner = submissions.owner(username, false);
    lock();
    expire();
    if (request == null || request.isBlank() || request.length() > 2000)
      throw new AccountException(400, "원하는 문제를 1~2000자로 적어 주세요.");
    request = request.strip();
    if (contains(id)) {
      var saved = view(username, id);
      if (!saved.request().equals(request) || shared != repository.createGenerationSpecDraft(id))
        throw new AccountException(409, "같은 요청 키의 출제 요청이 달라요.");
      return saved;
    }
    if (repository.createGenerationJob(id) > 0) throw new AccountException(409, "이미 사용된 요청 키예요.");
    if (generationActivity.active(owner))
      throw new AccountException(409, "진행 중인 규칙 고정 출제를 먼저 마쳐 주세요.");
    if (active(owner) || repository.createGenerationJob2(owner) > 0)
      throw new AccountException(409, "진행 중인 출제 작업을 먼저 마쳐 주세요.");
    String model = settings.value("CODEX_GENERATION_MODEL", "gpt-6.1-sol"),
        effort = settings.value("CODEX_GENERATION_REASONING", "medium");
    if (!List.of("low", "medium", "high", "xhigh", "max").contains(effort))
      throw new AccountException(503, "Codex reasoning 설정을 확인해 주세요.");
    repository.createGenerationSpecDraft2(id, owner, request, model, effort);
    repository.createGenerationSpecDraft3(shared, id);
    return view(username, id);
  }

  public List<View> list(String username) {
    UUID owner = submissions.owner(username, false);
    return repository.listGenerationSpecDraft(owner).map(id -> view(username, id)).toList();
  }

  public View view(String username, UUID id) {
    return repository
        .viewGenerationSpecDraft(
            id,
            submissions.owner(username, false),
            (r, n) ->
                new View(
                    id,
                    r.getString("status"),
                    "EXPERIMENTAL",
                    r.getString("request_text"),
                    r.getString("spec_json") == null
                        ? null
                        : JudgeJson.parse(r.getString("spec_json")),
                    r.getString("spec_sha256"),
                    r.getString("error_code"),
                    r.getString("build_report_json") == null
                        ? null
                        : JudgeJson.parse(r.getString("build_report_json")),
                    r.getString("review_report_json") == null
                        ? null
                        : JudgeJson.parse(r.getString("review_report_json")),
                    r.getString("final_report_json") == null
                        ? null
                        : JudgeJson.parse(r.getString("final_report_json")),
                    r.getString("status").equals("PUBLISHED") ? "experimental-check-" + id : null,
                    r.getBoolean("review_hold"),
                    r.getString("review_reason"),
                    generationDraftRecovery.progress("DIRECT", id),
                    generationResources.progress("DIRECT", id)))
        .orElseThrow(() -> new AccountException(404, "출제 초안을 찾을 수 없어요."));
  }

  // Called under the same transaction/lock and concurrency guard as ordinary generation.
  public GenerationJobs.Assignment claim() {
    var row =
        repository.claimGenerationSpecDraft(
            (r, n) ->
                new String[] {
                  r.getString(1),
                  r.getString(2),
                  r.getString(3),
                  r.getString(4),
                  r.getString(5),
                  r.getString(6)
                });
    if (row.isEmpty()) return null;
    var data = row.get();
    UUID id = UUID.fromString(data[0]), token = UUID.randomUUID();
    boolean build = data[4].equals("BUILD_QUEUED"),
        review = data[4].equals("REVIEW_QUEUED"),
        finish = data[4].equals("FINAL_QUEUED");
    repository.claimGenerationSpecDraft2(
        finish, review, build, token, OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(20), id);
    var spec =
        JudgeJson.JSON
            .createObjectNode()
            .put("phase", "EXPERIMENTAL_SPEC_DRAFT")
            .put("request", data[1])
            .put("runtime", "Java 8 / Main / STDIO");
    if (review) {
      spec.put("requirementsPolicy", "v1");
      spec.put("thinkingRubric", "v1");
      if (recoveryEnabled(id)) spec.put("failureScopePolicy", "v1");
      long maximum =
          repository
              .claimGenerationSpecExecution(id)
              .map(JudgeJson::parse)
              .mapToLong(ProblemTimeLimits::maximum)
              .max()
              .orElse(0);
      spec.putObject("timeEvidence")
          .put("javaMaxWallMs", maximum)
          .put("otherLanguagesMeasured", false)
          .put("scope", "implementation checks only; final maximum-size checks have not run yet");
      repository.claimGenerationSpecDraft3(id);
    }
    if (build || review || finish) {
      spec.put(
          "phase",
          finish
              ? "EXPERIMENTAL_FINAL_PLAN"
              : review ? "EXPERIMENTAL_REVIEW" : "EXPERIMENTAL_IMPLEMENTATION");
      var definition = (com.fasterxml.jackson.databind.node.ObjectNode) JudgeJson.parse(data[5]);
      if (review || finish) {
        definition.remove("referenceStrategy");
        definition.remove("oracleStrategy");
      }
      spec.set("definition", definition);
    }
    String recoveryScope = repository.claimGenerationSpecDraft4(id).orElse(null);
    String feedback = repository.claimGenerationSpecDraft5(id).orElse(null);
    if ("PROSE".equals(recoveryScope) && !build && !review && !finish) {
      spec.put("phase", "EXPERIMENTAL_PROSE_REPAIR");
      spec.set("definition", JudgeJson.parse(data[5]));
    }
    if (feedback != null) spec.put("recoveryFeedback", feedback);
    GenerationValidationPolicy.attach(spec, data[5] == null ? spec : JudgeJson.parse(data[5]));
    JsonNode repair = null;
    if (build && "TEACHING".equals(recoveryScope)) {
      var stored =
          repository.claimGenerationSpecDraft6(
              id, (r, n) -> new String[] {r.getString(1), r.getString(2)});
      var artifacts = JudgeJson.parse(stored[0]);
      var oracle = JudgeJson.parse(stored[1]);
      var bundle = JudgeJson.JSON.createObjectNode();
      bundle.set("artifacts", artifacts);
      bundle.set("oracle", oracle);
      var fields = bundle.putArray("fields");
      GenerationJobs.invalidArtifactFields(artifacts, oracle).forEach(fields::add);
      bundle
          .putArray("failedChecks")
          .add(
              "Repair only invalid prose fields; title<=100 UTF-8 bytes, context/editorial<=6000"
                  + " UTF-8 bytes, exactly3 hints <=2000 characters. Preserve every source and"
                  + " oracle byte.");
      repair = bundle;
    }
    return new GenerationJobs.Assignment(id, token, 0, data[2], data[3], spec, null, repair, null);
  }

  public boolean recoveryEnabled(UUID id) {
    return repository.recoveryEnabledGenerationSpecDraft(id);
  }

  public void complete(
      UUID id, UUID token, JsonNode artifacts, JsonNode oracle, JsonNode usage, String error) {
    String archived = generationDraftRecovery.receipt(id, token);
    if (archived != null) {
      var receipt = JudgeJson.JSON.createObjectNode();
      receipt.set("artifacts", artifacts);
      receipt.set("oracle", oracle);
      receipt.set("usage", usage);
      receipt.put("error", error);
      if (archived.equals(JudgeJson.canonical(receipt))) return;
      throw new AccountException(409, "보존된 이전 시도의 결과와 달라요.");
    }
    if (repository.completeGenerationSpecDraft(id, token) > 0) {
      completeFinal(id, artifacts, oracle, usage, error);
      return;
    }
    if (repository.completeGenerationSpecDraft2(id, token) > 0) {
      completeReview(id, artifacts, oracle, usage, error);
      return;
    }
    if (repository.completeGenerationSpecDraft3(id, token) > 0) {
      completeBuild(id, artifacts, oracle, usage, error);
      return;
    }
    var row =
        repository.completeGenerationSpecDraft4(
            id, (r, n) -> new String[] {r.getString(1), r.getString(2), r.getString(3)});
    if (!token.toString().equals(row[0])) throw new AccountException(409, "지난 초안 작업의 결과예요.");
    var envelope = JudgeJson.JSON.createObjectNode();
    envelope.set("artifacts", artifacts);
    envelope.set("oracle", oracle);
    envelope.set("usage", usage);
    envelope.put("error", error);
    String audit = JudgeJson.canonical(envelope);
    if (audit.length() > 600_000) throw new AccountException(400, "초안 결과가 너무 커요.");
    if (row[2] != null) {
      if (row[2].equals(audit)) return;
      throw new AccountException(409, "이미 저장된 초안 결과와 달라요.");
    }
    if (!row[1].equals("GENERATING") || repository.completeGenerationSpecDraft5(id) != 1)
      throw new AccountException(409, "초안 작업의 유효 시간이 지났어요.");
    if ("PROSE".equals(repository.completeGenerationSpecDraft6(id).orElse(null))) {
      completeProse(id, artifacts, oracle, audit, error);
      return;
    }
    String state = "DRAFT_READY", failure = null, spec = null;
    if (error != null) {
      state = "FAILED";
      failure =
          Set.of(
                      "NEEDS_CHATGPT_AUTH",
                      "CODEX_TIMEOUT",
                      "CODEX_OUTPUT_LIMIT",
                      "CODEX_FAILED_CHECK_MODEL_OR_AUTH",
                      "INVALID_CODEX_ARTIFACT",
                      "CODEX_VERSION_MISMATCH",
                      "CODEX_QUOTA_EXHAUSTED")
                  .contains(error)
              ? error
              : "CODEX_DRAFT_FAILED";
    } else {
      try {
        validate(artifacts);
        if (oracle != null && !oracle.isNull()) throw new IllegalArgumentException();
        spec = JudgeJson.canonical(artifacts);
      } catch (IllegalArgumentException e) {
        state = "FAILED";
        failure = "INVALID_SPEC_DRAFT";
      }
    }
    repository.completeGenerationSpecDraft7(
        state, spec, spec == null ? null : JudgeJson.hash(spec), audit, failure, id);
  }

  @Transactional
  public View build(String username, UUID id, String hash) {
    lock();
    expire();
    var draft = view(username, id);
    if (!Objects.equals(hash, draft.specHash()) || hash == null)
      throw new AccountException(409, "현재 초안의 명세를 다시 확인해 주세요.");
    if (List.of(
            "BUILD_QUEUED",
            "BUILD_GENERATING",
            "CHECKING",
            "CHECKED",
            "BUILD_FAILED",
            "REVIEW_QUEUED",
            "REVIEW_GENERATING",
            "REVIEW_CHECKING",
            "REVIEW_CHECKED",
            "REVIEW_REJECTED",
            "REVIEW_FAILED",
            "FINAL_QUEUED",
            "FINAL_GENERATING",
            "FINAL_CHECKING",
            "FINAL_FAILED",
            "FINAL_REJECTED",
            "PUBLISHED")
        .contains(draft.status())) return draft;
    if (!draft.status().equals("DRAFT_READY"))
      throw new AccountException(409, "명세 작성이 완료된 초안을 선택해 주세요.");
    UUID owner = submissions.owner(username, false);
    if (active(owner) || repository.buildGenerationJob(owner) > 0)
      throw new AccountException(409, "진행 중인 출제 작업을 먼저 마쳐 주세요.");
    if (!JudgeJson.hash(JudgeJson.canonical(draft.spec())).equals(hash))
      throw new AccountException(409, "저장된 명세 해시를 확인해 주세요.");
    repository.buildGenerationSpecDraft(id);
    return view(username, id);
  }

  private void completeBuild(
      UUID id, JsonNode artifacts, JsonNode oracle, JsonNode usage, String error) {
    var envelope = JudgeJson.JSON.createObjectNode();
    envelope.set("artifacts", artifacts);
    envelope.set("oracle", oracle);
    envelope.set("usage", usage);
    envelope.put("error", error);
    String audit = JudgeJson.canonical(envelope);
    if (audit.length() > 600_000) throw new AccountException(400, "생성 결과가 너무 커요.");
    String previous = repository.completeBuildGenerationSpecDraft(id).orElse(null);
    if (previous != null) {
      if (previous.equals(audit)) return;
      throw new AccountException(409, "이미 저장된 코드 결과와 달라요.");
    }
    if (repository.completeBuildGenerationSpecDraft2(id) != 1)
      throw new AccountException(409, "코드 작성 작업의 유효 시간이 지났어요.");
    String failure =
        error == null
            ? null
            : Set.of(
                        "NEEDS_CHATGPT_AUTH",
                        "CODEX_TIMEOUT",
                        "CODEX_OUTPUT_LIMIT",
                        "CODEX_FAILED_CHECK_MODEL_OR_AUTH",
                        "INVALID_CODEX_ARTIFACT",
                        "CODEX_VERSION_MISMATCH",
                        "CODEX_QUOTA_EXHAUSTED")
                    .contains(error)
                ? error
                : "CODEX_IMPLEMENTATION_FAILED";
    if (failure == null
        && "TEACHING".equals(repository.completeBuildGenerationSpecDraft3(id).orElse(null))) {
      var previousArtifacts = JudgeJson.parse(repository.completeBuildGenerationSpecDraft4(id));
      var previousOracle = JudgeJson.parse(repository.completeBuildGenerationSpecDraft5(id));
      var invalid = GenerationJobs.invalidArtifactFields(previousArtifacts, previousOracle);
      if (artifacts == null || !artifacts.isObject() || !previousOracle.equals(oracle))
        failure = "PROSE_SOURCE_FENCE_MISMATCH";
      else
        for (var field :
            List.of(
                "title",
                "context",
                "reference",
                "generator",
                "inputValidator",
                "editorial",
                "hints"))
          if (!invalid.contains(field)
              && !previousArtifacts.path(field).equals(artifacts.path(field)))
            failure = "PROSE_SOURCE_FENCE_MISMATCH";
    }
    if (failure == null)
      try {
        GenerationJobs.validateArtifacts(artifacts, oracle);
      } catch (AccountException invalid) {
        var fields = GenerationJobs.invalidArtifactFields(artifacts, oracle);
        failure =
            Set.of("title", "context", "editorial", "hints").containsAll(fields)
                ? "INVALID_IMPLEMENTATION_PROSE"
                : "INVALID_IMPLEMENTATION";
      }
    repository.completeBuildGenerationSpecDraft6(
        audit, failure == null ? "CHECKING" : "BUILD_FAILED", failure, id);
    if (failure != null) {
      if ("INVALID_IMPLEMENTATION_PROSE".equals(failure)) {
        String code = JudgeJson.canonical(artifacts), independent = JudgeJson.canonical(oracle);
        repository.completeBuildGenerationSpecDraft7(
            code, independent, JudgeJson.hash(code + "\n" + independent), id);
      }
      return;
    }
    String code = JudgeJson.canonical(artifacts), independent = JudgeJson.canonical(oracle);
    repository.completeBuildGenerationSpecDraft8(
        code, independent, JudgeJson.hash(code + "\n" + independent), id);
    var spec = JudgeJson.parse(repository.completeBuildGenerationSpecDraft9(id));
    checks.start(id, spec, artifacts, oracle);
  }

  @Transactional
  public View review(String username, UUID id, String hash) {
    lock();
    expire();
    var draft = view(username, id);
    if (hash == null || !hash.equals(draft.specHash()))
      throw new AccountException(409, "현재 명세를 다시 확인해 주세요.");
    if (draft.status().startsWith("REVIEW_")
        || draft.status().startsWith("FINAL_")
        || draft.status().equals("PUBLISHED")) return draft;
    if (!draft.status().equals("CHECKED"))
      throw new AccountException(409, "예비 검사를 통과한 초안을 선택해 주세요.");
    UUID owner = submissions.owner(username, false);
    if (active(owner) || repository.reviewGenerationJob(owner) > 0)
      throw new AccountException(409, "진행 중인 출제 작업을 먼저 마쳐 주세요.");
    if (!reviews.intact(id)) throw new AccountException(409, "저장된 검증 산출물이 변경됐어요.");
    repository.reviewGenerationSpecDraft(id);
    return view(username, id);
  }

  private void completeReview(
      UUID id, JsonNode artifacts, JsonNode oracle, JsonNode usage, String error) {
    var envelope = JudgeJson.JSON.createObjectNode();
    envelope.set("artifacts", artifacts);
    envelope.set("oracle", oracle);
    envelope.set("usage", usage);
    envelope.put("error", error);
    String audit = JudgeJson.canonical(envelope);
    if (audit.length() > 600_000) throw new AccountException(400, "검토 결과가 너무 커요.");
    String previous = repository.completeReviewGenerationSpecDraft(id).orElse(null);
    if (previous != null) {
      if (previous.equals(audit)) return;
      throw new AccountException(409, "이미 저장된 검토 결과와 달라요.");
    }
    if (repository.completeReviewGenerationSpecDraft2(id) != 1)
      throw new AccountException(409, "검토 작업의 유효 시간이 지났어요.");
    String failure =
        error == null
            ? null
            : Set.of(
                        "NEEDS_CHATGPT_AUTH",
                        "CODEX_TIMEOUT",
                        "CODEX_OUTPUT_LIMIT",
                        "CODEX_FAILED_CHECK_MODEL_OR_AUTH",
                        "INVALID_CODEX_ARTIFACT",
                        "CODEX_VERSION_MISMATCH",
                        "CODEX_QUOTA_EXHAUSTED")
                    .contains(error)
                ? error
                : "CODEX_REVIEW_FAILED";
    if (failure == null)
      try {
        ExperimentalReview.validate(
            artifacts,
            repository.completeReviewGenerationSpecDraft3(id),
            repository.completeReviewGenerationSpecDraft4(id));
        if (oracle != null && !oracle.isNull()) throw new IllegalArgumentException();
      } catch (ArtifactValidation.Invalid invalid) {
        failure =
            "REQUIREMENTS_NOT_MET".equals(invalid.getMessage())
                ? "REQUIREMENTS_NOT_MET"
                : "INVALID_REQUIREMENTS_REVIEW";
      } catch (IllegalArgumentException invalid) {
        failure = "INVALID_REVIEW";
      }
    repository.completeReviewGenerationSpecDraft5(audit, failure, id);
    if (failure != null) return;
    String payload = JudgeJson.canonical(artifacts);
    repository.completeReviewGenerationSpecDraft6(payload, JudgeJson.hash(payload), id);
    reviews.start(id, artifacts);
  }

  @Transactional
  public View publish(String username, UUID id, String hash) {
    lock();
    expire();
    var draft = view(username, id);
    if (hash == null || !hash.equals(draft.specHash()))
      throw new AccountException(409, "현재 명세를 다시 확인해 주세요.");
    if (draft.status().startsWith("FINAL_") || draft.status().equals("PUBLISHED")) return draft;
    if (!draft.status().equals("REVIEW_CHECKED"))
      throw new AccountException(409, "예비 검사를 통과한 초안을 선택해 주세요.");
    UUID owner = submissions.owner(username, false);
    if (active(owner) || repository.publishGenerationJob(owner) > 0)
      throw new AccountException(409, "진행 중인 출제 작업을 먼저 마쳐 주세요.");
    if (!publication.intact(id)) throw new AccountException(409, "저장된 검증 산출물이 변경됐어요.");
    repository.publishGenerationSpecDraft(id);
    return view(username, id);
  }

  private void completeFinal(
      UUID id, JsonNode artifacts, JsonNode oracle, JsonNode usage, String error) {
    var envelope = JudgeJson.JSON.createObjectNode();
    envelope.set("artifacts", artifacts);
    envelope.set("oracle", oracle);
    envelope.set("usage", usage);
    envelope.put("error", error);
    String audit = JudgeJson.canonical(envelope);
    if (audit.length() > 600_000) throw new AccountException(400, "검토 결과가 너무 커요.");
    String previous = repository.completeFinalGenerationSpecDraft(id).orElse(null);
    if (previous != null) {
      if (previous.equals(audit)) return;
      throw new AccountException(409, "이미 저장된 검토 결과와 달라요.");
    }
    if (repository.completeFinalGenerationSpecDraft2(id) != 1)
      throw new AccountException(409, "검토 작업의 유효 시간이 지났어요.");
    String failure =
        error == null
            ? null
            : Set.of(
                        "NEEDS_CHATGPT_AUTH",
                        "CODEX_TIMEOUT",
                        "CODEX_OUTPUT_LIMIT",
                        "CODEX_FAILED_CHECK_MODEL_OR_AUTH",
                        "INVALID_CODEX_ARTIFACT",
                        "CODEX_VERSION_MISMATCH",
                        "CODEX_QUOTA_EXHAUSTED")
                    .contains(error)
                ? error
                : "CODEX_FINAL_FAILED";
    if (failure == null)
      try {
        ExperimentalPublication.validate(artifacts, oracle);
      } catch (IllegalArgumentException invalid) {
        failure = "INVALID_FINAL_PLAN";
      }
    repository.completeFinalGenerationSpecDraft3(audit, failure, id);
    if (failure != null) return;
    String payload = JudgeJson.canonical(artifacts);
    repository.completeFinalGenerationSpecDraft4(payload, JudgeJson.hash(payload), id);
    publication.start(id, artifacts, oracle);
  }

  public void advance() {
    expire();
    checks.advance();
    reviews.advance();
    publication.advance();
    generationDraftRecovery.advance();
  }

  private void completeProse(
      UUID id, JsonNode artifacts, JsonNode oracle, String audit, String error) {
    String failure = error;
    if (failure == null
        && (artifacts == null
            || !artifacts.isObject()
            || artifacts.size() != 2
            || !artifacts.has("title")
            || !artifacts.has("statement")
            || oracle != null && !oracle.isNull())) failure = "INVALID_PROSE_REPAIR";
    if (failure == null)
      try {
        text(artifacts.path("title"), 100);
        text(artifacts.path("statement"), 6000);
      } catch (IllegalArgumentException e) {
        failure = "INVALID_PROSE_REPAIR";
      }
    repository.completeProseGenerationSpecDraft(audit, failure, id);
    if (failure != null) return;
    if (!reviews.intact(id)) {
      repository.completeProseGenerationSpecDraft2(id);
      return;
    }
    var definition =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            JudgeJson.parse(repository.completeProseGenerationSpecDraft3(id));
    String oldHash = JudgeJson.hash(JudgeJson.canonical(definition));
    definition.set("title", artifacts.path("title"));
    definition.set("statement", artifacts.path("statement"));
    try {
      validate(definition);
    } catch (IllegalArgumentException invalid) {
      repository.completeProseGenerationSpecDraft4(id);
      return;
    }
    String spec = JudgeJson.canonical(definition), hash = JudgeJson.hash(spec);
    var report =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            JudgeJson.parse(repository.completeProseGenerationSpecDraft5(id));
    report.put("previousSpecHash", oldHash).put("specHash", hash).put("proseOnlyRebase", true);
    var packageNode =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            JudgeJson.parse(repository.completeProseProblemVersion("experimental-check-" + id));
    if (!JudgeJson.hash(JudgeJson.canonical(packageNode))
        .equals(repository.completeProseProblemVersion2("experimental-check-" + id))) {
      repository.completeProseGenerationSpecDraft6(id);
      return;
    }
    packageNode.set("title", definition.path("title"));
    packageNode.set("statement", definition.path("statement"));
    String pack = JudgeJson.canonical(packageNode);
    repository.completeProseProblemVersion3(pack, JudgeJson.hash(pack), "experimental-check-" + id);
    repository.completeProseGenerationSpecDraft7(spec, hash, report.toString(), id);
  }

  public static void validate(JsonNode spec) {
    Set<String> fields =
        Set.of(
            "title",
            "category",
            "tags",
            "statement",
            "inputDefinition",
            "outputDefinition",
            "constraints",
            "samples",
            "referenceStrategy",
            "oracleStrategy",
            "boundaryClasses",
            "mutantIdeas");
    if (spec == null || !spec.isObject() || spec.size() != fields.size())
      throw new IllegalArgumentException();
    spec.fieldNames()
        .forEachRemaining(
            key -> {
              if (!fields.contains(key)) throw new IllegalArgumentException();
            });
    for (String field :
        List.of(
            "title",
            "category",
            "statement",
            "inputDefinition",
            "outputDefinition",
            "constraints",
            "referenceStrategy",
            "oracleStrategy"))
      text(spec.path(field), field.equals("title") ? 100 : field.equals("category") ? 80 : 6000);
    for (String field : List.of("tags", "boundaryClasses", "mutantIdeas")) {
      var list = spec.path(field);
      if (!list.isArray() || list.size() < 1 || list.size() > 10)
        throw new IllegalArgumentException();
      for (var value : list) text(value, field.equals("tags") ? 80 : 1000);
    }
    var samples = spec.path("samples");
    if (!samples.isArray() || samples.size() < 2 || samples.size() > 5)
      throw new IllegalArgumentException();
    for (var sample : samples) {
      if (!sample.isObject() || sample.size() != 3) throw new IllegalArgumentException();
      for (String field : List.of("input", "output", "explanation")) text(sample.path(field), 2000);
    }
    if (JudgeJson.canonical(spec).length() > 32_000) throw new IllegalArgumentException();
  }

  private static void text(JsonNode value, int max) {
    if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > max)
      throw new IllegalArgumentException();
  }
}
