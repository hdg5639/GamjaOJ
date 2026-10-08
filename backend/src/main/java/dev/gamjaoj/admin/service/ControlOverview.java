package dev.gamjaoj.admin.service;

import dev.gamjaoj.admin.dto.ControlDtos;
import dev.gamjaoj.admin.repository.ControlRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ControlOverview {
  private final ControlRepository repository;

  public ControlOverview(ControlRepository repository) {
    this.repository = repository;
  }

  @Transactional(readOnly = true)
  public ControlDtos.Overview overview() {
    return new ControlDtos.Overview(
        Instant.now(),
        repository.members(),
        repository.problems(),
        repository.judgeQueue(),
        repository.generationJobs(),
        repository.aiTasks());
  }
}
