package dev.gamjaoj.controller;

import static dev.gamjaoj.dto.GenerationDtos.*;

import dev.gamjaoj.service.generation.GenerationChoices;
import dev.gamjaoj.service.generation.GenerationJobs;
import dev.gamjaoj.service.generation.GenerationRecommendations;
import dev.gamjaoj.service.generation.GenerationRequests;
import dev.gamjaoj.service.generation.GenerationSpecDrafts;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class GenerationController {
  private final GenerationRequests requests;
  private final GenerationJobs jobs;
  private final GenerationSpecDrafts drafts;
  private final GenerationRecommendations recommendations;

  public GenerationController(
      GenerationJobs jobs,
      GenerationRecommendations recommendations,
      GenerationSpecDrafts drafts,
      GenerationRequests requests) {
    this.requests = requests;
    this.drafts = drafts;
    this.jobs = jobs;
    this.recommendations = recommendations;
  }

  public @GetMapping("/api/generation/spec-drafts") List<GenerationSpecDrafts.View> drafts(
      Principal user) {
    return drafts.list(user.getName());
  }

  public @GetMapping("/api/generation/spec-drafts/{id}") GenerationSpecDrafts.View draft(
      Principal user, @PathVariable UUID id) {
    return drafts.view(user.getName(), id);
  }

  public @PostMapping("/api/generation/spec-drafts") GenerationSpecDrafts.View draft(
      Principal user,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody DraftRequest request) {
    return drafts.create(
        user.getName(), key, request.request(), Boolean.TRUE.equals(request.shared()));
  }

  public @PostMapping("/api/generation/spec-drafts/{id}/build") GenerationSpecDrafts.View build(
      Principal user, @PathVariable UUID id, @Valid @RequestBody BuildRequest request) {
    return drafts.build(user.getName(), id, request.specHash());
  }

  public @PostMapping("/api/generation/spec-drafts/{id}/review") GenerationSpecDrafts.View review(
      Principal user, @PathVariable UUID id, @Valid @RequestBody BuildRequest request) {
    return drafts.review(user.getName(), id, request.specHash());
  }

  public @PostMapping("/api/generation/spec-drafts/{id}/publish") GenerationSpecDrafts.View publish(
      Principal user, @PathVariable UUID id, @Valid @RequestBody BuildRequest request) {
    return drafts.publish(user.getName(), id, request.specHash());
  }

  public @GetMapping("/api/generation/options") List<GenerationChoices.Category> options() {
    return GenerationChoices.catalog();
  }

  public @GetMapping("/api/generation/selection") GenerationChoices.Selection selection(
      @RequestParam String category, @RequestParam List<String> tags) {
    return GenerationChoices.resolve(category, tags);
  }

  public @GetMapping("/api/generation/recommendations") GenerationRecommendations.Result
      recommendations(
          Principal user, @RequestParam String category, @RequestParam List<String> tags) {
    return recommendations.recommend(user.getName(), category, tags);
  }

  public @PostMapping("/api/generation") GenerationJobs.View create(
      Principal user,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody Create request) {
    return requests.create(user.getName(), key, request);
  }

  public @GetMapping("/api/generation/learning-context") List<GenerationJobs.LearningOption>
      learning(Principal user, @RequestParam(defaultValue = "sequence-sum-v1") String template) {
    return jobs.learningOptions(user.getName(), template);
  }

  public @GetMapping("/api/generation") List<GenerationJobs.View> list(Principal user) {
    return jobs.list(user.getName());
  }

  public @PostMapping("/api/generation/{id}/retry-theme") GenerationJobs.View retryTheme(
      Principal user, @PathVariable UUID id) {
    return jobs.retryTheme(user.getName(), id);
  }

  public @PostMapping("/api/generation/{id}/review") GenerationJobs.View review(
      Principal user, @PathVariable UUID id, @Valid @RequestBody Review request) {
    return jobs.review(user.getName(), id, request.artifactHash(), request.approve());
  }

  public @PostMapping("/internal/generation/{id}/repair-input-layout") GenerationJobs.View
      repairInputLayout(@PathVariable UUID id, @Valid @RequestBody InputLayoutRepair request) {
    return jobs.repairInputLayout(id, request.packageHash());
  }

  public @PostMapping("/internal/generation/claim") ResponseEntity<GenerationJobs.Assignment>
      claim() {
    var work = jobs.claim();
    return work == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(work);
  }

  public @PostMapping("/internal/generation/{id}/result") ResponseEntity<Void> complete(
      @PathVariable UUID id, @Valid @RequestBody Completion request) {
    jobs.complete(
        id,
        request.token(),
        request.artifacts(),
        request.oracle(),
        request.usage(),
        request.error());
    return ResponseEntity.noContent().build();
  }
}
