package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamjaoj.ai.OpenAiResponses;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static dev.gamjaoj.HybridGeneration.Role.*;

/** Budgeted compatibility dispatcher. Public admission remains closed pending validation profiles. */
@Service
class HybridExecution {
    record Wakeup() {}
    record Work(UUID attemptId,HybridModels.ApiRequest request,OffsetDateTime deadlineAt) {}
    private final JdbcClient jdbc;private final HybridGeneration jobs;private final AiTasks ledger;
    private final HybridPublication publication;private final AiSettings config;private final ApplicationEventPublisher events;
    HybridExecution(JdbcClient jdbc,HybridGeneration jobs,AiTasks ledger,AiSettings config,ApplicationEventPublisher events,HybridPublication publication) {
        this.jdbc=jdbc;this.jobs=jobs;this.ledger=ledger;this.config=config;this.events=events;this.publication=publication;
    }
    private void lock(){jdbc.sql("SELECT id FROM ai_budget_lock WHERE id=1 FOR UPDATE").query(Integer.class).single();}
    private static String month(){return YearMonth.now(ZoneOffset.UTC).toString();}
    private static String json(Object object){return JudgeJson.canonical(JudgeJson.JSON.valueToTree(object));}
    private static <T> T parse(String value,Class<T> type) {
        try{return JudgeJson.JSON.readValue(value,type);}catch(Exception e){throw new IllegalStateException("Invalid hybrid record",e);}
    }
    private boolean configured(){return config.enabled()&&!config.key().isBlank();}
    static BigDecimal reserve(HybridGeneration.Role role,AiSettings.Model model) {
        // Future writer/reader inputs are not known at admission. Bound the complete accepted input,
        // instructions, schema and framing, rather than estimating from a shorter current request.
        long bound=HybridArtifacts.MAX_PAYLOAD_BYTES+HybridModels.instructions(role).getBytes(StandardCharsets.UTF_8).length
                +HybridModels.schema(role).toString().getBytes(StandardCharsets.UTF_8).length+4096L
                +(HybridModels.author(role)?8192L:0L); // profile-specific author guidance
        return model.inputRate().multiply(BigDecimal.valueOf(bound))
                .add(model.outputRate().multiply(BigDecimal.valueOf(model.maxOutputTokens())))
                .movePointLeft(6).setScale(8,RoundingMode.CEILING);
    }
    @Transactional
    HybridGeneration.Progress admit(String user,UUID id,String request,boolean shared) {
        lock();
        if(jdbc.sql("SELECT count(*) FROM hybrid_api_reservation WHERE generation_id=?").param(id).query(Integer.class).single()>0)
            return jobs.start(user,id,request,shared); // Owner and exact request idempotency still checked.
        if(!Boolean.parseBoolean(config.value("HYBRID_ADMISSION_ENABLED","false"))||!configured())
            throw new AccountException(503,"하이브리드 출제 실행은 아직 활성화되지 않았어요.");
        var writer=HybridModels.slot(config,PRESENTATION);var reader=HybridModels.slot(config,READER);
        var models=new EnumMap<HybridGeneration.Role,AiSettings.Model>(HybridGeneration.Role.class);
        models.put(PRESENTATION,writer);models.put(READER,reader);
        if(Boolean.parseBoolean(config.value("HYBRID_CONTENT_REVIEW_ENABLED","false"))) {
            if(!HybridFiniteProfile.PACKAGE_POLICY.equals(config.value("HYBRID_VALIDATION_PROFILE","")))
                throw new AccountException(503,"최종 검토에는 지원되는 전체 패키지 검증 정책이 필요해요.");
            models.put(CONTENT_REVIEW,HybridModels.slot(config,CONTENT_REVIEW));
        }
        var budget=ledger.budget();BigDecimal amount=BigDecimal.ZERO;
        for(var entry:models.entrySet())amount=amount.add(reserve(entry.getKey(),entry.getValue()));
        if(budget.spentUsd().add(budget.reservedUsd()).add(amount).compareTo(budget.limitUsd())>0)
            throw new AccountException(429,"본문 작성과 독립 검토에 필요한 AI 예산이 부족해요.");
        if(jdbc.sql("SELECT count(*) FROM hybrid_generation WHERE id=?").param(id).query(Integer.class).single()>0)
            throw new AccountException(409,"예약 없이 시작된 출제를 실행 경로로 전환할 수 없어요.");
        // One active generation; no parallel user admissions hidden behind internal branch concurrency.
        if(jdbc.sql("SELECT count(*) FROM hybrid_generation g WHERE EXISTS (SELECT 1 FROM hybrid_api_reservation r WHERE r.generation_id=g.id) AND (g.status IN ('QUEUED','DESIGNING','BUILDING','VALIDATING','REVIEWING') OR (g.status='HELD' AND g.error_code IN ('VALIDATION_ADAPTER_NOT_CONNECTED','CONTENT_REVIEW_REQUIRED') AND g.deadline_at>CURRENT_TIMESTAMP))").query(Integer.class).single()>0)
            throw new AccountException(429,"진행 중인 출제가 끝난 뒤 다시 요청해 주세요.");
        var job=jobs.start(user,id,request,shared);
        jdbc.sql("INSERT INTO hybrid_execution_policy(generation_id,codex_model,codex_effort) VALUES (?,?,?)")
                .param(id).param(config.value("CODEX_GENERATION_MODEL","gpt-5.6-sol"))
                .param(config.value("CODEX_GENERATION_REASONING","medium")).update();
        for(var role:models.keySet()) {
            var model=models.get(role);UUID attempt=UUID.randomUUID();
            jdbc.sql("INSERT INTO ai_attempt(id,month_key,status,reserved_usd,settings_json) VALUES (?,?,'HYBRID_RESERVED',?,?)")
                    .param(attempt).param(month()).param(reserve(role,model)).param(json(model)).update();
            jdbc.sql("INSERT INTO hybrid_api_reservation(attempt_id,generation_id,revision,role) VALUES (?,?,0,?)")
                    .param(attempt).param(id).param(role.name()).update();
        }
        events.publishEvent(new Wakeup());return job;
    }
    static final String CODEX_QUOTA="CODEX_QUOTA_EXHAUSTED";
    /** Explicit opt-in: Codex quota exhaustion may spend the shared API budget on author roles. */
    private boolean fallbackEnabled() {
        if(!Boolean.parseBoolean(config.value("HYBRID_CODEX_API_FALLBACK_ENABLED","false"))||!configured())return false;
        try{HybridModels.slot(config,CORE);return true;}catch(AccountException missing){return false;}
    }
    private boolean codexBlocked() {
        return jdbc.sql("SELECT count(*) FROM hybrid_codex_quota WHERE id=1 AND blocked_until>?").param(OffsetDateTime.now(ZoneOffset.UTC)).query(Integer.class).single()>0;
    }
    private void markCodexQuota() {
        var now=OffsetDateTime.now(ZoneOffset.UTC);
        long minutes=Math.max(1,Math.min(24*60,Long.parseLong(config.value("HYBRID_CODEX_QUOTA_COOLDOWN_MINUTES","60"))));
        jdbc.sql("DELETE FROM hybrid_codex_quota WHERE id=1").update();
        jdbc.sql("INSERT INTO hybrid_codex_quota(id,observed_at,blocked_until) VALUES (1,?,?)").param(now).param(now.plusMinutes(minutes)).update();
    }
    /** Reserves the author call in the shared ledger; false when the monthly budget cannot cover it. */
    private boolean reserveAuthor(UUID generation,int revision,HybridGeneration.Role role) {
        var model=HybridModels.slot(config,role);var amount=reserve(role,model);var budget=ledger.budget();
        if(budget.spentUsd().add(budget.reservedUsd()).add(amount).compareTo(budget.limitUsd())>0)return false;
        UUID attempt=UUID.randomUUID();
        jdbc.sql("INSERT INTO ai_attempt(id,month_key,status,reserved_usd,settings_json) VALUES (?,?,'HYBRID_RESERVED',?,?)")
                .param(attempt).param(month()).param(amount).param(json(model)).update();
        jdbc.sql("INSERT INTO hybrid_api_reservation(attempt_id,generation_id,revision,role) VALUES (?,?,?,?)")
                .param(attempt).param(generation).param(revision).param(role.name()).update();
        return true;
    }
    private boolean hasAuthorReservation(UUID generation,int revision,HybridGeneration.Role role) {
        return jdbc.sql("SELECT count(*) FROM hybrid_api_reservation WHERE generation_id=? AND revision=? AND role=?")
                .param(generation).param(revision).param(role.name()).query(Integer.class).single()>0;
    }
    /** While Codex is known to be quota-limited, send queued author work straight to the API lane. */
    private void routeBlockedAuthors() {
        if(!codexBlocked()||!fallbackEnabled())return;
        var queued=jdbc.sql("SELECT b.generation_id,b.role,g.revision FROM hybrid_branch b JOIN hybrid_generation g ON g.id=b.generation_id WHERE b.revision=g.revision AND b.status='QUEUED' AND b.role IN ('CONTRACT','CORE') AND g.status IN ('QUEUED','DESIGNING','BUILDING') AND EXISTS (SELECT 1 FROM hybrid_api_reservation r WHERE r.generation_id=g.id)")
                .query((r,n)->new Object[]{r.getObject(1,UUID.class),HybridGeneration.Role.valueOf(r.getString(2)),r.getInt(3)}).list();
        for(var row:queued) {
            UUID id=(UUID)row[0];var role=(HybridGeneration.Role)row[1];int revision=(Integer)row[2];
            if(hasAuthorReservation(id,revision,role)||!jobs.queuedAuthor(id,role))continue;
            if(!reserveAuthor(id,revision,role))jobs.hold(id,"CODEX_QUOTA_API_BUDGET");
        }
    }
    @Transactional
    HybridModels.CodexRequest claimCodex() {
        lock();recover();
        // Disabling admissions does not reinterpret or restart already admitted work.
        // Author branches with their own API reservation belong to the fallback lane, never to Codex.
        var candidates=jdbc.sql("SELECT b.generation_id,b.role,g.deadline_at FROM hybrid_branch b JOIN hybrid_generation g ON g.id=b.generation_id WHERE b.revision=g.revision AND b.status='QUEUED' AND b.role IN ('CONTRACT','CORE') AND g.status IN ('QUEUED','DESIGNING','BUILDING') AND EXISTS (SELECT 1 FROM hybrid_api_reservation r WHERE r.generation_id=g.id) AND NOT EXISTS (SELECT 1 FROM hybrid_api_reservation f WHERE f.generation_id=g.id AND f.revision=g.revision AND f.role=b.role) ORDER BY b.created_at LIMIT 20")
                .query((r,n)->new Object[]{r.getObject(1,UUID.class),HybridGeneration.Role.valueOf(r.getString(2)),r.getObject(3,OffsetDateTime.class)}).list();
        for(var row:candidates) {
            var a=jobs.claim((UUID)row[0],(HybridGeneration.Role)row[1]);
            if(a!=null) {
                var request=HybridModels.codex(a,config,(OffsetDateTime)row[2]);
                var pinned=jdbc.sql("SELECT codex_model,codex_effort FROM hybrid_execution_policy WHERE generation_id=?")
                        .param(a.generationId()).query((r,n)->new String[]{r.getString(1),r.getString(2)}).single();
                return new HybridModels.CodexRequest(request.pipelineVersion(),request.id(),request.token(),pinned[0],pinned[1],request.deadlineAt(),request.spec(),request.outputSchema());
            }
        }
        return null;
    }
    @Transactional
    void completeCodex(HybridGeneration.Completion c) {
        lock();
        if(c==null||c.role()==null||!Set.of(CONTRACT,CORE).contains(c.role()))throw new AccountException(400,"Codex 분기 결과 형식을 확인해 주세요.");
        if(jdbc.sql("SELECT count(*) FROM hybrid_branch b WHERE b.id=? AND EXISTS (SELECT 1 FROM hybrid_api_reservation r WHERE r.generation_id=b.generation_id)").param(c.branchId()).query(Integer.class).single()==0)
            throw new AccountException(404,"예약된 출제 분기가 없어요.");
        if(CODEX_QUOTA.equals(c.error())) {
            markCodexQuota();
            if(fallbackEnabled()) {
                var model=HybridModels.slot(config,c.role());var budget=ledger.budget();
                boolean affordable=budget.spentUsd().add(budget.reservedUsd()).add(reserve(c.role(),model)).compareTo(budget.limitUsd())<=0;
                if(affordable) {
                    var next=jobs.reroute(c);
                    if(next!=null&&!reserveAuthor(next.generation(),next.revision(),c.role()))jobs.hold(next.generation(),"CODEX_QUOTA_API_BUDGET");
                    releaseStopped();events.publishEvent(new Wakeup());return;
                }
            }
        }
        jobs.complete(c);releaseStopped();events.publishEvent(new Wakeup());
    }
    @Transactional
    Work claimApi(){return claimApi(false);}
    @Transactional
    Work claimAuthorApi(){return claimApi(true);}
    /**
     * Two lanes. The ordinary lane keeps the single API-call limit. The author lane replaces a Codex
     * invocation, which already ran concurrently with writer/reader, so it keeps that overlap.
     */
    private Work claimApi(boolean authorLane) {
        lock();recover();if(!configured())return null;
        String roles=authorLane?"('CONTRACT','CORE')":"('PRESENTATION','READER','CONTENT_REVIEW')";
        if(authorLane?jdbc.sql("SELECT count(*) FROM ai_attempt a JOIN hybrid_api_reservation r ON r.attempt_id=a.id WHERE a.status='HYBRID_RUNNING' AND r.role IN "+roles).query(Integer.class).single()>0
                :jdbc.sql("SELECT count(*) FROM ai_attempt a WHERE a.status='RUNNING' OR (a.status='HYBRID_RUNNING' AND NOT EXISTS (SELECT 1 FROM hybrid_api_reservation r WHERE r.attempt_id=a.id AND r.role IN ('CONTRACT','CORE')))").query(Integer.class).single()>0)return null;
        var candidates=jdbc.sql("SELECT r.attempt_id,r.generation_id,r.role,a.settings_json FROM hybrid_api_reservation r JOIN ai_attempt a ON a.id=r.attempt_id JOIN hybrid_generation g ON g.id=r.generation_id WHERE a.status='HYBRID_RESERVED' AND r.revision=g.revision AND r.role IN "+roles+" AND g.status IN ('QUEUED','BUILDING','DESIGNING','REVIEWING') ORDER BY g.created_at,r.role")
                .query((r,n)->new Object[]{r.getObject(1,UUID.class),r.getObject(2,UUID.class),HybridGeneration.Role.valueOf(r.getString(3)),parse(r.getString(4),AiSettings.Model.class)}).list();
        for(var row:candidates) {
            var a=jobs.claim((UUID)row[1],(HybridGeneration.Role)row[2]);if(a==null)continue;
            var model=(AiSettings.Model)row[3];
            if(HybridModels.author(a.role())) {
                var task=HybridModels.authorTask(a);
                return dispatch((UUID)row[0],(UUID)row[1],a,new HybridModels.ApiRequest(a,model,task.instructions(),JudgeJson.canonical(task.input()),
                        "hybrid_"+a.role().name().toLowerCase(Locale.ROOT)+"_v1",task.schema()));
            }
            var input=HybridModels.checkedInput(a);
            String instructions=HybridModels.instructions(a);
            if(a.role()==READER) {
                var selected=jdbc.sql("SELECT profile_id FROM hybrid_public_request WHERE generation_id=?").param(a.generationId()).query(String.class).optional();
                if(selected.isPresent())instructions+=HybridProfiles.byId(selected.get()).readerInstructions();
            }
            var request=new HybridModels.ApiRequest(a,model,instructions,JudgeJson.canonical(input),
                    "hybrid_"+a.role().name().toLowerCase(Locale.ROOT)+"_v1",HybridModels.outputSchema(a));
            return dispatch((UUID)row[0],(UUID)row[1],a,request);
        }
        return null;
    }
    private Work dispatch(UUID attempt,UUID generation,HybridGeneration.Assignment a,HybridModels.ApiRequest request) {
        jdbc.sql("UPDATE hybrid_api_reservation SET branch_id=?,assignment_json=? WHERE attempt_id=?")
                .param(a.branchId()).param(json(a)).param(attempt).update();
        jdbc.sql("UPDATE ai_attempt SET status='HYBRID_RUNNING',started_at=CURRENT_TIMESTAMP,month_key=? WHERE id=?")
                .param(month()).param(attempt).update();
        return new Work(attempt,request,jdbc.sql("SELECT deadline_at FROM hybrid_generation WHERE id=?").param(generation).query(OffsetDateTime.class).single());
    }
    @Transactional
    void finish(UUID attempt,OpenAiResponses.Result result,OpenAiResponses.Failure failure) {
        lock();
        var row=jdbc.sql("SELECT r.assignment_json,r.receipt_json,a.settings_json,a.status FROM hybrid_api_reservation r JOIN ai_attempt a ON a.id=r.attempt_id WHERE r.attempt_id=?")
                .param(attempt).query((r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4)}).single();
        if(row[0]==null)throw new AccountException(409,"시작하지 않은 호출의 결과예요.");
        if((result==null)==(failure==null))throw new IllegalArgumentException("Exactly one provider outcome required");
        var receipt=JudgeJson.JSON.createObjectNode();receipt.set("result",JudgeJson.JSON.valueToTree(result));
        if(failure!=null){receipt.put("error",failure.code()).put("requestId",failure.requestId());receipt.set("usage",failure.usage());}
        String saved=JudgeJson.canonical(receipt);
        if(row[1]!=null){if(!row[1].equals(saved))throw new AccountException(409,"이미 저장된 호출 결과와 달라요.");return;}
        var a=parse(row[0],HybridGeneration.Assignment.class);var model=parse(row[2],AiSettings.Model.class);
        JsonNode usage=result==null?failure.usage():result.usage();BigDecimal cost=AiTasks.cost(model,usage);
        String error=failure==null?null:failure.code();JsonNode payload=result==null?null:result.value();
        // Keep cost evidence even when oversized or malformed model output cannot enter the DAG.
        if(payload!=null&&JudgeJson.canonical(payload).getBytes(StandardCharsets.UTF_8).length>HybridArtifacts.MAX_PAYLOAD_BYTES-8192) {
            payload=null;error="ARTIFACT_TOO_LARGE";
        }
        var observed=JudgeJson.JSON.createObjectNode().put("executor","OPENAI_API").put("billingMode","API")
                .put("attemptId",attempt.toString()).put("actualCostKnown",cost!=null);
        if(HybridModels.author(a.role()))observed.put("fallbackFrom","CODEX_CLI").put("fallbackReason",CODEX_QUOTA);
        // Full usage belongs to ai_attempt; bounded branch metadata avoids a second payload-size failure.
        jdbc.sql("UPDATE ai_attempt SET status=?,actual_usd=?,usage_json=?,request_id=?,response_id=?,provider_model=?,error_code=?,finished_at=CURRENT_TIMESTAMP WHERE id=?")
                .param(error==null?"HYBRID_COMPLETED":cost==null?"HYBRID_UNKNOWN":"HYBRID_FAILED").param(cost)
                .param(usage==null?null:usage.toString()).param(result==null?failure.requestId():result.requestId())
                .param(result==null?null:result.responseId()).param(result==null?null:result.model()).param(error).param(attempt).update();
        jdbc.sql("UPDATE hybrid_api_reservation SET receipt_json=? WHERE attempt_id=?").param(saved).param(attempt).update();
        jobs.complete(new HybridGeneration.Completion(a.branchId(),a.revision(),a.role(),a.token(),a.inputHash(),a.contractHash(),a.publicHash(),payload,observed,error));
        publication.advance();releaseStopped();events.publishEvent(new Wakeup());
    }
    @Transactional
    void recover() {
        lock();jobs.expirePending();publication.advance();routeBlockedAuthors();releaseStopped();
        // No automatic retry for a possibly billed request. Late receipts can still settle its cost.
        jdbc.sql("UPDATE ai_attempt SET status='HYBRID_UNKNOWN',error_code='INTERRUPTED_USAGE_UNKNOWN' WHERE status='HYBRID_RUNNING' AND id IN (SELECT r.attempt_id FROM hybrid_api_reservation r JOIN hybrid_generation g ON g.id=r.generation_id WHERE g.deadline_at<=CURRENT_TIMESTAMP)").update();
    }
    private void releaseStopped() {
        jdbc.sql("UPDATE ai_attempt SET status='HYBRID_RELEASED',actual_usd=0,finished_at=CURRENT_TIMESTAMP WHERE status='HYBRID_RESERVED' AND id IN (SELECT r.attempt_id FROM hybrid_api_reservation r JOIN hybrid_generation g ON g.id=r.generation_id WHERE (g.status IN ('FAILED','CANCELLED','DEADLINE_EXCEEDED','PUBLISHED') OR (g.status='HELD' AND (g.error_code IS NULL OR g.error_code NOT IN ('VALIDATION_ADAPTER_NOT_CONNECTED','CONTENT_REVIEW_REQUIRED')))) OR g.revision<>r.revision)").update();
    }
}
