package dev.gamjaoj.generation.service;

import static dev.gamjaoj.generation.service.HybridGeneration.Role.*;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamjaoj.ai.config.AiSettings;
import dev.gamjaoj.ai.infrastructure.OpenAiResponses;
import dev.gamjaoj.ai.service.AiTasks;
import dev.gamjaoj.generation.repository.HybridExecutionRepository;
import dev.gamjaoj.problem.service.ThinkingDifficulty;
import dev.gamjaoj.shared.exception.AccountException;
import dev.gamjaoj.shared.support.JudgeJson;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Budgeted compatibility dispatcher. Public admission remains closed pending validation profiles.
 */
@Service
public class HybridExecution {
  private final HybridStageRecovery hybridStageRecovery;

  public record Wakeup() {}

  public record Work(UUID attemptId, HybridModels.ApiRequest request, OffsetDateTime deadlineAt) {}

  private final HybridExecutionRepository repository;
  private final HybridGeneration jobs;
  private final AiTasks ledger;
  private final HybridPublication publication;
  private final AiSettings config;
  private final ApplicationEventPublisher events;

  public HybridExecution(
      HybridExecutionRepository repository,
      HybridGeneration jobs,
      AiTasks ledger,
      AiSettings config,
      ApplicationEventPublisher events,
      HybridPublication publication,
      HybridStageRecovery hybridStageRecovery) {
    this.hybridStageRecovery = hybridStageRecovery;
    this.repository = repository;
    this.jobs = jobs;
    this.ledger = ledger;
    this.config = config;
    this.events = events;
    this.publication = publication;
  }

  private void lock() {
    repository.lockAiBudgetLock();
  }

  private static String month() {
    return YearMonth.now(ZoneOffset.UTC).toString();
  }

  private static String json(Object object) {
    return JudgeJson.canonical(JudgeJson.JSON.valueToTree(object));
  }

  private static <T> T parse(String value, Class<T> type) {
    try {
      return JudgeJson.JSON.readValue(value, type);
    } catch (Exception e) {
      throw new IllegalStateException("Invalid hybrid record", e);
    }
  }

  private boolean configured() {
    return config.enabled() && !config.key().isBlank();
  }

  /**
   * Concurrent admitted generations and ordinary API calls; bounded to protect deadlines and
   * budget.
   */
  public int maxActive() {
    try {
      return Math.max(
          1, Math.min(4, Integer.parseInt(config.value("HYBRID_MAX_ACTIVE", "1").trim())));
    } catch (NumberFormatException e) {
      return 1;
    }
  }

  public static BigDecimal reserve(HybridGeneration.Role role, AiSettings.Model model) {
    // Future writer/reader inputs are not known at admission. Bound the complete accepted input,
    // instructions, schema and framing, rather than estimating from a shorter current request.
    long bound =
        HybridArtifacts.MAX_PAYLOAD_BYTES
            + HybridModels.instructions(role).getBytes(StandardCharsets.UTF_8).length
            + HybridModels.schema(role).toString().getBytes(StandardCharsets.UTF_8).length
            + 4096L
            + (role == READER
                ? 0L
                : (role == CONTENT_REVIEW
                        ? GenerationRequirements.REVIEW
                        : GenerationRequirements.AUTHOR)
                    .getBytes(StandardCharsets.UTF_8)
                    .length)
            + (role == CONTENT_REVIEW
                ? GenerationRequirements.schema().toString().getBytes(StandardCharsets.UTF_8).length
                    + ThinkingDifficulty.REVIEW.getBytes(StandardCharsets.UTF_8).length
                    + ThinkingDifficulty.schema().toString().getBytes(StandardCharsets.UTF_8).length
                : 0L)
            + 65536L // bounded common validation-profile guidance
            + (role == PRESENTATION
                ? 8192L
                : 0L) // bounded registered teaching and retheming instructions
            + (HybridModels.author(role) ? 8192L : 0L); // profile-specific author guidance
    return model
        .inputRate()
        .multiply(BigDecimal.valueOf(bound))
        .add(model.outputRate().multiply(BigDecimal.valueOf(model.maxOutputTokens())))
        .movePointLeft(6)
        .setScale(8, RoundingMode.CEILING);
  }

