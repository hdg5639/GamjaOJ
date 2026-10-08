package dev.gamjaoj.judge.service;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamjaoj.diagnostic.service.Diagnostics;
import dev.gamjaoj.generation.service.NativeCallablePrograms;
import dev.gamjaoj.judge.domain.ExecutionMetrics;
import dev.gamjaoj.judge.domain.LanguageProfiles;
import dev.gamjaoj.judge.dto.RunDtos;
import dev.gamjaoj.judge.dto.SubmissionDtos;
import dev.gamjaoj.judge.repository.SubmissionsRepository;
import dev.gamjaoj.problem.domain.ThinkingProfile;
import dev.gamjaoj.problem.service.ProblemCatalogMetadata;
import dev.gamjaoj.problem.service.PublicProblemPackages;
import dev.gamjaoj.problem.service.ThinkingDifficulty;
import dev.gamjaoj.shared.exception.AccountException;
import dev.gamjaoj.shared.support.JudgeJson;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Submissions {
  private final SubmissionsRepository repository;
  private final PublicProblemPackages publicPackages = new PublicProblemPackages();
  private final boolean enabled;
  private final Diagnostics diagnostics;
  private final String executionMode;

  /**
   * RUNNER_USER_EXECUTION_MODE=FUNCTIONAL lets learner submissions/runs share Runner slots (each
   * sandbox stays CPU/memory capped).
   */
  public Submissions(
      SubmissionsRepository repository,
      @Value("${gamjaoj.submissions-enabled:false}") boolean enabled,
      Diagnostics diagnostics,
      @Value("${RUNNER_USER_EXECUTION_MODE:EXCLUSIVE}") String executionMode) {
    this.executionMode = "FUNCTIONAL".equals(executionMode) ? "FUNCTIONAL" : "EXCLUSIVE";
    this.repository = repository;
    this.enabled = enabled;
    this.diagnostics = diagnostics;
  }

  public record Problem(
      String version,
      String title,
      String statement,
      String sampleInput,
      String sampleOutput,
      List<Example> examples,
      int sourceLimitBytes,
      boolean submissionsEnabled,
      boolean problemHeld,
      String reviewReason,
      boolean mine,
      boolean shared,
      boolean generated,
      String category,
      List<String> tags,
      String difficulty,
      String difficultySource,
      ThinkingProfile.Profile thinking,
      String solveStatus,
      long pendingSubmissions,
      List<LanguageProfiles.Option> languages,
      JsonNode api) {}

  public record View(
      UUID id,
      String problemVersion,
      String sourceSha256,
      String source,
      String status,
      String verdict,
      String compileMessage,
      OffsetDateTime createdAt,
      OffsetDateTime finishedAt,
      String input,
      String stdout,
      String stderr,
      boolean outputTruncated,
      UUID sessionId,
      String runnerPolicy,
      boolean problemHeld,
      UUID diagnosticItemId,
      String language,
      LanguageProfiles.Option execution,
      List<TestResult> tests,
      int testCount,
      Long wallMs,
      Long memoryPeakBytes,
      Double cpuMs,
      List<RunCase> runCases) {}

  public record RunCase(
      int number,
      String verdict,
      String stdout,
      String stderr,
      boolean outputTruncated,
      Integer wallMs,
      Long memoryPeakBytes,
      Double cpuMs) {}

  private static List<RunCase> runCases(String result) {
    var out = new java.util.ArrayList<RunCase>();
    if (result != null)
      for (JsonNode t : JudgeJson.parse(result).path("tests"))
        out.add(
            new RunCase(
                out.size() + 1,
                t.path("verdict").asText(),
                t.path("stdout").asText(""),
                t.path("stderr").asText(""),
                t.path("stdout_truncated").asBoolean(),
                t.has("wall_ms") ? t.path("wall_ms").asInt() : null,
                t.hasNonNull("memory_peak_bytes") ? t.path("memory_peak_bytes").asLong() : null,
                t.hasNonNull("cpu_ms") ? t.path("cpu_ms").asDouble() : null));
    return out;
  }

  /**
   * One judged test of a formal submission, in plan order: number and verdict only, never its input
   * or output.
   */
  public record TestResult(
      int number, String verdict, Integer wallMs, Long memoryPeakBytes, Double cpuMs) {}

  private static List<TestResult> testResults(String result) {
    var out = new java.util.ArrayList<TestResult>();
    if (result == null) return out;
    int number = 1;
    for (JsonNode t : JudgeJson.parse(result).path("tests"))
      out.add(
          new TestResult(
              number++,
              t.path("verdict").asText(),
              t.has("wall_ms") ? t.path("wall_ms").asInt() : null,
              t.hasNonNull("memory_peak_bytes") ? t.path("memory_peak_bytes").asLong() : null,
              t.hasNonNull("cpu_ms") ? t.path("cpu_ms").asDouble() : null));
    return out;
  }

  /**
   * A public example; explanation only for worked examples confirmed by the Runner
   * (ExampleEnrichment).
   */
  public record Example(String input, String output, String explanation) {}

  /**
   * Published samples when the package lists them (generated problems), otherwise the first test;
   * then verified worked examples.
   */
  public static List<Example> examples(JsonNode data, String verified) {
    var out = new java.util.ArrayList<Example>();
    for (JsonNode s : data.path("samples"))
      if (s.path("input").isTextual() && s.path("output").isTextual() && out.size() < 5)
        out.add(new Example(s.path("input").asText(), s.path("output").asText(), null));
    if (out.isEmpty())
      out.add(
          new Example(
              data.path("tests").get(0).path("input").asText(),
              data.path("tests").get(0).path("output").asText(),
              null));
    if (verified != null)
      for (JsonNode e : JudgeJson.parse(verified))
        out.add(
            new Example(
                e.path("input").asText(),
                e.path("output").asText(),
                e.path("explanation").asText(null)));
    return out;
  }

  private static int testCount(String plan) {
    if (plan == null) return 0;
    JsonNode p = JudgeJson.parse(plan);
    return p.path("tests").size() + p.path("generated").path("tests").size();
  }

  public UUID owner(String username, boolean lock) {
    return repository
        .ownerAppUser(lock, username)
        .orElseThrow(() -> new AccountException(401, "다시 로그인해 주세요."));
  }

  public List<Problem> problems(String username) {
    UUID owner = owner(username, false);
    boolean canSubmit = enabled || repository.problemsExecutionGrant(owner) > 0;
    var cached = publicPackages.snapshot();
    return repository.findVisibleProblems(
        owner,
        cached.keySet(),
        (row, index) -> {
          String hash = row.getString("package_sha256"), raw = row.getString("package_json");
          var data = raw == null ? cached.get(hash) : publicPackages.put(hash, raw);
          var shownExamples = new java.util.ArrayList<>(data.examples());
          if (row.getString("examples_json") != null)
            for (var e : JudgeJson.parse(row.getString("examples_json")))
              shownExamples.add(
                  new Example(
                      e.path("input").asText(),
                      e.path("output").asText(),
                      e.path("explanation").asText(null)));
          var metadata = ProblemCatalogMetadata.read(row);
          // Explicit public fields only: never serialize a private problem package.
          return new Problem(
              row.getString("id"),
              data.title(),
              data.statement(),
              data.sampleInput(),
              data.sampleOutput(),
              shownExamples,
              65536,
              canSubmit && !row.getBoolean("review_hold"),
              row.getBoolean("review_hold"),
              row.getString("review_reason"),
              owner.equals(row.getObject("owner_id", UUID.class)),
              row.getObject("owner_id") == null || row.getBoolean("shared"),
              row.getObject("owner_id") != null,
              metadata.category(),
              metadata.tags(),
              metadata.difficulty(),
              metadata.difficultySource(),
              ThinkingDifficulty.read(row),
              row.getLong("my_accepted") > 0
                  ? "SOLVED"
                  : row.getLong("my_submissions") > 0 ? "ATTEMPTED" : "UNATTEMPTED",
              row.getLong("my_pending"),
              LanguageProfiles.options(row.getString("time_limits_json")),
              data.api());
        });
  }

  @Transactional
  public View submit(String username, UUID key, SubmissionDtos.Request request) {
    return save(username, key, request, null);
  }

  @Transactional
  public View run(String username, UUID key, RunDtos.Request request) {
    if ((request.input() == null) == (request.inputs() == null))
      throw new AccountException(400, "입력 또는 입력 목록 중 하나를 보내 주세요.");
    var inputs = request.inputs() == null ? List.of(request.input()) : request.inputs();
    if (inputs.isEmpty()
        || inputs.size() > 20
        || inputs.stream().anyMatch(java.util.Objects::isNull))
      throw new AccountException(400, "테스트 입력은 1~20개로 설정해 주세요.");
    if (inputs.stream().anyMatch(input -> input.getBytes(StandardCharsets.UTF_8).length > 16384))
      throw new AccountException(400, "입력은 UTF-8 기준 16 KiB 이내로 작성해 주세요.");
    return save(
        username,
        key,
        new SubmissionDtos.Request(
            request.problemVersion(),
            request.source(),
            request.sessionId(),
            request.diagnosticItemId(),
            request.language()),
        List.copyOf(inputs));
  }

  private View save(
      String username, UUID key, SubmissionDtos.Request request, List<String> inputs) {
    String input = inputs == null ? null : inputs.get(0);
    if (request.source().getBytes(StandardCharsets.UTF_8).length > 65536)
      throw new AccountException(400, "코드는 UTF-8 기준 64 KiB 이내로 제출해 주세요.");
    String language = LanguageProfiles.normalize(request.language());
    String hash = JudgeJson.hash(request.source());
    UUID user =
        owner(username, true); // Serialize admission for this user's idempotency and pending cap.
    var existing = repository.saveSubmission(user, key);
    if (existing.isPresent()) {
      View view = find(user, existing.get(), true);
      if (!view.language().equals(language)
          || !view.sourceSha256().equals(hash)
          || !view.problemVersion().equals(request.problemVersion())
          || !java.util.Objects.equals(view.input(), input)
          || !java.util.Objects.equals(view.sessionId(), request.sessionId())
          || !java.util.Objects.equals(view.diagnosticItemId(), request.diagnosticItemId())
          || (inputs != null && !inputs.equals(savedRunInputs(existing.get()))))
        throw new AccountException(409, "같은 요청 키에 다른 코드가 들어왔어요. 새 제출로 보내 주세요.");
      return view;
    }
    if (!enabled && repository.saveExecutionGrant(user, hash) == 0)
      throw new AccountException(503, "코드 채점을 준비하고 있어요. 잠시 후 다시 확인해 주세요.");
    if (repository.saveProblemVersion(request.problemVersion(), user) == 0)
      throw new AccountException(404, "제출할 수 있는 문제 버전이 아니에요.");
    if (repository.saveSubmission2(user) >= 3)
      throw new AccountException(429, "진행 중인 채점이 끝나면 다시 제출해 주세요.");
    var problemData = JudgeJson.parse(repository.saveProblemVersion2(request.problemVersion()));
    boolean diagnosticProblem = repository.saveProblemVersion3(request.problemVersion());
    if (diagnosticProblem != (request.diagnosticItemId() != null)
        || (request.diagnosticItemId() != null && request.sessionId() != null))
      throw new AccountException(409, "진단 문항은 현재 진단에서 제출해 주세요.");
    Diagnostics.Snapshot snapshot =
        request.diagnosticItemId() == null
            ? null
            : diagnostics.admit(
                user, request.diagnosticItemId(), request.problemVersion(), input != null);
    if (request.sessionId() != null) {
      var session =
          repository
              .saveTrainingSession(
                  request.sessionId(),
                  user,
                  (r, n) -> new String[] {r.getString(1), r.getString(2)})
              .orElseThrow(() -> new AccountException(404, "훈련 기록을 찾을 수 없어요."));
      if (!session[0].equals(request.problemVersion()) || !session[1].equals("ACTIVE"))
        throw new AccountException(409, "진행 중인 훈련의 문제를 확인해 주세요. 종료된 훈련에는 새 작업을 추가할 수 없어요.");
    }
    String limits =
        snapshot == null
            ? repository
                .saveProblemVersion4(request.problemVersion(), (r, n) -> r.getString(1))
                .orElse(null)
            : snapshot.limits();
    JsonNode execution = LanguageProfiles.profile(language, limits);
    UUID id = UUID.randomUUID();
    repository.saveSubmission3(
        id,
        user,
        request.problemVersion(),
        request.source(),
        hash,
        key,
        execution.path("image").asText(),
        execution.path(input == null ? "policy" : "runPolicy").asText(),
        language,
        JudgeJson.canonical(execution));
    if (request.sessionId() != null) repository.saveSubmission4(request.sessionId(), id);
    if (snapshot != null) {
      repository.saveSubmission5(request.diagnosticItemId(), id);
    }
    if (input != null || problemData.has("api")) {
      var plan =
          input == null
              ? (com.fasterxml.jackson.databind.node.ObjectNode) problemData.deepCopy()
              : JudgeJson.JSON
                  .createObjectNode()
                  .put("version", request.problemVersion())
                  .put("output_policy", "RUN_ONLY");
      if (input != null) {
        var tests = plan.putArray("tests");
        for (int i = 0; i < inputs.size(); i++)
          tests
              .addObject()
              .put("id", inputs.size() == 1 ? "custom-input" : "custom-input-" + (i + 1))
              .put("input", inputs.get(i))
              .put("output", "");
      }
      if (problemData.has("api"))
        plan.set(
            "callable",
            NativeCallablePrograms.bundleForProblem(problemData, language, input != null));
      String json = JudgeJson.canonical(plan);
      if (input == null) repository.saveSubmission6(json, JudgeJson.hash(json), id);
      else repository.saveSubmission7(input, json, JudgeJson.hash(json), id);
    }
    repository.saveJudgeJob(id, executionMode);
    return find(user, id, true);
  }

  private List<String> savedRunInputs(UUID id) {
    String plan = repository.savedRunInputsSubmission(id);
    if (plan == null) return List.of();
    var inputs = new java.util.ArrayList<String>();
    for (var test : JudgeJson.parse(plan).path("tests")) inputs.add(test.path("input").asText());
    return inputs;
  }

  public List<View> history(String username) {
    return history(username, null, 0);
  }

  public List<View> history(String username, String problemVersion, int page) {
    return history(username, problemVersion, page, 50);
  }

  public List<View> history(String username, String problemVersion, int page, int size) {
    if (size < 1 || size > 50) throw new AccountException(400, "페이지 크기는 1~50개로 설정해 주세요.");
    if (page < 0 || page > 100000) throw new AccountException(400, "잘못된 페이지예요.");
    UUID user = owner(username, false);
    return repository.findHistory(
        user, problemVersion, size, page * size, (row, index) -> viewRow(row, false));
  }

  public List<View> runs(String username) {
    owner(username, false);
    return List.of();
  }

  public View detail(String username, UUID id) {
    return detail(username, id, false);
  }

  public View runDetail(String username, UUID id) {
    return detail(username, id, true);
  }

  private View detail(String username, UUID id, boolean run) {
    View view = find(owner(username, false), id, true);
    if ((view.input() != null) != run) throw new AccountException(404, "기록을 찾을 수 없어요.");
    return view;
  }

  private View find(UUID user, UUID id, boolean includeSource) {
    return repository
        .findRow(includeSource, id, user, (row, index) -> viewRow(row, includeSource))
        .orElseThrow(() -> new AccountException(404, "제출 기록을 찾을 수 없어요."));
  }

  private View viewRow(java.sql.ResultSet row, boolean includeSource) throws java.sql.SQLException {
    String verdict = row.getString("verdict"), result = row.getString("result_json");
    String compile =
        "CE".equals(verdict) && result != null
            ? JudgeJson.parse(result).path("compile").path("stderr").asText("")
            : "";
    String input = row.getString("run_input");
    JsonNode output =
        includeSource && input != null && result != null
            ? JudgeJson.parse(result).path("tests").path(0)
            : JudgeJson.JSON.createObjectNode();
    return new View(
        row.getObject("id", UUID.class),
        row.getString("problem_version"),
        row.getString("source_sha256"),
        includeSource ? row.getString("source_code") : null,
        row.getString("status"),
        verdict,
        compile,
        row.getObject("created_at", OffsetDateTime.class),
        row.getObject("finished_at", OffsetDateTime.class),
        input,
        output.path("stdout").asText(""),
        output.path("stderr").asText(""),
        output.path("stdout_truncated").asBoolean(),
        row.getObject("training_session_id", UUID.class),
        row.getString("runner_policy"),
        row.getBoolean("review_hold"),
        row.getObject("diagnostic_item_id", UUID.class),
        row.getString("language"),
        row.getString("execution_profile_json") == null
            ? null
            : LanguageProfiles.option(JudgeJson.parse(row.getString("execution_profile_json"))),
        includeSource && input == null ? testResults(result) : List.of(),
        includeSource && input == null && "FINISHED".equals(row.getString("status"))
            ? testCount(row.getString("plan_json"))
            : 0,
        result == null ? null : ExecutionMetrics.maximum(JudgeJson.parse(result), "wall_ms"),
        result == null
            ? null
            : ExecutionMetrics.maximum(JudgeJson.parse(result), "memory_peak_bytes"),
        result == null ? null : ExecutionMetrics.maximumCpu(JudgeJson.parse(result)),
        includeSource && input != null ? runCases(result) : List.of());
  }
}
