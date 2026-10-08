package dev.gamjaoj.learning.api;

import static dev.gamjaoj.learning.dto.PracticeFollowupDtos.*;

import dev.gamjaoj.learning.service.PracticeFollowups;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/practice-followups")
public class PracticeFollowupController {
  private final PracticeFollowups followups;

  public PracticeFollowupController(PracticeFollowups followups) {
    this.followups = followups;
  }

  private int round(Integer value) {
    return value == null ? 1 : value;
  }

  public @GetMapping("/options") PracticeFollowups.Options options(
      Principal user, @RequestParam UUID analysisId) {
    return followups.options(user.getName(), analysisId);
  }

  public @GetMapping List<PracticeFollowups.View> list(Principal user) {
    return followups.list(user.getName());
  }

  public @GetMapping("/{id}") PracticeFollowups.View detail(Principal user, @PathVariable UUID id) {
    return followups.detail(user.getName(), id);
  }

  public @PostMapping PracticeFollowups.View confirm(
      Principal user, @Valid @RequestBody Confirm request) {
    return followups.confirm(
        user.getName(), request.analysisId(), request.stepIndex(), request.focus());
  }

  public @PostMapping("/{id}/start") PracticeFollowups.View start(
      Principal user, @PathVariable UUID id, @Valid @RequestBody Start request) {
    return followups.start(user.getName(), id, request.problemVersion(), round(request.round()));
  }

  public @PostMapping("/{id}/generate") PracticeFollowups.View generate(
      Principal user, @PathVariable UUID id, @RequestBody(required = false) Round request) {
    return followups.generate(user.getName(), id, request == null ? 1 : round(request.round()));
  }

  public @PostMapping("/{id}/repeat") PracticeFollowups.View repeat(
      Principal user, @PathVariable UUID id, @Valid @RequestBody Round request) {
    return followups.repeat(user.getName(), id, request.round());
  }

  public @PostMapping("/{id}/reflect") PracticeFollowups.View reflect(
      Principal user, @PathVariable UUID id, @Valid @RequestBody Reflection request) {
    return followups.reflect(user.getName(), id, request.usedHelp(), round(request.round()));
  }
}
