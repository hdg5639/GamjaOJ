package dev.gamjaoj.service.account;

import dev.gamjaoj.exception.AccountException;
import dev.gamjaoj.repository.account.ContentDeletionRepository;
import dev.gamjaoj.service.judge.Submissions;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A member deletes one problem or rule they made. Same principle as account deletion: content
 * another member already relies on moves to the non-login archive account (and leaves the catalog);
 * otherwise it is deleted together with the owner's own records on it. Generation histories stay,
 * without a link to the deleted problem.
 */
@Service
public class ContentDeletion {
  private final AccountArchive accountArchive;

  public record Result(String outcome) {
    public static final String DELETED = "DELETED", ARCHIVED = "ARCHIVED";
  }

  private final ContentDeletionRepository repository;
  private final Submissions submissions;

  public ContentDeletion(
      ContentDeletionRepository repository,
      Submissions submissions,
      AccountArchive accountArchive) {
    this.accountArchive = accountArchive;
    this.repository = repository;
    this.submissions = submissions;
  }

  @Transactional
  public Result problem(String username, String version) {
    UUID user = submissions.owner(username, true);
    var owned = repository.problemProblemVersion(version, user);
    if (owned == 0) throw new AccountException(404, "삭제할 수 있는 내 문제를 찾을 수 없어요.");
    if (repository.problemJudgeJob(version) > 0)
      throw new AccountException(409, "이 문제의 채점이 끝난 뒤 삭제할 수 있어요.");
    boolean others = repository.problemProblemVersion2(version, user, user, user) > 0;
    if (others) {
      // Other members' submissions and training keep their problem; it just leaves the catalog.
      accountArchive.moveToArchive(version, accountArchive.archive());
      repository.problemProblemVersion3(version);
      return new Result(Result.ARCHIVED);
    }

    repository.problemHybridGeneration(version);
    repository.problemGenerationExecution(version);
    repository.problemGenerationSpecExecution(version);
    repository.problemPracticeFollowup(version);
    // Judge jobs/attempts, AI tasks and execution checks cascade from the submissions.
    repository.problemSubmission(version);
    repository.problemTrainingSession(version);
    repository.problemCodeDraft("p:" + version);
    repository.problemProblemVersion4(version);
    return new Result(Result.DELETED);
  }

  @Transactional
  public Result rule(String username, String versionId) {
    UUID user = submissions.owner(username, true);
    String family =
        repository
            .ruleHybridRuleFamily(versionId, user)
            .orElseThrow(() -> new AccountException(404, "삭제할 수 있는 내 규칙을 찾을 수 없어요."));
    List<String> versions = repository.ruleHybridRuleVersion(family);

    if (repository.ruleHybridGeneration(family) > 0)
      throw new AccountException(409, "이 규칙으로 진행 중인 문제 생성이 끝난 뒤 삭제할 수 있어요.");
    boolean others = repository.ruleHybridGeneration2(family, user) > 0;
    // The registration requests that produced this rule leave the member's list either way.
    for (String v : versions) repository.ruleHybridRuleOnboarding(user, v);
    if (others) {
      // Other members generated from it: keep the rule rows their generations point to, but nobody
      // can select it.
      repository.ruleHybridRuleArtifact(family);
      repository.ruleHybridRuleVersion2(family);
      repository.ruleHybridRuleFamily2(accountArchive.archive(), family);
      return new Result(Result.ARCHIVED);
    }
    repository.ruleHybridPublicRequest(family);
    repository.ruleHybridRuleArtifact2(family);
    repository.ruleHybridRuleVersion3(family);
    repository.ruleHybridRuleFamily3(family);
    return new Result(Result.DELETED);
  }
}
