package dev.gamjaoj.diagnostic.service;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamjaoj.ai.service.AiTasks;
import dev.gamjaoj.diagnostic.repository.DiagnosticEvaluationsRepository;
import dev.gamjaoj.judge.service.Submissions;
import dev.gamjaoj.shared.exception.AccountException;
import dev.gamjaoj.shared.support.JudgeJson;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DiagnosticEvaluations {
  private final DiagnosticEvaluationsRepository repository;
  private final Diagnostics diagnostics;
  private final Submissions submissions;
  private final AiTasks tasks;

  public DiagnosticEvaluations(
      DiagnosticEvaluationsRepository repository,
      Diagnostics diagnostics,
      Submissions submissions,
      AiTasks tasks) {
    this.repository = repository;
    this.diagnostics = diagnostics;
    this.submissions = submissions;
    this.tasks = tasks;
  }

  public record View(
      UUID id,
      UUID sessionId,
      String evidenceHash,
      JsonNode facts,
      String status,
      JsonNode interpretation,
      String errorCode,
      List<Correction> corrections) {}

  public record Correction(
      UUID id, int observationIndex, String note, java.time.OffsetDateTime createdAt) {}

  @Transactional
  public View request(String username, UUID session) {
    UUID owner = submissions.owner(username, true);
    var saved =
        diagnostics.detail(
            username, session); // Reconcile and lock final verdicts before snapshotting.
    if (saved.items().stream().anyMatch(i -> i.pending() > 0))
      throw new AccountException(409, "진행 중인 정식 채점이 끝난 뒤 평가해 주세요.");
    var input =
        JudgeJson.JSON
            .createObjectNode()
            .put("kind", "DIAGNOSTIC")
            .put("policy", "diagnostic-evidence-v1")
            .put("sessionId", session.toString())
            .put("complete", saved.status().equals("COMPLETED"));
    int revision = repository.requestDiagnosticSession(session);
    input.put("exposureRevision", revision);
    var evidence = input.putArray("items");
    var facts =
        JudgeJson.JSON.createObjectNode().put("complete", saved.status().equals("COMPLETED"));
    if (saved.sourceSessionId() != null) {
      String correspondence = repository.requestDiagnosticSession2(session);
      input.put("sourceSessionId", saved.sourceSessionId().toString());
      input.set("correspondence", JudgeJson.parse(correspondence));
      facts.put("sourceSessionId", saved.sourceSessionId().toString());
      facts.put("exposureScope", "NO_PRIOR_DIAGNOSTIC_ASSIGNMENT");
    }
    var coverage = facts.putArray("items");
    int submissionsCount = 0;
    for (var item : saved.items()) {
      var fact =
          coverage
              .addObject()
              .put("itemId", item.id().toString())
              .put("category", item.category())
              .put("difficulty", item.difficulty())
              .put("status", item.status())
              .put("attempts", item.attempts())
              .put("externallySeen", item.externallySeen());
      if (item.skipReason() != null && !item.skipReason().equals("UNSPECIFIED"))
        fact.put("skipReason", item.skipReason());
      if (item.status().equals("OPEN"))
        continue; // Never send open-question source, statement or rubric for interpretation.
      var data =
          repository.requestDiagnosticItem(
              item.id(),
              (r, n) -> {
                var node =
                    JudgeJson.JSON
                        .createObjectNode()
                        .put("itemId", item.id().toString())
                        .put("category", item.category())
                        .put("difficulty", item.difficulty())
                        .put("status", item.status())
                        .put("problemHash", r.getString(1))
                        .put(
                            "statement", JudgeJson.parse(r.getString(2)).path("statement").asText())
                        .put("referenceRuntimeImage", r.getString(4))
                        .put("referenceRunnerPolicy", r.getString(5));
                node.set("rubric", JudgeJson.parse(r.getString(3)));
                return node;
              });
      data.put("externallySeen", item.externallySeen());
      if (item.skipReason() != null && !item.skipReason().equals("UNSPECIFIED"))
        data.put("skipReason", item.skipReason());
      var attempts = data.putArray("submissions");
      if (!item.externallySeen())
        repository.requestSubmission(
            item.id(),
            (r, n) -> {
              attempts
                  .addObject()
                  .put("submissionId", r.getObject(1, UUID.class).toString())
                  .put("source", r.getString(2))
                  .put("sourceHash", r.getString(3))
                  .put("verdict", r.getString(4))
                  .put("resultHash", r.getString(5))
                  .put("language", r.getString(6))
                  .put("executionProfile", r.getString(7))
                  .put("runtimeImage", r.getString(8))
                  .put("runnerPolicy", r.getString(9));
              return true;
            });
      submissionsCount += attempts.size();
      evidence.add(data);
    }
    if (evidence.isEmpty()) throw new AccountException(409, "완료하거나 건너뛴 문항이 생기면 부분 결과를 확인할 수 있어요.");
    input.set("coverage", coverage.deepCopy());
    String json = JudgeJson.canonical(input);
    if (bytes(json) > EVIDENCE_LIMIT) {
      // Deterministic, declared reduction: keep each item's first and last two sources; others keep
      // verdict and hashes only.
      for (var item : evidence) {
        var attempts = item.path("submissions");
        for (int i = 1; i < attempts.size() - 2; i++) {
          var attempt = (com.fasterxml.jackson.databind.node.ObjectNode) attempts.get(i);
          attempt.remove("source");
          attempt.put("sourceOmitted", true);
        }
      }
      input.put("sourceCompaction", "FIRST_AND_LAST_TWO_PER_ITEM");
      json = JudgeJson.canonical(input);
    }
    String hash = JudgeJson.hash(json);
    if (bytes(json) > EVIDENCE_LIMIT)
      throw new AccountException(413, "평가 근거가 한 번에 처리할 수 있는 크기를 넘었어요. 제출 기록은 보존되어 있어요.");
    var old = repository.requestDiagnosticEvaluation(session, hash);
    if (old.isPresent()) return find(owner, old.get());
    UUID id = UUID.randomUUID();
    // Partial results stay deterministic: free-text inference could hint at remaining related
    // questions.
    UUID task =
        saved.status().equals("COMPLETED") && submissionsCount > 0
            ? tasks.diagnostic(owner, session, input)
            : null;
    repository.requestDiagnosticEvaluation2(
        id, session, hash, json, JudgeJson.canonical(facts), task, revision);
    return find(owner, id);
  }

  @Transactional
  public List<View> list(String username, UUID session) {
    UUID owner = submissions.owner(username, true);
    diagnostics.detail(username, session);
    return repository.listDiagnosticEvaluation(session).map(id -> find(owner, id)).toList();
  }

  @Transactional
  public View correct(
      String username, UUID session, UUID evaluation, UUID key, int observation, String note) {
    UUID owner = submissions.owner(username, true);
    if (note == null || note.isBlank() || note.length() > 1000 || observation < 0)
      throw new AccountException(400, "정정할 관찰과 1~1000자의 설명을 확인해 주세요.");
    View saved = find(owner, evaluation);
    if (!saved.sessionId().equals(session)) throw new AccountException(404, "진단 평가를 찾을 수 없어요.");
    var previous =
        repository.correctDiagnosticCorrection(
            evaluation, key, (r, n) -> new Object[] {r.getInt(1), r.getString(2)});
    if (previous.isPresent()) {
      if ((int) previous.get()[0] != observation || !previous.get()[1].equals(note))
        throw new AccountException(409, "같은 요청 키의 정정 내용이 달라요.");
      return saved;
    }
    if (!saved.status().equals("COMPLETED")
        || saved.interpretation() == null
        || observation >= saved.interpretation().path("observations").size())
      throw new AccountException(409, "현재 확인할 수 있는 완료 평가의 관찰을 선택해 주세요.");
    repository.correctDiagnosticCorrection2(
        UUID.randomUUID(),
        evaluation,
        key,
        observation,
        JudgeJson.hash(JudgeJson.canonical(saved.interpretation())),
        note);
    return find(owner, evaluation);
  }

  private List<Correction> corrections(UUID evaluation) {
    return repository.correctionsDiagnosticCorrection(
        evaluation,
        (r, n) ->
            new Correction(
                r.getObject(1, UUID.class),
                r.getInt(2),
                r.getString(3),
                r.getObject(4, java.time.OffsetDateTime.class)));
  }

  @Transactional
  public View detail(String username, UUID id) {
    return find(submissions.owner(username, true), id);
  }

  private View find(UUID owner, UUID id) {
    return repository
        .findDiagnosticEvaluation(
            id,
            owner,
            (r, n) -> {
              boolean hidden = repository.findDiagnosticSession(owner) > 0;
              boolean stale =
                  repository.requestDiagnosticSession(r.getObject("session_id", UUID.class))
                      != r.getInt("exposure_revision");
              boolean held =
                  repository.findDiagnosticItem(r.getObject("session_id", UUID.class)) > 0;
              String result = r.getString("result_json"), state = r.getString("status");
              return new View(
                  id,
                  r.getObject("session_id", UUID.class),
                  r.getString("evidence_sha256"),
                  JudgeJson.parse(r.getString("facts_json")),
                  stale
                      ? "STALE_EXPOSURE"
                      : held
                          ? "HELD_REVIEW"
                          : state == null
                              ? "FACTS_ONLY"
                              : hidden ? "HIDDEN_DURING_ASSESSMENT" : state,
                  stale || held || hidden || result == null ? null : JudgeJson.parse(result),
                  r.getString("error_code"),
                  stale || held || hidden ? List.of() : corrections(id));
            })
        .orElseThrow(() -> new AccountException(404, "진단 평가를 찾을 수 없어요."));
  }

  public static final int EVIDENCE_LIMIT = 524288;

  private static int bytes(String json) {
    return json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
  }
}
