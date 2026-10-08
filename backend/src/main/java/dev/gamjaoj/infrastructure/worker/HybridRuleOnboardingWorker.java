package dev.gamjaoj.infrastructure.worker;

import dev.gamjaoj.config.AiSettings;
import dev.gamjaoj.infrastructure.ai.OpenAiResponses;
import dev.gamjaoj.infrastructure.ai.RuleOnboardingProvider;
import dev.gamjaoj.service.generation.HybridExecution;
import dev.gamjaoj.service.generation.HybridRuleFollowup;
import dev.gamjaoj.service.generation.HybridRuleOnboarding;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;

@Component
public class HybridRuleOnboardingWorker {
  private final HybridRuleOnboarding onboarding;
  private final RuleOnboardingProvider provider;
  private final AiSettings config;
  private final HybridRuleFollowup followup;
  private final AtomicBoolean running = new AtomicBoolean();
  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          r -> {
            var t = new Thread(r, "rule-onboarding");
            t.setDaemon(true);
            return t;
          });

  public HybridRuleOnboardingWorker(
      HybridRuleOnboarding onboarding,
      RuleOnboardingProvider provider,
      AiSettings config,
      HybridRuleFollowup followup) {
    this.onboarding = onboarding;
    this.provider = provider;
    this.config = config;
    this.followup = followup;
  }

  public @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT) void changed(
      HybridExecution.Wakeup event) {
    wake();
  }

  public @Scheduled(
      fixedDelayString = "${AI_POLL_MS:5000}",
      initialDelayString = "${AI_POLL_MS:5000}") void tick() {
    wake();
  }

  public void wake() {
    if (!Boolean.parseBoolean(config.value("HYBRID_RULE_ONBOARDING_WORKER_ENABLED", "true"))
        || !running.compareAndSet(false, true)) return;
    try {
      executor.submit(
          () -> {
            try {
              onboarding.advance();
              while (runOnce()) {
                onboarding.advance();
              }
              try {
                followup.advance();
              } catch (RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(getClass())
                    .warn(
                        "Rule followup generation step failed; retried on the next tick", failure);
              }
            } catch (RuntimeException failure) {
              org.slf4j.LoggerFactory.getLogger(getClass())
                  .warn("Rule onboarding step failed; retried on the next tick", failure);
            } finally {
              running.set(false);
            }
          });
    } catch (RejectedExecutionException closed) {
      running.set(false);
    }
  }

  public boolean runOnce() {
    var call = onboarding.claimCall();
    if (call == null) return false;
    OpenAiResponses.Result result = null;
    OpenAiResponses.Failure failure = null;
    try {
      result = provider.generate(call);
      if (result == null) throw new IllegalStateException("Missing provider result");
    } catch (OpenAiResponses.Failure e) {
      failure = e;
    } catch (RuntimeException e) {
      failure = new OpenAiResponses.Failure("PROVIDER_FAILURE_USAGE_UNKNOWN", null, null);
    }
    onboarding.finishCall(call.attemptId(), result, failure);
    return true;
  }

  public @PreDestroy void close() {
    executor.shutdownNow();
  }
}
