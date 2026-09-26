package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class GenerationController {
    private final GenerationJobs jobs;
    private final GenerationSpecDrafts drafts;
    private final GenerationRecommendations recommendations;
    public GenerationController(GenerationJobs jobs,GenerationRecommendations recommendations,GenerationSpecDrafts drafts) { this.drafts=drafts; this.jobs=jobs; this.recommendations=recommendations; }
    public record Create(@Size(max=80) String template,@Size(max=200) String focus,UUID sourceAnalysisId,
                         @Size(max=40) String category,@Size(max=6) List<@NotBlank @Size(max=40) String> tags,Boolean shared) {
        public Create(String template,String focus,UUID sourceAnalysisId,String category,List<String> tags){this(template,focus,sourceAnalysisId,category,tags,false);}
    }
    public record DraftRequest(@NotBlank @Size(max=2000) String request,Boolean shared) {}
    @GetMapping("/api/generation/spec-drafts") List<GenerationSpecDrafts.View> drafts(Principal user){return drafts.list(user.getName());}
    @GetMapping("/api/generation/spec-drafts/{id}") GenerationSpecDrafts.View draft(Principal user,@PathVariable UUID id){return drafts.view(user.getName(),id);}
    @PostMapping("/api/generation/spec-drafts") GenerationSpecDrafts.View draft(Principal user,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody DraftRequest request){return drafts.create(user.getName(),key,request.request(),Boolean.TRUE.equals(request.shared()));}
    public record BuildRequest(@NotBlank String specHash) {}
    @PostMapping("/api/generation/spec-drafts/{id}/build") GenerationSpecDrafts.View build(Principal user,@PathVariable UUID id,@Valid @RequestBody BuildRequest request){return drafts.build(user.getName(),id,request.specHash());}
    @PostMapping("/api/generation/spec-drafts/{id}/review") GenerationSpecDrafts.View review(Principal user,@PathVariable UUID id,@Valid @RequestBody BuildRequest request){return drafts.review(user.getName(),id,request.specHash());}
    @PostMapping("/api/generation/spec-drafts/{id}/publish") GenerationSpecDrafts.View publish(Principal user,@PathVariable UUID id,@Valid @RequestBody BuildRequest request){return drafts.publish(user.getName(),id,request.specHash());}
    public record Review(@NotNull String artifactHash,boolean approve) {}
    public record Completion(@NotNull UUID token,JsonNode artifacts,JsonNode oracle,JsonNode usage,@Size(max=80) String error) {}
    @GetMapping("/api/generation/options") List<GenerationChoices.Category> options() {return GenerationChoices.catalog();}
    @GetMapping("/api/generation/selection") GenerationChoices.Selection selection(@RequestParam String category,@RequestParam List<String> tags) {return GenerationChoices.resolve(category,tags);}
    @GetMapping("/api/generation/recommendations") GenerationRecommendations.Result recommendations(Principal user,@RequestParam String category,@RequestParam List<String> tags) {return recommendations.recommend(user.getName(),category,tags);}
    @PostMapping("/api/generation") GenerationJobs.View create(Principal user,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Create request) {
        if(request.category()!=null||request.tags()!=null) {
            if(request.template()!=null||request.focus()!=null)throw new AccountException(400,"카테고리·태그와 이전 유형 조건을 함께 보낼 수 없어요.");
            var selection=GenerationChoices.resolve(request.category(),request.tags());
            return jobs.create(user.getName(),key,selection.template(),selection.focus(),request.sourceAnalysisId(),Boolean.TRUE.equals(request.shared()));
        }
        return jobs.create(user.getName(),key,request.template(),request.focus()==null?"basics":request.focus(),request.sourceAnalysisId(),Boolean.TRUE.equals(request.shared()));
    }
    @GetMapping("/api/generation/learning-context") List<GenerationJobs.LearningOption> learning(Principal user,@RequestParam(defaultValue="sequence-sum-v1") String template) { return jobs.learningOptions(user.getName(),template); }
    @GetMapping("/api/generation") List<GenerationJobs.View> list(Principal user) { return jobs.list(user.getName()); }
    @PostMapping("/api/generation/{id}/retry-theme") GenerationJobs.View retryTheme(Principal user,@PathVariable UUID id) {return jobs.retryTheme(user.getName(),id);}
    @PostMapping("/api/generation/{id}/review") GenerationJobs.View review(Principal user,@PathVariable UUID id,@Valid @RequestBody Review request) { return jobs.review(user.getName(),id,request.artifactHash(),request.approve()); }
    public record InputLayoutRepair(@NotBlank String packageHash) {}
    @PostMapping("/internal/generation/{id}/repair-input-layout") GenerationJobs.View repairInputLayout(@PathVariable UUID id,@Valid @RequestBody InputLayoutRepair request) {
        return jobs.repairInputLayout(id,request.packageHash());
    }
    @PostMapping("/internal/generation/claim") ResponseEntity<GenerationJobs.Assignment> claim() { var work=jobs.claim();return work==null?ResponseEntity.noContent().build():ResponseEntity.ok(work); }
    @PostMapping("/internal/generation/{id}/result") ResponseEntity<Void> complete(@PathVariable UUID id,@Valid @RequestBody Completion request) {
        jobs.complete(id,request.token(),request.artifacts(),request.oracle(),request.usage(),request.error());return ResponseEntity.noContent().build();
    }
}
