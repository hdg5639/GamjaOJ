package dev.gamjaoj.service.learning;

import dev.gamjaoj.domain.ThinkingProfile;
import dev.gamjaoj.exception.AccountException;
import dev.gamjaoj.repository.learning.LearningCurriculaRepository;
import dev.gamjaoj.service.diagnostic.DiagnosticEvaluations;
import dev.gamjaoj.service.diagnostic.DiagnosticPlans;
import dev.gamjaoj.service.judge.Submissions;
import dev.gamjaoj.support.JudgeJson;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Saved learning goals, not generated questions or a model judgement of mastery. */
@Service
public class LearningCurricula {
  private final LearningCurriculaRepository repository;
  private final Submissions submissions;
  private final DiagnosticEvaluations evaluations;
  private final DiagnosticPlans plans;
  private final LearningProblemPreparation preparation;
  private final TrainingSessions training;

  public LearningCurricula(
      LearningCurriculaRepository repository,
      Submissions submissions,
      DiagnosticEvaluations evaluations,
      DiagnosticPlans plans,
      LearningProblemPreparation preparation,
      TrainingSessions training) {
    this.repository = repository;
    this.submissions = submissions;
    this.evaluations = evaluations;
    this.plans = plans;
    this.preparation = preparation;
    this.training = training;
  }

  private static final Map<String, String> CATEGORIES =
      Map.ofEntries(
          Map.entry("implementation", "구현"),
          Map.entry("arrays-strings", "배열·문자열"),
          Map.entry("basic-data-structures", "기초 자료구조"),
          Map.entry("basic-search", "기초 탐색"),
          Map.entry("bfs", "너비 우선 탐색"),
          Map.entry("dfs", "깊이 우선 탐색"),
          Map.entry("backtracking", "백트래킹"),
          Map.entry("dp", "동적 계획법"),
          Map.entry("binary-search", "이분 탐색"),
          Map.entry("greedy", "탐욕법"),
          Map.entry("graph", "그래프·최단 경로"),
          Map.entry("mst", "최소 신장 트리"),
          Map.entry("exam-a-implementation", "조건 구현·경계 처리"),
          Map.entry("exam-a-simulation", "시뮬레이션·상태 전이"),
          Map.entry("exam-a-combinatorial-search", "조합·최적화"),
          Map.entry("exam-a-state-search", "상태 탐색"),
          Map.entry("exam-b-indexed-structures", "인덱스 기반 자료구조"),
          Map.entry("exam-b-priority-order", "우선순위·정렬"),
          Map.entry("exam-b-dynamic-queries", "동적 조회·구간 집계"),
          Map.entry("exam-b-combined-design", "복합 설계·관계 경로"));

  public static String categoryLabel(String category) {
    return category == null ? "기초 개념" : CATEGORIES.getOrDefault(category, "기초 개념");
  }

  public record Created(
      UUID evaluationId, List<DiagnosticPlans.Plan> plans, int manualReviewCount) {}

  public record Candidate(
      String version,
      String title,
      String category,
      String difficulty,
      ThinkingProfile.Profile thinking) {}

  public record Progress(int submissions, int accepted, int pending, String latestVerdict) {}

  public record Step(
      DiagnosticPlans.Plan plan,
      String category,
      String basis,
      String problemTitle,
      Candidate candidate,
      Progress progress,
      LearningProblemPreparation.State preparation) {}

  public record Track(
      UUID evaluationId,
      UUID diagnosticSessionId,
      String bankId,
      OffsetDateTime createdAt,
      List<Step> steps,
      int manualReviewCount,
      OffsetDateTime endedAt,
      String endNote) {}

  public record Ended(UUID evaluationId, OffsetDateTime endedAt, String note) {}

  @Transactional
  public Ended end(String username, UUID evaluationId, String note) {
    UUID owner = submissions.owner(username, true);
    evaluations.detail(username, evaluationId);
    if (note == null || note.length() > 2000)
      throw new AccountException(400, "마무리 메모는 2000자 이내로 작성해 주세요.");
    if (repository.endDiagnosticPracticePlan(owner, evaluationId) == 0)
      throw new AccountException(404, "종료할 학습 계획을 찾지 못했어요.");
    var previous = ended(owner, evaluationId);
    if (previous != null) {
      if (!previous.note().equals(note))
        throw new AccountException(409, "이미 종료한 계획의 메모와 달라요. 최신 기록을 확인해 주세요.");
      return previous;
    }
    // End only this plan's sessions. An independently active course or free practice is preserved.
    var active = repository.endTrainingSession(owner, evaluationId);
    for (var id : active) training.end(username, id, note);
    repository.endLearningCurriculumEnd(evaluationId, owner, note);
    return ended(owner, evaluationId);
  }

