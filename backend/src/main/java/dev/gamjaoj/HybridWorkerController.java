package dev.gamjaoj;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Protected by the existing stateless GENERATOR bearer-token chain. */
@RestController
class HybridWorkerController {
    private final HybridExecution execution;
    HybridWorkerController(HybridExecution execution){this.execution=execution;}
    @PostMapping("/internal/generation/hybrid/claim")
    ResponseEntity<HybridModels.CodexRequest> claim() {
        var work=execution.claimCodex();return work==null?ResponseEntity.noContent().build():ResponseEntity.ok(work);
    }
    @PostMapping("/internal/generation/hybrid/result")
    ResponseEntity<Void> complete(@RequestBody HybridGeneration.Completion result) {
        execution.completeCodex(result);return ResponseEntity.noContent().build();
    }
}
