package dev.gamjaoj;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@Service
class ProblemReview {
    private final VerificationLedger ledger; private final JdbcClient jdbc;
    private final Submissions submissions;
    ProblemReview(JdbcClient jdbc, Submissions submissions,VerificationLedger ledger) { this.jdbc=jdbc;this.submissions=submissions;this.ledger=ledger; }
    record State(boolean held, String reason) {}
    @Transactional
    State hold(String username, String version, String reason) {
        // Same account lock as submission/training admission; budget lock fences AI claims.
        UUID owner=submissions.owner(username,true);
        jdbc.sql("SELECT id FROM ai_budget_lock WHERE id=1 FOR UPDATE").query(Integer.class).single();
        var state=jdbc.sql("SELECT review_hold,review_reason FROM problem_version p WHERE p.id=? AND p.owner_id=? AND p.ready=true AND (EXISTS (SELECT 1 FROM generation_spec_draft d WHERE d.owner_id=p.owner_id AND d.status='PUBLISHED' AND p.id=CONCAT('experimental-check-',CAST(d.id AS VARCHAR(36)))) OR EXISTS (SELECT 1 FROM generation_job g WHERE g.owner_id=p.owner_id AND g.status='READY' AND p.id=CONCAT(CONCAT(CONCAT('generated-',CAST(g.id AS VARCHAR(36))),'-r'),CAST(g.revision AS VARCHAR(10))))) FOR UPDATE")
                .param(version).param(owner).query((r,n)->new State(r.getBoolean(1),r.getString(2))).optional()
                .orElseThrow(()->new AccountException(404,"본인이 게시한 생성 문제를 찾을 수 없어요."));
        if(state.held())return state; // Lost responses can be retried without changing the original reason.
        if(reason==null||reason.isBlank()||reason.length()>500)throw new AccountException(400,"검토 사유를 1~500자로 입력해 주세요.");
        jdbc.sql("UPDATE problem_version SET review_hold=true,review_reason=?,review_held_at=CURRENT_TIMESTAMP WHERE id=?")
                .param(reason.trim()).param(version).update();
        var root=jdbc.sql("SELECT id FROM generation_job WHERE owner_id=? AND ?=CONCAT(CONCAT(CONCAT('generated-',CAST(id AS VARCHAR(36))),'-r'),CAST(revision AS VARCHAR(10)))")
                .param(owner).param(version).query(UUID.class).optional();
        root.ifPresent(id->ledger.revokeTree(owner,id,reason.trim()));
        return new State(true,reason.trim());
    }
}

@RestController
class ProblemReviewController {
    private final ProblemReview review;
    ProblemReviewController(ProblemReview review){this.review=review;}
    record Request(@NotBlank @Size(max=500) String reason) {}
    @PostMapping("/api/problems/{version}/review-hold")
    ProblemReview.State hold(Principal user,@PathVariable String version,@Valid @RequestBody Request request){
        return review.hold(user.getName(),version,request.reason());
    }
}
