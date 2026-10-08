package dev.gamjaoj.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamjaoj.config.AiSettings;
import dev.gamjaoj.exception.AccountException;
import dev.gamjaoj.infrastructure.ai.OpenAiResponses;
import dev.gamjaoj.infrastructure.ai.ResponsesFeedbackProvider;
import dev.gamjaoj.repository.ai.AiTasksRepository;
import dev.gamjaoj.service.diagnostic.DiagnosticEvaluationContract;
import dev.gamjaoj.service.generation.GenerationThemes;
import dev.gamjaoj.service.judge.Submissions;
import dev.gamjaoj.support.JudgeJson;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiTasks {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AiTasks.class);
  private final AiTasksRepository repository;
  private final Submissions submissions;
  private final AiSettings config;

  public AiTasks(AiTasksRepository repository, Submissions submissions, AiSettings config) {
    this.repository = repository;
    this.submissions = submissions;
    this.config = config;
  }

  public record View(
      UUID id,
      UUID submissionId,
      String kind,
      String status,
      JsonNode result,
      String errorCode,
      String model,
      String effort,
      boolean problemHeld) {}

  public record Work(UUID attemptId, UUID taskId, AiSettings.Model settings, String input) {}

  public record Budget(
      BigDecimal limitUsd,
      BigDecimal spentUsd,
      BigDecimal reservedUsd,
      boolean warning,
      boolean enabled,
      boolean keyConfigured) {}

  private void lock() {
    repository.lockAiBudgetLock();
  }

  private static String month() {
    return YearMonth.now(ZoneOffset.UTC).toString();
  }

  private String json(Object value) {
    return JudgeJson.JSON.valueToTree(value).toString();
  }

  private AiSettings.Model settings(String value) {
    try {
      return JudgeJson.JSON.readValue(value, AiSettings.Model.class);
    } catch (Exception e) {
      throw new IllegalStateException("Invalid stored AI settings");
    }
  }

  @Transactional
  public View request(
      String username, UUID submission, String kind, String question, boolean strong) {
    if (strong) config.requireOperator(username);
    UUID owner = submissions.owner(username, true);
    if (!List.of("ANALYSIS", "HINT").contains(kind) || question == null || question.length() > 1000)
      throw new AccountException(400, "분석 요청 형식을 확인해 주세요.");
    return enqueue(owner, submission, kind, question, strong);
  }

  private View enqueue(UUID owner, UUID submission, String kind, String question, boolean strong) {
    var data =
        repository
            .enqueueSubmission(
                submission,
                owner,
                (r, n) -> {
                  if (r.getBoolean("diagnostic_only"))
                    throw new AccountException(409, "진단 문항의 분석은 진단 결과에서 제공할 예정이에요.");
                  if (r.getBoolean("review_hold"))
                    throw new AccountException(409, "문제 검토 중에는 새 분석을 요청할 수 없어요.");
                  if (!"FINISHED".equals(r.getString("status"))
                      || "IE".equals(r.getString("verdict")))
                    throw new AccountException(409, "정식 채점이 완료된 제출을 선택해 주세요. 시스템 오류는 학습 분석하지 않아요.");
                  var problem = JudgeJson.parse(r.getString("package_json"));
                  return JudgeJson.JSON
                      .createObjectNode()
                      .put("kind", kind)
                      .put("question", question)
                      .put("title", problem.path("title").asText())
                      .put("statement", problem.path("statement").asText())
                      .put("source", r.getString("source_code"))
                      .put("verdict", r.getString("verdict"))
                      .put("runtimeImage", r.getString("runtime_image"))
                      .put("language", r.getString("language"))
                      .put("executionProfile", r.getString("execution_profile_json"))
                      .put("runnerPolicy", r.getString("runner_policy"))
                      .put("judgeHash", r.getString("result_sha256"))
                      .put("problemHash", r.getString("package_sha256"));
                })
            .orElseThrow(() -> new AccountException(404, "제출 기록을 찾을 수 없어요."));
    AiSettings.Model model = config.model(strong);
    String configuration = json(model), input = data.toString();
    String cache = JudgeJson.hash(submission + ":" + configuration + ":" + input);
    var previous = repository.enqueueAiTask(owner, cache);
    if (previous.isPresent()) return find(owner, previous.get());
    if (repository.enqueueAiTask2(owner) >= 3)
      throw new AccountException(429, "대기 중인 AI 요청이 끝나면 다시 요청해 주세요.");
    UUID id = UUID.randomUUID();
    repository.enqueueAiTask3(
        id,
        owner,
        submission,
        kind,
        cache,
        configuration,
        input,
        config.enabled() && !config.key().isBlank() ? "QUEUED" : "HELD_DISABLED");
    return find(owner, id);
  }

  @Transactional
  public UUID theme(UUID owner, UUID generation, JsonNode input) {
    lock();
    String cache = JudgeJson.hash("theme-v1:" + generation);
    var existing = repository.themeAiTask(owner, cache);
    if (existing.isPresent()) return existing.get();
    var base = config.model(false);
    var model =
        new AiSettings.Model(
            base.model(),
            base.effort(),
            base.inputRate(),
            base.cachedRate(),
            base.outputRate(),
            base.pricingVersion(),
            768,
            "theme-v1",
            "theme-v1");
    UUID id = UUID.randomUUID();
    repository.themeAiTask2(
        id,
        owner,
        cache,
        json(model),
        input.toString(),
        config.enabled() && !config.key().isBlank() ? "QUEUED" : "HELD_DISABLED");
    return id;
  }

  @Transactional
  public UUID diagnostic(UUID owner, UUID session, JsonNode input) {
    var base = config.model(false);
    var model =
        new AiSettings.Model(
            base.model(),
            base.effort(),
            base.inputRate(),
            base.cachedRate(),
            base.outputRate(),
            base.pricingVersion(),
            8192,
            "diagnostic-v5",
            "diagnostic-v3");
    String configuration = json(model),
        payload = JudgeJson.canonical(input),
        cache = JudgeJson.hash(configuration + ":" + payload);
    var old = repository.diagnosticAiTask(owner, cache);
    if (old.isPresent()) return old.get();
    if (repository.diagnosticAiTask2(owner) >= 3)
      throw new AccountException(429, "대기 중인 평가가 끝난 뒤 요청해 주세요.");
    UUID id = UUID.randomUUID();
    repository.diagnosticAiTask3(
        id,
        owner,
        session,
        cache,
        configuration,
        payload,
        config.enabled() && !config.key().isBlank() ? "QUEUED" : "HELD_DISABLED");
    return id;
  }

  public List<View> list(String username, UUID submission) {
    UUID owner = submissions.owner(username, false);
    submissions.detail(
        username, submission); // A foreign submission must not even expose task existence.
    return repository.listAiTask(owner, submission).map(id -> find(owner, id)).toList();
  }

  public View detail(String username, UUID id) {
    return find(submissions.owner(username, false), id);
  }

  private View find(UUID owner, UUID id) {
    return repository
        .findAiTask(
            id,
            owner,
            (r, n) -> {
              var model = settings(r.getString("settings_json"));
              return new View(
                  id,
                  r.getObject("submission_id", UUID.class),
                  r.getString("kind"),
                  r.getString("status"),
                  r.getString("kind").equals("DIAGNOSTIC") || r.getString("result_json") == null
                      ? null
                      : JudgeJson.parse(r.getString("result_json")),
                  r.getString("error_code"),
                  model.model(),
                  model.effort(),
                  r.getBoolean("problem_held"));
            })
        .orElseThrow(() -> new AccountException(404, "분석 기록을 찾을 수 없어요."));
  }

  @Transactional
  public View retry(String username, UUID id) {
    UUID owner = submissions.owner(username, true);
    View saved = find(owner, id);
    if (saved.kind().equals("DIAGNOSTIC"))
      throw new AccountException(409, "진단 평가 재시도는 아직 지원하지 않아요. 저장된 상태를 확인해 주세요.");
    if (saved.problemHeld()) throw new AccountException(409, "문제 검토 중에는 재분석할 수 없어요.");
    if (List.of("FAILED", "UNKNOWN").contains(saved.status())) {
      if (repository.retryAiAttempt(id) >= 3)
        throw new AccountException(409, "재시도 상한에 도달했어요. 운영자에게 문의해 주세요.");
      repository.retryAiTask(config.enabled() ? "QUEUED" : "HELD_DISABLED", id);
    }
    return find(owner, id);
  }

  public Budget budget(String username) {
    config.requireOperator(username);
    return budget();
  }

  public Budget budget() {
    BigDecimal spent = repository.budgetAiAttempt(month());
    BigDecimal reserved = repository.budgetAiAttempt2();
    return new Budget(
        config.budget(),
        spent,
        reserved,
        spent.add(reserved).compareTo(config.budget().multiply(new BigDecimal("0.8"))) >= 0,
        config.enabled(),
        !config.key().isBlank());
  }

  @Transactional
  public Work claim() {
    lock();
    // Never re-send a possibly charged request after a crash. Retain its reservation for
    // reconciliation.
    var expired =
        repository.claimAiAttempt(
            OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5),
            (r, n) -> new UUID[] {r.getObject(1, UUID.class), r.getObject(2, UUID.class)});
    for (var row : expired) {
      repository.claimAiAttempt2(row[0]);
      repository.claimAiTask(row[1]);
    }
    if (!config.enabled() || config.key().isBlank()) return null;
    if (repository.claimAiAttempt3() > 0) return null;
    var next =
        repository.claimAiTask2(
            (r, n) ->
                new Object[] {
                  r.getObject("id", UUID.class),
                  settings(r.getString("settings_json")),
                  r.getString("input_json")
                });
    if (next.isEmpty()) return null;
    var row = next.get();
    UUID task = (UUID) row[0];
    var model = (AiSettings.Model) row[1];
    String input = (String) row[2];
    // UTF-8 bytes conservatively bound text tokens, with explicit schema/instruction/framing
    // allowance.
    boolean theme = JudgeJson.parse(input).path("kind").asText().equals("THEME");
    boolean diagnostic = JudgeJson.parse(input).path("kind").asText().equals("DIAGNOSTIC");
    long inputBound =
        input.getBytes(StandardCharsets.UTF_8).length
            + (theme
                    ? GenerationThemes.INSTRUCTIONS
                    : diagnostic
                        ? DiagnosticEvaluationContract.INSTRUCTIONS
                        : ResponsesFeedbackProvider.INSTRUCTIONS)
                .getBytes(StandardCharsets.UTF_8)
                .length
            + (theme
                    ? GenerationThemes.SCHEMA
                    : diagnostic
                        ? DiagnosticEvaluationContract.schema(JudgeJson.parse(input))
                        : ResponsesFeedbackProvider.SCHEMA)
                .toString()
                .getBytes(StandardCharsets.UTF_8)
                .length
            + 4096L;
    BigDecimal reserve =
        model
            .inputRate()
            .multiply(BigDecimal.valueOf(inputBound))
            .add(model.outputRate().multiply(BigDecimal.valueOf(model.maxOutputTokens())))
            .movePointLeft(6)
            .setScale(8, RoundingMode.CEILING);
    Budget budget = budget();
    if (budget.spentUsd().add(budget.reservedUsd()).add(reserve).compareTo(budget.limitUsd()) > 0) {
      repository.claimAiTask3(task);
      notice(budget);
      return null;
    }
    UUID attempt = UUID.randomUUID();
    repository.claimAiAttempt4(attempt, task, month(), reserve, json(model));
    repository.claimAiTask4(task);
    notice(budget());
    return new Work(attempt, task, model, input);
  }

  private void notice(Budget budget) {
    if (budget.warning() && repository.noticeAiBudgetNotice(month()) == 0)
      repository.noticeAiBudgetNotice2(month());
  }

  @Transactional
  public void finish(Work work, OpenAiResponses.Result result, OpenAiResponses.Failure failure) {
    lock();
    String state = repository.finishAiAttempt(work.attemptId());
    if (!state.equals("RUNNING")) return;
    JsonNode usage = result != null ? result.usage() : failure.usage();
    BigDecimal actual = cost(work.settings(), usage);
    String error = failure == null ? null : failure.code();
    JsonNode output = result == null ? null : result.value();
    boolean theme = JudgeJson.parse(work.input()).path("kind").asText().equals("THEME");
    boolean diagnostic = JudgeJson.parse(work.input()).path("kind").asText().equals("DIAGNOSTIC");
    if (output != null && diagnostic) {
      String violation =
          DiagnosticEvaluationContract.violation(output, JudgeJson.parse(work.input()));
      if (violation != null)
        log.warn(
            "Diagnostic evaluation output rejected: attempt={} check={}",
            work.attemptId(),
            violation);
    }
    if (output != null
        && !(theme
            ? GenerationThemes.valid(output)
            : diagnostic
                ? DiagnosticEvaluationContract.valid(output, JudgeJson.parse(work.input()))
                : validFeedback(output))) {
      error =
          theme ? "INVALID_THEME" : diagnostic ? "INVALID_DIAGNOSTIC_EVIDENCE" : "INVALID_FEEDBACK";
      output = null;
    }
    String status = output != null ? "COMPLETED" : actual == null ? "UNKNOWN" : "FAILED";
    repository.finishAiAttempt2(
        status,
        actual,
        usage == null ? null : usage.toString(),
        result != null ? result.requestId() : failure.requestId(),
        result == null ? null : result.responseId(),
        result == null ? null : result.model(),
        error,
        work.attemptId());
    repository.finishAiTask(
        status, output == null ? null : output.toString(), error, work.taskId());
    notice(budget());
  }

  public static boolean validFeedback(JsonNode value) {
    if (!value.isObject() || value.size() != 4) return false;
    for (String name : List.of("summary", "uncertainty"))
      if (!value.path(name).isTextual() || value.path(name).asText().length() > 6000) return false;
    for (String name : List.of("observations", "nextSteps")) {
      if (!value.path(name).isArray() || value.path(name).size() > 10) return false;
      for (JsonNode item : value.path(name))
        if (!item.isTextual() || item.asText().length() > 3000) return false;
    }
    return true;
  }

  public static BigDecimal cost(AiSettings.Model settings, JsonNode usage) {
    if (usage == null
        || !usage.path("input_tokens").isIntegralNumber()
        || !usage.path("output_tokens").isIntegralNumber()
        || !usage.path("input_tokens").canConvertToLong()
        || !usage.path("output_tokens").canConvertToLong()) return null;
    long input = usage.path("input_tokens").asLong(), output = usage.path("output_tokens").asLong();
    long cached = usage.path("input_tokens_details").path("cached_tokens").asLong(0);
    if (input < 0 || output < 0 || cached < 0 || cached > input) return null;
    return settings
        .inputRate()
        .multiply(BigDecimal.valueOf(input - cached))
        .add(settings.cachedRate().multiply(BigDecimal.valueOf(cached)))
        .add(settings.outputRate().multiply(BigDecimal.valueOf(output)))
        .movePointLeft(6)
        .setScale(8, RoundingMode.CEILING);
  }

  @Transactional
  public void enqueueEndedSessions() {
    // Serialize with manual admission for each account; final pending judges are allowed to finish
    // first.
    var ids =
        repository.enqueueEndedSessionsTrainingSession(
            (r, n) -> new UUID[] {r.getObject(1, UUID.class), r.getObject(2, UUID.class)});
    for (var row : ids) {
      repository.enqueueEndedSessionsAppUser(row[1]);
      if (repository.enqueueEndedSessionsSubmission(row[0]) > 0) continue;
      var latest = repository.enqueueEndedSessionsSubmission2(row[0]);
      UUID task = null;
      if (latest.isPresent()) {
        try {
          task = enqueue(row[1], latest.get(), "ANALYSIS", "", false).id();
        } catch (AccountException e) {
          if (e.status == 429 || e.status == 503) continue;
          throw e;
        }
      }
      repository.enqueueEndedSessionsTrainingSession2(task, row[0]);
    }
  }
}
