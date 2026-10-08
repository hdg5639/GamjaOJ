package dev.gamjaoj.service.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gamjaoj.config.AiSettings;
import dev.gamjaoj.domain.ArtifactValidation;
import dev.gamjaoj.domain.JudgeScheduling;
import dev.gamjaoj.domain.ProblemTimeLimits;
import dev.gamjaoj.exception.AccountException;
import dev.gamjaoj.repository.generation.GenerationJobsRepository;
import dev.gamjaoj.service.ai.AiTasks;
import dev.gamjaoj.service.judge.Submissions;
import dev.gamjaoj.service.problem.ThinkingAssessmentPublication;
import dev.gamjaoj.service.problem.ThinkingDifficulty;
import dev.gamjaoj.support.JudgeJson;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GenerationJobs {
  private final GenerationActivity generationActivity;
  private final GenerationProseReview generationProseReview;
  private final GenerationResources generationResources;
  private final ThinkingAssessmentPublication thinkingAssessmentPublication;
  private final GenerationSpecDrafts drafts;
  private final VerificationLedger ledger;
  private final GenerationEvidence executionEvidence;
  private final GenerationJobsRepository repository;
  private final Submissions submissions;
  private final AiSettings settings;
  private final AiTasks ai;
  private final GenerationStructures structures;

  public GenerationJobs(
      GenerationJobsRepository repository,
      Submissions submissions,
      AiSettings settings,
      AiTasks ai,
      GenerationStructures structures,
      GenerationSpecDrafts drafts,
      GenerationEvidence executionEvidence,
      VerificationLedger ledger,
      GenerationActivity generationActivity,
      GenerationProseReview generationProseReview,
      GenerationResources generationResources,
      ThinkingAssessmentPublication thinkingAssessmentPublication) {
    this.generationActivity = generationActivity;
    this.generationProseReview = generationProseReview;
    this.generationResources = generationResources;
    this.thinkingAssessmentPublication = thinkingAssessmentPublication;
    this.ledger = ledger;
    this.executionEvidence = executionEvidence;
    this.drafts = drafts;
    this.structures = structures;
    this.repository = repository;
    this.submissions = submissions;
    this.settings = settings;
    this.ai = ai;
  }

  public record View(
      UUID id,
      String status,
      int revision,
      String model,
      String effort,
      String artifactHash,
      JsonNode artifacts,
      JsonNode validation,
      String error,
      JsonNode preview,
      String problemVersion,
      ThemeView theme,
      boolean problemHeld,
      String reviewReason,
      JsonNode recovery,
      JsonNode resources,
      JsonNode prose) {}

  public record ThemeView(String status, String domain, JsonNode result, String error) {}

  public record Assignment(
      UUID id,
      UUID token,
      int revision,
      String model,
      String effort,
      JsonNode spec,
      String feedback,
      JsonNode repair,
      JsonNode reuse) {}

  private void lock() {
    repository.lockAiBudgetLock();
  }

  private String version(UUID id, int revision) {
    return "generated-" + id + "-r" + revision;
  }

  @Transactional
  public View create(String username, UUID key, String template) {
    return create(username, key, template, "basics");
  }

  @Transactional
  public View create(String username, UUID key, String template, String focus) {
    return create(username, key, template, focus, null);
  }

  @Transactional
  public View create(
      String username, UUID key, String template, String focus, UUID sourceAnalysis) {
    return create(username, key, template, focus, sourceAnalysis, false);
  }

  @Transactional
  public View create(
      String username,
      UUID key,
      String template,
      String focus,
      UUID sourceAnalysis,
      boolean shared) {
    UUID owner = submissions.owner(username, false);
    lock();
    if (drafts.contains(key)) throw new AccountException(409, "이미 사용된 초안 요청 키예요.");
    var type = GenerationType.of(template);
    focus = GenerationChoices.normalize(type, focus);
    if (repository.createGenerationJob(key) > 0) {
      View saved = view(username, key);
      Object[] original =
          repository.createGenerationJob2(
              key,
              (r, n) ->
                  new Object[] {
                    r.getString(1), r.getObject(2, UUID.class), r.getString(3), r.getBoolean(4)
                  });
      if (!java.util.Objects.equals(shared, original[3])
          || !template.equals(original[2])
          || !focus.equals(original[0])
          || !java.util.Objects.equals(sourceAnalysis, original[1]))
        throw new AccountException(409, "같은 요청 키의 연습 조건이 달라요. 기존 생성 기록을 확인해 주세요.");
      return saved;
    }
    if (generationActivity.active(owner))
      throw new AccountException(409, "진행 중인 규칙 고정 출제를 먼저 마쳐 주세요.");
    if (drafts.active(owner)) throw new AccountException(409, "진행 중인 출제 초안을 먼저 마쳐 주세요.");
    if (repository.createGenerationJob3(owner) > 0)
      throw new AccountException(409, "진행 중인 생성 작업을 먼저 마쳐 주세요.");
    JsonNode learning =
        sourceAnalysis == null ? null : learningContext(owner, sourceAnalysis, type);
    String model = settings.value("CODEX_GENERATION_MODEL", "gpt-6.1-sol"),
        effort = settings.value("CODEX_GENERATION_REASONING", "medium");
    if (!List.of("low", "medium", "high", "xhigh", "max").contains(effort))
      throw new AccountException(503, "Codex reasoning 설정을 확인해 주세요.");
    repository.createGenerationJob4(key, owner, template, model, effort, focus);
    repository.createGenerationJob5(shared, key);
    if (learning != null) repository.createGenerationJob6(sourceAnalysis, learning.toString(), key);
    structures.select(key, owner, type, focus);
    var recent = JudgeJson.JSON.createArrayNode();
    repository
        .createGenerationJob7(owner, key)
        .forEach(
            raw -> {
              var old = JudgeJson.parse(raw);
              recent
                  .addObject()
                  .put("title", old.path("title").asText())
                  .put(
                      "context",
                      old.path("context")
                          .asText()
                          .substring(0, Math.min(800, old.path("context").asText().length())));
            });
    var used = repository.createGenerationJob8(owner);
    var available =
        GenerationThemes.DOMAINS.stream().filter(domain -> !used.contains(domain)).toList();
    String domain = available.get(new java.security.SecureRandom().nextInt(available.size()));
    var input =
        JudgeJson.JSON
            .createObjectNode()
            .put("kind", "THEME")
            .put("domain", domain)
            .put("trustedStatement", type.statement());
    input.set("recentStories", recent);
    UUID theme = ai.theme(owner, key, input);
    repository.createGenerationJob9(theme, domain, key);
    return view(username, key);
  }

  public record LearningOption(UUID id, UUID submissionId, String summary) {}

  public List<LearningOption> learningOptions(String username) {
    return learningOptions(username, GenerationTemplate.ID);
  }

  public List<LearningOption> learningOptions(String username, String template) {
    var type = GenerationType.of(template);
    UUID owner = submissions.owner(username, false);
    return repository.learningOptionsAiTask(
        owner,
        owner,
        type.recipe == null ? type.baseProblem : type.id,
        type.id,
        (r, n) ->
            new LearningOption(
                r.getObject(1, UUID.class),
                r.getObject(2, UUID.class),
                JudgeJson.parse(r.getString(3)).path("summary").asText()));
  }

  private JsonNode learningContext(UUID owner, UUID id, GenerationType type) {
    var data =
        repository
            .learningContextAiTask(
                id, owner, owner, (r, n) -> new String[] {r.getString(1), r.getString(2)})
            .orElseThrow(() -> new AccountException(404, "완료된 본인의 풀이 분석을 선택해 주세요."));
    boolean compatible =
        data[1].equals(type.recipe == null ? type.baseProblem : type.id)
            || repository.learningContextGenerationJob(type.id, data[1]) > 0;
    if (!compatible) throw new AccountException(400, "선택한 문제 유형과 같은 유형의 풀이 분석을 선택해 주세요.");
    JsonNode result = JudgeJson.parse(data[0]);
    if (!AiTasks.validFeedback(result)) throw new AccountException(409, "분석 결과 형식을 확인해 주세요.");
    var context = JudgeJson.JSON.createObjectNode().put("summary", result.path("summary").asText());
    context.set("nextSteps", result.path("nextSteps").deepCopy());
    return context;
  }

  public List<View> list(String username) {
    return repository
        .listGenerationJob(submissions.owner(username, false))
        .map(id -> view(username, id))
        .toList();
  }

  public View view(String username, UUID id) {
    if (repository.viewGenerationJob(id, submissions.owner(username, false)) == 0)
      throw new AccountException(404, "생성 작업을 찾을 수 없어요.");
    return find(id);
  }

  private record JobRow(
      String status,
      int revision,
      String model,
      String effort,
      String hash,
      JsonNode artifacts,
      JsonNode validation,
      String error,
      String template,
      boolean held,
      String reviewReason) {}

  private View find(UUID id) {
    // Materialize the row before enrichment: a mapper still owns its JDBC connection.
    var row =
        repository
            .findGenerationJob(
                id,
                (r, n) ->
                    new JobRow(
                        r.getString("status"),
                        r.getInt("revision"),
                        r.getString("model"),
                        r.getString("effort"),
                        r.getString("artifacts_sha256"),
                        r.getString("artifacts_json") == null
                            ? null
                            : JudgeJson.parse(r.getString("artifacts_json")),
                        r.getString("validation_json") == null
                            ? null
                            : JudgeJson.parse(r.getString("validation_json")),
                        r.getString("error_code"),
                        r.getString("template_id"),
                        r.getBoolean("problem_held"),
                        r.getString("review_reason")))
            .orElseThrow(() -> new AccountException(404, "생성 작업을 찾을 수 없어요."));
    return new View(
        id,
        row.status,
        row.revision,
        row.model,
        row.effort,
        row.hash,
        row.artifacts,
        row.validation,
        row.error,
        preview(id, GenerationType.of(row.template)),
        row.status.equals("READY") ? version(id, row.revision) : null,
        themeFor(id),
        row.held,
        row.reviewReason,
        recoveryInfo(id, row.revision),
        generationResources.progress("TAG", id),
        generationProseReview.progress(id, row.revision));
  }

  private JsonNode recoveryInfo(UUID id, int revision) {
    if (revision == 0) return null;
    var raw = repository.recoveryInfoGenerationJob(id).orElse(null);
    if (raw == null) return null;
    var repair = JudgeJson.parse(raw);
    boolean prose = true;
    for (var field : repair.path("fields"))
      if (!List.of("title", "context", "hints", "editorial").contains(field.asText()))
        prose = false;
    return JudgeJson.JSON
        .createObjectNode()
        .put("attempt", revision)
        .put("limit", generationResources.enabled("TAG", id) ? 3 : 1)
        .put("scope", prose ? "PROSE" : "IMPLEMENTATION");
  }

  private JsonNode preview(UUID id, GenerationType type) {
    var preview = type.spec().put("contractTitle", type.title);
    preview.set("structure", structures.summary(id, type));
    return preview;
  }

  private ThemeView themeFor(UUID id) {
    return repository
        .themeForGenerationJob(
            id,
            (r, n) ->
                new ThemeView(
                    r.getString(1),
                    r.getString(4),
                    r.getString(2) == null ? null : JudgeJson.parse(r.getString(2)),
                    r.getString(3)))
        .orElse(null);
  }

  @Transactional
  public View retryTheme(String username, UUID id) {
    lock();
    var job = view(username, id);
    if (!ledger.valid(id)) {
      ledger.block(id);
      return find(id);
    }
    if (!List.of("QUEUED", "THEME_FAILED").contains(job.status()) || job.theme() == null)
      throw new AccountException(409, "테마 준비 중인 작업만 다시 요청할 수 있어요.");
    UUID task = repository.retryThemeGenerationJob(id);
    if (repository.retryThemeGenerationJob2(id, id) > 0)
      throw new AccountException(409, "현재 진행 중인 생성 작업을 먼저 마쳐 주세요.");
    ai.retry(username, task);
    repository.retryThemeGenerationJob3(id);
    return view(username, id);
  }

  private JsonNode recentStories(UUID id) {
    return repository
        .recentStoriesGenerationJob(id)
        .map(raw -> JudgeJson.parse(raw).path("recentStories"))
        .orElse(JudgeJson.JSON.createArrayNode());
  }

  private GenerationType typeFor(UUID id) {
    return GenerationType.of(repository.typeForGenerationJob(id));
  }

  private ObjectNode specFor(UUID id) {
    var type = typeFor(id);
    var spec = type.spec();
    String focus = repository.specForGenerationJob(id);
    spec.put(
        "learningFocus",
        java.util.Arrays.stream(focus.split(","))
            .map(type::focus)
            .collect(java.util.stream.Collectors.joining("\n")));
    String learning = repository.specForGenerationJob2(id, (r, n) -> r.getString(1)).orElse(null);
    if (learning != null) spec.set("learnerFeedback", JudgeJson.parse(learning));
    var theme = themeFor(id);
    if (theme != null && theme.result() != null) {
      spec.set("theme", theme.result());
      spec.put("themeDomain", theme.domain());
      spec.set("recentStories", recentStories(id));
    }
    GenerationValidationPolicy.attach(spec, spec);
    return spec;
  }

  private JsonNode repairFor(UUID id) {
    String raw = repository.repairForGenerationJob(id).orElse(null);
    if (raw == null) return null;
    var repair = (ObjectNode) JudgeJson.parse(raw);
    var saved = find(id);
    repair.set("artifacts", saved.artifacts());
    repair.set("oracle", JudgeJson.parse(repository.repairForGenerationJob2(id)));
    return repair;
  }

  @Transactional
  public Assignment claim() {
    lock();
    drafts.expire();
    if (drafts.running() || generationResources.running()) return null;
    var proseWork = generationProseReview.claim(settings);
    if (proseWork != null) return proseWork;
    if (repository.claimGenerationProseReview() > 0) return null;
    var resourceWork = generationResources.claim(settings);
    if (resourceWork != null) return resourceWork;
    repository.claimGenerationJob();
    if (repository.claimGenerationJob2() > 0) return null;
    var id = repository.claimGenerationJob3();
    if (id.isEmpty()) return drafts.claim();
    if (!ledger.valid(id.get())) {
      ledger.block(id.get());
      return null;
    }
    var job = find(id.get());
    UUID token = UUID.randomUUID();
    repository.claimGenerationJob4(
        token, OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(20), id.get());
    repository.claimGenerationAttempt(id.get(), job.revision(), job.model(), job.effort());
    return new Assignment(
        id.get(),
        token,
        job.revision(),
        job.model(),
        job.effort(),
        specFor(id.get()),
        job.error(),
        repairFor(id.get()),
        structures.snapshot(id.get()));
  }

  @Transactional
  public void complete(
      UUID id, UUID token, JsonNode artifacts, JsonNode oracle, JsonNode usage, String error) {
    lock();
    if (generationProseReview.contains(id)) {
      var decision = generationProseReview.complete(id, token, artifacts, oracle, usage, error);
      if (decision == null) return;
      if (!decision.accepted()) {
        if (GenerationDraftRecovery.stopped(decision.error()))
          repository.completeGenerationJob(decision.error(), decision.job());
        else fail(find(decision.job()), decision.error(), java.util.Set.of("title", "context"));
        return;
      }
      String username = repository.completeAppUser(decision.job());
      review(username, decision.job(), decision.hash(), true);
      return;
    }
    if (generationResources.contains(id)) {
      generationResources.complete(id, token, artifacts, oracle, usage, error);
      return;
    }
    if (drafts.contains(id)) {
      drafts.complete(id, token, artifacts, oracle, usage, error);
      return;
    }
    View job = find(id);
    String savedToken = repository.completeGenerationJob2(id).toString();
    if (!token.toString().equals(savedToken)) throw new AccountException(409, "지난 생성 작업의 결과예요.");
    var envelope = JudgeJson.JSON.createObjectNode();
    envelope.set("artifacts", artifacts);
    envelope.set("oracle", oracle);
    envelope.set("usage", usage);
    envelope.put("error", error);
    String audit = JudgeJson.canonical(envelope);
    String previous = repository.completeGenerationAttempt(id, job.revision()).orElse(null);
    if (previous != null) {
      if (previous.equals(audit)) return;
      throw new AccountException(409, "이미 저장된 생성 결과와 달라요.");
    }
    if (!job.status().equals("GENERATING") || repository.completeGenerationJob3(id) != 1)
      throw new AccountException(409, "생성 작업의 유효 시간이 지났어요.");
    if (audit.length() > 600_000) throw new AccountException(400, "생성 결과가 너무 커요.");
    String cliVersion = usage == null ? "unknown" : usage.path("cliVersion").asText("unknown");
    if (!List.of("0.154.0", "0.155.1", "0.160.0").contains(cliVersion)) cliVersion = "unknown";
    repository.completeGenerationAttempt2(audit, cliVersion, id, job.revision());
    if (!ledger.valid(id)) {
      ledger.block(id);
      return;
    }
    if (error != null) {
      if (List.of("INVALID_CODEX_ARTIFACT", "CODEX_OUTPUT_LIMIT").contains(error)) {
        retryInvalidArtifact(job, error);
        return;
      }
      repository.completeGenerationJob4(error.substring(0, Math.min(80, error.length())), id);
      return;
    }
    try {
      validateArtifacts(artifacts, oracle);
    } catch (AccountException invalid) {
      var fields = invalidArtifactFields(artifacts, oracle);
      if (job.artifacts() == null
          && Set.of("title", "context", "editorial", "hints").containsAll(fields)) {
        structures.enforce(id, artifacts, oracle);
        String raw = JudgeJson.canonical(artifacts);
        repository.completeGenerationJob5(
            raw, JudgeJson.canonical(oracle), JudgeJson.hash(raw), id);
        fail(find(id), "INVALID_PROSE_ARTIFACT", fields);
      } else retryInvalidArtifact(job, "INVALID_GENERATION_ARTIFACT");
      return;
    }
    JsonNode repair = repairFor(id);
    if (repair == null) structures.enforce(id, artifacts, oracle);
    if (repair != null) {
      var changed = new java.util.HashSet<String>();
      repair.path("fields").forEach(field -> changed.add(field.asText()));
      for (String field :
          List.of(
              "title", "context", "reference", "generator", "inputValidator", "editorial", "hints"))
        if (!changed.contains(field)
            && !artifacts.path(field).equals(repair.path("artifacts").path(field)))
          throw new AccountException(400, "수정 대상이 아닌 산출물이 변경됐어요.");
      if (!changed.contains("oracle") && !oracle.equals(repair.path("oracle")))
        throw new AccountException(400, "수정 대상이 아닌 oracle이 변경됐어요.");
    }
    String json = JudgeJson.canonical(artifacts), oracleJson = JudgeJson.canonical(oracle);
    String hash = JudgeJson.hash(json + "\n" + oracleJson);
    repository.completeGenerationJob6(json, oracleJson, hash, id);
    if (job.theme() != null && GenerationThemes.duplicate(artifacts, recentStories(id))) {
      fail(find(id), "STORY_TOO_SIMILAR", java.util.Set.of("title", "context"));
      return;
    }
    if (generationResources.enabled("TAG", id)) {
      generationProseReview.start(
          find(id), specFor(id), typeFor(id).statement(), typeFor(id).sample());
      return;
    }
    String username = repository.completeAppUser2(id);
    review(username, id, hash, true);
  }

  public static Set<String> invalidArtifactFields(JsonNode artifacts, JsonNode oracle) {
    var invalid = new java.util.TreeSet<String>();
    var all =
        Set.of(
            "title", "context", "reference", "generator", "inputValidator", "editorial", "hints");
    if (artifacts == null
        || !artifacts.isObject()
        || artifacts.size() != 7
        || !all.stream().allMatch(artifacts::has)) {
      invalid.addAll(all);
      invalid.add("oracle");
      return invalid;
    }
    for (String field :
        List.of("title", "context", "reference", "generator", "inputValidator", "editorial")) {
      var value = artifacts.path(field);
      int max =
          List.of("reference", "generator", "inputValidator").contains(field)
              ? 65536
              : field.equals("title") ? 100 : 6000;
      if (!value.isTextual()
          || value.asText().isBlank()
          || value.asText().getBytes(StandardCharsets.UTF_8).length > max) invalid.add(field);
    }
    if (oracle == null
        || !oracle.isObject()
        || oracle.size() != 1
        || !oracle.path("source").isTextual()
        || oracle.path("source").asText().isBlank()
        || oracle.path("source").asText().getBytes(StandardCharsets.UTF_8).length > 65536)
      invalid.add("oracle");
    var hints = artifacts.path("hints");
    if (!hints.isArray() || hints.size() != 3) invalid.add("hints");
    else
      for (var hint : hints)
        if (!hint.isTextual() || hint.asText().isBlank() || hint.asText().length() > 2000)
          invalid.add("hints");
    return invalid;
  }

  public static void validateArtifacts(JsonNode artifacts, JsonNode oracle) {
    if (!invalidArtifactFields(artifacts, oracle).isEmpty())
      throw new AccountException(400, "생성 산출물의 형식과 크기를 확인해 주세요.");
  }

  @Transactional
  public View review(String username, UUID id, String hash, boolean approve) {
    lock();
    View job = view(username, id);
    if (approve
        && List.of("VALIDATING", "READY").contains(job.status())
        && java.util.Objects.equals(job.artifactHash(), hash)) return job;
    if (!job.status().equals("AWAITING_REVIEW")
        || !java.util.Objects.equals(job.artifactHash(), hash))
      throw new AccountException(409, "현재 산출물을 다시 확인해 주세요.");
    if (!approve) {
      fail(job, "SEMANTIC_REVIEW_REJECTED");
      return find(id);
    }
    if (!ledger.valid(id)) {
      ledger.block(id);
      return find(id);
    }
    if (generationResources.enabled("TAG", id)
        && !generationProseReview.passed(id, job.revision(), hash))
      throw new AccountException(409, "독립 본문 검수가 끝나면 실행 검증이 시작돼요.");
    var type = typeFor(id);
    String ver = version(id, job.revision());
    var problem =
        JudgeJson.JSON
            .createObjectNode()
            .put("version", ver)
            .put("title", job.artifacts().path("title").asText())
            .put("statement", job.artifacts().path("context").asText() + "\n\n" + type.statement())
            .put("output_policy", "TOKEN_EXACT");
    problem.putArray("tests").add(type.sample());
    String json = JudgeJson.canonical(problem);
    repository.reviewProblemVersion(ver, json, JudgeJson.hash(json), type.baseProblem);
    repository.reviewProblemVersion2(id, id, ver);
    repository.reviewGenerationJob(hash, id);
    // Generator execution is an ordinary, low-priority Runner job; it receives no model credential.
    var plan =
        plan(
            ver,
            List.of(
                JudgeJson.JSON
                    .createObjectNode()
                    .put("id", "custom-input")
                    .put("input", new java.security.SecureRandom().nextLong() + "\n")
                    .put("output", "")),
            true);
    execute(job, "generator", job.artifacts().path("generator").asText(), plan, "OK");
    return find(id);
  }

  /** Operator repair: preserve the old package/reports; rerun every gate without any model call. */
  @Transactional
  public View repairInputLayout(UUID id, String expectedHash) {
    lock();
    View old = find(id);
    var type = typeFor(id);
    String oldVersion = version(id, old.revision());
    var saved =
        repository.repairInputLayoutProblemVersion(
            oldVersion, (r, n) -> new String[] {r.getString(1), r.getString(2)});
    if (saved.isEmpty() || !saved.get()[1].equals(expectedHash) || !old.status().equals("READY"))
      throw new AccountException(409, "현재 게시 버전과 해시를 다시 확인해 주세요.");
    if (InputLayout.matches(type, JudgeJson.parse(saved.get()[0])))
      throw new AccountException(409, "입력 형식 수정 대상이 아니에요.");
    if (!ledger.valid(id)) throw new AccountException(409, "원본 검증 근거부터 복구해야 해요.");
    var owner =
        repository.repairInputLayoutGenerationJob(
            id, (r, n) -> new Object[] {r.getObject(1, UUID.class), r.getString(2)});
    String reason = "문제 설명과 테스트 줄 형식 불일치 · 수정 버전 전체 검증 중";
    ledger.revokeTree((UUID) owner[0], id, reason);
    repository.repairInputLayoutProblemVersion2(reason, "generated-" + id + "-r%");
    // Revisions >= 1 fail closed instead of entering the automatic paid repair path.
    repository.repairInputLayoutGenerationJob2(structures.contract(type), id);
    return review((String) owner[1], id, old.artifactHash(), true);
  }

  private ObjectNode plan(String version, List<JsonNode> tests, boolean run) {
    ObjectNode plan =
        JudgeJson.JSON
            .createObjectNode()
            .put("version", version)
            .put("output_policy", run ? "RUN_ONLY" : "TOKEN_EXACT");
    var array = plan.putArray("tests");
    tests.forEach(array::add);
    return plan;
  }

  private void execute(View job, String role, String source, JsonNode plan, String expected) {
    UUID submission = UUID.randomUUID();
    String ver = version(job.id(), job.revision()), json = JudgeJson.canonical(plan);
    boolean run = plan.path("output_policy").asText().equals("RUN_ONLY");
    repository.executeSubmission(
        submission,
        ver,
        source,
        JudgeJson.hash(source),
        submission,
        run ? "java8-run-v1" : "java8-judge-v1",
        run ? plan.path("tests").path(0).path("input").asText() : "validation",
        json,
        JudgeJson.hash(json),
        ver,
        job.id());
    repository.executeJudgeJob(submission, JudgeScheduling.generated(role, plan));
    repository.executeGenerationExecution(job.id(), job.revision(), role, submission, expected);
  }

  private void retryInvalidArtifact(View job, String error) {
    if (job.artifacts() != null) {
      var repair = repairFor(job.id());
      var fields = new java.util.HashSet<String>();
      if (repair != null) repair.path("fields").forEach(f -> fields.add(f.asText()));
      if (fields.isEmpty())
        fields.addAll(
            List.of(
                "title",
                "context",
                "reference",
                "generator",
                "inputValidator",
                "editorial",
                "hints",
                "oracle"));
      fail(job, error, fields);
      return;
    }
    int maximum = generationResources.enabled("TAG", job.id()) ? 3 : 1;
    repository.retryInvalidArtifactGenerationJob(
        job.revision() < maximum ? "QUEUED" : "FAILED",
        job.revision() < maximum ? job.revision() + 1 : job.revision(),
        error,
        job.id());
  }

  private void fail(View job, String error) {
    fail(
        job,
        error,
        java.util.Set.of(
            "title",
            "context",
            "reference",
            "generator",
            "inputValidator",
            "editorial",
            "hints",
            "oracle"));
  }

  private void fail(View job, String error, java.util.Set<String> fields) {
    var repair = JudgeJson.JSON.createObjectNode();
    var selected = repair.putArray("fields");
    fields.stream().sorted().forEach(selected::add);
    var checks = repair.putArray("failedChecks");
    repository
        .failGenerationExecution(
            job.id(), job.revision(), (r, n) -> r.getString(1) + ":" + r.getString(2))
        .forEach(checks::add);
    if (error.equals("PROSE_REVIEW_REJECTED") || error.equals("INVALID_PROSE_REVIEW"))
      repository
          .failGenerationProseReview(job.id(), job.revision())
          .filter(java.util.Objects::nonNull)
          .map(JudgeJson::parse)
          .ifPresent(
              receipt -> {
                for (var issue : receipt.path("artifacts").path("issues"))
                  checks.add("PROSE:" + issue.asText());
              });
    // New jobs permit three targeted repairs; old jobs keep their original single-repair contract.
    int maximumRepairs = generationResources.enabled("TAG", job.id()) ? 3 : 1;
    repository.failGenerationJob(
        job.revision() < maximumRepairs ? "QUEUED" : "FAILED",
        job.revision() < maximumRepairs ? job.revision() + 1 : job.revision(),
        error,
        repair.toString(),
        job.id());
  }

  @Transactional
  public void advance() {
    lock();
    generationResources.advance();
    drafts.advance();
    repository.advanceGenerationJob();
    repository.advanceGenerationJob2();
    var jobs = repository.advanceGenerationJob3();
    for (UUID id : jobs) {
      if (!ledger.valid(id)) {
        ledger.block(id);
        continue;
      }
      View job = find(id);
      var rows =
          repository.advanceGenerationExecution(
              id,
              job.revision(),
              (r, n) ->
                  new String[] {
                    r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5)
                  });
      if (rows.isEmpty() || rows.stream().anyMatch(r -> !r[2].equals("FINISHED"))) continue;
      var failed = rows.stream().filter(r -> !r[1].equals(r[3])).toList();
      if (!failed.isEmpty()) {
        if (failed.stream().anyMatch(r -> "IE".equals(r[3]))) {
          repository.advanceGenerationJob4(id);
          continue;
        }
        var fields = new java.util.HashSet<String>();
        for (var row : failed) {
          String role = row[0];
          if (role.equals("generator")) fields.add("generator");
          else if (role.startsWith("oracle-") || role.equals("final-oracle")) fields.add("oracle");
          else if (role.startsWith("validator-")) fields.add("inputValidator");
          else if (role.startsWith("reference-") || role.equals("final-reference"))
            fields.addAll(List.of("reference", "editorial", "hints"));
          else {
            // A trusted mutant behaving unexpectedly indicates a validation/infrastructure defect.
            repository.advanceGenerationJob5(id);
            fields.clear();
            break;
          }
        }
        if (!fields.isEmpty())
          fail(job, "VALIDATION_" + failed.get(0)[0] + "_" + failed.get(0)[3], fields);
        continue;
      }
      var type = typeFor(id);
      String ver = version(id, job.revision());
      if (rows.size() == 1) {
        var output = JudgeJson.parse(rows.get(0)[4]).path("tests").path(0);
        try {
          if (output.path("stdout_truncated").asBoolean())
            throw new AccountException(400, "Generator output truncated");
          String[] lines = output.path("stdout").asText().strip().split("\\R");
          if (lines.length != 4)
            throw new AccountException(400, "Generator must produce four inputs");
          List<JsonNode> cases = type.cases(new java.security.SecureRandom().nextLong());
          for (int i = 0; i < 4; i++) cases.add(type.test("generated-" + i, lines[i] + "\n"));
          String oracle =
              JudgeJson.parse(repository.advanceGenerationJob6(id)).path("source").asText();
          for (int offset = 0; offset < cases.size(); offset += 20) {
            var batch = cases.subList(offset, Math.min(offset + 20, cases.size()));
            var testPlan = plan(ver, batch, false);
            execute(
                job,
                "reference-" + offset,
                job.artifacts().path("reference").asText(),
                testPlan,
                "AC");
            execute(job, "oracle-" + offset, oracle, testPlan, "AC");
            List<JsonNode> checks =
                batch.stream()
                    .map(t -> (JsonNode) ((ObjectNode) t.deepCopy()).put("output", "VALID\n"))
                    .toList();
            execute(
                job,
                "validator-" + offset,
                job.artifacts().path("inputValidator").asText(),
                plan(ver, checks, false),
                "AC");
          }
          var invalid = new ArrayList<JsonNode>();
          int idx = 0;
          for (String input : type.invalidInputs())
            invalid.add(
                JudgeJson.JSON
                    .createObjectNode()
                    .put("id", "invalid-" + idx++)
                    .put("input", input)
                    .put("output", "INVALID\n"));
          execute(
              job,
              "validator-invalid",
              job.artifacts().path("inputValidator").asText(),
              plan(ver, invalid, false),
              "AC");
          var boundary = type.mutantCases(cases);
          execute(job, type.mutantRole(true), type.mutant(true), plan(ver, boundary, false), "WA");
          execute(
              job, type.mutantRole(false), type.mutant(false), plan(ver, boundary, false), "WA");
          // Fresh seed is sampled after artifact acceptance; it is never part of a repair prompt.
          var finalCases = type.cases(new java.security.SecureRandom().nextLong());
          finalCases = finalCases.subList(finalCases.size() - 5, finalCases.size());
          for (JsonNode test : finalCases)
            ((ObjectNode) test).put("id", "final-" + test.path("id").asText());
          execute(
              job,
              "final-reference",
              job.artifacts().path("reference").asText(),
              plan(ver, finalCases, false),
              "AC");
          execute(job, "final-oracle", oracle, plan(ver, finalCases, false), "AC");
          // Publish a bounded 20-case package only after every validation job succeeds.
          List<JsonNode> published = new ArrayList<>();
          published.add(cases.get(0));
          published.addAll(
              cases.stream()
                  .filter(
                      t -> {
                        String key = t.path("id").asText();
                        return !key.equals("sample")
                            && !key.startsWith("small-")
                            && (!key.startsWith("seed-")
                                || key.equals("seed-0")
                                || key.equals("seed-1"));
                      })
                  .limit(14)
                  .toList());
          published.addAll(finalCases);
          var packagePlan =
              plan(ver, published, false)
                  .put("title", job.artifacts().path("title").asText())
                  .put(
                      "statement",
                      job.artifacts().path("context").asText() + "\n\n" + type.statement());
          String json = JudgeJson.canonical(packagePlan);
          repository.advanceProblemVersion(json, JudgeJson.hash(json), ver);
        } catch (AccountException e) {
          fail(job, "INVALID_GENERATOR_INPUT", java.util.Set.of("generator"));
        }
      } else {
        if (rows.size() != type.executions())
          throw new IllegalStateException("Incomplete generation gate set");
        if (!job.artifactHash().equals(repository.advanceGenerationJob7(id)))
          throw new IllegalStateException("Generation review fence mismatch");
        // Check the exact bytes being published, including jobs started before deployment.
        var publication = JudgeJson.parse(repository.advanceProblemVersion2(ver));
        if (!InputLayout.matches(type, publication)) {
          fail(job, "INPUT_LAYOUT_MISMATCH", java.util.Set.of("generator"));
          continue;
        }
        var report =
            JudgeJson.JSON
                .createObjectNode()
                .put("policy", type.id + "-validation-v4")
                .put("artifactHash", job.artifactHash())
                .put("smallDomain", type.smallDomain())
                .put("gates", "V01-V08")
                .put("executions", rows.size());
        var evidence = report.putArray("results");
        for (var row : rows)
          evidence
              .addObject()
              .put("role", row[0])
              .put("verdict", row[3])
              .put("reportHash", JudgeJson.hash(row[4]));
        var manifest = executionEvidence.capture(id, job.revision(), type);
        report.set("executionInputAudit", manifest);
        var reuse = structures.snapshot(id);
        if (reuse != null)
          report.set(
              "reuseAudit",
              GenerationEvidence.compare(manifest, reuse.path("executionInputAudit"))
                  .put("sourceJobId", reuse.path("sourceJobId").asText()));
        long referenceMs =
            rows.stream()
                .filter(r -> r[0].contains("reference"))
                .mapToLong(r -> ProblemTimeLimits.maximum(JudgeJson.parse(r[4])))
                .max()
                .orElse(0);
        String limits;
        try {
          limits = ProblemTimeLimits.measured(referenceMs);
        } catch (ArtifactValidation.Invalid invalid) {
          fail(job, invalid.getMessage(), java.util.Set.of("reference"));
          continue;
        }
        try {
          limits =
              generationResources.ensure(
                  "TAG",
                  id,
                  ver,
                  specFor(id),
                  job.artifacts().path("reference").asText(),
                  job.artifacts().path("inputValidator").asText(),
                  limits);
        } catch (ArtifactValidation.Invalid invalid) {
          if (GenerationDraftRecovery.stopped(invalid.getMessage()))
            repository.advanceGenerationJob8(invalid.getMessage(), id);
          else
            fail(
                job,
                invalid.getMessage(),
                java.util.Set.of("reference", "generator", "inputValidator", "oracle"));
          continue;
        }
        if (limits == null) continue;
        report.set("timeLimits", JudgeJson.parse(limits));
        report.put("threeLanguagesMeasured", generationResources.enabled("TAG", id));
        report.put("evidenceId", ledger.freeze(id, job.revision(), report).toString());
        var teaching =
            JudgeJson.JSON
                .createObjectNode()
                .put("editorial", job.artifacts().path("editorial").asText());
        teaching.set("hints", job.artifacts().path("hints"));
        repository.advanceProblemVersion3(limits, teaching.toString(), job.id(), ver);
        thinkingAssessmentPublication.publish(ver, ThinkingDifficulty.template(type), "TEMPLATE");
        repository.advanceGenerationJob9(report.toString(), id);
      }
    }
  }
}
