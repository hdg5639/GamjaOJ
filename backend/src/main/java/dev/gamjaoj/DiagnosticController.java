package dev.gamjaoj;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/diagnostics")
class DiagnosticController {
    private final Diagnostics diagnostics;
    private final DiagnosticEvaluations evaluations;
    private final DiagnosticProfiles profiles;
    DiagnosticController(Diagnostics diagnostics,DiagnosticEvaluations evaluations,DiagnosticProfiles profiles) { this.diagnostics=diagnostics;this.evaluations=evaluations;this.profiles=profiles; }
    @GetMapping("/{id}/evaluations/{evaluation}/profile") DiagnosticProfiles.Profile profile(Principal user,@PathVariable UUID id,@PathVariable UUID evaluation) {
        return profiles.profile(user.getName(),id,evaluation);
    }
    @PostMapping("/{id}/evaluations") DiagnosticEvaluations.View evaluate(Principal user,@PathVariable UUID id) {
        return evaluations.request(user.getName(),id);
    }
    @GetMapping("/{id}/evaluations") List<DiagnosticEvaluations.View> evaluations(Principal user,@PathVariable UUID id) {
        return evaluations.list(user.getName(),id);
    }
    record Correction(@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) Integer observationIndex,@NotBlank @Size(max=1000) String note) {}
    @PostMapping("/{id}/evaluations/{evaluation}/corrections") DiagnosticEvaluations.View correct(Principal user,
            @PathVariable UUID id,@PathVariable UUID evaluation,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Correction body) {
        return evaluations.correct(user.getName(),id,evaluation,key,body.observationIndex(),body.note());
    }
    record Start(@NotBlank @Size(max=80) String bankId, @Size(max=20) List<@NotBlank @Size(max=80) String> categories) {}
    record State(@NotBlank String status) {}
    @PostMapping Diagnostics.View start(Principal user,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Start body) {
        return diagnostics.start(user.getName(),key,body.bankId(),body.categories());
    }
    @PostMapping("/{id}/reassessments") Diagnostics.View reassess(Principal user,@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Start body) {
        return diagnostics.reassess(user.getName(),key,id,body.bankId(),body.categories());
    }
    @GetMapping("/{id}/reassessments") List<Diagnostics.Bank> reassessmentOptions(Principal user,@PathVariable UUID id) {
        return diagnostics.reassessmentOptions(user.getName(),id);
    }
    @PostMapping("/{id}/items/{item}/exposure") Diagnostics.View exposure(Principal user,@PathVariable UUID id,@PathVariable UUID item) {
        return diagnostics.reportExposure(user.getName(),id,item);
    }
    @GetMapping("/banks") List<Diagnostics.Bank> banks() { return diagnostics.banks(); }
    @GetMapping List<Diagnostics.View> history(Principal user) { return diagnostics.history(user.getName()); }
    @GetMapping("/{id}") Diagnostics.View detail(Principal user,@PathVariable UUID id) { return diagnostics.detail(user.getName(),id); }
    @PostMapping("/{id}/state") Diagnostics.View state(Principal user,@PathVariable UUID id,@Valid @RequestBody State body) {
        return diagnostics.state(user.getName(),id,body.status());
    }
    @PostMapping("/{id}/finish") Diagnostics.View finish(Principal user,@PathVariable UUID id) {
        return diagnostics.finish(user.getName(),id);
    }
    @PostMapping("/{id}/items/{item}/skip") Diagnostics.View skip(Principal user,@PathVariable UUID id,@PathVariable UUID item) {
        return diagnostics.skip(user.getName(),id,item);
    }
}
