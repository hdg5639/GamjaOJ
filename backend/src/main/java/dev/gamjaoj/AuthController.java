package dev.gamjaoj;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestController
public class AuthController {
    private final Accounts accounts;
    public AuthController(Accounts accounts) { this.accounts = accounts; }

    public record Signup(
            @NotBlank @Pattern(regexp="[a-z0-9_]{3,24}") String username,
            @NotBlank @Size(min=8, max=72) String password,
            @NotBlank @Size(max=24) String nickname,
            @NotBlank @Size(max=128) String inviteCode) {}
    public record Preferences(@NotBlank @Size(max=24) String nickname,
                              @jakarta.validation.constraints.NotNull @Size(max=120) String trainingGoal) {}

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
    public Accounts.Profile me(Principal principal) { return accounts.profile(principal.getName()); }

    @PatchMapping("/api/me")
    public Accounts.Profile preferences(Principal principal, @Valid @RequestBody Preferences request) {
        return accounts.update(principal.getName(), request);
    }

    @GetMapping("/healthz")
    public Map<String, String> health() { return Map.of("status", "ok"); }

    @ExceptionHandler(AccountException.class)
    ResponseEntity<?> accountError(AccountException error) {
        return ResponseEntity.status(error.status).body(Map.of("message", error.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<?> invalidInput(Exception ignored) {
        return ResponseEntity.badRequest().body(Map.of("message", "입력 형식과 길이를 확인해 주세요."));
    }
}
