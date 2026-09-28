package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Final content review consumes a reserved call; publication consumes exactly its frozen input. */
@Service
class HybridPublication {
    private final JdbcClient jdbc;private final HybridRunnerChecks checks;private final ApplicationEventPublisher events;
    private final HybridRuleRegistry registry;
    HybridPublication(JdbcClient jdbc,HybridRunnerChecks checks,ApplicationEventPublisher events,HybridRuleRegistry registry){this.jdbc=jdbc;this.checks=checks;this.events=events;this.registry=registry;}
    /** A reused implementation must still be qualified at the publication fence, not only at admission. */
    private void requireReferenceQualified(UUID id,int revision) {
        var completion=jdbc.sql("SELECT completion_json FROM hybrid_branch WHERE generation_id=? AND revision=? AND role='CORE' AND status='SUCCEEDED'")
                .param(id).param(revision).query(String.class).optional();
        if(completion.isEmpty()||!HybridRuleRegistry.REUSED_EXECUTOR.equals(JudgeJson.parse(completion.get()).path("usage").path("executor").asText()))return;
        var artifact=jdbc.sql("SELECT reference_artifact_id FROM hybrid_public_request WHERE generation_id=?").param(id).query(UUID.class).optional();
        HybridArtifacts.require(artifact.isPresent()&&registry.stillQualified(artifact.get()),"REFERENCE_ARTIFACT_REVOKED");
    }
    private static String hash(JsonNode n){return JudgeJson.hash(JudgeJson.canonical(n));}
    private record State(UUID id,UUID owner,int revision,boolean shared,String contract,String publicHash,UUID validation) {}
    private JsonNode input(State s) {
        var data=checks.checkedPackage(s.validation);
        var contract=data.get("CONTRACT");var presentation=data.get("PRESENTATION");var core=data.get("CORE");
        var snapshot=HybridArtifacts.publicSnapshot(presentation);
        HybridArtifacts.require(hash(contract).equals(s.contract)&&hash(snapshot).equals(s.publicHash),"PUBLICATION_ARTIFACT_FENCE");
        String version="hybrid-check-"+s.validation;
        var p=jdbc.sql("SELECT package_json,package_sha256,teaching_json,runtime_image,runner_policy,owner_id,ready,review_hold,shared FROM problem_version WHERE id=? FOR UPDATE")
                .param(version).query((r,n)->new Object[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getObject(6,UUID.class),r.getBoolean(7),r.getBoolean(8),r.getBoolean(9)}).single();
        HybridArtifacts.require(s.owner.equals(p[5])&&Boolean.FALSE.equals(p[6])&&Boolean.FALSE.equals(p[7])&&Boolean.FALSE.equals(p[8]),"PUBLICATION_VISIBILITY_FENCE");
        var pack=JudgeJson.parse((String)p[0]);var teaching=JudgeJson.parse((String)p[2]);
        var validation=jdbc.sql("SELECT input_sha256,completion_json FROM hybrid_branch WHERE id=?").param(s.validation)
                .query((r,n)->new String[]{r.getString(1),r.getString(2)}).single();
        var runtime=JudgeJson.JSON.createObjectNode().put("image",(String)p[3]).put("policy",(String)p[4]);
        // Every completed job must have used the frozen runtime. The generator is RUN_ONLY.
        var runtimes=jdbc.sql("SELECT e.role,s.runtime_image,s.runner_policy,s.language,s.execution_profile_json,j.execution_mode,j.result_json FROM hybrid_execution_check e JOIN submission s ON s.id=e.submission_id JOIN judge_job j ON j.submission_id=s.id WHERE e.branch_id=? ORDER BY e.role")
                .param(s.validation).query((r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7)}).list();
        String scheduling=jdbc.sql("SELECT scheduling FROM hybrid_validation_profile WHERE branch_id=?").param(s.validation).query(String.class).single();
        for(var row:runtimes) {
            var report=JudgeJson.parse(row[6]);
            String expectedMode=HybridRunnerChecks.executionMode(scheduling,row[0]);
            HybridArtifacts.require(Objects.equals(row[1],p[3])&&row[2].equals(row[0].equals("package-generator")?"java8-run-v1":p[4])
                    &&"JAVA".equals(row[3])&&row[4]==null&&expectedMode.equals(row[5])
                    &&row[1].equals(report.path("image").asText())&&row[2].equals(report.path("policy").asText())
                    &&expectedMode.equals(report.path("execution_mode").asText()),"PUBLICATION_RUNTIME_FENCE");
        }
        var bindings=JudgeJson.JSON.createObjectNode().put("generationId",s.id.toString()).put("revision",s.revision)
                .put("ownerId",s.owner.toString()).put("shared",s.shared).put("validationBranchId",s.validation.toString())
                .put("manifestHash",validation[0]).put("executionReportHash",JudgeJson.hash(validation[1]))
                .put("contractHash",s.contract).put("publicHash",s.publicHash).put("packageHash",(String)p[1])
                .put("teachingHash",hash(teaching)).put("runtimeHash",hash(runtime));
        var result=JudgeJson.JSON.createObjectNode();result.set("bindings",bindings);result.set("contract",contract);
        result.set("publicSnapshot",snapshot);result.put("reference",core.path("reference").asText());result.set("authorNotes",core.path("authorNotes"));
        result.put("statement",pack.path("statement").asText());result.set("samples",pack.path("samples"));result.set("teaching",teaching);
        return HybridArtifacts.bounded(result);
    }
    @Transactional
    void advance() {
        jdbc.sql("SELECT id FROM ai_budget_lock WHERE id=1 FOR UPDATE").query(Integer.class).single();
        var ids=jdbc.sql("SELECT id FROM hybrid_generation WHERE (status='HELD' AND error_code='CONTENT_REVIEW_REQUIRED') OR status='REVIEWING'").query(UUID.class).list();
        for(UUID id:ids) {
            var state=jdbc.sql("SELECT g.owner_id,g.revision,g.share_on_publish,g.contract_sha256,g.public_sha256,b.id FROM hybrid_generation g JOIN hybrid_branch b ON b.generation_id=g.id AND b.revision=g.revision AND b.role='VALIDATION' AND b.status='CHECKED' WHERE g.id=? AND g.deadline_at>CURRENT_TIMESTAMP FOR UPDATE")
                    .param(id).query((r,n)->new State(id,r.getObject(1,UUID.class),r.getInt(2),r.getBoolean(3),r.getString(4),r.getString(5),r.getObject(6,UUID.class))).optional();
            if(state.isEmpty())continue;
            var s=state.get();
            var review=jdbc.sql("SELECT id,status,input_json,input_sha256,output_sha256 FROM hybrid_branch WHERE generation_id=? AND revision=? AND role='CONTENT_REVIEW'")
                    .param(id).param(s.revision).query((r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5)}).optional();
            if(review.isPresent()&&!review.get()[1].equals("SUCCEEDED"))continue;
            if(review.isEmpty()&&jdbc.sql("SELECT count(*) FROM hybrid_api_reservation r JOIN ai_attempt a ON a.id=r.attempt_id WHERE r.generation_id=? AND r.revision=? AND r.role='CONTENT_REVIEW' AND a.status='HYBRID_RESERVED'")
                    .param(id).param(s.revision).query(Integer.class).single()!=1)continue;
            try {
                var input=input(s);String raw=JudgeJson.canonical(input),hash=JudgeJson.hash(raw);
                if(review.isEmpty()) {
                    jdbc.sql("INSERT INTO hybrid_branch(id,generation_id,revision,role,attempt,status,input_json,input_sha256,contract_sha256,public_sha256,created_at) VALUES (?,?,?,'CONTENT_REVIEW',0,'QUEUED',?,?,?,?,CURRENT_TIMESTAMP)")
                            .param(UUID.randomUUID()).param(id).param(s.revision).param(raw).param(hash).param(s.contract).param(s.publicHash).update();
                    jdbc.sql("UPDATE hybrid_generation SET status='REVIEWING',error_code=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?").param(id).update();
                    events.publishEvent(new HybridExecution.Wakeup());continue;
                }
                var r=review.get();
                HybridArtifacts.require(jdbc.sql("SELECT count(*) FROM hybrid_api_reservation r JOIN ai_attempt a ON a.id=r.attempt_id WHERE r.branch_id=? AND r.generation_id=? AND r.revision=? AND r.role='CONTENT_REVIEW' AND r.receipt_json IS NOT NULL AND a.status='HYBRID_COMPLETED'")
                        .param(UUID.fromString(r[0])).param(id).param(s.revision).query(Integer.class).single()==1,"CONTENT_REVIEW_ACCOUNTING_FENCE");
                HybridArtifacts.require(raw.equals(r[2])&&hash.equals(r[3]),"CONTENT_REVIEW_STALE");
                var artifact=jdbc.sql("SELECT payload_json,payload_sha256 FROM hybrid_artifact WHERE branch_id=?").param(UUID.fromString(r[0]))
                        .query((row,n)->new String[]{row.getString(1),row.getString(2)}).single();
                HybridArtifacts.require(JudgeJson.hash(artifact[0]).equals(artifact[1])&&artifact[1].equals(r[4]),"CONTENT_REVIEW_ARTIFACT_FENCE");
                HybridArtifacts.contentReview(JudgeJson.parse(artifact[0]),hash);
                String version="hybrid-check-"+s.validation;
                // The budget/generation locks serialize cancellation, provider completion and publication.
                HybridArtifacts.require(OffsetDateTime.now(ZoneOffset.UTC).isBefore(jdbc.sql("SELECT deadline_at FROM hybrid_generation WHERE id=?").param(id).query(OffsetDateTime.class).single()),"PUBLICATION_DEADLINE");
                requireReferenceQualified(id,s.revision);
                jdbc.sql("UPDATE problem_version SET ready=true,shared=? WHERE id=? AND ready=false").param(s.shared).param(version).update();
                var ruleVersion=jdbc.sql("SELECT rule_version_id FROM hybrid_public_request WHERE generation_id=?").param(id).query(String.class).optional();
                if(ruleVersion.isPresent()) {
                    var profile=HybridProfiles.byPolicy(jdbc.sql("SELECT policy FROM hybrid_validation_profile WHERE branch_id=?").param(s.validation).query(String.class).single());
                    var catalog=registry.version(ruleVersion.get()).filter(v->v.catalog().has("category"));
                    jdbc.sql("UPDATE problem_version SET catalog_category=?,catalog_tags=? WHERE id=?")
                            .param(catalog.map(HybridRuleRegistry.Version::category).orElse(profile.category()))
                            .param(catalog.map(HybridRuleRegistry.Version::tags).orElse(profile.tags())).param(version).update();
                    // A member-requested rule carries its target difficulty (하/중/상/최상) onto every problem generated from it.
                    jdbc.sql("SELECT request_json FROM hybrid_rule_onboarding WHERE version_id=?").param(ruleVersion.get()).query(String.class).optional()
                            .map(requestJson->JudgeJson.parse(requestJson).path("difficulty").asText("")).filter(HybridRuleOnboarding.DIFFICULTIES::contains)
                            .ifPresent(d->jdbc.sql("UPDATE problem_version SET catalog_difficulty=? WHERE id=?").param(d).param(version).update());
                }
                jdbc.sql("UPDATE hybrid_generation SET status='PUBLISHED',error_code=NULL,published_version_id=?,updated_at=CURRENT_TIMESTAMP WHERE id=?").param(version).param(id).update();
                registry.qualify(id);
            } catch(HybridArtifacts.Invalid|IllegalArgumentException|IllegalStateException|org.springframework.dao.IncorrectResultSizeDataAccessException invalid) {
                jdbc.sql("UPDATE hybrid_generation SET status='HELD',error_code=?,updated_at=CURRENT_TIMESTAMP WHERE id=?")
                        .param(invalid.getMessage()!=null&&invalid.getMessage().matches("[A-Z][A-Z0-9_]{0,79}")?invalid.getMessage():"INVALID_PUBLICATION_EVIDENCE").param(id).update();
            }
        }
    }
}
