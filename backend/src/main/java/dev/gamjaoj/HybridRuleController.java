package dev.gamjaoj;

import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** Member rule registration: private until the owner shares it explicitly. */
@RestController
@RequestMapping("/api/rules")
class HybridRuleController {
    /** difficulty EASY|MEDIUM|HARD|EXPERT, style GENERAL|SIMULATION|COMMAND, category a diagnostic category id or AUTO.
     *  evaluationId/observationIndex target a habit from the member's own diagnosis; the server reads it, never the client. */
    record Request(String request,String difficulty,String style,String category,Boolean publish,Boolean shared,UUID evaluationId,Integer observationIndex) {}
    record Sharing(Boolean shared) {}
    record Owned(String id,String label,String category,String status,boolean shared) {}
    private final HybridRuleOnboarding onboarding;private final HybridRuleRegistry registry;private final Submissions submissions;
    private final DiagnosticEvaluations evaluations;private final DiagnosticProfiles profiles;
    HybridRuleController(HybridRuleOnboarding onboarding,HybridRuleRegistry registry,Submissions submissions,DiagnosticEvaluations evaluations,DiagnosticProfiles profiles){
        this.onboarding=onboarding;this.registry=registry;this.submissions=submissions;this.evaluations=evaluations;this.profiles=profiles;
    }
    private com.fasterxml.jackson.databind.JsonNode target(String user,UUID evaluation,Integer index) {
        if(evaluation==null)return null;
        var view=evaluations.detail(user,evaluation);var observations=view.interpretation()==null?null:view.interpretation().path("observations");
        if(index==null||observations==null||index<0||index>=observations.size())throw new AccountException(409,"겨냥할 진단 관찰을 찾을 수 없어요. 평가를 다시 확인해 주세요.");
        var o=observations.get(index);String quote=o.path("quote").asText();
        var t=JudgeJson.JSON.createObjectNode().put("pattern",o.path("pattern").asText(o.path("interpretation").asText()))
                .put("risk",o.path("risk").asText(o.path("recommendation").asText())).put("quote",quote.length()>400?quote.substring(0,400):quote);
        profiles.category(view.sessionId(),o.path("submissionId").asText()).ifPresent(c->t.put("category",c));
        return t;
    }
    @GetMapping("/onboarding/options")
    java.util.Map<String,Object> options(){return java.util.Map.of("enabled",onboarding.enabled());}
    @PostMapping("/onboarding")
    HybridRuleOnboarding.View create(Principal user,@RequestHeader("Idempotency-Key") UUID id,@RequestBody Request body) {
        if(body==null)return onboarding.create(user.getName(),id,(String)null);
        boolean legacy=body.difficulty()==null&&body.style()==null&&body.category()==null&&body.publish()==null&&body.evaluationId()==null;
        if(legacy)return onboarding.create(user.getName(),id,body.request());
        return onboarding.create(user.getName(),id,new HybridRuleOnboarding.Spec(body.request(),body.difficulty(),body.style(),body.category(),
                target(user.getName(),body.evaluationId(),body.observationIndex()),Boolean.TRUE.equals(body.publish()),Boolean.TRUE.equals(body.shared())));
    }
    @GetMapping("/onboarding")
    List<HybridRuleOnboarding.View> list(Principal user){return onboarding.list(user.getName());}
    @PostMapping("/onboarding/{id}/cancel")
    HybridRuleOnboarding.View cancel(Principal user,@PathVariable UUID id){return onboarding.cancel(user.getName(),id);}
    @GetMapping("/mine")
    List<Owned> mine(Principal user) {
        return registry.owned(submissions.owner(user.getName(),false)).stream()
                .map(v->new Owned(v.id(),v.label(),v.category(),v.status(),v.shared())).toList();
    }
    @PutMapping("/{id}/sharing")
    Owned share(Principal user,@PathVariable String id,@RequestBody Sharing body) {
        if(body==null||body.shared()==null)throw new AccountException(400,"공개 여부를 선택해 주세요.");
        var v=registry.share(submissions.owner(user.getName(),false),id,body.shared());
        return new Owned(v.id(),v.label(),v.category(),v.status(),v.shared());
    }
}
