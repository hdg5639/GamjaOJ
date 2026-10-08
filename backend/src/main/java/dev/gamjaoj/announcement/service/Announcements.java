package dev.gamjaoj.announcement.service;

import dev.gamjaoj.admin.service.AdminAudit;
import dev.gamjaoj.announcement.dto.AnnouncementDtos.*;
import dev.gamjaoj.announcement.repository.AnnouncementRepository;
import dev.gamjaoj.shared.exception.AccountException;
import java.time.ZoneId;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Announcements {
  private final AnnouncementRepository repository;
  private final AdminAudit audit;

  public Announcements(AnnouncementRepository repository, AdminAudit audit) {
    this.repository = repository;
    this.audit = audit;
  }

  public List<Entry> list() {
    return repository.list(false);
  }

  public List<PublicEntry> published() {
    return repository.list(true).stream()
        .map(
            e ->
                new PublicEntry(
                    e.id(),
                    e.kind(),
                    e.title(),
                    e.summary(),
                    e.pinned(),
                    e.publishedAt()
                        .atZoneSameInstant(ZoneId.of("Asia/Seoul"))
                        .toLocalDate()
                        .toString(),
                    List.of(e.body().split("\\n\\s*\\n"))))
        .toList();
  }

  @Transactional
  public Entry create(String actor, UUID key, Write w) {
    // Lock actor to serialize same-key requests without returning an unrelated duplicate.
    repository.lockActor(actor);
    var prior = repository.find(key.toString());
    if (prior.isPresent()) {
      Entry e = prior.get();
      if (!e.updatedBy().equals(actor) || !same(e, w))
        throw new AccountException(409, "같은 요청 키의 내용이 달라요.");
      return e;
    }
    try {
      repository.create(key.toString(), actor, w);
    } catch (org.springframework.dao.DuplicateKeyException e) {
      throw new AccountException(409, "이미 처리된 요청이에요. 목록을 새로고침해 주세요.");
    }
    Entry saved = repository.find(key.toString()).orElseThrow();
    audit.record(actor, "ANNOUNCEMENT_CREATE", saved.id(), "공지 작성", Map.of(), metadata(saved));
    return saved;
  }

  @Transactional
  public Entry update(String actor, String id, Write w) {
    Entry before =
        repository.find(id).orElseThrow(() -> new AccountException(404, "공지를 찾을 수 없어요."));
    if (repository.update(id, actor, w) != 1)
      throw new AccountException(409, "다른 관리자가 수정했어요. 목록을 새로고침해 주세요.");
    Entry after = repository.find(id).orElseThrow();
    audit.record(
        actor, "ANNOUNCEMENT_UPDATE", id, "공지 수정·게시 상태 변경", metadata(before), metadata(after));
    return after;
  }

  private boolean same(Entry e, Write w) {
    return e.kind().equals(w.kind())
        && e.title().equals(w.title().strip())
        && e.summary().equals(w.summary().strip())
        && e.body().equals(w.body().strip())
        && e.pinned() == w.pinned()
        && e.published() == w.published();
  }

  private Map<String, Object> metadata(Entry e) {
    return Map.of(
        "title",
        e.title(),
        "published",
        e.published(),
        "pinned",
        e.pinned(),
        "revision",
        e.revision());
  }
}
