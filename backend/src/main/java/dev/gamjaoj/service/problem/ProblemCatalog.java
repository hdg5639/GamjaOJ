package dev.gamjaoj.service.problem;

import dev.gamjaoj.domain.ProblemCategories;
import dev.gamjaoj.dto.ProblemCatalogDtos.Settings;
import dev.gamjaoj.exception.AccountException;
import dev.gamjaoj.repository.problem.ProblemCatalogRepository;
import dev.gamjaoj.service.judge.Submissions;
import jakarta.validation.constraints.*;
import org.springframework.transaction.annotation.Transactional;

@org.springframework.stereotype.Service
public class ProblemCatalog {
  private final ProblemCatalogRepository repository;
  private final Submissions submissions;

  public ProblemCatalog(ProblemCatalogRepository repository, Submissions submissions) {
    this.repository = repository;
    this.submissions = submissions;
  }

  public @Transactional Submissions.Problem save(
      String username, String version, Settings request) {
    var owner = submissions.owner(username, true);
    if (request.tags().stream().anyMatch(t -> t.contains(",")))
      throw new AccountException(400, "태그 안에는 쉼표를 사용할 수 없어요.");
    int changed =
        repository.saveProblemVersion(
            request.shared(),
            ProblemCategories.forSave(request.category()),
            String.join(",", request.tags().stream().map(String::strip).distinct().toList()),
            request.difficulty(),
            version,
            owner,
            request.shared());
    if (changed != 1)
      throw new AccountException(404, "공개 설정을 변경할 수 있는 내 문제를 찾을 수 없어요. 검토 중인 문제는 공개할 수 없습니다.");
    if (Boolean.TRUE.equals(request.clearThinking()) && request.thinking() != null)
      throw new AccountException(400, "난도 지정과 해제는 함께 할 수 없어요.");
    if (Boolean.TRUE.equals(request.clearThinking()))
      repository.saveProblemThinkingProfile(version);
    if (request.thinking() != null) {
      var t = request.thinking();
      repository.saveProblemThinkingProfile2(version);
      repository.saveProblemThinkingProfile3(
          t.layer(),
          t.insight(),
          t.implementation(),
          t.edgeCases(),
          t.rationale().strip(),
          version);
    }
    return submissions.problems(username).stream()
        .filter(p -> p.version().equals(version))
        .findFirst()
        .orElseThrow();
  }
}
