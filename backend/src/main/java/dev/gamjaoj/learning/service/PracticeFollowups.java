package dev.gamjaoj.learning.service;

import dev.gamjaoj.ai.service.AiTasks;
import dev.gamjaoj.generation.service.GenerationChoices;
import dev.gamjaoj.generation.service.GenerationJobs;
import dev.gamjaoj.generation.service.GenerationSpecDrafts;
import dev.gamjaoj.generation.service.GenerationTemplate;
import dev.gamjaoj.generation.service.GenerationType;
import dev.gamjaoj.generation.service.HybridAdmission;
import dev.gamjaoj.generation.service.HybridRuleRegistry;
import dev.gamjaoj.judge.service.Submissions;
import dev.gamjaoj.learning.dto.TrainingSessionDtos;
import dev.gamjaoj.learning.repository.PracticeFollowupsRepository;
import dev.gamjaoj.problem.domain.ProblemTitles;
import dev.gamjaoj.shared.exception.AccountException;
import dev.gamjaoj.shared.support.JudgeJson;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Explicit learner confirmation, conservative contract matching, no model calls for
 * recommendations.
 */
@Service
public class PracticeFollowups {
  public record Focus(String id, String label) {}

  public record Options(List<String> steps, List<Focus> focuses, String type) {}

  public record Candidate(String version, String title, String statement) {}

  public record Attempt(
      int round,
      UUID sessionId,
      String problemVersion,
      String status,
      Boolean usedHelp,
      UUID reviewedSubmissionId) {}

  public record View(
      UUID id,
      UUID analysisId,
      String goal,
      String focus,
      String status,
      List<Candidate> candidates,
      UUID sessionId,
      String problemVersion,
      String generationStatus,
      Boolean usedHelp,
      UUID reviewedSubmissionId,
      int round,
      List<Attempt> attempts) {}

  private record Source(
      String version, String template, com.fasterxml.jackson.databind.JsonNode result) {}

  private final PracticeFollowupsRepository repository;
  private final Submissions submissions;
  private final TrainingSessions training;
  private final GenerationJobs generation;
  private final GenerationSpecDrafts drafts;
  private final HybridAdmission rules;
  private final HybridRuleRegistry registry;

  /** Template marker for problems published from a registered rule version. */
  public static final String RULE = "hybrid:";

  public PracticeFollowups(
      PracticeFollowupsRepository repository,
      Submissions submissions,
      TrainingSessions training,
      GenerationJobs generation,
      GenerationSpecDrafts drafts,
      HybridAdmission rules,
      HybridRuleRegistry registry) {
    this.repository = repository;
    this.submissions = submissions;
    this.training = training;
    this.generation = generation;
    this.drafts = drafts;
    this.rules = rules;
    this.registry = registry;
  }

  private static boolean rule(String template) {
    return template != null && template.startsWith(RULE);
  }

  private String template(String version) {
    if (version.equals("total-v1")) return GenerationTemplate.ID;
    if (version.equals("valid-parentheses-v1")) return GenerationType.PARENTHESES.id;
    var registered = repository.templateHybridGeneration(version);
    if (registered.isPresent()) return RULE + registered.get();
    return repository.templateGenerationJob(version).orElse(null);
  }

  private Source source(UUID owner, UUID analysis) {
    return repository
        .sourceAiTask(
            analysis,
            owner,
            owner,
            (r, n) -> {
              if (r.getBoolean("review_hold"))
                throw new AccountException(409, "검토 중인 문제의 분석은 훈련 근거로 사용할 수 없어요.");
              var result = JudgeJson.parse(r.getString("result_json"));
              if (!AiTasks.validFeedback(result))
                throw new AccountException(409, "분석 결과를 확인해 주세요.");
              String version = r.getString("problem_version");
              return new Source(version, null, result);
            })
        .map(row -> new Source(row.version, template(row.version), row.result))
        .orElseThrow(() -> new AccountException(404, "완료된 본인의 정식 제출 분석을 선택해 주세요."));
  }