  private Ended ended(UUID owner, UUID evaluationId) {
    return repository
        .endedLearningCurriculumEnd(
            evaluationId,
            owner,
            (r, n) -> new Ended(evaluationId, r.getObject(1, OffsetDateTime.class), r.getString(2)))
        .orElse(null);
  }

  @Transactional
  public Created create(String username, UUID key, UUID evaluationId) {
    UUID owner =
        submissions.owner(
            username,
            true); // Serialize duplicate clicks/tabs and manual confirmations for this owner.
    plans.requireOpen(owner, evaluationId);
    var previous =
        repository.createLearningCurriculumRequest(
            key,
            (r, n) ->
                new Object[] {
                  r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getString(3)
                });
    if (previous.isPresent()) {
      var saved = previous.get();
      if (!owner.equals(saved[0]) || !evaluationId.equals(saved[1]))
        throw new AccountException(409, "같은 요청 키로 다른 계획을 만들 수 없어요.");
      var result = JudgeJson.parse((String) saved[2]);
      var ids = new ArrayList<DiagnosticPlans.Plan>();
      for (var id : result.path("planIds"))
        ids.add(plans.view(username, owner, UUID.fromString(id.asText())));
      ids.forEach(p -> preparation.enroll(p.id()));
      return new Created(evaluationId, List.copyOf(ids), result.path("manualReviewCount").asInt());
    }
    var evaluation = evaluations.detail(username, evaluationId);
    if (!DiagnosticPlans.basicAvailable(evaluation)
        || repository.createDiagnosticSession(owner) > 0)
      throw new AccountException(409, "진단 종료와 근거 확인이 끝난 뒤 맞춤 계획을 만들 수 있어요.");
    var created = new ArrayList<DiagnosticPlans.Plan>();
    int manual = 0;
    var savedPlans = plans.list(username, evaluationId);
    var seenCategories = new HashSet<String>();
    var facts = evaluation.facts().path("items");
    for (int index = 0; index < facts.size(); index++) {
      var item = facts.get(index);
      if ("NOT_SURE".equals(item.path("skipReason").asText())
          && !item.path("externallySeen").asBoolean()
          && "SKIPPED".equals(item.path("status").asText())
          && seenCategories.add(item.path("category").asText()))
        created.add(confirmDefault(username, evaluationId, index, "SELF_REPORT", savedPlans));
    }
    if ("COMPLETED".equals(evaluation.status()) && evaluation.interpretation() != null) {
      var observations = evaluation.interpretation().path("observations");
      // A strength, uncertain hypothesis or ASSESS proposal is never converted into a deficit.
      for (String tone : List.of("RISK", "WATCH"))
        for (int index = 0; index < observations.size(); index++) {
          var o = observations.get(index);
          if (!tone.equals(o.path("tone").asText())
              || !"SUPPORTED".equals(o.path("confidence").asText())
              || !"PRACTICE".equals(o.path("nextAction").asText())) continue;
          final int position = index;
          if (evaluation.corrections().stream().anyMatch(c -> c.observationIndex() == position)) {
            manual++;
            continue;
          }
          created.add(
              confirmDefault(username, evaluationId, index, "CODE_OBSERVATION", savedPlans));
        }
    }
    if (created.isEmpty())
      throw new AccountException(
          409,
          manual > 0
              ? "정정 의견이 있는 제안은 수동으로 확인하고 목표를 정해 주세요."
              : "바로 만들 수 있는 보완 목표가 없어요. 수동 계획이나 다른 분야의 진단을 선택해 주세요.");
    var result = JudgeJson.JSON.createObjectNode().put("manualReviewCount", manual);
    var ids = result.putArray("planIds");
    created.forEach(p -> ids.add(p.id().toString()));
    repository.createLearningCurriculumRequest2(
        key, owner, evaluationId, JudgeJson.canonical(result));
    created.forEach(p -> preparation.enroll(p.id()));
    return new Created(evaluationId, List.copyOf(created), manual);
  }

  private DiagnosticPlans.Plan confirmDefault(
      String username, UUID evaluation, int index, String kind, List<DiagnosticPlans.Plan> saved) {
    var options = plans.options(username, evaluation, index, kind);
    // Preserve the user's existing goal and rounds rather than creating a parallel default goal.
    for (var plan : saved)
      if (plan.observationIndex() == index
          && kind.equals(plan.sourceKind())
          && plan.previousPlanId() == null
          && !List.of("HELD", "NEEDS_REVIEW").contains(plan.status())) return plan;
    String goal = options.observation().path("recommendation").asText().strip();
    if (goal.isEmpty()) goal = options.observation().path("pattern").asText().strip();
    if (goal.isEmpty()) throw new AccountException(409, "연습 목표가 없는 제안은 수동으로 확인해 주세요.");
    if (goal.length() > 120) {
      int end = 119;
      if (Character.isHighSurrogate(goal.charAt(end - 1))) end--;
      goal = goal.substring(0, end) + "…";
    }
    return plans.confirm(
        username, UUID.randomUUID(), evaluation, index, options.reviewHash(), goal, kind);
  }

