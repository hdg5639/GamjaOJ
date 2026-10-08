package dev.gamjaoj.support.api;

import dev.gamjaoj.support.dto.SupportDtos.*;
import dev.gamjaoj.support.service.SupportRequests;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
public class SupportController {
  private final SupportRequests requests;

  public SupportController(SupportRequests requests) {
    this.requests = requests;
  }

  @GetMapping("/api/support")
  public Page mine(Principal actor, @RequestParam(defaultValue = "0") int page) {
    return requests.list(actor.getName(), "", page);
  }

  @PostMapping("/api/support")
  public Entry create(
      Principal actor, @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody Create w) {
    return requests.create(actor.getName(), key, w);
  }

  @GetMapping("/api/admin/support")
  public Page admin(
      @RequestParam(defaultValue = "") String status, @RequestParam(defaultValue = "0") int page) {
    return requests.list("", status, page);
  }

  @PutMapping("/api/admin/support/{id}")
  public Entry update(Principal actor, @PathVariable UUID id, @Valid @RequestBody Update w) {
    return requests.update(actor.getName(), id, w);
  }
}