  public @Transactional HybridGeneration.Progress admit(
      String user, UUID id, String request, boolean shared) {
    lock();
    if (repository.admitHybridApiReservation(id) > 0)
      return jobs.start(
          user, id, request, shared); // Owner and exact request idempotency still checked.
    if (!Boolean.parseBoolean(config.value("HYBRID_ADMISSION_ENABLED", "false")) || !configured())
      throw new AccountException(503, "하이브리드 출제 실행은 아직 활성화되지 않았어요.");
    var writer = HybridModels.slot(config, PRESENTATION);
    var reader = HybridModels.slot(config, READER);
    var models = new EnumMap<HybridGeneration.Role, AiSettings.Model>(HybridGeneration.Role.class);
    models.put(PRESENTATION, writer);
    models.put(READER, reader);
    if (Boolean.parseBoolean(config.value("HYBRID_CONTENT_REVIEW_ENABLED", "false"))) {
      if (!HybridFiniteProfile.PACKAGE_POLICY.equals(config.value("HYBRID_VALIDATION_PROFILE", "")))
        throw new AccountException(503, "최종 검토에는 지원되는 전체 패키지 검증 정책이 필요해요.");
      models.put(CONTENT_REVIEW, HybridModels.slot(config, CONTENT_REVIEW));
    }
    var budget = ledger.budget();
    BigDecimal amount = BigDecimal.ZERO;
    for (var entry : models.entrySet())
      amount = amount.add(reserve(entry.getKey(), entry.getValue()));
    if (budget.spentUsd().add(budget.reservedUsd()).add(amount).compareTo(budget.limitUsd()) > 0)
      throw new AccountException(429, "본문 작성과 독립 검토에 필요한 AI 예산이 부족해요.");
    if (repository.admitHybridGeneration(id) > 0)
      throw new AccountException(409, "예약 없이 시작된 출제를 실행 경로로 전환할 수 없어요.");
    // Bounded service-wide admissions (HYBRID_MAX_ACTIVE, default 1) and one active request per
    // owner.

    UUID owner = repository.admitAppUser(user).orElse(null);
    if (owner != null && repository.admitHybridGeneration2(owner) > 0)
      throw new AccountException(429, "진행 중인 출제가 끝난 뒤 다시 요청해 주세요.");
    if (repository.admitHybridGeneration3() >= maxActive())
      throw new AccountException(429, "다른 회원의 출제가 진행 중이에요. 잠시 후 다시 요청해 주세요.");
    var job = jobs.start(user, id, request, shared);
    repository.admitHybridExecutionPolicy(
        id,
        config.value("CODEX_GENERATION_MODEL", "gpt-6.1-sol"),
        config.value("CODEX_GENERATION_REASONING", "medium"));
    for (var role : models.keySet()) {
      var model = models.get(role);
      UUID attempt = UUID.randomUUID();
      repository.admitAiAttempt(attempt, month(), reserve(role, model), json(model));
      repository.admitHybridApiReservation2(attempt, id, role.name());
    }
    events.publishEvent(new Wakeup());
    return job;
  }

  public static final String CODEX_QUOTA = "CODEX_QUOTA_EXHAUSTED";

  /** Explicit opt-in: Codex quota exhaustion may spend the shared API budget on author roles. */
  private boolean fallbackEnabled() {
    if (!Boolean.parseBoolean(config.value("HYBRID_CODEX_API_FALLBACK_ENABLED", "false"))
        || !configured()) return false;
    try {
      HybridModels.slot(config, CORE);
      return true;
    } catch (AccountException missing) {
      return false;
    }
  }

  private boolean codexBlocked() {
    return repository.codexBlockedHybridCodexQuota(OffsetDateTime.now(ZoneOffset.UTC)) > 0;
  }

  private void markCodexQuota() {
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    long minutes =
        Math.max(
            1,
            Math.min(
                24 * 60,
                Long.parseLong(config.value("HYBRID_CODEX_QUOTA_COOLDOWN_MINUTES", "60"))));
    repository.markCodexQuotaHybridCodexQuota();
    repository.markCodexQuotaHybridCodexQuota2(now, now.plusMinutes(minutes));
  }

  /**
   * Reserves the author call in the shared ledger; false when the monthly budget cannot cover it.
   */
  private boolean reserveAuthor(UUID generation, int revision, HybridGeneration.Role role) {
    var model = HybridModels.slot(config, role);
    var amount = reserve(role, model);
    var budget = ledger.budget();
    if (budget.spentUsd().add(budget.reservedUsd()).add(amount).compareTo(budget.limitUsd()) > 0)
      return false;
    UUID attempt = UUID.randomUUID();
    repository.reserveAuthorAiAttempt(attempt, month(), amount, json(model));
    repository.reserveAuthorHybridApiReservation(attempt, generation, revision, role.name());
    return true;
  }

  private boolean hasAuthorReservation(UUID generation, int revision, HybridGeneration.Role role) {
    return repository.hasAuthorReservationHybridApiReservation(generation, revision, role.name())
        > 0;
  }

