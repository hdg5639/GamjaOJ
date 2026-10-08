package dev.gamjaoj.controller;

import static dev.gamjaoj.dto.SubmissionDtos.*;

import dev.gamjaoj.service.judge.Submissions;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class SubmissionController {
  private final Submissions submissions;

  public SubmissionController(Submissions submissions) {
    this.submissions = submissions;
  }

  public @GetMapping("/api/problems") List<Submissions.Problem> problems(Principal principal) {
    return submissions.problems(principal.getName());
  }

  public @PostMapping("/api/submissions") ResponseEntity<Submissions.View> submit(
      Principal principal,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody Request request) {
    return ResponseEntity.accepted().body(submissions.submit(principal.getName(), key, request));
  }

  public @GetMapping("/api/submissions") List<Submissions.View> history(
      Principal principal,
      @RequestParam(required = false) String problemVersion,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return submissions.history(principal.getName(), problemVersion, page, size);
  }

  public @GetMapping("/api/submissions/{id}") Submissions.View detail(
      Principal principal, @PathVariable UUID id) {
    return submissions.detail(principal.getName(), id);
  }
}
