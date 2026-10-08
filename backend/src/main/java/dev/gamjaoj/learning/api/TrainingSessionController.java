package dev.gamjaoj.learning.api;

import static dev.gamjaoj.learning.dto.TrainingSessionDtos.*;

import dev.gamjaoj.learning.service.TrainingSessions;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/training-sessions")
public class TrainingSessionController {
  private final TrainingSessions sessions;

  public TrainingSessionController(TrainingSessions sessions) {
    this.sessions = sessions;
  }

  public @PostMapping TrainingSessions.View start(
      Principal user,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody Start request) {
    return sessions.start(user.getName(), key, request);
  }

  public @GetMapping List<TrainingSessions.View> history(Principal user) {
    return sessions.history(user.getName());
  }

  public @GetMapping("/{id}") TrainingSessions.Detail detail(
      Principal user, @PathVariable UUID id) {
    return sessions.detail(user.getName(), id);
  }

  public @PostMapping("/{id}/end") TrainingSessions.View end(
      Principal user, @PathVariable UUID id, @Valid @RequestBody End request) {
    return sessions.end(user.getName(), id, request.note());
  }
}
