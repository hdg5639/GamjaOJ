package dev.gamjaoj.account.service;

import dev.gamjaoj.account.repository.CodeDraftsRepository;
import dev.gamjaoj.judge.service.Submissions;
import dev.gamjaoj.shared.exception.AccountException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Server copy of in-progress code so a member can continue on another browser or device. Latest
 * write wins.
 */
@Service
public class CodeDrafts {
  public static final int MAX_DRAFTS = 300;
  private static final Pattern SCOPE = Pattern.compile("(p|d):[A-Za-z0-9._:-]{1,110}");

  public record Draft(String scope, String language, String source, OffsetDateTime updatedAt) {}

  private final CodeDraftsRepository repository;
  private final Submissions submissions;

  public CodeDrafts(CodeDraftsRepository repository, Submissions submissions) {
    this.repository = repository;
    this.submissions = submissions;
  }

  private static void check(String scope, String language) {
    if (scope == null || !SCOPE.matcher(scope).matches())
      throw new AccountException(400, "초안 위치가 올바르지 않아요.");
    if (language == null || !java.util.List.of("JAVA", "CPP", "PYTHON").contains(language))
      throw new AccountException(400, "지원하지 않는 언어예요.");
  }

  public Optional<Draft> get(String username, String scope, String language) {
    check(scope, language);
    UUID user = submissions.owner(username, false);
    return repository.getCodeDraft(
        user,
        scope,
        language,
        (r, n) -> new Draft(scope, language, r.getString(1), r.getObject(2, OffsetDateTime.class)));
  }

  @Transactional
  public Draft save(String username, String scope, String language, String source) {
    check(scope, language);
    if (source == null || source.getBytes(StandardCharsets.UTF_8).length > 65536)
      throw new AccountException(400, "코드는 UTF-8 기준 64 KiB 이내로 저장할 수 있어요.");
    UUID user = submissions.owner(username, true);
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    if (repository.saveCodeDraft(source, now, user, scope, language) == 0) {
      repository.saveCodeDraft2(user, scope, language, source, now);
      // Bounded per member: the least recently edited drafts go first.
      repository.saveCodeDraft3(user).ifPresent(cutoff -> repository.saveCodeDraft4(user, cutoff));
    }
    return new Draft(scope, language, source, now);
  }
}
