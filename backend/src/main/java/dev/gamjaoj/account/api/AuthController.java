package dev.gamjaoj.account.api;

import static dev.gamjaoj.account.dto.AuthDtos.*;

import dev.gamjaoj.account.service.AccountDeletion;
import dev.gamjaoj.account.service.Accounts;
import dev.gamjaoj.shared.exception.AccountException;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestController
public class AuthController {
  private final Accounts accounts;
  private final AccountDeletion deletion;

  public AuthController(Accounts accounts, AccountDeletion deletion) {
    this.accounts = accounts;
    this.deletion = deletion;
  }

  // Invitation is no longer required; older clients may still send it.

  @GetMapping("/api/auth/csrf")
  public Map<String, String> csrf(CsrfToken token) {
    return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
  }

  @PostMapping("/api/auth/signup")
  public ResponseEntity<Void> signup(@Valid @RequestBody Signup request) {
    accounts.register(request);
    return ResponseEntity.status(201).build();
  }

  @GetMapping("/api/me")
  public Accounts.Profile me(Principal principal) {
    return accounts.profile(principal.getName());
  }

  @PatchMapping("/api/me")
  public Accounts.Profile preferences(
      Principal principal, @Valid @RequestBody Preferences request) {
    return accounts.update(principal.getName(), request);
  }

  /** Hard deletion; the caller's session ends with it. */
  @PostMapping("/api/me/delete")
  public AccountDeletion.Result delete(
      Principal principal,
      @Valid @RequestBody Deletion request,
      jakarta.servlet.http.HttpServletRequest http) {
    var result = deletion.delete(principal.getName(), request.password(), request.confirmation());
    var session = http.getSession(false);
    if (session != null) session.invalidate();
    org.springframework.security.core.context.SecurityContextHolder.clearContext();
    return result;
  }

  @GetMapping("/healthz")
  public Map<String, String> health() {
    return Map.of("status", "ok");
  }

  public @ExceptionHandler(AccountException.class) ResponseEntity<?> accountError(
      AccountException error) {
    return ResponseEntity.status(error.status).body(Map.of("message", error.getMessage()));
  }

  public @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class
  }) ResponseEntity<?> invalidInput(Exception ignored) {
    return ResponseEntity.badRequest().body(Map.of("message", "입력 형식과 길이를 확인해 주세요."));
  }
}
