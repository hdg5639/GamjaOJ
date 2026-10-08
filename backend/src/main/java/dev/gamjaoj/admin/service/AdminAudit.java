package dev.gamjaoj.admin.service;

import dev.gamjaoj.admin.repository.AdminAuditRepository;
import dev.gamjaoj.shared.support.JudgeJson;
import org.springframework.stereotype.Service;

@Service
public class AdminAudit {
  private final AdminAuditRepository repository;

  public AdminAudit(AdminAuditRepository repository) {
    this.repository = repository;
  }

  public void record(
      String actor, String action, String target, String reason, Object before, Object after) {
    repository.record(
        actor,
        action,
        target,
        reason,
        JudgeJson.canonical(JudgeJson.JSON.valueToTree(before)),
        JudgeJson.canonical(JudgeJson.JSON.valueToTree(after)));
  }
}