  public Options options(String username, UUID analysis) {
    var source = source(submissions.owner(username, false), analysis);
    var steps = new ArrayList<String>();
    source.result().path("nextSteps").forEach(step -> steps.add(step.asText()));
    if (rule(source.template())) {
      String label =
          registry
              .version(source.template().substring(RULE.length()))
              .map(HybridRuleRegistry.Version::label)
              .orElse("규칙 고정 문제");
      return new Options(
          steps, List.of(new Focus("same-rules", "같은 규칙 · 새 상황")), label + " · 같은 규칙으로 재확인");
    }
    var focuses =
        source.template() == null
            ? List.of(new Focus("custom", "확인한 목표 그대로"))
            : GenerationType.of(source.template()).focuses().stream()
                .filter(
                    f -> List.of("basics", "overflow", "edge-cases", "prefix-balance").contains(f))
                .map(f -> new Focus(f, GenerationChoices.label(f)))
                .toList();
    return new Options(
        steps,
        focuses,
        source.template() == null
            ? "자유 출제 · 개별 검증 필요"
            : GenerationType.of(source.template()).title);
  }

  public @Transactional View confirm(String username, UUID analysis, int step, String focus) {
    UUID owner = submissions.owner(username, true);
    var source = source(owner, analysis);
    if (step < 0
        || step >= source.result().path("nextSteps").size()
        || options(username, analysis).focuses().stream().noneMatch(f -> f.id().equals(focus)))
      throw new AccountException(400, "연습할 지점과 목표를 선택해 주세요.");
    var old = repository.confirmPracticeFollowup(owner, analysis, step, focus);
    if (old.isPresent()) return view(owner, old.get());
    if (source.result().path("nextSteps").get(step).asText().isBlank())
      throw new AccountException(400, "내용이 있는 학습 목표를 선택해 주세요.");
    UUID id = UUID.randomUUID();
    repository.confirmPracticeFollowup2(
        id,
        owner,
        analysis,
        step,
        source.result().path("nextSteps").get(step).asText(),
        focus,
        source.template(),
        source.version(),
        id);
    return view(owner, id);
  }

  public List<View> list(String username) {
    UUID owner = submissions.owner(username, false);
    return repository.listPracticeFollowup(owner).map(id -> view(owner, id)).toList();
  }

  public View detail(String username, UUID id) {
    return view(submissions.owner(username, false), id);
  }

  private List<Candidate> candidates(
      UUID owner,
      String origin,
      String contract,
      String focus,
      UUID id,
      UUID roundId,
      boolean requested) {
    // Free-form output is eligible only when it was generated for this exact confirmed goal.
    return repository
        .candidatesProblemVersion(
            owner,
            origin,
            owner,
            (r, n) ->
                new Candidate(
                    r.getString(1),
                    ProblemTitles.display(JudgeJson.parse(r.getString(2))),
                    JudgeJson.parse(r.getString(2)).path("statement").asText()))
        .filter(
            p -> {
              // Same registered rule version, or a free-form fallback generated for this exact
              // round.
              if (rule(contract))
                return contract.equals(template(p.version()))
                    || (requested && p.version().equals("experimental-check-" + roundId));
              if (contract == null)
                return (requested && p.version().equals("experimental-check-" + roundId))
                    || repository.candidatesPracticeFollowupAttempt(id, p.version()) > 0;
              if (!contract.equals(template(p.version()))) return false;
              if (p.version().equals(GenerationType.of(contract).baseProblem)) return true;
              return repository
                  .candidatesGenerationJob(p.version())
                  .map(value -> Arrays.asList(value.split(",")).contains(focus))
                  .orElse(false);
            })
        .limit(3)
        .toList();
  }

  private record FollowupRow(
      UUID session,
      UUID reviewed,
      Boolean helped,
      boolean held,
      String sessionStatus,
      UUID roundId,
      boolean requested,
      String contract,
      UUID analysis,
      String goal,
      String focus,
      String source,
      String target,
      int round) {}

