package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Retries explicit failed stages. Unknown provider receipts and infrastructure never trigger another call. */
final class GenerationDraftRecovery {
    enum Scope { TEACHING, PROSE, IMPLEMENTATION, CONTRACT, REVIEW, FINAL }
    private static final List<String> SPEC=List.of("token","completion_json","spec_json","spec_sha256");
    private static final List<String> BUILD=List.of("build_token","build_completion_json","build_artifacts_json","build_oracle_json","build_sha256","build_report_json","build_inputs_json");
    private static final List<String> REVIEW=List.of("review_token","review_completion_json","review_payload_json","review_payload_sha256","review_report_json");
    private static final List<String> FINAL=List.of("final_token","final_completion_json","final_plan_json","final_plan_sha256","final_report_json","final_inputs_json");
    static boolean stopped(String error) {
        if(error==null)return false;
        return List.of("AUTH","QUOTA","TIMEOUT","INTERRUPTED","VERSION_MISMATCH","FENCE","_IE","INFRASTRUCTURE","INCOMPLETE","UNKNOWN","BUDGET","CANCEL","RETRY_LIMIT").stream().anyMatch(error::contains);
    }
    static Scope scope(String status,String error,JsonNode review) {
        if(stopped(error))return null;
        if("FAILED".equals(status))return Scope.CONTRACT;
        if("BUILD_FAILED".equals(status))return "INVALID_IMPLEMENTATION_PROSE".equals(error)?Scope.TEACHING:Scope.IMPLEMENTATION;
        if("REVIEW_REJECTED".equals(status)) {
            try{return Scope.valueOf(review.path("failureScope").asText("CONTRACT"));}catch(IllegalArgumentException e){return Scope.CONTRACT;}
        }
        if("REVIEW_FAILED".equals(status)) {
            if(error!=null&&(error.startsWith("REVIEW_review-reference_")||error.startsWith("REVIEW_review-oracle_")||error.startsWith("REVIEW_review-valid-inputs_")||error.startsWith("REVIEW_review-invalid-inputs_")))return Scope.IMPLEMENTATION;
            return Scope.REVIEW;
        }
        if("FINAL_FAILED".equals(status)) {
            if(error!=null&&(error.contains("REFERENCE")||error.contains("ORACLE")||error.contains("VALIDATOR")))return Scope.IMPLEMENTATION;
            return Scope.FINAL;
        }
        if("FINAL_REJECTED".equals(status))return Scope.FINAL;
        return null;
    }
    static void advance(JdbcClient jdbc) {
        for(var id:jdbc.sql("SELECT id FROM generation_spec_draft WHERE auto_recovery=true AND status IN ('FAILED','BUILD_FAILED','REVIEW_FAILED','REVIEW_REJECTED','FINAL_FAILED','FINAL_REJECTED') ORDER BY updated_at LIMIT 30 FOR UPDATE").query(UUID.class).list())recover(jdbc,id);
    }
    private static void recover(JdbcClient jdbc,UUID id) {
        // Completed/ended training goals must not initiate new paid work.
        if(jdbc.sql("SELECT count(*) FROM diagnostic_practice_plan p WHERE p.generation_id=? AND (p.training_session_id IS NOT NULL OR EXISTS (SELECT 1 FROM learning_curriculum_end e WHERE e.evaluation_id=p.evaluation_id AND e.user_id=p.user_id) OR EXISTS (SELECT 1 FROM diagnostic_practice_plan n WHERE n.previous_plan_id=p.id))").param(id).query(Integer.class).single()>0)return;
        UUID owner=jdbc.sql("SELECT owner_id FROM generation_spec_draft WHERE id=?").param(id).query(UUID.class).single();
        if(jdbc.sql("SELECT count(*) FROM generation_spec_draft WHERE owner_id=? AND id<>? AND status IN ('QUEUED','GENERATING','BUILD_QUEUED','BUILD_GENERATING','CHECKING','REVIEW_QUEUED','REVIEW_GENERATING','REVIEW_CHECKING','FINAL_QUEUED','FINAL_GENERATING','FINAL_CHECKING')").param(owner).param(id).query(Integer.class).single()>0||HybridAdmission.active(jdbc,owner)||jdbc.sql("SELECT count(*) FROM generation_job WHERE owner_id=? AND status IN ('QUEUED','GENERATING','AWAITING_REVIEW','VALIDATING')").param(owner).query(Integer.class).single()>0)return;
        ObjectNode snapshot=jdbc.sql("SELECT * FROM generation_spec_draft WHERE id=?").param(id).query((r,n)->{
            var value=JudgeJson.JSON.createObjectNode();var metadata=r.getMetaData();
            for(int i=1;i<=metadata.getColumnCount();i++) {String key=metadata.getColumnLabel(i).toLowerCase(Locale.ROOT);String text=r.getString(i);if(text==null)value.putNull(key);else value.put(key,text);}
            return value;
        }).single();
        var review=snapshot.path("review_payload_json").isNull()?JudgeJson.JSON.createObjectNode():JudgeJson.parse(snapshot.path("review_payload_json").asText());
        String error=snapshot.path("error_code").isNull()?null:snapshot.path("error_code").asText();
        if(stopped(error)){jdbc.sql("UPDATE generation_spec_draft SET auto_recovery=false WHERE id=?").param(id).update();return;}
        var scope=scope(snapshot.path("status").asText(),error,review);
        if("PROSE".equals(snapshot.path("recovery_scope").asText())&&!stopped(error)&&"REVIEW_REJECTED".equals(snapshot.path("status").asText()))scope=Scope.PROSE;
        if(scope==null)return;
        int total=jdbc.sql("SELECT count(*) FROM generation_recovery_attempt WHERE pipeline='DIRECT' AND job_id=?").param(id).query(Integer.class).single();
        int stage=jdbc.sql("SELECT count(*) FROM generation_recovery_attempt WHERE pipeline='DIRECT' AND job_id=? AND scope=?").param(id).param(scope.name()).query(Integer.class).single();
        if(total>=6||stage>=3){jdbc.sql("UPDATE generation_spec_draft SET auto_recovery=false,recovery_feedback='재시도 한도에 도달했어요. 저장된 실패와 검증 기록을 확인해 주세요.' WHERE id=?").param(id).update();return;}
        var executions=snapshot.putArray("executionLinks");
        jdbc.sql("SELECT role,submission_id,expected_verdict FROM generation_spec_execution WHERE draft_id=? ORDER BY role").param(id).query((r,n)->JudgeJson.JSON.createObjectNode().put("role",r.getString(1)).put("submissionId",r.getString(2)).put("expected",r.getString(3))).list().forEach(executions::add);
        jdbc.sql("INSERT INTO generation_recovery_attempt(pipeline,job_id,attempt,scope,error_code,snapshot_json) VALUES ('DIRECT',?,?,?,?,?)").param(id).param(total+1).param(scope.name()).param(error).param(JudgeJson.canonical(snapshot)).update();
        for(var group:List.of(SPEC,BUILD,REVIEW,FINAL)) {
            var token=snapshot.path(group.get(0));var completion=snapshot.path(group.get(1));
            if(!token.isNull()&&!completion.isNull()&&jdbc.sql("SELECT count(*) FROM generation_recovery_receipt WHERE job_id=? AND token=?").param(id).param(UUID.fromString(token.asText())).query(Integer.class).single()==0)
                jdbc.sql("INSERT INTO generation_recovery_receipt(job_id,token,completion_json) VALUES (?,?,?)").param(id).param(UUID.fromString(token.asText())).param(completion.asText()).update();
        }
        var clear=new ArrayList<String>();clear.addAll(FINAL);
        String state;
        switch(scope) {
            case TEACHING -> {state="BUILD_QUEUED";clear.addAll(REVIEW);clear.addAll(List.of("build_token","build_completion_json","build_report_json","build_inputs_json"));}
            case FINAL -> state="FINAL_QUEUED";
            case REVIEW -> {state="REVIEW_QUEUED";clear.addAll(REVIEW);}
            case PROSE -> {state="QUEUED";clear.addAll(REVIEW);clear.add("token");clear.add("completion_json");}
            case IMPLEMENTATION -> {state="BUILD_QUEUED";clear.addAll(REVIEW);clear.addAll(BUILD);}
            case CONTRACT -> {state="QUEUED";clear.addAll(REVIEW);clear.addAll(BUILD);clear.addAll(SPEC);}
            default -> throw new IllegalStateException();
        }
        if(scope==Scope.FINAL)jdbc.sql("DELETE FROM generation_spec_execution WHERE draft_id=? AND role LIKE 'final-%'").param(id).update();
        else if(scope==Scope.REVIEW||scope==Scope.PROSE)jdbc.sql("DELETE FROM generation_spec_execution WHERE draft_id=? AND (role LIKE 'review-%' OR role LIKE 'final-%')").param(id).update();
        else jdbc.sql("DELETE FROM generation_spec_execution WHERE draft_id=?").param(id).update();
        String feedback="Retry "+(total+1)+"/6; scope="+scope+"; error="+error+"; review="+JudgeJson.canonical(review);
        jdbc.sql("UPDATE generation_spec_draft SET "+String.join(",",clear.stream().map(k->k+"=NULL").toList())+",final_stage=0,status=?,recovery_scope=?,recovery_feedback=?,retry_after=?,error_code=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?")
            .param(state).param(scope.name()).param(feedback).param(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(Math.min(120,10L*(total+1)))).param(id).update();
    }
    static JsonNode progress(JdbcClient jdbc,String pipeline,UUID id) {
        int attempts=jdbc.sql("SELECT count(*) FROM generation_recovery_attempt WHERE pipeline=? AND job_id=?").param(pipeline).param(id).query(Integer.class).single();
        if(attempts==0)return null;
        String scope=jdbc.sql("SELECT scope FROM generation_recovery_attempt WHERE pipeline=? AND job_id=? ORDER BY attempt DESC LIMIT 1").param(pipeline).param(id).query(String.class).single();
        return JudgeJson.JSON.createObjectNode().put("attempt",attempts).put("limit",6).put("scope",scope);
    }
    static String receipt(JdbcClient jdbc,UUID id,UUID token){return jdbc.sql("SELECT completion_json FROM generation_recovery_receipt WHERE job_id=? AND token=?").param(id).param(token).query(String.class).optional().orElse(null);}
    private GenerationDraftRecovery(){}
}
