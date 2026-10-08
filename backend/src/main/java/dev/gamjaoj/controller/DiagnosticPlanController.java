package dev.gamjaoj.controller;

import static dev.gamjaoj.dto.DiagnosticPlanDtos.*;

import dev.gamjaoj.service.diagnostic.DiagnosticPlans;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/diagnostic-plans")
public class DiagnosticPlanController {
  private final DiagnosticPlans plans;

  public DiagnosticPlanController(DiagnosticPlans plans) {
    this.plans = plans;
  }

  public @PostMapping("/{id}/generate") DiagnosticPlans.Plan generate(
      Principal user, @PathVariable UUID id, @Valid @RequestBody(required = false) Generate body) {
    return plans.generate(user.getName(), id, body == null ? null : body.ruleVersionId());
  }

  public @PostMapping("/{id}/reflect") DiagnosticPlans.Plan reflect(
      Principal user, @PathVariable UUID id, @Valid @RequestBody Reflection body) {
    return plans.reflect(user.getName(), id, body.usedHelp());
  }

  public @PostMapping("/{id}/next-round") DiagnosticPlans.Plan nextRound(
      Principal user, @PathVariable UUID id, @Valid @RequestBody NextRound body) {
    return plans.nextRound(user.getName(), id, body.reviewHash());
  }

  public @PostMapping("/order") List<DiagnosticPlans.Plan> reorder(
      Principal user, @Valid @RequestBody Order body) {
    return plans.reorder(user.getName(), body.evaluationId(), body.previous(), body.desired());
  }

  public @GetMapping("/trained-scope") List<String> trainedScope(
      Principal user, @RequestParam UUID sourceSessionId) {
    return plans.trainedScope(user.getName(), sourceSessionId);
  }

  public @GetMapping("/options") DiagnosticPlans.Options options(
      Principal user,
      @RequestParam UUID evaluationId,
      @RequestParam int observationIndex,
      @RequestParam(defaultValue = "CODE_OBSERVATION") String sourceKind) {
    return plans.options(user.getName(), evaluationId, observationIndex, sourceKind);
  }

  public @GetMapping List<DiagnosticPlans.Plan> list(
      Principal user, @RequestParam UUID evaluationId) {
    return plans.list(user.getName(), evaluationId);
  }

  public @PostMapping DiagnosticPlans.Plan confirm(
      Principal user,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody Confirm body) {
    return plans.confirm(
        user.getName(),
        key,
        body.evaluationId(),
        body.observationIndex(),
        body.reviewHash(),
        body.goal(),
        body.sourceKind() == null ? "CODE_OBSERVATION" : body.sourceKind());
  }

  public @PostMapping("/{id}/start") DiagnosticPlans.Plan start(
      Principal user, @PathVariable UUID id, @Valid @RequestBody Start body) {
    return plans.start(user.getName(), id, body.problemVersion());
  }
}
