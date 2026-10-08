package dev.gamjaoj.controller;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamjaoj.service.generation.HybridAdmission;
import dev.gamjaoj.service.generation.HybridGeneration;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** Explicit fixed-profile admission; existing owner status/cancellation remains available. */
@RestController
public class HybridGenerationController {
  private final HybridGeneration jobs;
  private final HybridAdmission admission;

  public HybridGenerationController(HybridGeneration jobs, HybridAdmission admission) {
    this.jobs = jobs;
    this.admission = admission;
  }

  public @PostMapping("/api/generation/hybrid") HybridGeneration.Progress create(
      Principal user, @RequestHeader("Idempotency-Key") UUID id, @RequestBody JsonNode request) {
    return admission.create(user.getName(), id, request);
  }

  public @GetMapping("/api/generation/hybrid/options") HybridAdmission.Options options(
      Principal user) {
    return admission.options(user.getName());
  }

  public @GetMapping("/api/generation/hybrid") List<HybridGeneration.Progress> list(
      Principal user) {
    return admission.list(user.getName());
  }

  public @GetMapping("/api/generation/hybrid/{id}") HybridGeneration.Progress view(
      Principal user, @PathVariable UUID id) {
    return jobs.view(user.getName(), id);
  }

  public @PostMapping("/api/generation/hybrid/{id}/cancel") HybridGeneration.Progress cancel(
      Principal user, @PathVariable UUID id) {
    return jobs.cancel(user.getName(), id);
  }
}
