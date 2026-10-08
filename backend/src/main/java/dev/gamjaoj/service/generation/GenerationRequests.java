package dev.gamjaoj.service.generation;

import dev.gamjaoj.dto.GenerationDtos;
import dev.gamjaoj.exception.AccountException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves member choices before the shared generation admission checks. */
@Service
public class GenerationRequests {
  private final GenerationJobs jobs;

  public GenerationRequests(GenerationJobs jobs) {
    this.jobs = jobs;
  }

  @Transactional
  public GenerationJobs.View create(String username, UUID key, GenerationDtos.Create request) {
    if (request.category() != null || request.tags() != null) {
      if (request.template() != null || request.focus() != null)
        throw new AccountException(400, "카테고리·태그와 이전 유형 조건을 함께 보낼 수 없어요.");
      var selection = GenerationChoices.resolve(request.category(), request.tags());
      return jobs.create(
          username,
          key,
          selection.template(),
          selection.focus(),
          request.sourceAnalysisId(),
          Boolean.TRUE.equals(request.shared()));
    }
    return jobs.create(
        username,
        key,
        request.template(),
        request.focus() == null ? "basics" : request.focus(),
        request.sourceAnalysisId(),
        Boolean.TRUE.equals(request.shared()));
  }
}
