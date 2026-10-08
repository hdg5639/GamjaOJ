package dev.gamjaoj.infrastructure.worker;

import dev.gamjaoj.service.generation.GenerationJobs;
import dev.gamjaoj.service.generation.HybridGeneration;
import dev.gamjaoj.service.generation.HybridPublication;
import dev.gamjaoj.service.generation.HybridRunnerChecks;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class GenerationCoordinator {
  private final GenerationJobs jobs;
  private final HybridGeneration hybrid;
  private final HybridRunnerChecks checks;
  private final HybridPublication publication;

  public GenerationCoordinator(
      GenerationJobs jobs,
      HybridGeneration hybrid,
      HybridRunnerChecks checks,
      HybridPublication publication) {
    this.jobs = jobs;
    this.hybrid = hybrid;
    this.checks = checks;
    this.publication = publication;
  }

  public @Scheduled(
      fixedDelayString = "${AI_POLL_MS:5000}",
      initialDelayString = "${AI_POLL_MS:5000}") void tick() {
    hybrid.expirePending();
    checks.advance();
    publication.advance();
    jobs.advance();
  }
}
