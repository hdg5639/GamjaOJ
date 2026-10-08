package dev.gamjaoj.generation.api;

import dev.gamjaoj.generation.service.HybridExecution;
import dev.gamjaoj.generation.service.HybridGeneration;
import dev.gamjaoj.generation.service.HybridModels;
import dev.gamjaoj.generation.service.HybridRuleOnboarding;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Protected by the existing stateless GENERATOR bearer-token chain. */
@RestController
public class HybridWorkerController {
  private final HybridRuleOnboarding onboarding;
  private final HybridExecution execution;

  public HybridWorkerController(HybridExecution execution, HybridRuleOnboarding onboarding) {
    this.execution = execution;
    this.onboarding = onboarding;
  }

  public @PostMapping("/internal/generation/rule-author/claim") ResponseEntity<
          com.fasterxml.jackson.databind.JsonNode>
      claimRuleAuthor() {
    var work = onboarding.claimCodexAuthor();
    return work == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(work);
  }

  public @PostMapping("/internal/generation/rule-author/result") ResponseEntity<Void>
      finishRuleAuthor(@RequestBody HybridRuleOnboarding.CodexCompletion result) {
    onboarding.finishCodexAuthor(result);
    return ResponseEntity.noContent().build();
  }

  public @PostMapping("/internal/generation/hybrid/claim") ResponseEntity<HybridModels.CodexRequest>
      claim() {
    var work = execution.claimCodex();
    return work == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(work);
  }

  public @PostMapping("/internal/generation/hybrid/result") ResponseEntity<Void> complete(
      @RequestBody HybridGeneration.Completion result) {
    execution.completeCodex(result);
    return ResponseEntity.noContent().build();
  }
}