  @Transactional
  public List<Track> overview(String username) {
    UUID owner = submissions.owner(username, true);
    var evaluationsWithPlans = repository.overviewDiagnosticPracticePlan(owner);
    if (evaluationsWithPlans.isEmpty()) return List.of();
    var problems = submissions.problems(username);
    var tracks = new ArrayList<Track>();
    for (var evaluationId : evaluationsWithPlans) {
      var evaluation = evaluations.detail(username, evaluationId);
      var metadata =
          repository.overviewDiagnosticSession(
              evaluation.sessionId(),
              (r, n) -> new Object[] {r.getString(1), r.getObject(2, OffsetDateTime.class)});
      var all = plans.list(username, evaluationId);
      var steps = new ArrayList<Step>();
      for (var root : all) {
        if (root.previousPlanId() != null) continue;
        var plan = root;
        while (true) {
          final UUID id = plan.id();
          var next = all.stream().filter(p -> id.equals(p.previousPlanId())).findFirst();
          if (next.isEmpty()) break;
          plan = next.get();
        }
        String category = null;
        Candidate candidate = null;
        Progress progress = null;
        String title = null;
        if (!"HELD".equals(plan.status())) {
          var options =
              plans.options(
                  username, evaluationId, plan.observationIndex(), plan.sourceKind(), problems);
          category = options.category();
          if (plan.problemVersion() != null) {
            final String version = plan.problemVersion();
            title =
                problems.stream()
                    .filter(p -> version.equals(p.version()))
                    .map(Submissions.Problem::title)
                    .findFirst()
                    .orElse("목록에 없는 문제");
          }
          if (plan.generatedVersion() != null) {
            final String generated = plan.generatedVersion();
            candidate =
                problems.stream()
                    .filter(
                        p ->
                            generated.equals(p.version())
                                && !p.problemHeld()
                                && p.submissionsEnabled())
                    .map(
                        p ->
                            new Candidate(
                                p.version(), p.title(), p.category(), p.difficulty(), p.thinking()))
                    .findFirst()
                    .orElse(null);
          }
          var mapping = preparation.state(plan.id());
          if (candidate == null
              && "READY".equals(plan.status())
              && mapping != null
              && mapping.problemVersion() != null) {
            final String version = mapping.problemVersion();
            candidate =
                problems.stream()
                    .filter(
                        p ->
                            version.equals(p.version())
                                && !p.problemHeld()
                                && p.submissionsEnabled())
                    .map(
                        p ->
                            new Candidate(
                                p.version(), p.title(), p.category(), p.difficulty(), p.thinking()))
                    .findFirst()
                    .orElse(null);
          }
          // Unenrolled manual plans retain a catalog preview without scheduling model work.
          if (candidate == null
              && "READY".equals(plan.status())
              && mapping == null
              && plan.generationId() == null) {
            var matched = LearningProblemPreparation.match(plan, options, problems, Set.of());
            if (matched != null)
              candidate =
                  new Candidate(
                      matched.version(),
                      matched.title(),
                      matched.category(),
                      matched.difficulty(),
                      matched.thinking());
          }
          if (plan.sessionId() != null) {
            var session =
                repository.overviewSubmission(
                    plan.sessionId(),
                    owner,
                    (r, n) -> new int[] {r.getInt(1), r.getInt(2), r.getInt(3)});
            String verdict =
                repository
                    .overviewSubmission2(plan.sessionId(), owner, (r, n) -> r.getString(1))
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
            progress = new Progress(session[0], session[1], session[2], verdict);
          }
        }
        steps.add(
            new Step(
                plan,
                category,
                plan.sourceKind(),
                title,
                candidate,
                progress,
                preparation.state(plan.id())));
      }
      var correctedGoals = new HashSet<Integer>();
      if ("COMPLETED".equals(evaluation.status()) && evaluation.interpretation() != null)
        for (var correction : evaluation.corrections()) {
          var observation =
              evaluation.interpretation().path("observations").path(correction.observationIndex());
          if (List.of("RISK", "WATCH").contains(observation.path("tone").asText())
              && "SUPPORTED".equals(observation.path("confidence").asText())
              && "PRACTICE".equals(observation.path("nextAction").asText()))
            correctedGoals.add(correction.observationIndex());
        }
      var end = ended(owner, evaluationId);
      tracks.add(
          new Track(
              evaluationId,
              evaluation.sessionId(),
              (String) metadata[0],
              (OffsetDateTime) metadata[1],
              List.copyOf(steps),
              correctedGoals.size(),
              end == null ? null : end.endedAt(),
              end == null ? null : end.note()));
    }
    return List.copyOf(tracks);
  }
}
