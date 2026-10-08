package dev.gamjaoj.judge.api;

import static dev.gamjaoj.judge.dto.RunDtos.*;

import dev.gamjaoj.judge.service.Submissions;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/runs")
public class RunController {
  private final Submissions submissions;

  public RunController(Submissions submissions) {
    this.submissions = submissions;
  }

  public @PostMapping ResponseEntity<Submissions.View> run(
      Principal principal,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody Request request) {
    return ResponseEntity.accepted().body(submissions.run(principal.getName(), key, request));
  }

  public @GetMapping List<Submissions.View> history(Principal principal) {
    return submissions.runs(principal.getName());
  }

  public @GetMapping("/{id}") Submissions.View detail(Principal principal, @PathVariable UUID id) {
    return submissions.runDetail(principal.getName(), id);
  }
}
