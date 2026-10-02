package dev.gamjaoj;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/learning-curricula")
class LearningCurriculumController {
    private final LearningCurricula curricula;
    private final LearningProblemPreparation preparation;
    LearningCurriculumController(LearningCurricula curricula,LearningProblemPreparation preparation){this.curricula=curricula;this.preparation=preparation;}
    record Create(@NotNull UUID evaluationId) {}
    @PostMapping("/plans/{id}/prepare") LearningProblemPreparation.State prepare(Principal user,@PathVariable UUID id){return preparation.prepare(user.getName(),id,true);}
    @GetMapping List<LearningCurricula.Track> overview(Principal user){return curricula.overview(user.getName());}
    @PostMapping LearningCurricula.Created create(Principal user,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Create body){return curricula.create(user.getName(),key,body.evaluationId());}
}