  private View view(UUID owner, UUID id) {
    var row =
        repository
            .viewPracticeFollowup(
                id,
                owner,
                (r, n) ->
                    new FollowupRow(
                        r.getObject("session_id", UUID.class),
                        r.getObject("reviewed_submission_id", UUID.class),
                        r.getObject("used_help", Boolean.class),
                        r.getBoolean("review_hold") || r.getBoolean("target_held"),
                        r.getString("session_status"),
                        r.getObject("round_id", UUID.class),
                        r.getBoolean("generation_requested"),
                        r.getString("template_id"),
                        r.getObject("analysis_id", UUID.class),
                        r.getString("goal"),
                        r.getString("focus"),
                        r.getString("source_version"),
                        r.getString("target_version"),
                        r.getInt("round_number")))
            .orElseThrow(() -> new AccountException(404, "다음 훈련 기록을 찾을 수 없어요."));
    String state =
        row.held
            ? "HELD"
            : row.reviewed != null
                ? (Boolean.TRUE.equals(row.helped) ? "AC_WITH_HELP" : "SELF_REPORTED_UNASSISTED_AC")
                : row.session == null ? "READY_TO_PRACTICE" : "ACTIVE";
    if (!row.held
        && row.reviewed == null
        && row.session != null
        && "ENDED".equals(row.sessionStatus)) {
      int pending = repository.viewSubmission(row.session);
      state =
          pending > 0
              ? "WAITING_JUDGE"
              : latestAc(row.session) != null ? "AWAITING_REFLECTION" : "NEEDS_PRACTICE";
    }
    String generationStatus =
        !row.requested
            ? null
            : rule(row.contract)
                ? repository
                    .viewHybridGeneration(row.roundId)
                    .or(() -> repository.viewGenerationSpecDraft(row.roundId))
                    .orElse("UNAVAILABLE")
                : repository
                    .viewGenerationSpecDraft2(row.contract == null, row.roundId)
                    .orElse("UNAVAILABLE");
    return new View(
        id,
        row.analysis,
        row.goal,
        row.focus,
        state,
        row.held || row.session != null
            ? List.of()
            : candidates(
                owner, row.source, row.contract, row.focus, id, row.roundId, row.requested),
        row.session,
        row.target,
        generationStatus,
        row.helped,
        row.reviewed,
        row.round,
        attempts(id));
  }

  private List<Attempt> attempts(UUID id) {
    return repository.attemptsPracticeFollowupAttempt(
        id,
        (r, n) ->
            new Attempt(
                r.getInt("round_number"),
                r.getObject("session_id", UUID.class),
                r.getString("problem_version"),
                r.getBoolean("review_hold")
                    ? "HELD"
                    : r.getObject("reviewed_submission_id", UUID.class) == null
                        ? "NEEDS_PRACTICE"
                        : r.getBoolean("used_help")
                            ? "AC_WITH_HELP"
                            : "SELF_REPORTED_UNASSISTED_AC",
                r.getObject("used_help", Boolean.class),
                r.getObject("reviewed_submission_id", UUID.class)));
  }

  private void requireRound(View saved, int round) {
    if (saved.round() != round) throw new AccountException(409, "다른 시도로 넘어갔어요. 최신 훈련 기록을 확인해 주세요.");
  }

  private UUID roundId(UUID id) {
    return repository.roundIdPracticeFollowup(id);
  }

  public @Transactional View repeat(String username, UUID id, int expectedRound) {
    UUID owner = submissions.owner(username, true);
    var saved = view(owner, id);
    // A lost repeat response reuses the already-created next round, never creates a third one.
    if (saved.round() == expectedRound + 1) return saved;
    requireRound(saved, expectedRound);
    if (!List.of("NEEDS_PRACTICE", "AC_WITH_HELP", "SELF_REPORTED_UNASSISTED_AC")
        .contains(saved.status()))
      throw new AccountException(409, "진행 중인 채점과 훈련 확인을 마친 뒤 다시 연습할 수 있어요. 검토 중인 문제는 보류합니다.");
    repository.repeatPracticeFollowupAttempt(id);
    repository.repeatPracticeFollowup(UUID.randomUUID(), id);
    return view(owner, id);
  }

  private UUID latestAc(UUID session) {
    return repository
        .latestAcSubmission(
            session,
            (r, n) ->
                ("AC".equals(r.getString(2)) && "FINISHED".equals(r.getString(3)))
                    ? r.getObject(1, UUID.class)
                    : null)
        .orElse(null);
  }

  public @Transactional View start(String username, UUID id, String version) {
    return start(username, id, version, 1);
  }

