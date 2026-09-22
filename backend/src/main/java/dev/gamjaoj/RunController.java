package dev.gamjaoj;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/runs")
public class RunController {
    private final Submissions submissions;
    public RunController(Submissions submissions) { this.submissions = submissions; }
    public record Request(@NotBlank @Size(max=80) String problemVersion,
                          @NotBlank @Size(max=65536) String source,
                          @NotNull @Size(max=16384) String input, UUID sessionId) {
        public Request(String problemVersion, String source, String input) { this(problemVersion,source,input,null); }
    }
    @PostMapping
    ResponseEntity<Submissions.View> run(Principal principal, @RequestHeader("Idempotency-Key") UUID key,
                                        @Valid @RequestBody Request request) {
        return ResponseEntity.accepted().body(submissions.run(principal.getName(), key, request));
    }
    @GetMapping
    List<Submissions.View> history(Principal principal) { return submissions.runs(principal.getName()); }
    @GetMapping("/{id}")
    Submissions.View detail(Principal principal, @PathVariable UUID id) { return submissions.runDetail(principal.getName(), id); }
}
