package dev.gamjaoj.learning.api;

import static dev.gamjaoj.learning.dto.LearningCurriculumDtos.*;

import dev.gamjaoj.diagnostic.service.DiagnosticPlans;
import dev.gamjaoj.learning.service.LearningCurricula;
import dev.gamjaoj.learning.service.LearningProblemPreparation;
import dev.gamjaoj.learning.service.LearningProblemSwitch;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/learning-curricula")
public class LearningCurriculumController {
  private final LearningCurricula curricula;
  private final LearningProblemPreparation preparation;
  private final LearningProblemSwitch switching;

  public LearningCurriculumController(
      LearningCurricula curricula,
      LearningProblemPreparation preparation,
      LearningProblemSwitch switching) {
    this.curricula = curricula;
    this.preparation = preparation;
    this.switching = switching;
  }

  public @PostMapping("/switch") DiagnosticPlans.Plan switchProblem(
      Principal user, @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody Switch body) {
    return switching.switchProblem(
        user.getName(),
        key,
        body.planId(),
        body.problemVersion(),
        body.activeSessionId(),
        body.note());
  }

  public @PostMapping("/{id}/end") LearningCurricula.Ended end(
      Principal user, @PathVariable UUID id, @Valid @RequestBody End body) {
    return curricula.end(user.getName(), id, body.note());
  }

  public @PostMapping("/plans/{id}/prepare") LearningProblemPreparation.State prepare(
      Principal user, @PathVariable UUID id) {
    return preparation.prepare(user.getName(), id, true);
  }

  public @GetMapping List<LearningCurricula.Track> overview(Principal user) {
    return curricula.overview(user.getName());
  }

  public @PostMapping LearningCurricula.Created create(
      Principal user, @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody Create body) {
    return curricula.create(user.getName(), key, body.evaluationId());
  }
}
