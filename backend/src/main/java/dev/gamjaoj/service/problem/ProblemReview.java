package dev.gamjaoj.service.problem;

import dev.gamjaoj.dto.ProblemReviewDtos.State;
import dev.gamjaoj.exception.AccountException;
import dev.gamjaoj.repository.problem.ProblemReviewRepository;
import dev.gamjaoj.service.generation.VerificationLedger;
import dev.gamjaoj.service.judge.Submissions;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProblemReview {
  private final VerificationLedger ledger;
  private final ProblemReviewRepository repository;
  private final Submissions submissions;

  public ProblemReview(
      ProblemReviewRepository repository, Submissions submissions, VerificationLedger ledger) {
    this.repository = repository;
    this.submissions = submissions;
    this.ledger = ledger;
  }

  public @Transactional State hold(String username, String version, String reason) {
    // Same account lock as submission/training admission; budget lock fences AI claims.
    UUID owner = submissions.owner(username, true);
    repository.holdAiBudgetLock();
    var state =
        repository
            .holdProblemVersion(
                version, owner, (r, n) -> new State(r.getBoolean(1), r.getString(2)))
            .orElseThrow(() -> new AccountException(404, "본인이 게시한 생성 문제를 찾을 수 없어요."));
    if (state.held())
      return state; // Lost responses can be retried without changing the original reason.
    if (reason == null || reason.isBlank() || reason.length() > 500)
      throw new AccountException(400, "검토 사유를 1~500자로 입력해 주세요.");
    repository.holdProblemVersion2(reason.trim(), version);
    var root = repository.holdGenerationJob(owner, version);
    root.ifPresent(id -> ledger.revokeTree(owner, id, reason.trim()));
    return new State(true, reason.trim());
  }
}
