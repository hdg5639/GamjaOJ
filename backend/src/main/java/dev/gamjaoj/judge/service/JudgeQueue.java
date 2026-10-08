package dev.gamjaoj.judge.service;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamjaoj.export.service.SolutionExports;
import dev.gamjaoj.generation.service.HybridExecution;
import dev.gamjaoj.judge.domain.JudgeJob;
import dev.gamjaoj.judge.domain.RunnerEnvironment;
import dev.gamjaoj.judge.repository.JudgeQueueRepository;
import dev.gamjaoj.shared.exception.AccountException;
import dev.gamjaoj.shared.support.JudgeJson;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JudgeQueue {
  private final JudgeQueueRepository repository;
  private final org.springframework.context.ApplicationEventPublisher events;
  private final int functionalSlots;

  /**
   * functionalSlots must match the Runner host's GAMJAOJ_FUNCTIONAL_SLOTS; the host lock is the
   * physical cap.
   */
  public JudgeQueue(
      JudgeQueueRepository repository,
      org.springframework.context.ApplicationEventPublisher events,
      @org.springframework.beans.factory.annotation.Value("${RUNNER_FUNCTIONAL_SLOTS:2}")
          int functionalSlots) {
    this.repository = repository;
    this.events = events;
    this.functionalSlots = Math.max(1, Math.min(32, functionalSlots));
  }

  public record Assignment(
      UUID submissionId,
      int attempt,
      UUID token,
      String source,
      String sourceSha256,
      JsonNode problem,
      String problemSha256,
      String runtimeImage,
      String runnerPolicy,
      int heartbeatSeconds,
      String executionMode,
      JsonNode runnerEnvironment,
      String language,
      JsonNode executionProfile,
      boolean judgeAll) {}

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  private JudgeJob job(UUID id) {
    return repository
        .jobJudgeJob(id)
        .orElseThrow(() -> new AccountException(404, "Unknown judge job"));
  }

  @Transactional
  public Optional<Assignment> claim(UUID worker) {
    // Short coordinator-only lock makes retries with the same worker ID resume one active attempt.
    repository.claimJudgeQueueLock();
    OffsetDateTime now = now();
    var exhausted = repository.claimJudgeJob(now);
    for (UUID id : exhausted) {
      String report = "{\"verdict\":\"IE\",\"error\":\"worker lease exhausted\"}";
      repository.claimJudgeJob2(report, JudgeJson.hash(report), now, id);
      repository.claimJudgeAttempt(now, id);
    }
    // Checks of a generation that ended (deadline passed, finished or stopped, or its branch
    // closed) can never be
    // claimed again. Close them as IE so they do not look RUNNING/QUEUED forever; live generations
    // are untouched.
    var abandoned = repository.claimJudgeJob3(now, now);
    for (UUID id : abandoned) {
      String report = "{\"verdict\":\"IE\",\"error\":\"generation ended before this check ran\"}";
      repository.claimJudgeJob4(report, JudgeJson.hash(report), now, id);
      repository.claimJudgeAttempt2(now, id);
    }
    // A job whose hybrid generation was stopped is not resumed: a worker failing on it would
    // otherwise renew and retry forever.
    var resumed = repository.claimJudgeJob5(worker, now);
    if (resumed.isPresent()) return Optional.of(assignment(job(resumed.get())));
    var next = repository.claimJudgeJob6(now);
    if (next.isEmpty()) return Optional.empty();
    JudgeJob old = job(next.get());
    // An exclusive job at the queue head drains every functional slot. Do not skip the queue head.
    var active = repository.claimJudgeJob7(now);
    if (!active.isEmpty()
        && (!old.executionMode().equals("FUNCTIONAL")
            || active.size() >= functionalSlots
            || active.stream().anyMatch(mode -> !mode.equals("FUNCTIONAL"))))
      return Optional.empty();
    if (old.attempt() > 0) repository.claimJudgeAttempt3(now, old.submissionId(), old.attempt());
    UUID token = UUID.randomUUID();
    repository.claimJudgeJob8(
        old.attempt() + 1, token, worker, now.plusSeconds(60), old.submissionId());
    repository.claimJudgeAttempt4(
        old.submissionId(),
        old.attempt() + 1,
        token,
        worker,
        JudgeJson.canonical(RunnerEnvironment.expected()));
    return Optional.of(assignment(job(old.submissionId())));
  }

  private Assignment assignment(JudgeJob job) {
    String environment =
        repository.assignmentJudgeAttempt(job.submissionId(), job.attempt()).orElse(null);
    return repository.assignmentSubmission(
        job.submissionId(),
        (row, index) ->
            new Assignment(
                job.submissionId(),
                job.attempt(),
                job.token(),
                row.getString("source_code"),
                row.getString("source_sha256"),
                JudgeJson.parse(row.getString("package_json")),
                row.getString("package_sha256"),
                row.getString("runtime_image"),
                row.getString("runner_policy"),
                10,
                job.executionMode(),
                environment == null ? null : JudgeJson.parse(environment),
                row.getString("language"),
                row.getString("execution_profile_json") == null
                    ? null
                    : JudgeJson.parse(row.getString("execution_profile_json")),
                row.getBoolean("judge_all")));
  }

  @Transactional
  public void heartbeat(UUID id, UUID token) {
    OffsetDateTime now = now();
    if (repository.heartbeatJudgeJob(now.plusSeconds(60), id, token, now) != 1)
      throw new AccountException(409, "Expired or superseded judge attempt");
  }

  @Transactional
  public void complete(UUID id, UUID token, JsonNode report) {
    String json = JudgeJson.canonical(report);
    if (json.length() > 262144) throw new AccountException(400, "Judge report too large");
    String hash = JudgeJson.hash(json);
    JudgeJob current = job(id);
    if (current.status().equals("FINISHED")
        && token.equals(current.token())
        && hash.equals(current.resultSha256())) return;
    if (!current.status().equals("RUNNING")
        || !token.equals(current.token())
        || !current.leaseUntil().isAfter(now()))
      throw new AccountException(409, "Expired or superseded judge attempt");
    Assignment expected = assignment(current);
    validate(expected, report);
    OffsetDateTime now = now();
    // One isolated replay protects timing boundaries during normalization.
    // Keep the provisional evidence but never publish it as a final verdict.
    if (expected.executionMode().equals("FUNCTIONAL")
        && expected.executionProfile() != null
        && repository.completeSubmission(id) == 1) {
      boolean cpu = expected.executionProfile().has("testCpuSeconds");
      double budget =
          expected.executionProfile().path(cpu ? "testCpuSeconds" : "testWallSeconds").asDouble()
              * 1000;
      boolean boundary = false;
      for (var test : report.path("tests"))
        if (test.path("verdict").asText().equals("TLE")
            || test.path(cpu ? "cpu_ms" : "wall_ms").asDouble() >= budget * .8) boundary = true;
      if (boundary) {
        repository.completeJudgeAttempt(json, now, id, token);
        repository.completeJudgeJob(id);
        return;
      }
    }
    repository.completeJudgeJob2(report.path("verdict").asText(), json, hash, now, id);
    repository.completeJudgeAttempt2(json, now, id, token);
    if (report.path("verdict").asText().equals("AC"))
      events.publishEvent(new SolutionExports.Accepted(id));
    if (repository.completeSubmission2(id) > 0) events.publishEvent(new HybridExecution.Wakeup());
  }

  private void validate(Assignment expected, JsonNode report) {
    if (expected.runnerEnvironment() != null
        && !RunnerEnvironment.matches(
            expected.runnerEnvironment(), report.get("runner_environment")))
      throw new AccountException(400, "Runner environment does not match the saved attempt");
    if (expected.executionProfile() != null
        && (!expected.executionProfile().equals(report.path("execution_profile"))
            || !expected.language().equals(report.path("language").asText())))
      throw new AccountException(400, "Language/limits do not match the saved submission");
    String verdict = report.path("verdict").asText();
    boolean run = expected.problem().path("output_policy").asText().equals("RUN_ONLY");
    String success = run ? "OK" : "AC";
    Set<String> allowed =
        run
            ? Set.of("OK", "CE", "RE", "TLE", "MLE", "OLE", "IE")
            : Set.of("AC", "WA", "CE", "RE", "TLE", "MLE", "OLE", "IE");
    if (!allowed.contains(verdict)
        || !expected.executionMode().equals(report.path("execution_mode").asText("EXCLUSIVE"))
        || !expected.sourceSha256().equals(report.path("source_sha256").asText())
        || !expected.problemSha256().equals(report.path("problem_sha256").asText())
        || !expected.runtimeImage().equals(report.path("image").asText())
        || !expected.runnerPolicy().equals(report.path("policy").asText())
        || !expected
            .problem()
            .path("version")
            .asText()
            .equals(report.path("problem_version").asText()))
      throw new AccountException(400, "Judge report does not match the saved execution plan");
    JsonNode tests = report.path("tests");
    // Saved explicit tests first, then the plan's generated large tests in order (Runner-side
    // inputs).
    var expectedTests = JudgeJson.JSON.createArrayNode();
    expectedTests.addAll(
        (com.fasterxml.jackson.databind.node.ArrayNode) expected.problem().path("tests"));
    int explicit = expectedTests.size();
    for (JsonNode g : expected.problem().path("generated").path("tests")) expectedTests.add(g);
    if (!tests.isArray() || tests.size() > expectedTests.size())
      throw new AccountException(400, "Invalid test evidence");
    // Learner formal submissions may continue after a failure (judge_all); every other plan stops
    // at the first one.
    boolean judgeAll = report.path("judge_all").asBoolean(false);
    if (judgeAll && !expected.judgeAll())
      throw new AccountException(
          400, "Judge-all report for a plan that stops at the first failure");
    String firstFailure = null;
    for (int i = 0; i < tests.size(); i++) {
      if (expected.executionProfile() != null
          && expected.executionProfile().has("testCpuSeconds")) {
        var cpu = tests.get(i).path("cpu_ms");
        if (!cpu.isNumber()
            || !Double.isFinite(cpu.asDouble())
            || cpu.asDouble() < 0
            || !tests.get(i).path("cpu_measurement").asText().equals("cgroup-v2-delta"))
          throw new AccountException(400, "CPU evidence missing or invalid");
        if (List.of("AC", "OK").contains(tests.get(i).path("verdict").asText())
            && cpu.asDouble()
                > expected.executionProfile().path("testCpuSeconds").asDouble() * 1000)
          throw new AccountException(400, "Successful result exceeded CPU budget");
      }
      var memory = tests.get(i).get("memory_peak_bytes");
      if (memory != null
          && (!memory.isIntegralNumber()
              || !memory.canConvertToLong()
              || memory.asLong() < 0
              || !tests.get(i).path("memory_measurement").asText().equals("cgroup-peak-observed")))
        throw new AccountException(400, "Invalid memory evidence");
      String testVerdict = tests.get(i).path("verdict").asText();
      if (!tests.get(i).path("id").equals(expectedTests.get(i).path("id"))
          || (i >= explicit) != "generated".equals(tests.get(i).path("kind").asText())
          || (!judgeAll && !run && i < tests.size() - 1 && !testVerdict.equals(success)))
        throw new AccountException(400, "Invalid test order or evidence");
      if (firstFailure == null && !testVerdict.equals(success)) firstFailure = testVerdict;
    }
    if (verdict.equals(success)
        && (tests.size() != expectedTests.size()
            || tests.findValuesAsText("verdict").stream()
                .anyMatch(value -> !value.equals(success))))
      throw new AccountException(400, "AC requires all saved tests to pass");
    if (run && !Set.of("CE", "IE").contains(verdict) && tests.size() != expectedTests.size())
      throw new AccountException(400, "Custom execution requires every saved input result");
    if (run)
      for (JsonNode test : tests) {
        if (!test.path("stdout").isTextual()
            || test.path("stdout").asText().length() > 32768
            || !test.path("stderr").isTextual()
            || test.path("stderr").asText().length() > 8192
            || !test.path("stdout_truncated").isBoolean())
          throw new AccountException(400, "Invalid custom execution output");
      }
    if (!Set.of("CE", "IE").contains(verdict)
        && (tests.isEmpty() || !verdict.equals(firstFailure == null ? success : firstFailure)))
      throw new AccountException(400, "Final verdict does not match evidence");
  }
}