  /** While Codex is known to be quota-limited, send queued author work straight to the API lane. */
  private void routeBlockedAuthors() {
    if (!codexBlocked() || !fallbackEnabled()) return;
    var queued =
        repository.routeBlockedAuthorsHybridBranch(
            (r, n) ->
                new Object[] {
                  r.getObject(1, UUID.class),
                  HybridGeneration.Role.valueOf(r.getString(2)),
                  r.getInt(3)
                });
    for (var row : queued) {
      UUID id = (UUID) row[0];
      var role = (HybridGeneration.Role) row[1];
      int revision = (Integer) row[2];
      if (hasAuthorReservation(id, revision, role) || !jobs.queuedAuthor(id, role)) continue;
      if (!reserveAuthor(id, revision, role)) jobs.hold(id, "CODEX_QUOTA_API_BUDGET");
    }
  }

  public @Transactional HybridModels.CodexRequest claimCodex() {
    lock();
    recover();
    // Disabling admissions does not reinterpret or restart already admitted work.
    // Author branches with their own API reservation belong to the fallback lane, never to Codex.
    var candidates =
        repository.claimCodexHybridBranch(
            (r, n) ->
                new Object[] {
                  r.getObject(1, UUID.class),
                  HybridGeneration.Role.valueOf(r.getString(2)),
                  r.getObject(3, OffsetDateTime.class)
                });
    for (var row : candidates) {
      var a = jobs.claim((UUID) row[0], (HybridGeneration.Role) row[1]);
      if (a != null) {
        var request = HybridModels.codex(a, config, (OffsetDateTime) row[2]);
        var pinned =
            repository.claimCodexHybridExecutionPolicy(
                a.generationId(), (r, n) -> new String[] {r.getString(1), r.getString(2)});
        return new HybridModels.CodexRequest(
            request.pipelineVersion(),
            request.id(),
            request.token(),
            pinned[0],
            pinned[1],
            request.deadlineAt(),
            request.spec(),
            request.outputSchema());
      }
    }
    return null;
  }

  public @Transactional void completeCodex(HybridGeneration.Completion c) {
    lock();
    if (c == null || c.role() == null || !Set.of(CONTRACT, CORE).contains(c.role()))
      throw new AccountException(400, "Codex 분기 결과 형식을 확인해 주세요.");
    if (repository.completeCodexHybridBranch(c.branchId()) == 0)
      throw new AccountException(404, "예약된 출제 분기가 없어요.");
    if (CODEX_QUOTA.equals(c.error())) {
      markCodexQuota();
      if (fallbackEnabled()) {
        var model = HybridModels.slot(config, c.role());
        var budget = ledger.budget();
        boolean affordable =
            budget
                    .spentUsd()
                    .add(budget.reservedUsd())
                    .add(reserve(c.role(), model))
                    .compareTo(budget.limitUsd())
                <= 0;
        if (affordable) {
          var next = jobs.reroute(c);
          if (next != null && !reserveAuthor(next.generation(), next.revision(), c.role()))
            jobs.hold(next.generation(), "CODEX_QUOTA_API_BUDGET");
          releaseStopped();
          events.publishEvent(new Wakeup());
          return;
        }
      }
    }
    jobs.complete(c);
    releaseStopped();
    events.publishEvent(new Wakeup());
  }

  public @Transactional Work claimApi() {
    return claimApi(false);
  }

  public @Transactional Work claimAuthorApi() {
    return claimApi(true);
  }

  /**
   * Two lanes. The ordinary lane keeps the single API-call limit. The author lane replaces a Codex
   * invocation, which already ran concurrently with writer/reader, so it keeps that overlap.
   */
  private Work claimApi(boolean authorLane) {
    lock();
    recover();
    if (!configured()) return null;

    if (authorLane
        ? repository.claimApiAiAttempt(authorLane) > 0
        : repository.claimApiAiAttempt2() > 0 || repository.claimApiAiAttempt3() >= maxActive())
      return null;
    var candidates =
        repository.claimApiHybridApiReservation(
            authorLane,
            (r, n) ->
                new Object[] {
                  r.getObject(1, UUID.class),
                  r.getObject(2, UUID.class),
                  HybridGeneration.Role.valueOf(r.getString(3)),
                  parse(r.getString(4), AiSettings.Model.class)
                });
    for (var row : candidates) {
      var a = jobs.claim((UUID) row[1], (HybridGeneration.Role) row[2]);
      if (a == null) continue;
      var model = (AiSettings.Model) row[3];
      if (HybridModels.author(a.role())) {
        var task = HybridModels.authorTask(a);
        return dispatch(
            (UUID) row[0],
            (UUID) row[1],
            a,
            new HybridModels.ApiRequest(
                a,
                model,
                task.instructions(),
                JudgeJson.canonical(task.input()),
                "hybrid_" + a.role().name().toLowerCase(Locale.ROOT) + "_v1",
                task.schema()));
      }
      var input = HybridModels.checkedInput(a);
      String instructions = HybridModels.apiInstructions(a, input);
      if (a.role() == READER) {
        var selected = repository.claimApiHybridPublicRequest(a.generationId());
        if (selected.isPresent())
          instructions += HybridProfiles.byId(selected.get()).readerInstructions();
      }
      var request =
          new HybridModels.ApiRequest(
              a,
              model,
              instructions,
              JudgeJson.canonical(input),
              (a.role() == CONTENT_REVIEW && input.has("requirements")
                  ? "hybrid_content_review_requirements_v1"
                  : "hybrid_" + a.role().name().toLowerCase(Locale.ROOT) + "_v1"),
              HybridModels.outputSchema(a));
      return dispatch((UUID) row[0], (UUID) row[1], a, request);
    }
    return null;
  }

