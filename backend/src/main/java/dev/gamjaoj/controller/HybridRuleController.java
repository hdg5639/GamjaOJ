package dev.gamjaoj.controller;

import dev.gamjaoj.dto.HybridRuleDtos.*;
import dev.gamjaoj.service.generation.HybridRuleOnboarding;
import dev.gamjaoj.service.generation.RuleManagement;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** Member rule registration: private until the owner shares it explicitly. */
@RestController
@RequestMapping("/api/rules")
public class HybridRuleController {
  private final RuleManagement rules;
  private final HybridRuleOnboarding onboarding;

  public HybridRuleController(RuleManagement rules, HybridRuleOnboarding onboarding) {
    this.rules = rules;
    this.onboarding = onboarding;
  }

  @GetMapping("/onboarding/options")
  public java.util.Map<String, Object> options() {
    return java.util.Map.of("enabled", onboarding.enabled());
  }

  @PostMapping("/onboarding")
  public HybridRuleOnboarding.View create(
      Principal user, @RequestHeader("Idempotency-Key") UUID id, @RequestBody Request body) {
    return rules.create(user.getName(), id, body);
  }

  @GetMapping("/onboarding")
  public List<HybridRuleOnboarding.View> list(Principal user) {
    return onboarding.list(user.getName());
  }

  @PostMapping("/onboarding/{id}/cancel")
  public HybridRuleOnboarding.View cancel(Principal user, @PathVariable UUID id) {
    return onboarding.cancel(user.getName(), id);
  }

  @PostMapping("/onboarding/{id}/retry")
  public HybridRuleOnboarding.View retry(
      Principal user, @PathVariable UUID id, @RequestBody Retry body) {
    return rules.retry(user.getName(), id, body);
  }

  @GetMapping("/mine")
  public List<Owned> mine(Principal user) {
    return rules.mine(user.getName());
  }

  @PutMapping("/{id}/sharing")
  public Owned share(Principal user, @PathVariable String id, @RequestBody Sharing body) {
    return rules.share(user.getName(), id, body);
  }
}
