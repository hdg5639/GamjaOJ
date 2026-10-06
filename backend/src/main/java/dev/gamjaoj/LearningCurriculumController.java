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
    private final LearningProblemSwitch switching;
    LearningCurriculumController(LearningCurricula curricula,LearningProblemPreparation preparation,LearningProblemSwitch switching){this.curricula=curricula;this.preparation=preparation;this.switching=switching;}
    record Switch(@NotNull UUID planId,@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=80) String problemVersion,UUID activeSessionId,@NotNull @jakarta.validation.constraints.Size(max=2000) String note) {}
    @PostMapping("/switch") DiagnosticPlans.Plan switchProblem(Principal user,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Switch body){return switching.switchProblem(user.getName(),key,body.planId(),body.problemVersion(),body.activeSessionId(),body.note());}
    record End(@NotNull @jakarta.validation.constraints.Size(max=2000) String note) {}
    @PostMapping("/{id}/end") LearningCurricula.Ended end(Principal user,@PathVariable UUID id,@Valid @RequestBody End body){return curricula.end(user.getName(),id,body.note());}
    record Create(@NotNull UUID evaluationId) {}
    @PostMapping("/plans/{id}/prepare") LearningProblemPreparation.State prepare(Principal user,@PathVariable UUID id){return preparation.prepare(user.getName(),id,true);}
    @GetMapping List<LearningCurricula.Track> overview(Principal user){return curricula.overview(user.getName());}
    @PostMapping LearningCurricula.Created create(Principal user,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Create body){return curricula.create(user.getName(),key,body.evaluationId());}
}
