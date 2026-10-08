package dev.gamjaoj.generation.infrastructure.ai;

import dev.gamjaoj.ai.infrastructure.OpenAiResponses;
import dev.gamjaoj.generation.service.HybridRuleOnboarding;
import java.time.*;
import java.util.concurrent.*;
import org.springframework.transaction.event.*;

public interface RuleOnboardingProvider {
  public OpenAiResponses.Result generate(HybridRuleOnboarding.Call call);
}
