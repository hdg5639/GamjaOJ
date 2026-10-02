package dev.gamjaoj;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/diagnostic-plans")
class DiagnosticPlanController {
    private final DiagnosticPlans plans;
    DiagnosticPlanController(DiagnosticPlans plans){this.plans=plans;}
    record Confirm(@NotNull UUID evaluationId,@NotNull @Min(0) Integer observationIndex,@NotBlank @Size(min=64,max=64) String reviewHash,@NotBlank @Size(max=120) String goal,@Pattern(regexp="CODE_OBSERVATION|SELF_REPORT") String sourceKind) {}
    record Reflection(@NotNull Boolean usedHelp) {}
    record Generate(@Size(max=80) String ruleVersionId) {}
    @PostMapping("/{id}/generate") DiagnosticPlans.Plan generate(Principal user,@PathVariable UUID id,@Valid @RequestBody(required=false) Generate body){return plans.generate(user.getName(),id,body==null?null:body.ruleVersionId());}
    @PostMapping("/{id}/reflect") DiagnosticPlans.Plan reflect(Principal user,@PathVariable UUID id,@Valid @RequestBody Reflection body){return plans.reflect(user.getName(),id,body.usedHelp());}
    record NextRound(@NotBlank @Size(min=64,max=64) String reviewHash) {}
    @PostMapping("/{id}/next-round") DiagnosticPlans.Plan nextRound(Principal user,@PathVariable UUID id,@Valid @RequestBody NextRound body){return plans.nextRound(user.getName(),id,body.reviewHash());}
    record Order(@NotNull UUID evaluationId,@NotNull @Size(max=500) List<@NotNull UUID> previous,@NotNull @Size(max=500) List<@NotNull UUID> desired) {}
    @PostMapping("/order") List<DiagnosticPlans.Plan> reorder(Principal user,@Valid @RequestBody Order body){return plans.reorder(user.getName(),body.evaluationId(),body.previous(),body.desired());}
    @GetMapping("/trained-scope") List<String> trainedScope(Principal user,@RequestParam UUID sourceSessionId){return plans.trainedScope(user.getName(),sourceSessionId);}
    record Start(@NotBlank @Size(max=80) String problemVersion) {}
    @GetMapping("/options") DiagnosticPlans.Options options(Principal user,@RequestParam UUID evaluationId,@RequestParam int observationIndex,@RequestParam(defaultValue="CODE_OBSERVATION") String sourceKind){return plans.options(user.getName(),evaluationId,observationIndex,sourceKind);}
    @GetMapping List<DiagnosticPlans.Plan> list(Principal user,@RequestParam UUID evaluationId){return plans.list(user.getName(),evaluationId);}
    @PostMapping DiagnosticPlans.Plan confirm(Principal user,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Confirm body){return plans.confirm(user.getName(),key,body.evaluationId(),body.observationIndex(),body.reviewHash(),body.goal(),body.sourceKind()==null?"CODE_OBSERVATION":body.sourceKind());}
    @PostMapping("/{id}/start") DiagnosticPlans.Plan start(Principal user,@PathVariable UUID id,@Valid @RequestBody Start body){return plans.start(user.getName(),id,body.problemVersion());}
}
