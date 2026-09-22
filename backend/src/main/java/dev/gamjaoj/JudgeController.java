package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/judge")
public class JudgeController {
    private final JudgeQueue queue;
    public JudgeController(JudgeQueue queue) { this.queue = queue; }
    public record Claim(@NotNull UUID workerId) {}
    public record Heartbeat(@NotNull UUID token) {}
    public record Completion(@NotNull UUID token, @NotNull JsonNode report) {}
    @PostMapping("/claim")
    ResponseEntity<JudgeQueue.Assignment> claim(@Valid @RequestBody Claim request) {
        return queue.claim(request.workerId()).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }
    @PostMapping("/{id}/heartbeat")
    ResponseEntity<Void> heartbeat(@PathVariable UUID id, @Valid @RequestBody Heartbeat request) {
        queue.heartbeat(id, request.token()); return ResponseEntity.noContent().build();
    }
    @PostMapping("/{id}/result")
    ResponseEntity<Void> complete(@PathVariable UUID id, @Valid @RequestBody Completion request) {
        queue.complete(id, request.token(), request.report()); return ResponseEntity.noContent().build();
    }
}
