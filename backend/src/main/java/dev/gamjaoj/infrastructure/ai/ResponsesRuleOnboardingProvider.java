package dev.gamjaoj.infrastructure.ai;

import dev.gamjaoj.config.AiSettings;
import dev.gamjaoj.service.generation.HybridRuleOnboarding;
import java.time.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;

@Component
public class ResponsesRuleOnboardingProvider implements RuleOnboardingProvider {
  private final AiSettings config;

  public ResponsesRuleOnboardingProvider(AiSettings config) {
    this.config = config;
  }

  public OpenAiResponses.Result generate(HybridRuleOnboarding.Call call) {
    Duration remaining = Duration.between(OffsetDateTime.now(ZoneOffset.UTC), call.deadlineAt());
    if (remaining.isNegative() || remaining.isZero())
      throw new OpenAiResponses.Failure("DEADLINE_BEFORE_DISPATCH", null, null);
    var m = call.model();
    return new OpenAiResponses(config.key())
        .generate(
            m.model(),
            m.effort(),
            call.instructions(),
            call.input(),
            "rule_" + call.role().toLowerCase(java.util.Locale.ROOT) + "_v1",
            call.schema(),
            m.maxOutputTokens(),
            remaining.compareTo(Duration.ofSeconds(280)) > 0 ? Duration.ofSeconds(280) : remaining);
  }
}
