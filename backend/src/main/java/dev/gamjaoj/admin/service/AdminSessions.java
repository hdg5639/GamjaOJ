package dev.gamjaoj.admin.service;

import dev.gamjaoj.account.service.Accounts;
import dev.gamjaoj.shared.exception.AccountException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

/** Server-side step-up state; never expose credentials or session identifiers to clients/audits. */
@Service
public class AdminSessions {
  public static final String USER = "CONTROL_VERIFIED_USER",
      UNTIL = "CONTROL_VERIFIED_UNTIL",
      LAST = "CONTROL_VERIFIED_LAST";
  private final Accounts accounts;
  private final dev.gamjaoj.admin.repository.AdminSecurityRepository repository;
  private final AdminAudit audit;

  public AdminSessions(
      Accounts accounts,
      dev.gamjaoj.admin.repository.AdminSecurityRepository repository,
      AdminAudit audit) {
    this.accounts = accounts;
    this.repository = repository;
    this.audit = audit;
  }

  public boolean verified(HttpServletRequest request, String username) {
    var session = request.getSession(false);
    if (session == null) return false;
    long now = System.currentTimeMillis();
    if (!username.equals(session.getAttribute(USER))
        || !(session.getAttribute(UNTIL) instanceof Long until)
        || !(session.getAttribute(LAST) instanceof Long last)
        || now >= until
        || now - last > 300_000) return false;
    session.setAttribute(LAST, now);
    return true;
  }

  public long until(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    return session != null && session.getAttribute(UNTIL) instanceof Long value ? value : 0;
  }

  @org.springframework.transaction.annotation.Transactional(noRollbackFor = AccountException.class)
  public void verify(HttpServletRequest request, String username, String password) {
    long now = System.currentTimeMillis();
    var guard = repository.verification(username);
    if (now < guard.lockedUntil())
      throw new AccountException(429, "재확인 시도가 많아요. 10분 후 다시 시도해 주세요.");
    if (!accounts.verifyPassword(username, password)) {
      int count = guard.lockedUntil() > 0 ? 1 : guard.failures() + 1;
      repository.verification(username, count >= 5 ? 0 : count, count >= 5 ? now + 600_000 : 0);
      audit.record(
          username,
          "ADMIN_VERIFY_FAILED",
          username,
          "관리자 비밀번호 재확인 실패",
          java.util.Map.of(),
          java.util.Map.of("locked", count >= 5));
      throw new AccountException(403, "비밀번호를 확인해 주세요.");
    }
    repository.verification(username, 0, 0);
    HttpSession session = request.getSession();
    request.changeSessionId();
    session.setAttribute(USER, username);
    session.setAttribute(UNTIL, now + 900_000);
    session.setAttribute(LAST, now);
    audit.record(
        username,
        "ADMIN_VERIFY",
        username,
        "관리자 비밀번호 재확인",
        java.util.Map.of(),
        java.util.Map.of("expiresInSeconds", 900));
  }
}
