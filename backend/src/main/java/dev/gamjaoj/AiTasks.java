package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamjaoj.ai.OpenAiResponses;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiTasks {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(AiTasks.class);
    private final JdbcClient jdbc;
    private final Submissions submissions;
    private final AiSettings config;
    public AiTasks(JdbcClient jdbc,Submissions submissions,AiSettings config) { this.jdbc=jdbc;this.submissions=submissions;this.config=config; }
    public record View(UUID id,UUID submissionId,String kind,String status,JsonNode result,String errorCode,String model,String effort,boolean problemHeld) {}
    record Work(UUID attemptId,UUID taskId,AiSettings.Model settings,String input) {}
    public record Budget(BigDecimal limitUsd,BigDecimal spentUsd,BigDecimal reservedUsd,boolean warning,boolean enabled,boolean keyConfigured) {}
    private void lock() { jdbc.sql("SELECT id FROM ai_budget_lock WHERE id=1 FOR UPDATE").query(Integer.class).single(); }
    private static String month() { return YearMonth.now(ZoneOffset.UTC).toString(); }
    private String json(Object value) { return JudgeJson.JSON.valueToTree(value).toString(); }
    private AiSettings.Model settings(String value) {
        try { return JudgeJson.JSON.readValue(value,AiSettings.Model.class); }
        catch(Exception e) { throw new IllegalStateException("Invalid stored AI settings"); }
    }
    @Transactional
    public View request(String username,UUID submission,String kind,String question,boolean strong) {
        if (strong) config.requireOperator(username);
        UUID owner=submissions.owner(username,true);
        if (!List.of("ANALYSIS","HINT").contains(kind) || question==null || question.length()>1000)
            throw new AccountException(400,"분석 요청 형식을 확인해 주세요.");
        return enqueue(owner,submission,kind,question,strong);
    }
    private View enqueue(UUID owner,UUID submission,String kind,String question,boolean strong) {
        var data=jdbc.sql("SELECT s.source_code,s.language,s.execution_profile_json,s.runtime_image,s.runner_policy,p.package_json,p.package_sha256,p.review_hold,p.diagnostic_only,j.result_sha256,j.status,j.verdict FROM submission s JOIN problem_version p ON p.id=s.problem_version JOIN judge_job j ON j.submission_id=s.id WHERE s.id=? AND s.user_id=? AND s.run_input IS NULL")
                .param(submission).param(owner).query((r,n)-> {
                    if(r.getBoolean("diagnostic_only"))throw new AccountException(409,"진단 문항의 분석은 진단 결과에서 제공할 예정이에요.");
                    if(r.getBoolean("review_hold"))throw new AccountException(409,"문제 검토 중에는 새 분석을 요청할 수 없어요.");
                    if (!"FINISHED".equals(r.getString("status")) || "IE".equals(r.getString("verdict")))
                        throw new AccountException(409,"정식 채점이 완료된 제출을 선택해 주세요. 시스템 오류는 학습 분석하지 않아요.");
                    var problem=JudgeJson.parse(r.getString("package_json"));
                    return JudgeJson.JSON.createObjectNode().put("kind",kind).put("question",question)
                            .put("title",problem.path("title").asText()).put("statement",problem.path("statement").asText())
                            .put("source",r.getString("source_code")).put("verdict",r.getString("verdict"))
                            .put("runtimeImage",r.getString("runtime_image")).put("language",r.getString("language")).put("executionProfile",r.getString("execution_profile_json")).put("runnerPolicy",r.getString("runner_policy")).put("judgeHash",r.getString("result_sha256"))
                            .put("problemHash",r.getString("package_sha256"));
                }).optional().orElseThrow(()->new AccountException(404,"제출 기록을 찾을 수 없어요."));
        AiSettings.Model model=config.model(strong);
        String configuration=json(model), input=data.toString();
        String cache=JudgeJson.hash(submission+":"+configuration+":"+input);
        var previous=jdbc.sql("SELECT id FROM ai_task WHERE user_id=? AND cache_key=?").param(owner).param(cache).query(UUID.class).optional();
        if (previous.isPresent()) return find(owner,previous.get());
        if (jdbc.sql("SELECT count(*) FROM ai_task WHERE user_id=? AND status IN ('QUEUED','RUNNING','HELD_DISABLED','HELD_BUDGET') AND NOT EXISTS (SELECT 1 FROM submission s JOIN problem_version p ON p.id=s.problem_version WHERE s.id=ai_task.submission_id AND p.review_hold=true)")
                .param(owner).query(Integer.class).single()>=3) throw new AccountException(429,"대기 중인 AI 요청이 끝나면 다시 요청해 주세요.");
        UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO ai_task (id,user_id,submission_id,kind,cache_key,settings_json,input_json,status) VALUES (?,?,?,?,?,?,?,?)")
                .param(id).param(owner).param(submission).param(kind).param(cache).param(configuration).param(input)
                .param(config.enabled()&&!config.key().isBlank()?"QUEUED":"HELD_DISABLED").update();
        return find(owner,id);
    }
    @Transactional
    public UUID theme(UUID owner,UUID generation,JsonNode input) {
        lock();
        String cache=JudgeJson.hash("theme-v1:"+generation);
        var existing=jdbc.sql("SELECT id FROM ai_task WHERE user_id=? AND cache_key=?").param(owner).param(cache).query(UUID.class).optional();
        if(existing.isPresent())return existing.get();
        var base=config.model(false);
        var model=new AiSettings.Model(base.model(),base.effort(),base.inputRate(),base.cachedRate(),base.outputRate(),base.pricingVersion(),768,"theme-v1","theme-v1");
        UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO ai_task (id,user_id,kind,cache_key,settings_json,input_json,status) VALUES (?,?,'THEME',?,?,?,?)")
                .param(id).param(owner).param(cache).param(json(model)).param(input.toString())
                .param(config.enabled()&&!config.key().isBlank()?"QUEUED":"HELD_DISABLED").update();
        return id;
    }
    @Transactional
    public UUID diagnostic(UUID owner,UUID session,JsonNode input) {
        var base=config.model(false);
        var model=new AiSettings.Model(base.model(),base.effort(),base.inputRate(),base.cachedRate(),base.outputRate(),base.pricingVersion(),
                8192,"diagnostic-v3","diagnostic-v2");
        String configuration=json(model),payload=JudgeJson.canonical(input),cache=JudgeJson.hash(configuration+":"+payload);
        var old=jdbc.sql("SELECT id FROM ai_task WHERE user_id=? AND cache_key=?").param(owner).param(cache).query(UUID.class).optional();
        if(old.isPresent())return old.get();
        if(jdbc.sql("SELECT count(*) FROM ai_task WHERE user_id=? AND status IN ('QUEUED','RUNNING','HELD_DISABLED','HELD_BUDGET')")
                .param(owner).query(Integer.class).single()>=3)throw new AccountException(429,"대기 중인 평가가 끝난 뒤 요청해 주세요.");
        UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO ai_task(id,user_id,diagnostic_session_id,kind,cache_key,settings_json,input_json,status) VALUES (?,?,?,'DIAGNOSTIC',?,?,?,?)")
                .param(id).param(owner).param(session).param(cache).param(configuration).param(payload)
                .param(config.enabled()&&!config.key().isBlank()?"QUEUED":"HELD_DISABLED").update();
        return id;
    }
    public List<View> list(String username,UUID submission) {
        UUID owner=submissions.owner(username,false);
        submissions.detail(username,submission); // A foreign submission must not even expose task existence.
        return jdbc.sql("SELECT id FROM ai_task WHERE user_id=? AND submission_id=? ORDER BY created_at DESC")
                .param(owner).param(submission).query(UUID.class).list().stream().map(id->find(owner,id)).toList();
    }
    public View detail(String username,UUID id) { return find(submissions.owner(username,false),id); }
    private View find(UUID owner,UUID id) {
        return jdbc.sql("SELECT a.*,COALESCE(p.review_hold,false) AS problem_held FROM ai_task a LEFT JOIN submission s ON s.id=a.submission_id LEFT JOIN problem_version p ON p.id=s.problem_version WHERE a.id=? AND a.user_id=?").param(id).param(owner).query((r,n)-> {
            var model=settings(r.getString("settings_json"));
            return new View(id,r.getObject("submission_id",UUID.class),r.getString("kind"),r.getString("status"),
                    r.getString("kind").equals("DIAGNOSTIC")||r.getString("result_json")==null?null:JudgeJson.parse(r.getString("result_json")),r.getString("error_code"),model.model(),model.effort(),r.getBoolean("problem_held"));
        }).optional().orElseThrow(()->new AccountException(404,"분석 기록을 찾을 수 없어요."));
    }
    @Transactional
    public View retry(String username,UUID id) {
        UUID owner=submissions.owner(username,true);
        View saved=find(owner,id);
        if(saved.kind().equals("DIAGNOSTIC"))throw new AccountException(409,"진단 평가 재시도는 아직 지원하지 않아요. 저장된 상태를 확인해 주세요.");
        if(saved.problemHeld())throw new AccountException(409,"문제 검토 중에는 재분석할 수 없어요.");
        if (List.of("FAILED","UNKNOWN").contains(saved.status())) {
            if (jdbc.sql("SELECT count(*) FROM ai_attempt WHERE task_id=?").param(id).query(Integer.class).single()>=3)
                throw new AccountException(409,"재시도 상한에 도달했어요. 운영자에게 문의해 주세요.");
            jdbc.sql("UPDATE ai_task SET status=?,error_code=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?")
                    .param(config.enabled()?"QUEUED":"HELD_DISABLED").param(id).update();
        }
        return find(owner,id);
    }
    public Budget budget(String username) { config.requireOperator(username);return budget(); }
    Budget budget() {
        BigDecimal spent=jdbc.sql("SELECT COALESCE(SUM(actual_usd),0) FROM ai_attempt WHERE month_key=?").param(month()).query(BigDecimal.class).single();
        BigDecimal reserved=jdbc.sql("SELECT COALESCE(SUM(reserved_usd),0) FROM ai_attempt WHERE actual_usd IS NULL").query(BigDecimal.class).single();
        return new Budget(config.budget(),spent,reserved,spent.add(reserved).compareTo(config.budget().multiply(new BigDecimal("0.8")))>=0,config.enabled(),!config.key().isBlank());
    }
    @Transactional
    public Work claim() {
        lock();
        // Never re-send a possibly charged request after a crash. Retain its reservation for reconciliation.
        var expired=jdbc.sql("SELECT id,task_id FROM ai_attempt WHERE status='RUNNING' AND started_at<?")
                .param(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5)).query((r,n)->new UUID[]{r.getObject(1,UUID.class),r.getObject(2,UUID.class)}).list();
        for (var row:expired) {
            jdbc.sql("UPDATE ai_attempt SET status='UNKNOWN',error_code='INTERRUPTED_USAGE_UNKNOWN' WHERE id=?").param(row[0]).update();
            jdbc.sql("UPDATE ai_task SET status='UNKNOWN',error_code='INTERRUPTED_USAGE_UNKNOWN' WHERE id=? AND status='RUNNING'").param(row[1]).update();
        }
        if (!config.enabled() || config.key().isBlank()) return null;
        if (jdbc.sql("SELECT count(*) FROM ai_attempt WHERE status IN ('RUNNING','HYBRID_RUNNING')").query(Integer.class).single()>0) return null;
        var next=jdbc.sql("SELECT id,settings_json,input_json FROM ai_task WHERE status IN ('QUEUED','HELD_DISABLED','HELD_BUDGET') AND NOT EXISTS (SELECT 1 FROM submission s JOIN problem_version p ON p.id=s.problem_version WHERE s.id=ai_task.submission_id AND p.review_hold=true) AND NOT EXISTS (SELECT 1 FROM generation_job g WHERE g.theme_task_id=ai_task.id AND g.error_code='STRUCTURE_EVIDENCE_REVOKED') AND NOT EXISTS (SELECT 1 FROM diagnostic_evaluation e JOIN diagnostic_session d ON d.id=e.session_id WHERE e.ai_task_id=ai_task.id AND e.exposure_revision<>d.exposure_revision) AND (diagnostic_session_id IS NULL OR (NOT EXISTS (SELECT 1 FROM diagnostic_session d WHERE d.user_id=ai_task.user_id AND d.status<>'COMPLETED') AND NOT EXISTS (SELECT 1 FROM diagnostic_item i JOIN problem_version p ON p.id=i.problem_version WHERE i.session_id=ai_task.diagnostic_session_id AND p.review_hold=true))) ORDER BY created_at,id LIMIT 1 FOR UPDATE")
                .query((r,n)->new Object[]{r.getObject("id",UUID.class),settings(r.getString("settings_json")),r.getString("input_json")}).optional();
        if (next.isEmpty()) return null;
        var row=next.get(); UUID task=(UUID)row[0]; var model=(AiSettings.Model)row[1]; String input=(String)row[2];
        // UTF-8 bytes conservatively bound text tokens, with explicit schema/instruction/framing allowance.
        boolean theme=JudgeJson.parse(input).path("kind").asText().equals("THEME");
        boolean diagnostic=JudgeJson.parse(input).path("kind").asText().equals("DIAGNOSTIC");
        long inputBound=input.getBytes(StandardCharsets.UTF_8).length+(theme?GenerationThemes.INSTRUCTIONS:diagnostic?DiagnosticEvaluationContract.INSTRUCTIONS:ResponsesFeedbackProvider.INSTRUCTIONS).getBytes(StandardCharsets.UTF_8).length
                +(theme?GenerationThemes.SCHEMA:diagnostic?DiagnosticEvaluationContract.schema(JudgeJson.parse(input)):ResponsesFeedbackProvider.SCHEMA).toString().getBytes(StandardCharsets.UTF_8).length+4096L;
        BigDecimal reserve=model.inputRate().multiply(BigDecimal.valueOf(inputBound))
                .add(model.outputRate().multiply(BigDecimal.valueOf(model.maxOutputTokens()))).movePointLeft(6).setScale(8,RoundingMode.CEILING);
        Budget budget=budget();
        if (budget.spentUsd().add(budget.reservedUsd()).add(reserve).compareTo(budget.limitUsd())>0) {
            jdbc.sql("UPDATE ai_task SET status='HELD_BUDGET',updated_at=CURRENT_TIMESTAMP WHERE id=?").param(task).update();
            notice(budget);return null;
        }
        UUID attempt=UUID.randomUUID();
        jdbc.sql("INSERT INTO ai_attempt (id,task_id,month_key,status,reserved_usd,settings_json) VALUES (?,?,?,'RUNNING',?,?)")
                .param(attempt).param(task).param(month()).param(reserve).param(json(model)).update();
        jdbc.sql("UPDATE ai_task SET status='RUNNING',error_code=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?").param(task).update();
        notice(budget());return new Work(attempt,task,model,input);
    }
    private void notice(Budget budget) {
        if (budget.warning() && jdbc.sql("SELECT count(*) FROM ai_budget_notice WHERE month_key=?").param(month()).query(Integer.class).single()==0)
            jdbc.sql("INSERT INTO ai_budget_notice (month_key) VALUES (?)").param(month()).update();
    }
    @Transactional
    public void finish(Work work,OpenAiResponses.Result result,OpenAiResponses.Failure failure) {
        lock();
        String state=jdbc.sql("SELECT status FROM ai_attempt WHERE id=?").param(work.attemptId()).query(String.class).single();
        if (!state.equals("RUNNING")) return;
        JsonNode usage=result!=null?result.usage():failure.usage();
        BigDecimal actual=cost(work.settings(),usage);
        String error=failure==null?null:failure.code();
        JsonNode output=result==null?null:result.value();
        boolean theme=JudgeJson.parse(work.input()).path("kind").asText().equals("THEME");
        boolean diagnostic=JudgeJson.parse(work.input()).path("kind").asText().equals("DIAGNOSTIC");
        if (output!=null && diagnostic) {
            String violation=DiagnosticEvaluationContract.violation(output,JudgeJson.parse(work.input()));
            if (violation!=null) log.warn("Diagnostic evaluation output rejected: attempt={} check={}",work.attemptId(),violation);
        }
        if (output!=null && !(theme?GenerationThemes.valid(output):diagnostic?DiagnosticEvaluationContract.valid(output,JudgeJson.parse(work.input())):validFeedback(output))) { error=theme?"INVALID_THEME":diagnostic?"INVALID_DIAGNOSTIC_EVIDENCE":"INVALID_FEEDBACK";output=null; }
        String status=output!=null?"COMPLETED":actual==null?"UNKNOWN":"FAILED";
        jdbc.sql("UPDATE ai_attempt SET status=?,actual_usd=?,usage_json=?,request_id=?,response_id=?,provider_model=?,error_code=?,finished_at=CURRENT_TIMESTAMP WHERE id=?")
                .param(status).param(actual).param(usage==null?null:usage.toString())
                .param(result!=null?result.requestId():failure.requestId()).param(result==null?null:result.responseId()).param(result==null?null:result.model()).param(error).param(work.attemptId()).update();
        jdbc.sql("UPDATE ai_task SET status=?,result_json=?,error_code=?,updated_at=CURRENT_TIMESTAMP WHERE id=?")
                .param(status).param(output==null?null:output.toString()).param(error).param(work.taskId()).update();
        notice(budget());
    }
    static boolean validFeedback(JsonNode value) {
        if (!value.isObject() || value.size()!=4) return false;
        for (String name:List.of("summary","uncertainty")) if (!value.path(name).isTextual() || value.path(name).asText().length()>6000) return false;
        for (String name:List.of("observations","nextSteps")) {
            if (!value.path(name).isArray() || value.path(name).size()>10) return false;
            for (JsonNode item:value.path(name)) if (!item.isTextual() || item.asText().length()>3000) return false;
        }
        return true;
    }
    static BigDecimal cost(AiSettings.Model settings,JsonNode usage) {
        if (usage==null || !usage.path("input_tokens").isIntegralNumber() || !usage.path("output_tokens").isIntegralNumber() || !usage.path("input_tokens").canConvertToLong() || !usage.path("output_tokens").canConvertToLong()) return null;
        long input=usage.path("input_tokens").asLong(),output=usage.path("output_tokens").asLong();
        long cached=usage.path("input_tokens_details").path("cached_tokens").asLong(0);
        if (input<0 || output<0 || cached<0 || cached>input) return null;
        return settings.inputRate().multiply(BigDecimal.valueOf(input-cached)).add(settings.cachedRate().multiply(BigDecimal.valueOf(cached)))
                .add(settings.outputRate().multiply(BigDecimal.valueOf(output))).movePointLeft(6).setScale(8,RoundingMode.CEILING);
    }
    @Transactional
    public void enqueueEndedSessions() {
        // Serialize with manual admission for each account; final pending judges are allowed to finish first.
        var ids=jdbc.sql("SELECT id,user_id FROM training_session WHERE status='ENDED' AND analysis_checked=false ORDER BY ended_at LIMIT 20")
                .query((r,n)->new UUID[]{r.getObject(1,UUID.class),r.getObject(2,UUID.class)}).list();
        for(var row:ids) {
            jdbc.sql("SELECT id FROM app_user WHERE id=? FOR UPDATE").param(row[1]).query(UUID.class).optional();
            if (jdbc.sql("SELECT count(*) FROM submission s JOIN judge_job j ON j.submission_id=s.id WHERE s.training_session_id=? AND j.status<>'FINISHED'").param(row[0]).query(Integer.class).single()>0) continue;
            var latest=jdbc.sql("SELECT s.id FROM submission s JOIN judge_job j ON j.submission_id=s.id WHERE s.training_session_id=? AND EXISTS (SELECT 1 FROM problem_version p WHERE p.id=s.problem_version AND p.review_hold=false) AND s.run_input IS NULL AND j.status='FINISHED' AND j.verdict<>'IE' ORDER BY s.created_at DESC,s.id DESC LIMIT 1")
                    .param(row[0]).query(UUID.class).optional();
            UUID task=null;
            if (latest.isPresent()) {
                try { task=enqueue(row[1],latest.get(),"ANALYSIS","",false).id(); }
                catch (AccountException e) { if (e.status==429 || e.status==503) continue;throw e; }
            }
            jdbc.sql("UPDATE training_session SET analysis_checked=true,analysis_task_id=? WHERE id=?").param(task).param(row[0]).update();
        }
    }
}
