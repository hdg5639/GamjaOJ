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
    LearningCurriculumController(LearningCurricula curricula){this.curricula=curricula;}
    record Create(@NotNull UUID evaluationId) {}
    @GetMapping List<LearningCurricula.Track> overview(Principal user){return curricula.overview(user.getName());}
    @PostMapping LearningCurricula.Created create(Principal user,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Create body){return curricula.create(user.getName(),key,body.evaluationId());}
}
