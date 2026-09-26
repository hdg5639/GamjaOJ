package dev.gamjaoj;

import java.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** Explicit fixed-profile admission; existing owner status/cancellation remains available. */
@RestController
class HybridGenerationController {
    private final HybridGeneration jobs;private final HybridAdmission admission;
    HybridGenerationController(HybridGeneration jobs,HybridAdmission admission){this.jobs=jobs;this.admission=admission;}
    @PostMapping("/api/generation/hybrid")
    HybridGeneration.Progress create(Principal user,@RequestHeader("Idempotency-Key") UUID id,@RequestBody JsonNode request){return admission.create(user.getName(),id,request);}
    @GetMapping("/api/generation/hybrid/options")
    HybridAdmission.Options options(Principal user){return admission.options(user.getName());}
    @GetMapping("/api/generation/hybrid")
    List<HybridGeneration.Progress> list(Principal user){return admission.list(user.getName());}
    @GetMapping("/api/generation/hybrid/{id}")
    HybridGeneration.Progress view(Principal user,@PathVariable UUID id){return jobs.view(user.getName(),id);}
    @PostMapping("/api/generation/hybrid/{id}/cancel")
    HybridGeneration.Progress cancel(Principal user,@PathVariable UUID id){return jobs.cancel(user.getName(),id);}
}
