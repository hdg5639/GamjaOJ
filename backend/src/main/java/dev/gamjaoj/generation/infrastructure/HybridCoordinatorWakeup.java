package dev.gamjaoj.generation.infrastructure;

import dev.gamjaoj.ai.config.AiSettings;
import dev.gamjaoj.generation.service.HybridExecution;
import dev.gamjaoj.generation.service.HybridPublication;
import dev.gamjaoj.generation.service.HybridRunnerChecks;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;

/** Coalesced post-commit wakeups; scheduled recovery remains authoritative after a restart. */
@Component
public class HybridCoordinatorWakeup {
  private final HybridRunnerChecks checks;
  private final HybridPublication publication;
  private final HybridApiWorker api;
  private final AiSettings settings;
  private final AtomicBoolean pending = new AtomicBoolean(), running = new AtomicBoolean();
  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          r -> {
            var t = new Thread(r, "hybrid-coordinator");
            t.setDaemon(true);
            return t;
          });

  public HybridCoordinatorWakeup(
      HybridRunnerChecks checks,
      HybridPublication publication,
      HybridApiWorker api,
      AiSettings settings) {
    this.checks = checks;
    this.publication = publication;
    this.api = api;
    this.settings = settings;
  }

  public @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT) void changed(
      HybridExecution.Wakeup event) {
    if (!Boolean.parseBoolean(settings.value("HYBRID_COORDINATOR_EVENTS_ENABLED", "false"))) return;
    pending.set(true);
    schedule();
  }

  private void schedule() {
    if (!running.compareAndSet(false, true)) return;
    try {
      executor.submit(
          () -> {
            try {
              while (pending.getAndSet(false)) {
                checks.advance();
                publication.advance();
                api.wake();
              }
            } catch (RuntimeException failure) {
              org.slf4j.LoggerFactory.getLogger(getClass())
                  .warn("Hybrid wakeup failed; scheduled recovery remains enabled", failure);
            } finally {
              running.set(false);
              if (pending.get()) schedule();
            }
          });
    } catch (RejectedExecutionException closed) {
      running.set(false);
    }
  }

  public @PreDestroy void close() {
    executor.shutdownNow();
  }
}
