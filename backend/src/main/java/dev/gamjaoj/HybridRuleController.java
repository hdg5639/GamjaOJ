package dev.gamjaoj;

import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** Member rule registration: private until the owner shares it explicitly. */
@RestController
@RequestMapping("/api/rules")
class HybridRuleController {
    record Request(String request) {}
    record Sharing(Boolean shared) {}
    record Owned(String id,String label,String category,String status,boolean shared) {}
    private final HybridRuleOnboarding onboarding;private final HybridRuleRegistry registry;private final Submissions submissions;
    HybridRuleController(HybridRuleOnboarding onboarding,HybridRuleRegistry registry,Submissions submissions){this.onboarding=onboarding;this.registry=registry;this.submissions=submissions;}
    @GetMapping("/onboarding/options")
    java.util.Map<String,Object> options(){return java.util.Map.of("enabled",onboarding.enabled());}
    @PostMapping("/onboarding")
    HybridRuleOnboarding.View create(Principal user,@RequestHeader("Idempotency-Key") UUID id,@RequestBody Request body){return onboarding.create(user.getName(),id,body==null?null:body.request());}
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
