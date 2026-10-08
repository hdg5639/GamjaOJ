package dev.gamjaoj.controller;

import static dev.gamjaoj.dto.DiagnosticDtos.*;

import dev.gamjaoj.service.diagnostic.DiagnosticEvaluations;
import dev.gamjaoj.service.diagnostic.DiagnosticProfiles;
import dev.gamjaoj.service.diagnostic.Diagnostics;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/diagnostics")
public class DiagnosticController {
  private final Diagnostics diagnostics;
  private final DiagnosticEvaluations evaluations;
  private final DiagnosticProfiles profiles;

  public DiagnosticController(
      Diagnostics diagnostics, DiagnosticEvaluations evaluations, DiagnosticProfiles profiles) {
    this.diagnostics = diagnostics;
    this.evaluations = evaluations;
    this.profiles = profiles;
  }

  public @GetMapping("/{id}/evaluations/{evaluation}/profile") DiagnosticProfiles.Profile profile(
      Principal user, @PathVariable UUID id, @PathVariable UUID evaluation) {
    return profiles.profile(user.getName(), id, evaluation);
  }

  public @PostMapping("/{id}/evaluations") DiagnosticEvaluations.View evaluate(
      Principal user, @PathVariable UUID id) {
    return evaluations.request(user.getName(), id);
  }

  public @GetMapping("/{id}/evaluations") List<DiagnosticEvaluations.View> evaluations(
      Principal user, @PathVariable UUID id) {
    return evaluations.list(user.getName(), id);
  }

  public @PostMapping("/{id}/evaluations/{evaluation}/corrections") DiagnosticEvaluations.View
      correct(
          Principal user,
          @PathVariable UUID id,
          @PathVariable UUID evaluation,
          @RequestHeader("Idempotency-Key") UUID key,
          @Valid @RequestBody Correction body) {
    return evaluations.correct(
        user.getName(), id, evaluation, key, body.observationIndex(), body.note());
  }

  public @PostMapping Diagnostics.View start(
      Principal user, @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody Start body) {
    return diagnostics.start(user.getName(), key, body.bankId(), body.categories());
  }

  public @PostMapping("/{id}/reassessments") Diagnostics.View reassess(
      Principal user,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody Start body) {
    return diagnostics.reassess(user.getName(), key, id, body.bankId(), body.categories());
  }

  public @GetMapping("/{id}/reassessments") List<Diagnostics.Bank> reassessmentOptions(
      Principal user, @PathVariable UUID id) {
    return diagnostics.reassessmentOptions(user.getName(), id);
  }

  public @PostMapping("/{id}/items/{item}/exposure") Diagnostics.View exposure(
      Principal user, @PathVariable UUID id, @PathVariable UUID item) {
    return diagnostics.reportExposure(user.getName(), id, item);
  }

  public @GetMapping("/banks") List<Diagnostics.Bank> banks() {
    return diagnostics.banks();
  }

  public @GetMapping List<Diagnostics.View> history(Principal user) {
    return diagnostics.history(user.getName());
  }

  public @GetMapping("/{id}") Diagnostics.View detail(Principal user, @PathVariable UUID id) {
    return diagnostics.detail(user.getName(), id);
  }

  public @PostMapping("/{id}/state") Diagnostics.View state(
      Principal user, @PathVariable UUID id, @Valid @RequestBody State body) {
    return diagnostics.state(user.getName(), id, body.status());
  }

  public @PostMapping("/{id}/finish") Diagnostics.View finish(
      Principal user, @PathVariable UUID id) {
    return diagnostics.finish(user.getName(), id);
  }

  public @PostMapping("/{id}/items/{item}/skip") Diagnostics.View skip(
      Principal user,
      @PathVariable UUID id,
      @PathVariable UUID item,
      @Valid @RequestBody(required = false) Skip body) {
    return diagnostics.skip(user.getName(), id, item, body == null ? null : body.reason());
  }
}
