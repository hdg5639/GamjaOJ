package dev.gamjaoj.problem.service;

import dev.gamjaoj.generation.service.VerificationLedger;
import dev.gamjaoj.judge.service.Submissions;
import dev.gamjaoj.problem.dto.ProblemReviewDtos.State;
import dev.gamjaoj.problem.repository.ProblemReviewRepository;
import dev.gamjaoj.shared.exception.AccountException;
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

  public @Transactional State holdAsAdministrator(String version, String reason) {
    UUID owner = repository.administratorOwner(version).orElse(null);
    if (owner != null)
      submissions.owner(repository.administratorUsername(owner).orElseThrow(), true);
    repository.holdAiBudgetLock();
    State state =
        repository
            .administratorProblem(version, (r, n) -> new State(r.getBoolean(1), r.getString(2)))
            .orElseThrow(() -> new AccountException(404, "문제를 찾을 수 없어요."));
    if (state.held()) return state;
    if (reason == null || reason.isBlank() || reason.length() > 500)
      throw new AccountException(400, "검토 사유를 입력해 주세요.");
    repository.holdProblemVersion2(reason.strip(), version);
    if (owner != null)
      repository
          .holdGenerationJob(owner, version)
          .ifPresent(id -> ledger.revokeTree(owner, id, reason.strip()));
    return new State(true, reason.strip());
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
