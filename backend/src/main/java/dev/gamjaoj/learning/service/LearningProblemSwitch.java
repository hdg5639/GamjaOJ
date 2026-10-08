package dev.gamjaoj.learning.service;

import dev.gamjaoj.diagnostic.service.DiagnosticPlans;
import dev.gamjaoj.judge.service.Submissions;
import dev.gamjaoj.learning.repository.LearningProblemSwitchRepository;
import dev.gamjaoj.shared.exception.AccountException;
import dev.gamjaoj.shared.support.JudgeJson;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LearningProblemSwitch {
  private final LearningProblemSwitchRepository repository;
  private final Submissions submissions;
  private final DiagnosticPlans plans;
  private final TrainingSessions training;

  public LearningProblemSwitch(
      LearningProblemSwitchRepository repository,
      Submissions submissions,
      DiagnosticPlans plans,
      TrainingSessions training) {
    this.repository = repository;
    this.submissions = submissions;
    this.plans = plans;
    this.training = training;
  }

  @Transactional
  public DiagnosticPlans.Plan switchProblem(
      String username, UUID key, UUID target, String version, UUID expectedActive, String note) {
    UUID owner = submissions.owner(username, true);
    var request =
        JudgeJson.JSON
            .createObjectNode()
            .put("planId", target.toString())
            .put("problemVersion", version)
            .put("note", note);
    if (expectedActive == null) request.putNull("activeSessionId");
    else request.put("activeSessionId", expectedActive.toString());
    String canonical = JudgeJson.canonical(request);
    var prior =
        repository.switchProblemLearningProblemSwitch(
            key,
            (r, n) ->
                new Object[] {
                  r.getObject(1, UUID.class), r.getString(2), r.getObject(3, UUID.class)
                });
    if (prior.isPresent()) {
      var p = prior.get();
      if (!owner.equals(p[0]) || !canonical.equals(p[1]))
        throw new AccountException(409, "같은 전환 요청의 내용이 달라요.");
      return plans.view(username, owner, (UUID) p[2]);
    }
    var selected = plans.view(username, owner, target);
    if (!List.of("READY", "TRAINING_ENDED", "AC_WITH_HELP", "SELF_REPORTED_UNASSISTED_AC")
        .contains(selected.status()))
      throw new AccountException(409, "현재 이용할 수 있는 훈련 문제를 선택해 주세요.");
    if (submissions.problems(username).stream()
        .noneMatch(p -> version.equals(p.version()) && !p.problemHeld() && p.submissionsEnabled()))
      throw new AccountException(409, "선택한 문제를 지금은 풀 수 없어요.");
    UUID active = repository.switchProblemTrainingSession(owner).orElse(null);
    if (!Objects.equals(active, expectedActive))
      throw new AccountException(409, "진행 중인 훈련이 바뀌었어요. 최신 화면에서 다시 선택해 주세요.");
    if (active != null) training.end(username, active, note);
    if (!"READY".equals(selected.status())) {
      var options =
          plans.options(
              username,
              selected.evaluationId(),
              selected.observationIndex(),
              selected.sourceKind());
      if (!options.corrections().isEmpty())
        throw new AccountException(409, "정정 의견을 확인한 뒤 수동 설정으로 다음 회차를 준비해 주세요.");
      selected = plans.nextRound(username, target, options.reviewHash());
    }
    var started = plans.start(username, selected.id(), version);
    if (!"ACTIVE".equals(started.status()))
      throw new AccountException(409, "선택한 회차가 변경됐어요. 최신 화면에서 다시 선택해 주세요.");
    repository.switchProblemLearningProblemSwitch2(key, owner, canonical, started.id());
    return started;
  }
}
