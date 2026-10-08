package dev.gamjaoj.generation.service;

import static dev.gamjaoj.generation.dto.HybridRuleDtos.*;

import dev.gamjaoj.diagnostic.service.DiagnosticEvaluations;
import dev.gamjaoj.diagnostic.service.DiagnosticProfiles;
import dev.gamjaoj.generation.dto.HybridRuleDtos;
import dev.gamjaoj.judge.service.Submissions;
import dev.gamjaoj.shared.exception.AccountException;
import dev.gamjaoj.shared.support.JudgeJson;
import java.util.List;
import java.util.UUID;

@org.springframework.stereotype.Service
public class RuleManagement {
  private final HybridRuleOnboarding onboarding;
  private final HybridRuleRegistry registry;
  private final Submissions submissions;
  private final DiagnosticEvaluations evaluations;
  private final DiagnosticProfiles profiles;

  public RuleManagement(
      HybridRuleOnboarding onboarding,
      HybridRuleRegistry registry,
      Submissions submissions,
      DiagnosticEvaluations evaluations,
      DiagnosticProfiles profiles) {
    this.onboarding = onboarding;
    this.registry = registry;
    this.submissions = submissions;
    this.evaluations = evaluations;
    this.profiles = profiles;
  }

  private com.fasterxml.jackson.databind.JsonNode target(
      String user, UUID evaluation, Integer index) {
    if (evaluation == null) return null;
    var view = evaluations.detail(user, evaluation);
    var observations =
        view.interpretation() == null ? null : view.interpretation().path("observations");
    if (index == null || observations == null || index < 0 || index >= observations.size())
      throw new AccountException(409, "겨냥할 진단 관찰을 찾을 수 없어요. 평가를 다시 확인해 주세요.");
    var o = observations.get(index);
    String quote = o.path("quote").asText();
    var t =
        JudgeJson.JSON
            .createObjectNode()
            .put("pattern", o.path("pattern").asText(o.path("interpretation").asText()))
            .put("risk", o.path("risk").asText(o.path("recommendation").asText()))
            .put("quote", quote.length() > 400 ? quote.substring(0, 400) : quote);
    profiles
        .category(view.sessionId(), o.path("submissionId").asText())
        .ifPresent(c -> t.put("category", c));
    return t;
  }

  public HybridRuleOnboarding.View create(String username, UUID id, HybridRuleDtos.Request body) {
    if (body == null) return onboarding.create(username, id, (String) null);
    boolean legacy =
        body.difficulty() == null
            && body.style() == null
            && body.category() == null
            && body.publish() == null
            && body.evaluationId() == null
            && body.thinkingLayer() == null;
    if (legacy) return onboarding.create(username, id, body.request());
    return onboarding.create(
        username,
        id,
        new HybridRuleOnboarding.Spec(
            body.request(),
            body.difficulty(),
            body.style(),
            body.category(),
            target(username, body.evaluationId(), body.observationIndex()),
            Boolean.TRUE.equals(body.publish()),
            Boolean.TRUE.equals(body.shared()),
            body.thinkingLayer()));
  }

  public List<HybridRuleDtos.Owned> mine(String username) {
    return registry.owned(submissions.owner(username, false)).stream()
        .map(v -> new HybridRuleDtos.Owned(v.id(), v.label(), v.category(), v.status(), v.shared()))
        .toList();
  }

  public HybridRuleDtos.Owned share(String username, String id, HybridRuleDtos.Sharing body) {
    if (body == null || body.shared() == null) throw new AccountException(400, "공개 여부를 선택해 주세요.");
    var v = registry.share(submissions.owner(username, false), id, body.shared());
    return new HybridRuleDtos.Owned(v.id(), v.label(), v.category(), v.status(), v.shared());
  }

  public HybridRuleOnboarding.View retry(String username, UUID id, HybridRuleDtos.Retry body) {
    if (body == null || body.attemptId() == null)
      throw new AccountException(400, "재시도할 작성 기록이 필요해요.");
    return onboarding.retryAuthor(username, id, body.attemptId());
  }
}
