package dev.gamjaoj.account.service;

import dev.gamjaoj.account.repository.AccountDeletionRepository;
import dev.gamjaoj.generation.service.GenerationActivity;
import dev.gamjaoj.generation.service.GenerationSpecDrafts;
import dev.gamjaoj.shared.exception.AccountException;
import java.util.List;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hard account deletion. The account row and all private data are deleted. Problems and rules other
 * members can use (shared, or already referenced by another member's work) move to one non-login
 * archive account so they neither disappear from others' history nor turn into official content
 * (owner_id NULL means official).
 */
@Service
public class AccountDeletion {
  private final AccountArchive accountArchive;
  private final GenerationActivity generationActivity;

  /** Signup allows only [a-z0-9_], so this name can never be registered or used to log in. */
  public static final String ARCHIVE_USERNAME = "#withdrawn";

  public record Result(int keptProblems, int keptRules) {}

  private final AccountDeletionRepository repository;
  private final PasswordEncoder passwords;
  private final GenerationSpecDrafts drafts;

  public AccountDeletion(
      AccountDeletionRepository repository,
      PasswordEncoder passwords,
      GenerationSpecDrafts drafts,
      AccountArchive accountArchive,
      GenerationActivity generationActivity) {
    this.accountArchive = accountArchive;
    this.generationActivity = generationActivity;
    this.repository = repository;
    this.passwords = passwords;
    this.drafts = drafts;
  }

  /** The shared non-login owner of content kept for other members after its author removed it. */
  @Transactional
  public Result delete(String username, String password, String confirmation) {
    if (ARCHIVE_USERNAME.equals(username)) throw new AccountException(403, "삭제할 수 없는 계정이에요.");
    var row =
        repository
            .deleteAppUser(
                username, (r, n) -> new Object[] {r.getObject(1, UUID.class), r.getString(2)})
            .orElseThrow(() -> new AccountException(401, "다시 로그인해 주세요."));
    UUID user = (UUID) row[0];
    if (!username.equals(confirmation)) throw new AccountException(400, "확인을 위해 아이디를 정확히 입력해 주세요.");
    if (password == null || !passwords.matches(password, (String) row[1]))
      throw new AccountException(403, "비밀번호가 맞지 않아요.");
    if (active(user)) throw new AccountException(409, "진행 중인 채점·출제·규칙 등록·AI 요청이 끝난 뒤 탈퇴할 수 있어요.");
    UUID archive = accountArchive.archive();

    // 1. Problems other members can see or already used stay, with catalog labels copied onto the
    // problem.
    var kept = repository.deleteProblemVersion(user, user, user, user);
    for (String id : kept) accountArchive.moveToArchive(id, archive);
    // 2. Shared rules, or rules another member generated from, stay; detach them from sources that
    // are deleted below.
    var keptRules = repository.deleteHybridRuleFamily(user, user);
    for (String family : keptRules) {
      repository.deleteHybridRuleArtifact(family);
      repository.deleteHybridRuleFamily2(archive, family);
    }
    // Reference artifacts of rules this account does not own (built-in or other members') may have
    // been qualified
    // from this account's generations or onboarding; they cascade on their source, so detach them
    // first.
    repository.deleteHybridRuleArtifact2(user, user);
    repository.deleteHybridRuleArtifact3(user, user);
    // 3. Private data, in foreign-key order (several links are NO ACTION and are not cascaded by
    // the account row).

    repository.deleteGenerationExecution(user, user);
    repository.deleteGenerationAttempt(user);
    repository.deleteGenerationSpecExecution(user, user);
    repository.deleteDiagnosticPracticePlan(user);
    repository.deletePracticeFollowup(user);
    repository.deleteSubmission(user);
    repository.deleteTrainingSession(user);
    repository.deleteHybridGeneration(user);
    repository.deleteGenerationJob(user);
    repository.deleteGenerationSpecDraft(user);
    repository.deleteProblemVersion2(user);
    repository.deleteSpringSession(username);
    // Remaining rows (AI tasks, diagnostics, grants, private rules, onboarding) cascade from the
    // account row.
    repository.deleteAppUser2(user);
    return new Result(kept.size(), keptRules.size());
  }

  /**
   * Keeps a problem other members rely on: owned by the archive account with its catalog labels
   * copied onto it.
   */
  private boolean active(UUID user) {
    int judging = repository.activeJudgeJob(user);
    return judging > 0
        || repository.activeGenerationJob(user) > 0
        || drafts.active(user)
        || generationActivity.active(user)
        || repository.activeHybridRuleOnboarding(user) > 0
        || repository.activeAiTask(user) > 0;
  }

  public static final List<String> DELETED =
      List.of(
          "계정·닉네임·학습 목표",
          "제출·실행 기록과 채점 결과",
          "훈련·후속 연습",
          "진단·평가·학습 계획",
          "AI 분석·힌트 요청",
          "문제 생성 요청과 비공개 문제",
          "비공개 규칙과 규칙 등록 요청",
          "로그인 세션");
}
