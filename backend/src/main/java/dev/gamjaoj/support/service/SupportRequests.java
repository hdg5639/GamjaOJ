package dev.gamjaoj.support.service;

import dev.gamjaoj.admin.service.AdminAudit;
import dev.gamjaoj.shared.exception.AccountException;
import dev.gamjaoj.support.dto.SupportDtos.*;
import dev.gamjaoj.support.repository.SupportRepository;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SupportRequests {
  private final SupportRepository repository;
  private final AdminAudit audit;

  public SupportRequests(SupportRepository repository, AdminAudit audit) {
    this.repository = repository;
    this.audit = audit;
  }

  public Page list(String owner, String status, int page) {
    if (page < 0
        || page > 100000
        || !Set.of("", "OPEN", "IN_PROGRESS", "RESOLVED").contains(status))
      throw new AccountException(400, "목록 조건을 확인해 주세요.");
    var rows = repository.list(owner, status, page);
    return new Page(rows.stream().limit(20).toList(), page, rows.size() > 20);
  }

  @Transactional
  public Entry create(String actor, UUID key, Create w) {
    UUID owner = repository.lockUser(actor);
    var prior = repository.find(key);
    if (prior.isPresent()) {
      var e = prior.get();
      if (!e.username().equals(actor)
          || !e.kind().equals(w.kind())
          || !e.title().equals(w.title().strip())
          || !e.body().equals(w.body().strip()))
        throw new AccountException(409, "같은 요청 키의 내용이 달라요.");
      return e;
    }
    if (repository.recent(owner, OffsetDateTime.now().minusDays(1)) >= 10)
      throw new AccountException(429, "문의는 24시간 동안 최대 10건 보낼 수 있어요.");
    try {
      repository.create(key, owner, w);
    } catch (org.springframework.dao.DuplicateKeyException e) {
      throw new AccountException(409, "요청 키를 확인해 주세요.");
    }
    return repository.find(key).orElseThrow();
  }

  @Transactional
  public Entry update(String actor, UUID id, Update w) {
    Entry before =
        repository.find(id).orElseThrow(() -> new AccountException(404, "문의를 찾을 수 없어요."));
    if (w.status().equals("RESOLVED") && w.reply().isBlank())
      throw new AccountException(400, "답변을 남긴 뒤 완료해 주세요.");
    if (repository.update(id, w) != 1)
      throw new AccountException(409, "다른 관리자가 수정했어요. 목록을 새로고침해 주세요.");
    var after = repository.find(id).orElseThrow();
    audit.record(
        actor,
        "SUPPORT_UPDATE",
        id.toString(),
        "문의 답변·처리 상태 변경",
        Map.of("status", before.status(), "revision", before.revision()),
        Map.of("status", after.status(), "revision", after.revision()));
    return after;
  }
}
