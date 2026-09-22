package dev.gamjaoj;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/training-sessions")
public class TrainingSessionController {
    private final TrainingSessions sessions;
    public TrainingSessionController(TrainingSessions sessions) { this.sessions=sessions; }
    public record Start(@NotBlank @Size(max=80) String problemVersion, @NotNull @Size(max=120) String goal) {}
    public record End(@NotNull @Size(max=2000) String note) {}
    @PostMapping
    TrainingSessions.View start(Principal user,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Start request) {
        return sessions.start(user.getName(),key,request);
    }
    @GetMapping
    List<TrainingSessions.View> history(Principal user) { return sessions.history(user.getName()); }
    @GetMapping("/{id}")
    TrainingSessions.Detail detail(Principal user,@PathVariable UUID id) { return sessions.detail(user.getName(),id); }
    @PostMapping("/{id}/end")
    TrainingSessions.View end(Principal user,@PathVariable UUID id,@Valid @RequestBody End request) {
        return sessions.end(user.getName(),id,request.note());
    }
}
