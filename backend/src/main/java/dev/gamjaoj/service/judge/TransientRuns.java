package dev.gamjaoj.service.judge;

import dev.gamjaoj.repository.judge.TransientRunsRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keep completed custom results briefly for polling/lost-response recovery, not learning history.
 */
@Service
public class TransientRuns {
  private final TransientRunsRepository repository;

  public TransientRuns(TransientRunsRepository repository) {
    this.repository = repository;
  }

  @Scheduled(fixedDelay = 3600000, initialDelay = 60000)
  @Transactional
  public void clean() {
    repository.cleanSubmission(OffsetDateTime.now(ZoneOffset.UTC).minusHours(24));
  }
}
