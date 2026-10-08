package dev.gamjaoj.account.service;

import dev.gamjaoj.account.repository.AccountArchiveRepository;
import dev.gamjaoj.problem.service.ProblemCatalogMetadata;
import java.util.UUID;

@org.springframework.stereotype.Service
public class AccountArchive {
  private final AccountArchiveRepository repository;

  public AccountArchive(AccountArchiveRepository repository) {
    this.repository = repository;
  }

  public UUID archive() {
    var existing = repository.archiveAppUser(AccountDeletion.ARCHIVE_USERNAME);
    if (existing.isPresent()) return existing.get();
    UUID id = UUID.randomUUID();
    // "!" is not a valid BCrypt hash, so no password can match.
    repository.archiveAppUser2(id, AccountDeletion.ARCHIVE_USERNAME, "!", "탈퇴한 회원");
    return id;
  }

  public void moveToArchive(String id, UUID archive) {
    var metadata =
        repository.moveToArchiveProblemVersion(id, (r, n) -> ProblemCatalogMetadata.read(r));
    repository.moveToArchiveProblemVersion2(
        archive, metadata.category(), String.join(",", metadata.tags()), id);
  }
}