  private Work dispatch(
      UUID attempt,
      UUID generation,
      HybridGeneration.Assignment a,
      HybridModels.ApiRequest request) {
    repository.dispatchHybridApiReservation(a.branchId(), json(a), attempt);
    repository.dispatchAiAttempt(month(), attempt);
    return new Work(attempt, request, repository.dispatchHybridGeneration(generation));
  }

  public @Transactional void finish(
      UUID attempt, OpenAiResponses.Result result, OpenAiResponses.Failure failure) {
    lock();
    var row =
        repository.finishHybridApiReservation(
            attempt,
            (r, n) ->
                new String[] {r.getString(1), r.getString(2), r.getString(3), r.getString(4)});
    if (row[0] == null) throw new AccountException(409, "시작하지 않은 호출의 결과예요.");
    if ((result == null) == (failure == null))
      throw new IllegalArgumentException("Exactly one provider outcome required");
    var receipt = JudgeJson.JSON.createObjectNode();
    receipt.set("result", JudgeJson.JSON.valueToTree(result));
    if (failure != null) {
      receipt.put("error", failure.code()).put("requestId", failure.requestId());
      receipt.set("usage", failure.usage());
    }
    String saved = JudgeJson.canonical(receipt);
    if (row[1] != null) {
      if (!row[1].equals(saved)) throw new AccountException(409, "이미 저장된 호출 결과와 달라요.");
      return;
    }
    var a = parse(row[0], HybridGeneration.Assignment.class);
    var model = parse(row[2], AiSettings.Model.class);
    JsonNode usage = result == null ? failure.usage() : result.usage();
    BigDecimal cost = AiTasks.cost(model, usage);
    String error = failure == null ? null : failure.code();
    JsonNode payload = result == null ? null : result.value();
    // Keep cost evidence even when oversized or malformed model output cannot enter the DAG.
    if (payload != null
        && JudgeJson.canonical(payload).getBytes(StandardCharsets.UTF_8).length
            > HybridArtifacts.MAX_PAYLOAD_BYTES - 8192) {
      payload = null;
      error = "ARTIFACT_TOO_LARGE";
    }
    var observed =
        JudgeJson.JSON
            .createObjectNode()
            .put("executor", "OPENAI_API")
            .put("billingMode", "API")
            .put("attemptId", attempt.toString())
            .put("actualCostKnown", cost != null);
    if (HybridModels.author(a.role()))
      observed.put("fallbackFrom", "CODEX_CLI").put("fallbackReason", CODEX_QUOTA);
    // Full usage belongs to ai_attempt; bounded branch metadata avoids a second payload-size
    // failure.
    repository.finishAiAttempt(
        error == null ? "HYBRID_COMPLETED" : cost == null ? "HYBRID_UNKNOWN" : "HYBRID_FAILED",
        cost,
        usage == null ? null : usage.toString(),
        result == null ? failure.requestId() : result.requestId(),
        result == null ? null : result.responseId(),
        result == null ? null : result.model(),
        error,
        attempt);
    repository.finishHybridApiReservation2(saved, attempt);
    jobs.complete(
        new HybridGeneration.Completion(
            a.branchId(),
            a.revision(),
            a.role(),
            a.token(),
            a.inputHash(),
            a.contractHash(),
            a.publicHash(),
            payload,
            observed,
            error));
    publication.advance();
    releaseStopped();
    events.publishEvent(new Wakeup());
  }

  public @Transactional void recover() {
    lock();
    jobs.expirePending();
    publication.advance();
    routeBlockedAuthors();
    releaseStopped();
    hybridStageRecovery.advance(jobs, config, ledger);
    // No automatic retry for a possibly billed request. Late receipts can still settle its cost.
    repository.recoverAiAttempt();
  }

  private void releaseStopped() {
    repository.releaseStoppedAiAttempt();
  }
}
