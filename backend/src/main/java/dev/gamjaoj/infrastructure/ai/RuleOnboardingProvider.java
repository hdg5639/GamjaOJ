package dev.gamjaoj.infrastructure.ai;

import dev.gamjaoj.service.generation.HybridRuleOnboarding;
import java.time.*;
import java.util.concurrent.*;
import org.springframework.transaction.event.*;

public interface RuleOnboardingProvider {
  public OpenAiResponses.Result generate(HybridRuleOnboarding.Call call);
}