  public @Transactional View start(String username, UUID id, String version, int round) {
    UUID owner = submissions.owner(username, true);
    var saved = view(owner, id);
    requireRound(saved, round);
    if (saved.sessionId() != null) {
      if (!Objects.equals(version, saved.problemVersion()))
        throw new AccountException(409, "이미 시작한 훈련의 문제와 달라요.");
      return saved;
    }
    if (saved.candidates().stream().noneMatch(p -> p.version().equals(version)))
      throw new AccountException(409, "현재 사용할 수 있는 추천 문제를 다시 확인해 주세요.");
    String goal = "다시 연습: " + saved.goal();
    UUID sessionId = roundId(id);
    training.start(
        username,
        sessionId,
        new TrainingSessionDtos.Start(version, goal.substring(0, Math.min(120, goal.length()))));
    repository.startPracticeFollowup(sessionId, id);
    return view(owner, id);
  }

  public @Transactional View generate(String username, UUID id) {
    return generate(username, id, 1);
  }

  public @Transactional View generate(String username, UUID id, int round) {
    UUID owner = submissions.owner(username, true);
    var saved = view(owner, id);
    requireRound(saved, round);
    if (saved.status().equals("HELD"))
      throw new AccountException(409, "문제 검토 중에는 다음 훈련을 생성할 수 없어요.");
    if (saved.generationStatus() != null) return saved;
    if (saved.sessionId() != null) throw new AccountException(409, "이미 훈련을 시작했어요.");
    String contract = repository.generatePracticeFollowup(id).orElse(null);
    UUID generationId = roundId(id);
    if (rule(contract)) {
      // Registered rules generate without free-form authoring; otherwise keep the previous
      // free-form path.
      String version = contract.substring(RULE.length());
      if (rules.available(username, version)) {
        rules.create(
            username,
            generationId,
            JudgeJson.JSON
                .createObjectNode()
                .put("profileId", version)
                .put("shared", false)
                .put("publishOnSuccess", true));
        repository.generatePracticeFollowup2(id);
        return view(owner, id);
      }
      contract = null;
    }
    if (contract != null) {
      generation.create(username, generationId, contract, saved.focus(), saved.analysisId());
      var context =
          JudgeJson.JSON.createObjectNode().put("summary", "사용자가 확인한 다음 연습 목표: " + saved.goal());
      context.putArray("nextSteps").add(saved.goal());
      repository.generateGenerationJob(context.toString(), generationId);
    } else {
      String origin = repository.generatePracticeFollowup3(id);
      String request =
          "다음 학습 목표를 다른 상황에서 연습할 새 문제를 작성해 주세요. 목표: "
              + saved.goal()
              + "\n이전 문제의 공개 명세(코드나 정답이 아님): "
              + JudgeJson.parse(origin).path("statement").asText();
      if (saved.goal().length() > 1500)
        throw new AccountException(409, "목표가 길어 자유 출제 입력 한도를 넘어요. 생성 화면에서 목표를 요약해 요청해 주세요.");
      drafts.create(
          username,
          generationId,
          request.length() > 2000 ? request.substring(0, 1985) + " (공개 명세 일부)" : request);
    }
    repository.generatePracticeFollowup4(id);
    return view(owner, id);
  }

  public @Transactional View reflect(String username, UUID id, boolean usedHelp) {
    return reflect(username, id, usedHelp, 1);
  }

  public @Transactional View reflect(String username, UUID id, boolean usedHelp, int round) {
    UUID owner = submissions.owner(username, true);
    var saved = view(owner, id);
    requireRound(saved, round);
    if (saved.status().equals("HELD")) throw new AccountException(409, "검토 중인 문제는 학습 확인을 보류해요.");
    if (saved.reviewedSubmissionId() != null) {
      if (!Objects.equals(saved.usedHelp(), usedHelp))
        throw new AccountException(409, "이미 저장한 확인과 달라요.");
      return saved;
    }
    if (!saved.status().equals("AWAITING_REFLECTION"))
      throw new AccountException(409, "훈련을 마치고 마지막 정식 제출의 정답 판정을 기다려 주세요.");
    repository.reflectPracticeFollowup(latestAc(saved.sessionId()), usedHelp, id);
    return view(owner, id);
  }
}
