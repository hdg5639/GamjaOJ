package dev.gamjaoj;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Independent tag/template prose equivalence; never sees implementation source or hidden tests. */
final class GenerationProseReview {
    record Decision(UUID job,int revision,String hash,boolean accepted,String error) {}
    static void start(JdbcClient jdbc,GenerationJobs.View job,JsonNode definition,String rules,JsonNode sample) {
        var input=JudgeJson.JSON.createObjectNode().put("phase","TAG_PROSE_REVIEW").put("title",job.artifacts().path("title").asText()).put("context",job.artifacts().path("context").asText()).put("rules",rules);
        input.set("definition",definition.deepCopy());input.set("sample",sample.deepCopy());
        jdbc.sql("INSERT INTO generation_prose_review(id,job_id,revision,artifact_hash,input_json,status) VALUES (?,?,?,?,?,'QUEUED')")
            .param(UUID.randomUUID()).param(job.id()).param(job.revision()).param(job.artifactHash()).param(JudgeJson.canonical(input)).update();
    }
    static JsonNode progress(JdbcClient jdbc,UUID job,int revision) {
        return jdbc.sql("SELECT status FROM generation_prose_review WHERE job_id=? AND revision=?").param(job).param(revision).query(String.class)
            .optional().map(state->(JsonNode)JudgeJson.JSON.createObjectNode().put("status",state)).orElse(null);
    }
    static boolean contains(JdbcClient jdbc,UUID id){return jdbc.sql("SELECT count(*) FROM generation_prose_review WHERE id=?").param(id).query(Integer.class).single()>0;}
    static boolean passed(JdbcClient jdbc,UUID job,int revision,String hash){return jdbc.sql("SELECT count(*) FROM generation_prose_review WHERE job_id=? AND revision=? AND artifact_hash=? AND status='PASSED'").param(job).param(revision).param(hash).query(Integer.class).single()==1;}
    static GenerationJobs.Assignment claim(JdbcClient jdbc,AiSettings settings) {
        jdbc.sql("UPDATE generation_job SET status='NEEDS_REVIEW',error_code='GENERATION_INTERRUPTED' WHERE status='AWAITING_REVIEW' AND EXISTS (SELECT 1 FROM generation_prose_review r WHERE r.job_id=generation_job.id AND r.revision=generation_job.revision AND r.status='GENERATING' AND r.lease_until<CURRENT_TIMESTAMP)").update();
        jdbc.sql("UPDATE generation_prose_review SET status='NEEDS_REVIEW' WHERE status='GENERATING' AND lease_until<CURRENT_TIMESTAMP").update();
        if(jdbc.sql("SELECT count(*) FROM generation_prose_review WHERE status='GENERATING'").query(Integer.class).single()>0)return null;
        var row=jdbc.sql("SELECT r.id,r.input_json,r.revision FROM generation_prose_review r JOIN generation_job j ON j.id=r.job_id WHERE r.status='QUEUED' AND j.status='AWAITING_REVIEW' AND j.revision=r.revision ORDER BY r.created_at LIMIT 1 FOR UPDATE").query((r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3)}).optional();
        if(row.isEmpty())return null;UUID id=UUID.fromString(row.get()[0]),token=UUID.randomUUID();
        jdbc.sql("UPDATE generation_prose_review SET status='GENERATING',token=?,lease_until=? WHERE id=?").param(token).param(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(20)).param(id).update();
        return new GenerationJobs.Assignment(id,token,Integer.parseInt(row.get()[2]),settings.value("CODEX_GENERATION_MODEL","gpt-6.1-sol"),settings.value("CODEX_GENERATION_REASONING","medium"),JudgeJson.parse(row.get()[1]),null,null,null);
    }
    static Decision complete(JdbcClient jdbc,UUID id,UUID token,JsonNode payload,JsonNode oracle,JsonNode usage,String error) {
        var row=jdbc.sql("SELECT job_id,revision,artifact_hash,token,status,completion_json FROM generation_prose_review WHERE id=? FOR UPDATE").param(id).query((r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6)}).single();
        if(!token.toString().equals(row[3]))throw new AccountException(409,"이전 본문 검수 작업이에요.");
        var receipt=JudgeJson.JSON.createObjectNode();receipt.set("artifacts",payload);receipt.set("oracle",oracle);receipt.set("usage",usage);receipt.put("error",error);String audit=JudgeJson.canonical(receipt);
        if(audit.length()>600000)throw new AccountException(400,"본문 검수 결과가 너무 커요.");
        if(row[5]!=null){if(row[5].equals(audit))return null;throw new AccountException(409,"저장된 본문 검수와 달라요.");}
        if(!"GENERATING".equals(row[4])||jdbc.sql("SELECT count(*) FROM generation_prose_review WHERE id=? AND lease_until>CURRENT_TIMESTAMP").param(id).query(Integer.class).single()!=1)throw new AccountException(409,"본문 검수 시간이 지났어요.");
        boolean accepted=false;
        if(error==null)try{HybridArtifacts.fields(payload,"accepted","issues");HybridArtifacts.require(payload.path("accepted").isBoolean(),"INVALID_PROSE_REVIEW");HybridArtifacts.texts(payload.path("issues"),0,8,2000);HybridArtifacts.require(oracle==null||oracle.isNull(),"INVALID_PROSE_REVIEW");accepted=payload.path("accepted").asBoolean()&&payload.path("issues").isEmpty();error=accepted?null:"PROSE_REVIEW_REJECTED";}catch(HybridArtifacts.Invalid invalid){error="INVALID_PROSE_REVIEW";}
        jdbc.sql("UPDATE generation_prose_review SET completion_json=?,status=? WHERE id=?").param(audit).param(accepted?"PASSED":"FAILED").param(id).update();
        UUID job=UUID.fromString(row[0]);int revision=Integer.parseInt(row[1]);
        if(jdbc.sql("SELECT count(*) FROM generation_job WHERE id=? AND revision=? AND artifacts_sha256=? AND status='AWAITING_REVIEW'").param(job).param(revision).param(row[2]).query(Integer.class).single()!=1)return null;
        return new Decision(job,revision,row[2],accepted,error);
    }
    private GenerationProseReview(){}
}
