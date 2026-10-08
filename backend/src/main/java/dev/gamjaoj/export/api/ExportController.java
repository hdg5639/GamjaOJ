package dev.gamjaoj.export.api;

import static dev.gamjaoj.export.dto.ExportDtos.*;

import dev.gamjaoj.export.config.ExportSettings;
import dev.gamjaoj.export.infrastructure.ExportRemote;
import dev.gamjaoj.export.service.SolutionExports;
import dev.gamjaoj.shared.exception.AccountException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.security.Principal;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/integrations")
public class ExportController {
  final SolutionExports exports;

  public ExportController(SolutionExports exports) {
    this.exports = exports;
  }

  public @GetMapping Object connections(Principal user) {
    return exports.connections(user.getName());
  }

  public @PostMapping("/{provider}/connect") Object connect(
      Principal user, @PathVariable String provider) {
    return Map.of("url", exports.start(user.getName(), ExportSettings.provider(provider)));
  }

  public @GetMapping("/{provider}/callback") ResponseEntity<Void> callback(
      Principal user,
      @PathVariable String provider,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String code,
      @RequestParam(required = false) String error) {
    provider = ExportSettings.provider(provider);
    boolean success = false;
    try {
      if (error == null) {
        exports.callback(user.getName(), provider, state, code);
        success = true;
      }
    } catch (AccountException | ExportRemote.Failure ignored) {
    } // Never echo authorization codes, tokens or provider responses.
    return ResponseEntity.status(303)
        .location(
            URI.create(
                "/?settings=integrations&"
                    + (success ? "connected=" : "connectionError=")
                    + provider))
        .build();
  }

  public @GetMapping("/{provider}/targets") Object targets(
      Principal user,
      @PathVariable String provider,
      @RequestParam(defaultValue = "") String search) {
    return exports.targets(user.getName(), ExportSettings.provider(provider), search);
  }

  public @PutMapping("/{provider}/target") ResponseEntity<Void> target(
      Principal user, @PathVariable String provider, @Valid @RequestBody Destination request) {
    exports.save(
        user.getName(),
        ExportSettings.provider(provider),
        request.targetId(),
        request.branch(),
        request.prefix(),
        request.autoEnabled(),
        request.layout());
    return ResponseEntity.noContent().build();
  }

  public @PatchMapping("/{provider}/automatic") ResponseEntity<Void> automatic(
      Principal user, @PathVariable String provider, @Valid @RequestBody Automatic request) {
    exports.automatic(user.getName(), ExportSettings.provider(provider), request.enabled());
    return ResponseEntity.noContent().build();
  }

  public @DeleteMapping("/{provider}") ResponseEntity<Void> disconnect(
      Principal user, @PathVariable String provider) {
    exports.disconnect(user.getName(), ExportSettings.provider(provider));
    return ResponseEntity.noContent().build();
  }

  public @GetMapping("/deliveries") Object deliveries(
      Principal user, @RequestParam(required = false) UUID submissionId) {
    return exports.deliveries(user.getName(), submissionId);
  }

  public @PostMapping("/exports") Object request(
      Principal user, @Valid @RequestBody Request request) {
    return Map.of(
        "id",
        exports.request(
            user.getName(), ExportSettings.provider(request.provider()), request.submissionId()));
  }

  public @PostMapping("/deliveries/{id}/retry") ResponseEntity<Void> retry(
      Principal user, @PathVariable UUID id) {
    exports.retry(user.getName(), id);
    return ResponseEntity.noContent().build();
  }

  public @ExceptionHandler(ExportRemote.Failure.class) ResponseEntity<?> remote(
      ExportRemote.Failure failure) {
    String message =
        switch (failure.code) {
          case "RECONNECT_REQUIRED" -> "계정 연결이 만료됐어요. 다시 연결해 주세요.";
          case "PERMISSION_REQUIRED" -> "저장 위치의 쓰기 권한을 확인해 주세요.";
          case "PATH_CONFLICT" -> "저장 폴더에 다른 기록이 있어요. 다른 저장 폴더를 선택해 주세요.";
          case "NOTION_SCHEMA_MISMATCH" ->
              "표의 제목 열과 저장용 열 유형을 확인해 주세요. 언어·결과는 선택, 문제 링크는 URL, 통과 시각은 날짜, GamjaOJ ID는 텍스트여야 해요.";
          case "NOTION_TABLE_AMBIGUOUS" -> "같은 풀이 표나 행이 여러 개 있어요. Notion에서 중복 항목을 확인해 주세요.";
          case "TARGET_NOT_FOUND", "INVALID_TARGET" -> "저장 위치를 확인해 주세요.";
          case "RATE_LIMITED", "CONNECTION_BUSY" -> "잠시 후 다시 시도해 주세요.";
          default -> "외부 서비스에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.";
        };
    return ResponseEntity.status(failure.retryable ? 503 : 409).body(Map.of("message", message));
  }
}
