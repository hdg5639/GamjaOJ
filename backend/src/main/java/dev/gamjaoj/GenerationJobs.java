package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GenerationJobs {
    private final GenerationSpecDrafts drafts;
    private final VerificationLedger ledger; private final GenerationEvidence executionEvidence; private final JdbcClient jdbc; private final Submissions submissions; private final AiSettings settings; private final AiTasks ai; private final GenerationStructures structures;
    public GenerationJobs(JdbcClient jdbc,Submissions submissions,AiSettings settings,AiTasks ai,GenerationStructures structures,GenerationSpecDrafts drafts,GenerationEvidence executionEvidence,VerificationLedger ledger) { this.ledger=ledger; this.executionEvidence=executionEvidence; this.drafts=drafts; this.structures=structures;this.jdbc=jdbc;this.submissions=submissions;this.settings=settings;this.ai=ai; }
    public record View(UUID id,String status,int revision,String model,String effort,String artifactHash,JsonNode artifacts,JsonNode validation,String error,JsonNode preview,String problemVersion,ThemeView theme,boolean problemHeld,String reviewReason) {}
    public record ThemeView(String status,String domain,JsonNode result,String error) {}
    public record Assignment(UUID id,UUID token,int revision,String model,String effort,JsonNode spec,String feedback,JsonNode repair,JsonNode reuse) {}
    private void lock() { jdbc.sql("SELECT id FROM ai_budget_lock WHERE id=1 FOR UPDATE").query(Integer.class).single(); }
    private String version(UUID id,int revision) { return "generated-"+id+"-r"+revision; }
    @Transactional
    public View create(String username,UUID key,String template) {
        return create(username,key,template,"basics");
    }
    @Transactional
    public View create(String username,UUID key,String template,String focus) {
        return create(username,key,template,focus,null);
    }
    @Transactional
    public View create(String username,UUID key,String template,String focus,UUID sourceAnalysis) {
        return create(username,key,template,focus,sourceAnalysis,false);
    }
    @Transactional
    public View create(String username,UUID key,String template,String focus,UUID sourceAnalysis,boolean shared) {
        UUID owner=submissions.owner(username,false);lock();
        if(drafts.contains(key))throw new AccountException(409,"이미 사용된 초안 요청 키예요.");
        var type=GenerationType.of(template);
        focus=GenerationChoices.normalize(type,focus);
        if(jdbc.sql("SELECT count(*) FROM generation_job WHERE id=?").param(key).query(Integer.class).single()>0) {
            View saved=view(username,key);
            Object[] original=jdbc.sql("SELECT focus,source_analysis_id,template_id,share_on_publish FROM generation_job WHERE id=?").param(key)
                    .query((r,n)->new Object[]{r.getString(1),r.getObject(2,UUID.class),r.getString(3),r.getBoolean(4)}).single();
            if(!java.util.Objects.equals(shared,original[3])||!template.equals(original[2])||!focus.equals(original[0])||!java.util.Objects.equals(sourceAnalysis,original[1]))
                throw new AccountException(409,"같은 요청 키의 연습 조건이 달라요. 기존 생성 기록을 확인해 주세요.");
            return saved;
        }
        if(HybridAdmission.active(jdbc,owner))throw new AccountException(409,"진행 중인 규칙 고정 출제를 먼저 마쳐 주세요.");
        if(drafts.active(owner))throw new AccountException(409,"진행 중인 출제 초안을 먼저 마쳐 주세요.");
        if(jdbc.sql("SELECT count(*) FROM generation_job WHERE owner_id=? AND status IN ('QUEUED','GENERATING','AWAITING_REVIEW','VALIDATING')").param(owner).query(Integer.class).single()>0)
            throw new AccountException(409,"진행 중인 생성 작업을 먼저 마쳐 주세요.");
        JsonNode learning=sourceAnalysis==null?null:learningContext(owner,sourceAnalysis,type);
        String model=settings.value("CODEX_GENERATION_MODEL","gpt-6.1-sol"),effort=settings.value("CODEX_GENERATION_REASONING","medium");
        if(!List.of("low","medium","high","xhigh","max").contains(effort)) throw new AccountException(503,"Codex reasoning 설정을 확인해 주세요.");
        jdbc.sql("INSERT INTO generation_job (id,owner_id,template_id,status,model,effort,focus) VALUES (?,?,?,'QUEUED',?,?,?)")
                .param(key).param(owner).param(template).param(model).param(effort).param(focus).update();
        jdbc.sql("UPDATE generation_job SET share_on_publish=? WHERE id=?").param(shared).param(key).update();
        if(learning!=null)jdbc.sql("UPDATE generation_job SET source_analysis_id=?,learning_context_json=? WHERE id=?")
                .param(sourceAnalysis).param(learning.toString()).param(key).update();
        structures.select(key,owner,type,focus);
        var recent=JudgeJson.JSON.createArrayNode();
        jdbc.sql("SELECT artifacts_json FROM generation_job WHERE owner_id=? AND id<>? AND artifacts_json IS NOT NULL ORDER BY created_at DESC LIMIT 12")
                .param(owner).param(key).query(String.class).list().forEach(raw->{var old=JudgeJson.parse(raw);recent.addObject().put("title",old.path("title").asText()).put("context",old.path("context").asText().substring(0,Math.min(800,old.path("context").asText().length())));});
        var used=jdbc.sql("SELECT theme_domain FROM generation_job WHERE owner_id=? AND theme_domain IS NOT NULL ORDER BY created_at DESC LIMIT 8").param(owner).query(String.class).list();
        var available=GenerationThemes.DOMAINS.stream().filter(domain->!used.contains(domain)).toList();
        String domain=available.get(new java.security.SecureRandom().nextInt(available.size()));
        var input=JudgeJson.JSON.createObjectNode().put("kind","THEME").put("domain",domain).put("trustedStatement",type.statement());input.set("recentStories",recent);
        UUID theme=ai.theme(owner,key,input);
        jdbc.sql("UPDATE generation_job SET theme_task_id=?,theme_domain=? WHERE id=?").param(theme).param(domain).param(key).update();
        return view(username,key);
    }
    public record LearningOption(UUID id,UUID submissionId,String summary) {}
    public List<LearningOption> learningOptions(String username) {return learningOptions(username,GenerationTemplate.ID);}
    public List<LearningOption> learningOptions(String username,String template) {
        var type=GenerationType.of(template);UUID owner=submissions.owner(username,false);
        return jdbc.sql("SELECT a.id,a.submission_id,a.result_json FROM ai_task a JOIN submission s ON s.id=a.submission_id WHERE a.user_id=? AND s.user_id=? AND a.kind='ANALYSIS' AND a.status='COMPLETED' AND EXISTS (SELECT 1 FROM problem_version p WHERE p.id=s.problem_version AND p.review_hold=false) AND (s.problem_version=? OR EXISTS (SELECT 1 FROM generation_job g WHERE g.template_id=? AND s.problem_version=CONCAT(CONCAT(CONCAT('generated-',CAST(g.id AS VARCHAR(36))),'-r'),CAST(g.revision AS VARCHAR(10))))) ORDER BY a.updated_at DESC,a.id LIMIT 10")
                .param(owner).param(owner).param(type.recipe==null?type.baseProblem:type.id).param(type.id).query((r,n)->new LearningOption(r.getObject(1,UUID.class),r.getObject(2,UUID.class),JudgeJson.parse(r.getString(3)).path("summary").asText())).list();
    }
    private JsonNode learningContext(UUID owner,UUID id,GenerationType type) {
        var data=jdbc.sql("SELECT a.result_json,s.problem_version FROM ai_task a JOIN submission s ON s.id=a.submission_id WHERE a.id=? AND a.user_id=? AND s.user_id=? AND a.kind='ANALYSIS' AND a.status='COMPLETED' AND EXISTS (SELECT 1 FROM problem_version p WHERE p.id=s.problem_version AND p.review_hold=false)")
                .param(id).param(owner).param(owner).query((r,n)->new String[]{r.getString(1),r.getString(2)}).optional()
                .orElseThrow(()->new AccountException(404,"완료된 본인의 풀이 분석을 선택해 주세요."));
        boolean compatible=data[1].equals(type.recipe==null?type.baseProblem:type.id) || jdbc.sql("SELECT count(*) FROM generation_job WHERE template_id=? AND ?=CONCAT(CONCAT(CONCAT('generated-',CAST(id AS VARCHAR(36))),'-r'),CAST(revision AS VARCHAR(10)))")
                .param(type.id).param(data[1]).query(Integer.class).single()>0;
        if(!compatible)throw new AccountException(400,"선택한 문제 유형과 같은 유형의 풀이 분석을 선택해 주세요.");
        JsonNode result=JudgeJson.parse(data[0]);
        if(!AiTasks.validFeedback(result))throw new AccountException(409,"분석 결과 형식을 확인해 주세요.");
        var context=JudgeJson.JSON.createObjectNode().put("summary",result.path("summary").asText());
        context.set("nextSteps",result.path("nextSteps").deepCopy());
        return context;
    }
    public List<View> list(String username) { return jdbc.sql("SELECT id FROM generation_job WHERE owner_id=? ORDER BY created_at DESC LIMIT 30").param(submissions.owner(username,false)).query(UUID.class).list().stream().map(id->view(username,id)).toList(); }
    public View view(String username,UUID id) { if(jdbc.sql("SELECT count(*) FROM generation_job WHERE id=? AND owner_id=?").param(id).param(submissions.owner(username,false)).query(Integer.class).single()==0)
        throw new AccountException(404,"생성 작업을 찾을 수 없어요.");return find(id); }
    private record JobRow(String status,int revision,String model,String effort,String hash,JsonNode artifacts,
                          JsonNode validation,String error,String template,boolean held,String reviewReason) {}
    private View find(UUID id) {
        // Materialize the row before enrichment: a mapper still owns its JDBC connection.
        var row=jdbc.sql("SELECT g.*,COALESCE(p.review_hold,false) AS problem_held,p.review_reason FROM generation_job g LEFT JOIN problem_version p ON p.id=CONCAT(CONCAT(CONCAT('generated-',CAST(g.id AS VARCHAR(36))),'-r'),CAST(g.revision AS VARCHAR(10))) WHERE g.id=?").param(id)
                .query((r,n)->new JobRow(r.getString("status"),r.getInt("revision"),r.getString("model"),r.getString("effort"),r.getString("artifacts_sha256"),
                    r.getString("artifacts_json")==null?null:JudgeJson.parse(r.getString("artifacts_json")),r.getString("validation_json")==null?null:JudgeJson.parse(r.getString("validation_json")),
                    r.getString("error_code"),r.getString("template_id"),r.getBoolean("problem_held"),r.getString("review_reason")))
                .optional().orElseThrow(()->new AccountException(404,"생성 작업을 찾을 수 없어요."));
        return new View(id,row.status,row.revision,row.model,row.effort,row.hash,row.artifacts,row.validation,row.error,
                preview(id,GenerationType.of(row.template)),row.status.equals("READY")?version(id,row.revision):null,themeFor(id),row.held,row.reviewReason);
    }
    private JsonNode preview(UUID id,GenerationType type) {
        var preview=type.spec().put("contractTitle",type.title);preview.set("structure",structures.summary(id,type));return preview;
    }
    private ThemeView themeFor(UUID id) {
        return jdbc.sql("SELECT a.status,a.result_json,a.error_code,g.theme_domain FROM generation_job g JOIN ai_task a ON a.id=g.theme_task_id WHERE g.id=?")
                .param(id).query((r,n)->new ThemeView(r.getString(1),r.getString(4),r.getString(2)==null?null:JudgeJson.parse(r.getString(2)),r.getString(3))).optional().orElse(null);
    }
    @Transactional
    public View retryTheme(String username,UUID id) {
        lock();var job=view(username,id);
        if(!ledger.valid(id)) {ledger.block(id);return find(id);}
        if(!List.of("QUEUED","THEME_FAILED").contains(job.status())||job.theme()==null)throw new AccountException(409,"테마 준비 중인 작업만 다시 요청할 수 있어요.");
        UUID task=jdbc.sql("SELECT theme_task_id FROM generation_job WHERE id=?").param(id).query(UUID.class).single();
        if(jdbc.sql("SELECT count(*) FROM generation_job WHERE owner_id=(SELECT owner_id FROM generation_job WHERE id=?) AND id<>? AND status IN ('QUEUED','GENERATING','AWAITING_REVIEW','VALIDATING')").param(id).param(id).query(Integer.class).single()>0)
            throw new AccountException(409,"현재 진행 중인 생성 작업을 먼저 마쳐 주세요.");
        ai.retry(username,task);
        jdbc.sql("UPDATE generation_job SET status='QUEUED',error_code=NULL WHERE id=?").param(id).update();return view(username,id);
    }
    private JsonNode recentStories(UUID id) {
        return jdbc.sql("SELECT a.input_json FROM generation_job g JOIN ai_task a ON a.id=g.theme_task_id WHERE g.id=?").param(id)
                .query(String.class).optional().map(raw->JudgeJson.parse(raw).path("recentStories")).orElse(JudgeJson.JSON.createArrayNode());
    }
    private GenerationType typeFor(UUID id) {return GenerationType.of(jdbc.sql("SELECT template_id FROM generation_job WHERE id=?").param(id).query(String.class).single());}
    private ObjectNode specFor(UUID id) {
        var type=typeFor(id);var spec=type.spec();
        String focus=jdbc.sql("SELECT focus FROM generation_job WHERE id=?").param(id).query(String.class).single();
        spec.put("learningFocus",java.util.Arrays.stream(focus.split(",")).map(type::focus).collect(java.util.stream.Collectors.joining("\n")));
        String learning=jdbc.sql("SELECT learning_context_json FROM generation_job WHERE id=?").param(id).query((r,n)->r.getString(1)).optional().orElse(null);
        if(learning!=null)spec.set("learnerFeedback",JudgeJson.parse(learning));
        var theme=themeFor(id);
        if(theme!=null&&theme.result()!=null){spec.set("theme",theme.result());spec.put("themeDomain",theme.domain());spec.set("recentStories",recentStories(id));}
        return spec;
    }
    private JsonNode repairFor(UUID id) {
        String raw=jdbc.sql("SELECT repair_json FROM generation_job WHERE id=?").param(id).query(String.class).optional().orElse(null);
        if(raw==null)return null;
        var repair=(ObjectNode)JudgeJson.parse(raw);
        var saved=find(id);
        repair.set("artifacts",saved.artifacts());
        repair.set("oracle",JudgeJson.parse(jdbc.sql("SELECT oracle_json FROM generation_job WHERE id=?").param(id).query(String.class).single()));
        return repair;
    }
    @Transactional
    public Assignment claim() {
        lock();drafts.expire();
        if(drafts.running())return null;
        jdbc.sql("UPDATE generation_job SET status='NEEDS_REVIEW',error_code='GENERATION_INTERRUPTED' WHERE status='GENERATING' AND lease_until<CURRENT_TIMESTAMP").update();
        if(jdbc.sql("SELECT count(*) FROM generation_job WHERE status='GENERATING'").query(Integer.class).single()>0) return null;
        var id=jdbc.sql("SELECT id FROM generation_job WHERE status='QUEUED' AND (theme_task_id IS NULL OR EXISTS (SELECT 1 FROM ai_task a WHERE a.id=theme_task_id AND a.status='COMPLETED')) ORDER BY created_at LIMIT 1 FOR UPDATE").query(UUID.class).optional();
        if(id.isEmpty())return drafts.claim();
        if(!ledger.valid(id.get())) {ledger.block(id.get());return null;}
        var job=find(id.get());UUID token=UUID.randomUUID();
        jdbc.sql("UPDATE generation_job SET status='GENERATING',token=?,lease_until=?,updated_at=CURRENT_TIMESTAMP WHERE id=?")
                .param(token).param(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(20)).param(id.get()).update();
        jdbc.sql("INSERT INTO generation_attempt (job_id,revision,model,effort,prompt_version) VALUES (?,?,?,?,'typed-author-oracle-v5')")
                .param(id.get()).param(job.revision()).param(job.model()).param(job.effort()).update();
        return new Assignment(id.get(),token,job.revision(),job.model(),job.effort(),specFor(id.get()),job.error(),repairFor(id.get()),structures.snapshot(id.get()));
    }
    @Transactional
    public void complete(UUID id,UUID token,JsonNode artifacts,JsonNode oracle,JsonNode usage,String error) {
        lock();
        if(drafts.contains(id)){drafts.complete(id,token,artifacts,oracle,usage,error);return;}
        View job=find(id);
        String savedToken=jdbc.sql("SELECT token FROM generation_job WHERE id=?").param(id).query(UUID.class).single().toString();
        if (!token.toString().equals(savedToken))throw new AccountException(409,"지난 생성 작업의 결과예요.");
        var envelope=JudgeJson.JSON.createObjectNode();envelope.set("artifacts",artifacts);envelope.set("oracle",oracle);envelope.set("usage",usage);envelope.put("error",error);
        String audit=JudgeJson.canonical(envelope);
        String previous=jdbc.sql("SELECT result_json FROM generation_attempt WHERE job_id=? AND revision=?").param(id).param(job.revision()).query(String.class).optional().orElse(null);
        if(previous!=null) { if(previous.equals(audit))return;throw new AccountException(409,"이미 저장된 생성 결과와 달라요."); }
        if(!job.status().equals("GENERATING") || jdbc.sql("SELECT count(*) FROM generation_job WHERE id=? AND lease_until>CURRENT_TIMESTAMP").param(id).query(Integer.class).single()!=1)
            throw new AccountException(409,"생성 작업의 유효 시간이 지났어요.");
        if(audit.length()>600_000) throw new AccountException(400,"생성 결과가 너무 커요.");
        String cliVersion=usage==null?"unknown":usage.path("cliVersion").asText("unknown");
        if(!List.of("0.154.0","0.155.1").contains(cliVersion))cliVersion="unknown";
        jdbc.sql("UPDATE generation_attempt SET result_json=?,cli_version=? WHERE job_id=? AND revision=?")
                .param(audit).param(cliVersion).param(id).param(job.revision()).update();
        if(!ledger.valid(id)) {ledger.block(id);return;}
        if(error!=null) {
            jdbc.sql("UPDATE generation_job SET status='NEEDS_AUTH',error_code=? WHERE id=?").param(error.substring(0,Math.min(80,error.length()))).param(id).update();return;
        }
        validateArtifacts(artifacts,oracle);
        JsonNode repair=repairFor(id);
        if(repair==null)structures.enforce(id,artifacts,oracle);
        if(repair!=null) {
            var changed=new java.util.HashSet<String>();
            repair.path("fields").forEach(field->changed.add(field.asText()));
            for(String field:List.of("title","context","reference","generator","inputValidator","editorial","hints"))
                if(!changed.contains(field)&&!artifacts.path(field).equals(repair.path("artifacts").path(field)))
                    throw new AccountException(400,"수정 대상이 아닌 산출물이 변경됐어요.");
            if(!changed.contains("oracle")&&!oracle.equals(repair.path("oracle")))
                throw new AccountException(400,"수정 대상이 아닌 oracle이 변경됐어요.");
        }
        String json=JudgeJson.canonical(artifacts),oracleJson=JudgeJson.canonical(oracle);
        String hash=JudgeJson.hash(json+"\n"+oracleJson);
        jdbc.sql("UPDATE generation_job SET status='AWAITING_REVIEW',artifacts_json=?,oracle_json=?,artifacts_sha256=?,review_sha256=NULL,error_code=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?")
                .param(json).param(oracleJson).param(hash).param(id).update();
        if(job.theme()!=null&&GenerationThemes.duplicate(artifacts,recentStories(id))) {
            fail(find(id),"STORY_TOO_SIMILAR",java.util.Set.of("title","context"));return;
        }
        String username=jdbc.sql("SELECT u.username FROM app_user u JOIN generation_job g ON g.owner_id=u.id WHERE g.id=?").param(id).query(String.class).single();
        review(username,id,hash,true);

    }
    static void validateArtifacts(JsonNode artifacts,JsonNode oracle) {
        if(artifacts==null||!artifacts.isObject()||artifacts.size()!=7||oracle==null||!oracle.isObject()||oracle.size()!=1)
            throw new AccountException(400,"생성 산출물의 필수 항목을 확인해 주세요.");
        for(String field:List.of("title","context","reference","generator","inputValidator","editorial")) {
            JsonNode value=artifacts.path(field);
            int max=List.of("reference","generator","inputValidator").contains(field)?65536:field.equals("title")?100:6000;
            if(!value.isTextual()||value.asText().isBlank()||value.asText().getBytes(StandardCharsets.UTF_8).length>max)
                throw new AccountException(400,"생성 산출물의 형식과 크기를 확인해 주세요.");
        }
        if(!oracle.path("source").isTextual()||oracle.path("source").asText().isBlank()||oracle.path("source").asText().getBytes(StandardCharsets.UTF_8).length>65536)
            throw new AccountException(400,"독립 oracle이 필요해요.");
        if(!artifacts.path("hints").isArray()||artifacts.path("hints").size()!=3) throw new AccountException(400,"단계형 힌트 3개가 필요해요.");
        for(var hint:artifacts.path("hints"))if(!hint.isTextual()||hint.asText().isBlank()||hint.asText().length()>2000)throw new AccountException(400,"힌트 형식을 확인해 주세요.");
    }
    @Transactional
    public View review(String username,UUID id,String hash,boolean approve) {
        lock();View job=view(username,id);
        if(approve && List.of("VALIDATING","READY").contains(job.status()) && java.util.Objects.equals(job.artifactHash(),hash)) return job;
        if(!job.status().equals("AWAITING_REVIEW")||!java.util.Objects.equals(job.artifactHash(),hash)) throw new AccountException(409,"현재 산출물을 다시 확인해 주세요.");
        if(!approve) { fail(job,"SEMANTIC_REVIEW_REJECTED");return find(id); }
        if(!ledger.valid(id)) {ledger.block(id);return find(id);}
        var type=typeFor(id);String ver=version(id,job.revision());
        var problem=JudgeJson.JSON.createObjectNode().put("version",ver).put("title",job.artifacts().path("title").asText())
                .put("statement",job.artifacts().path("context").asText()+"\n\n"+type.statement()).put("output_policy","TOKEN_EXACT");
        problem.putArray("tests").add(type.sample());
        String json=JudgeJson.canonical(problem);
        jdbc.sql("INSERT INTO problem_version (id,package_json,package_sha256,runtime_image,runner_policy,ready) SELECT ?,?,?,runtime_image,runner_policy,false FROM problem_version WHERE id=?")
                .param(ver).param(json).param(JudgeJson.hash(json)).param(type.baseProblem).update();
        jdbc.sql("UPDATE problem_version SET owner_id=(SELECT owner_id FROM generation_job WHERE id=?),shared=(SELECT share_on_publish FROM generation_job WHERE id=?) WHERE id=?").param(id).param(id).param(ver).update();
        jdbc.sql("UPDATE generation_job SET status='VALIDATING',review_sha256=?,validation_json=NULL WHERE id=?").param(hash).param(id).update();
        // Generator execution is an ordinary, low-priority Runner job; it receives no model credential.
        var plan=plan(ver,List.of(JudgeJson.JSON.createObjectNode().put("id","custom-input").put("input",new java.security.SecureRandom().nextLong()+"\n").put("output","")),true);
        execute(job,"generator",job.artifacts().path("generator").asText(),plan,"OK");
        return find(id);
    }
    /** Operator repair: preserve the old package/reports; rerun every gate without any model call. */
    @Transactional
    public View repairInputLayout(UUID id,String expectedHash) {
        lock();View old=find(id);var type=typeFor(id);
        String oldVersion=version(id,old.revision());
        var saved=jdbc.sql("SELECT package_json,package_sha256 FROM problem_version WHERE id=?").param(oldVersion)
                .query((r,n)->new String[]{r.getString(1),r.getString(2)}).optional();
        if(saved.isEmpty()||!saved.get()[1].equals(expectedHash)||!old.status().equals("READY"))
            throw new AccountException(409,"현재 게시 버전과 해시를 다시 확인해 주세요.");
        if(InputLayout.matches(type,JudgeJson.parse(saved.get()[0])))throw new AccountException(409,"입력 형식 수정 대상이 아니에요.");
        if(!ledger.valid(id))throw new AccountException(409,"원본 검증 근거부터 복구해야 해요.");
        var owner=jdbc.sql("SELECT g.owner_id,u.username FROM generation_job g JOIN app_user u ON u.id=g.owner_id WHERE g.id=?").param(id)
                .query((r,n)->new Object[]{r.getObject(1,UUID.class),r.getString(2)}).single();
        String reason="문제 설명과 테스트 줄 형식 불일치 · 수정 버전 전체 검증 중";
        ledger.revokeTree((UUID)owner[0],id,reason);
        jdbc.sql("UPDATE problem_version SET review_hold=true,review_reason=?,review_held_at=CURRENT_TIMESTAMP WHERE id LIKE ? AND review_hold=false")
                .param(reason).param("generated-"+id+"-r%").update();
        // Revisions >= 1 fail closed instead of entering the automatic paid repair path.
        jdbc.sql("UPDATE generation_job SET revision=revision+1,status='AWAITING_REVIEW',review_sha256=NULL,validation_json=NULL,error_code=NULL,structure_contract=? WHERE id=?").param(structures.contract(type)).param(id).update();
        return review((String)owner[1],id,old.artifactHash(),true);
    }

    private ObjectNode plan(String version,List<JsonNode> tests,boolean run) {
        ObjectNode plan=JudgeJson.JSON.createObjectNode().put("version",version).put("output_policy",run?"RUN_ONLY":"TOKEN_EXACT");
        var array=plan.putArray("tests");tests.forEach(array::add);return plan;
    }
    private void execute(View job,String role,String source,JsonNode plan,String expected) {
        UUID submission=UUID.randomUUID();String ver=version(job.id(),job.revision()),json=JudgeJson.canonical(plan);
        boolean run=plan.path("output_policy").asText().equals("RUN_ONLY");
        jdbc.sql("INSERT INTO submission (id,user_id,problem_version,source_code,source_sha256,idempotency_key,runtime_image,runner_policy,run_input,run_package,run_package_sha256,generation_job_id) SELECT ?,g.owner_id,?,?,?,?,p.runtime_image,?,?,?, ?,g.id FROM generation_job g JOIN problem_version p ON p.id=? WHERE g.id=?")
                .param(submission).param(ver).param(source).param(JudgeJson.hash(source)).param(submission)
                .param(run?"java8-run-v1":"java8-judge-v1").param(run?plan.path("tests").path(0).path("input").asText():"validation")
                .param(json).param(JudgeJson.hash(json)).param(ver).param(job.id()).update();
        jdbc.sql("INSERT INTO judge_job (submission_id,priority,execution_mode) VALUES (?,1,?)")
                .param(submission).param(JudgeScheduling.generated(role,plan)).update();
        jdbc.sql("INSERT INTO generation_execution (job_id,revision,role,submission_id,expected_verdict) VALUES (?,?,?,?,?)")
                .param(job.id()).param(job.revision()).param(role).param(submission).param(expected).update();
    }
    private void fail(View job,String error) {
        fail(job,error,java.util.Set.of("title","context","reference","generator","inputValidator","editorial","hints","oracle"));
    }
    private void fail(View job,String error,java.util.Set<String> fields) {
        var repair=JudgeJson.JSON.createObjectNode();
        var selected=repair.putArray("fields");fields.stream().sorted().forEach(selected::add);
        var checks=repair.putArray("failedChecks");
        jdbc.sql("SELECT e.role,j.verdict FROM generation_execution e JOIN judge_job j ON j.submission_id=e.submission_id WHERE e.job_id=? AND e.revision=? AND j.status='FINISHED' AND j.verdict<>e.expected_verdict")
                .param(job.id()).param(job.revision()).query((r,n)->r.getString(1)+":"+r.getString(2)).list().forEach(checks::add);
        // One repair budget per problem; preserve unrelated artifacts, then rerun every execution gate.
        jdbc.sql("UPDATE generation_job SET status=?,revision=?,error_code=?,repair_json=?,review_sha256=NULL WHERE id=?")
                .param(job.revision()<1?"QUEUED":"FAILED").param(job.revision()<1?1:job.revision()).param(error)
                .param(repair.toString()).param(job.id()).update();
    }
    @Transactional
    public void advance() {
        lock();drafts.advance();
        jdbc.sql("UPDATE generation_job SET status='THEME_FAILED',error_code='THEME_PREPARATION_FAILED' WHERE status='QUEUED' AND EXISTS (SELECT 1 FROM ai_task a WHERE a.id=theme_task_id AND a.status IN ('FAILED','UNKNOWN'))").update();
        jdbc.sql("UPDATE generation_job SET status='NEEDS_REVIEW',error_code='GENERATION_INTERRUPTED' WHERE status='GENERATING' AND lease_until<CURRENT_TIMESTAMP").update();
        var jobs=jdbc.sql("SELECT id FROM generation_job WHERE status='VALIDATING'").query(UUID.class).list();
        for(UUID id:jobs) {
            if(!ledger.valid(id)) {ledger.block(id);continue;}
            View job=find(id);
            var rows=jdbc.sql("SELECT e.role,e.expected_verdict,j.status,j.verdict,j.result_json FROM generation_execution e JOIN judge_job j ON j.submission_id=e.submission_id WHERE e.job_id=? AND e.revision=? ORDER BY e.role")
                    .param(id).param(job.revision()).query((r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5)}).list();
            if(rows.isEmpty()||rows.stream().anyMatch(r->!r[2].equals("FINISHED")))continue;
            var failed=rows.stream().filter(r->!r[1].equals(r[3])).toList();
            if(!failed.isEmpty()) {
                if(failed.stream().anyMatch(r->"IE".equals(r[3]))) {
                    jdbc.sql("UPDATE generation_job SET status='NEEDS_REVIEW',error_code='RUNNER_INFRASTRUCTURE_FAILURE' WHERE id=?").param(id).update();
                    continue;
                }
                var fields=new java.util.HashSet<String>();
                for(var row:failed) {
                    String role=row[0];
                    if(role.equals("generator"))fields.add("generator");
                    else if(role.startsWith("oracle-")||role.equals("final-oracle"))fields.add("oracle");
                    else if(role.startsWith("validator-"))fields.add("inputValidator");
                    else if(role.startsWith("reference-")||role.equals("final-reference"))fields.addAll(List.of("reference","editorial","hints"));
                    else {
                        // A trusted mutant behaving unexpectedly indicates a validation/infrastructure defect.
                        jdbc.sql("UPDATE generation_job SET status='NEEDS_REVIEW',error_code='TRUSTED_MUTANT_FAILURE' WHERE id=?").param(id).update();
                        fields.clear();break;
                    }
                }
                if(!fields.isEmpty())fail(job,"VALIDATION_"+failed.get(0)[0]+"_"+failed.get(0)[3],fields);
                continue;
            }
            var type=typeFor(id);String ver=version(id,job.revision());
            if(rows.size()==1) {
                var output=JudgeJson.parse(rows.get(0)[4]).path("tests").path(0);
                try {
                    if(output.path("stdout_truncated").asBoolean())throw new AccountException(400,"Generator output truncated");
                    String[] lines=output.path("stdout").asText().strip().split("\\R");
                    if(lines.length!=4)throw new AccountException(400,"Generator must produce four inputs");
                    List<JsonNode> cases=type.cases(new java.security.SecureRandom().nextLong());
                    for(int i=0;i<4;i++)cases.add(type.test("generated-"+i,lines[i]+"\n"));
                    String oracle=JudgeJson.parse(jdbc.sql("SELECT oracle_json FROM generation_job WHERE id=?").param(id).query(String.class).single()).path("source").asText();
                    for(int offset=0;offset<cases.size();offset+=20) {
                        var batch=cases.subList(offset,Math.min(offset+20,cases.size()));
                        var testPlan=plan(ver,batch,false);
                        execute(job,"reference-"+offset,job.artifacts().path("reference").asText(),testPlan,"AC");
                        execute(job,"oracle-"+offset,oracle,testPlan,"AC");
                        List<JsonNode> checks=batch.stream().map(t->(JsonNode)((ObjectNode)t.deepCopy()).put("output","VALID\n")).toList();
                        execute(job,"validator-"+offset,job.artifacts().path("inputValidator").asText(),plan(ver,checks,false),"AC");
                    }
                    var invalid=new ArrayList<JsonNode>();int idx=0;
                    for(String input:type.invalidInputs())
                        invalid.add(JudgeJson.JSON.createObjectNode().put("id","invalid-"+idx++).put("input",input).put("output","INVALID\n"));
                    execute(job,"validator-invalid",job.artifacts().path("inputValidator").asText(),plan(ver,invalid,false),"AC");
                    var boundary=type.mutantCases(cases);
                    execute(job,type.mutantRole(true),type.mutant(true),plan(ver,boundary,false),"WA");
                    execute(job,type.mutantRole(false),type.mutant(false),plan(ver,boundary,false),"WA");
                    // Fresh seed is sampled after artifact acceptance; it is never part of a repair prompt.
                    var finalCases=type.cases(new java.security.SecureRandom().nextLong());
                    finalCases=finalCases.subList(finalCases.size()-5,finalCases.size());
                    for (JsonNode test:finalCases) ((ObjectNode)test).put("id","final-"+test.path("id").asText());
                    execute(job,"final-reference",job.artifacts().path("reference").asText(),plan(ver,finalCases,false),"AC");
                    execute(job,"final-oracle",oracle,plan(ver,finalCases,false),"AC");
                    // Publish a bounded 20-case package only after every validation job succeeds.
                    List<JsonNode> published=new ArrayList<>();published.add(cases.get(0));
                    published.addAll(cases.stream().filter(t->{
                        String key=t.path("id").asText();
                        return !key.equals("sample")&&!key.startsWith("small-")
                                &&(!key.startsWith("seed-")||key.equals("seed-0")||key.equals("seed-1"));
                    }).limit(14).toList());published.addAll(finalCases);
                    var packagePlan=plan(ver,published,false).put("title",job.artifacts().path("title").asText())
                            .put("statement",job.artifacts().path("context").asText()+"\n\n"+type.statement());
                    String json=JudgeJson.canonical(packagePlan);
                    jdbc.sql("UPDATE problem_version SET package_json=?,package_sha256=? WHERE id=? AND ready=false").param(json).param(JudgeJson.hash(json)).param(ver).update();
                } catch(AccountException e) { fail(job,"INVALID_GENERATOR_INPUT",java.util.Set.of("generator")); }
            } else {
                if(rows.size()!=type.executions()) throw new IllegalStateException("Incomplete generation gate set");
                if(!job.artifactHash().equals(jdbc.sql("SELECT review_sha256 FROM generation_job WHERE id=?").param(id).query(String.class).single()))throw new IllegalStateException("Generation review fence mismatch");
                // Check the exact bytes being published, including jobs started before deployment.
                var publication=JudgeJson.parse(jdbc.sql("SELECT package_json FROM problem_version WHERE id=?").param(ver).query(String.class).single());
                if(!InputLayout.matches(type,publication)) {
                    fail(job,"INPUT_LAYOUT_MISMATCH",java.util.Set.of("generator"));continue;
                }
                var report=JudgeJson.JSON.createObjectNode().put("policy",type.id+"-validation-v4").put("artifactHash",job.artifactHash())
                        .put("smallDomain",type.smallDomain()).put("gates","V01-V08").put("executions",rows.size());
                var evidence=report.putArray("results");for(var row:rows)evidence.addObject().put("role",row[0]).put("verdict",row[3]).put("reportHash",JudgeJson.hash(row[4]));
                var manifest=executionEvidence.capture(id,job.revision(),type);
                report.set("executionInputAudit",manifest);
                var reuse=structures.snapshot(id);
                if(reuse!=null) report.set("reuseAudit",GenerationEvidence.compare(manifest,reuse.path("executionInputAudit"))
                        .put("sourceJobId",reuse.path("sourceJobId").asText()));
                long referenceMs=rows.stream().filter(r->r[0].contains("reference")).mapToLong(r->ProblemTimeLimits.maximum(JudgeJson.parse(r[4]))).max().orElse(0);
                String limits;
                try {limits=ProblemTimeLimits.measured(referenceMs);}catch(HybridArtifacts.Invalid invalid){fail(job,invalid.getMessage(),java.util.Set.of("reference"));continue;}
                report.set("timeLimits",JudgeJson.parse(limits));
                report.put("evidenceId",ledger.freeze(id,job.revision(),report).toString());
                var teaching=JudgeJson.JSON.createObjectNode().put("editorial",job.artifacts().path("editorial").asText());teaching.set("hints",job.artifacts().path("hints"));
                jdbc.sql("UPDATE problem_version SET ready=true,time_limits_json=?,teaching_json=?,shared=(SELECT share_on_publish FROM generation_job WHERE id=?) WHERE id=? AND ready=false").param(limits).param(teaching.toString()).param(job.id()).param(ver).update();
                jdbc.sql("UPDATE generation_job SET status='READY',validation_json=?,updated_at=CURRENT_TIMESTAMP WHERE id=?").param(report.toString()).param(id).update();
            }
        }
    }
}
