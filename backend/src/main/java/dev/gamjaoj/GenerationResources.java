package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Same frozen package, actual three-language measurements, ordinary alternative and budget replays. */
final class GenerationResources {
    static boolean enabled(JdbcClient jdbc,String pipeline,UUID id) {
        String table=switch(pipeline){case "TAG"->"generation_job";case "DIRECT"->"generation_spec_draft";case "RULE"->"hybrid_generation";default->throw new IllegalArgumentException();};
        return jdbc.sql("SELECT resource_validation FROM "+table+" WHERE id=?").param(id).query(Boolean.class).single();
    }
    static JsonNode resourceDefinition(String pipeline,JsonNode original) {
        var definition=(ObjectNode)original.deepCopy();
        definition.remove(List.of("validationPolicy","theme","themeDomain","recentStories","learnerFeedback","learningFocus"));
        if("TAG".equals(pipeline))definition.remove(List.of("generatorContract","inputLayoutPolicy"));
        definition.put("maximumInputContract","Main reads one integer seed 0..3 and emits ONE complete legal problem input at the public bounds. This is separate from the preliminary small-case/transport generator contract; do not emit four transport records.");
        return definition;
    }
    static String ensure(JdbcClient jdbc,String pipeline,UUID job,String version,JsonNode definition,String reference,String validator,String existingLimits) {
        if(!enabled(jdbc,pipeline,job))return existingLimits;
        definition=resourceDefinition(pipeline,definition);
        var pack=jdbc.sql("SELECT package_json,package_sha256 FROM problem_version WHERE id=? AND ready=false").param(version).query((r,n)->new String[]{r.getString(1),r.getString(2)}).single();
        HybridArtifacts.require(JudgeJson.hash(pack[0]).equals(pack[1]),"RESOURCE_PACKAGE_FENCE");
        var input=JudgeJson.JSON.createObjectNode().put("phase","RESOURCE_QUALIFICATION").put("problemVersion",version).put("packageHash",pack[1]).put("reference",reference).put("validator",validator);
        input.set("definition",definition.deepCopy());input.set("package",JudgeJson.parse(pack[0]));GenerationValidationPolicy.attach(input,definition);
        String raw=JudgeJson.canonical(input),fence=JudgeJson.hash(raw);
        var saved=jdbc.sql("SELECT id,status,limits_json,error_code FROM generation_resource_check WHERE pipeline=? AND job_id=? AND fence=?").param(pipeline).param(job).param(fence).query((r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4)}).optional();
        if(saved.isEmpty()){jdbc.sql("UPDATE generation_resource_check SET status='SUPERSEDED' WHERE pipeline=? AND job_id=? AND fence<>? AND status NOT IN ('PASSED','SUPERSEDED')").param(pipeline).param(job).param(fence).update();if("RULE".equals(pipeline))jdbc.sql("UPDATE hybrid_generation SET deadline_at=CASE WHEN deadline_at<? THEN ? ELSE deadline_at END WHERE id=? AND status IN ('HELD','REVIEWING')").param(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30)).param(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30)).param(job).update();jdbc.sql("INSERT INTO generation_resource_check(id,pipeline,job_id,problem_version,fence,input_json,status) VALUES (?,?,?,?,?,?,'QUEUED')").param(UUID.randomUUID()).param(pipeline).param(job).param(version).param(fence).param(raw).update();return null;}
        var row=saved.get();
        if("PASSED".equals(row[1]))return row[2];
        if("FAILED".equals(row[1])&&row[3]!=null&&row[3].startsWith("RESOURCE_REFERENCE_"))throw new HybridArtifacts.Invalid(row[3]);
        if("FAILED".equals(row[1])||"NEEDS_REVIEW".equals(row[1]))throw new HybridArtifacts.Invalid("RESOURCE_RETRY_LIMIT_"+(row[3]==null?"CHECK_FAILED":row[3]));
        return null;
    }
    static JsonNode progress(JdbcClient jdbc,String pipeline,UUID id) {
        return jdbc.sql("SELECT status,error_code FROM generation_resource_check WHERE pipeline=? AND job_id=? ORDER BY created_at DESC LIMIT 1").param(pipeline).param(id)
            .query((r,n)->(JsonNode)JudgeJson.JSON.createObjectNode().put("status",r.getString(1)).put("error",r.getString(2))).optional().orElse(null);
    }
    static boolean running(JdbcClient jdbc){return jdbc.sql("SELECT count(*) FROM generation_resource_check WHERE status='GENERATING'").query(Integer.class).single()>0;}
    static GenerationJobs.Assignment claim(JdbcClient jdbc,AiSettings settings) {
        var row=jdbc.sql("SELECT c.id,c.input_json,c.error_code,c.retries FROM generation_resource_check c WHERE c.status='QUEUED' AND EXISTS (SELECT 1 FROM problem_version p WHERE p.id=c.problem_version AND p.ready=false) AND NOT EXISTS (SELECT 1 FROM diagnostic_practice_plan p WHERE (p.generation_id=c.job_id OR p.hybrid_generation_id=c.job_id) AND (p.training_session_id IS NOT NULL OR EXISTS (SELECT 1 FROM learning_curriculum_end e WHERE e.evaluation_id=p.evaluation_id AND e.user_id=p.user_id) OR EXISTS (SELECT 1 FROM diagnostic_practice_plan n WHERE n.previous_plan_id=p.id))) AND (c.pipeline<>'RULE' OR EXISTS (SELECT 1 FROM hybrid_generation g WHERE g.id=c.job_id AND g.status IN ('HELD','REVIEWING') AND g.deadline_at>CURRENT_TIMESTAMP)) ORDER BY c.created_at LIMIT 1 FOR UPDATE").query((r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4)}).optional();
        if(row.isEmpty())return null;UUID id=UUID.fromString(row.get()[0]),token=UUID.randomUUID();
        var savedInput=JudgeJson.parse(row.get()[1]);
        String current=jdbc.sql("SELECT package_sha256 FROM problem_version WHERE id=?").param(savedInput.path("problemVersion").asText()).query(String.class).single();
        if(!current.equals(savedInput.path("packageHash").asText())){jdbc.sql("UPDATE generation_resource_check SET status='SUPERSEDED' WHERE id=?").param(id).update();return null;}
        jdbc.sql("UPDATE generation_resource_check SET status='GENERATING',token=?,lease_until=? WHERE id=?").param(token).param(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(20)).param(id).update();
        var spec=(ObjectNode)JudgeJson.parse(row.get()[1]);if(row.get()[2]!=null)spec.put("recoveryFeedback","Resource-stage retry "+row.get()[3]+": "+row.get()[2]+". Preserve the supplied frozen definition and original reference; repair the qualification sources/generator.");
        if(Integer.parseInt(row.get()[3])>0) {
            var previous=jdbc.sql("SELECT completion_json FROM generation_resource_attempt WHERE check_id=? ORDER BY attempt DESC LIMIT 1").param(id).query(String.class).optional().filter(Objects::nonNull).map(JudgeJson::parse);
            if(previous.isPresent()){spec.set("previousResourceArtifacts",previous.get().path("artifacts"));spec.set("previousMaximumIssues",previous.get().path("oracle").path("issues"));}
        }
        return new GenerationJobs.Assignment(id,token,Integer.parseInt(row.get()[3]),settings.value("CODEX_GENERATION_MODEL","gpt-6.1-sol"),settings.value("CODEX_GENERATION_REASONING","medium"),spec,null,null,null);
    }
    static boolean contains(JdbcClient jdbc,UUID id){return jdbc.sql("SELECT count(*) FROM generation_resource_check WHERE id=?").param(id).query(Integer.class).single()>0;}
    static void complete(JdbcClient jdbc,UUID id,UUID token,JsonNode artifacts,JsonNode oracle,JsonNode usage,String error) {
        String old=GenerationDraftRecovery.receipt(jdbc,id,token);
        if(old!=null){var receipt=JudgeJson.JSON.createObjectNode();receipt.set("artifacts",artifacts);receipt.set("oracle",oracle);receipt.set("usage",usage);receipt.put("error",error);if(old.equals(JudgeJson.canonical(receipt)))return;throw new AccountException(409,"이전 자원 검증 결과와 달라요.");}
        var row=jdbc.sql("SELECT token,status,input_json,completion_json FROM generation_resource_check WHERE id=? FOR UPDATE").param(id).query((r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4)}).single();
        if(!token.toString().equals(row[0]))throw new AccountException(409,"이전 자원 검증 작업이에요.");
        var envelope=JudgeJson.JSON.createObjectNode();envelope.set("artifacts",artifacts);envelope.set("oracle",oracle);envelope.set("usage",usage);envelope.put("error",error);String audit=JudgeJson.canonical(envelope);
        if(row[3]!=null){if(row[3].equals(audit))return;throw new AccountException(409,"저장된 자원 검증 결과와 달라요.");}
        if("SUPERSEDED".equals(row[1])){jdbc.sql("UPDATE generation_resource_check SET completion_json=? WHERE id=?").param(audit).param(id).update();return;}
        if(!"GENERATING".equals(row[1])||jdbc.sql("SELECT count(*) FROM generation_resource_check WHERE id=? AND lease_until>CURRENT_TIMESTAMP").param(id).query(Integer.class).single()!=1)throw new AccountException(409,"자원 검증 작업 시간이 지났어요.");
        if(audit.length()>600000)throw new AccountException(400,"자원 검증 산출물이 너무 커요.");
        jdbc.sql("UPDATE generation_resource_check SET completion_json=? WHERE id=?").param(audit).param(id).update();
        if(error!=null){fail(jdbc,id,error);return;}
        try {
            HybridArtifacts.fields(artifacts,"cpp","python","ordinaryJava","maximumGenerator","coverage");
            for(String key:List.of("cpp","python","ordinaryJava","maximumGenerator"))HybridArtifacts.require(artifacts.path(key).isTextual()&&!artifacts.path(key).asText().isBlank()&&artifacts.path(key).asText().length()<=65536,"INVALID_RESOURCE_SOURCE");
            HybridArtifacts.fields(oracle,"accepted","issues");
            HybridArtifacts.require(oracle.path("accepted").isBoolean()&&oracle.path("issues").isArray()&&oracle.path("issues").size()<=8,"INVALID_MAXIMUM_REVIEW");
            HybridArtifacts.require(oracle.path("accepted").asBoolean()&&oracle.path("issues").isEmpty(),"MAXIMUM_COVERAGE_REJECTED");
            HybridArtifacts.require(artifacts.path("coverage").isArray()&&artifacts.path("coverage").size()==4,"INVALID_MAXIMUM_COVERAGE");
            var input=JudgeJson.parse(row[2]);var covered=new HashSet<String>();var seeds=new HashSet<Integer>();
            for(var item:artifacts.path("coverage")) {
                HybridArtifacts.fields(item,"seed","checks","reason");HybridArtifacts.text(item.path("reason"),2000);
                HybridArtifacts.require(item.path("seed").isIntegralNumber()&&item.path("seed").asInt()>=0&&item.path("seed").asInt()<=3&&seeds.add(item.path("seed").asInt()),"INVALID_MAXIMUM_SEEDS");
                HybridArtifacts.texts(item.path("checks"),1,64,120);for(var check:item.path("checks"))covered.add(check.asText());
            }
            var required=new HashSet<String>();for(var check:input.path("validationPolicy").path("commonChecks"))required.add(check.asText());
            for(var profile:input.path("validationPolicy").path("profiles"))for(var check:profile.path("checks"))required.add(check.asText());
            HybridArtifacts.require(covered.containsAll(required),"MISSING_PROFILE_COVERAGE");
            HybridArtifacts.require(!artifacts.path("ordinaryJava").asText().equals(input.path("reference").asText()),"ORDINARY_REFERENCE_REQUIRED");
            jdbc.sql("UPDATE generation_resource_check SET artifacts_json=?,status='MEASURING' WHERE id=?").param(JudgeJson.canonical(artifacts)).param(id).update();
            submit(jdbc,id,"validator",input.path("validator").asText(),"JAVA",true,null);
            for(int repeat=0;repeat<2;repeat++)for(String language:ProblemTimeLimits.LANGUAGES)submit(jdbc,id,"measure-"+language+"-"+repeat,source(input,artifacts,language),language,false,null);
            for(int repeat=0;repeat<2;repeat++)submit(jdbc,id,"ordinary-JAVA-"+repeat,artifacts.path("ordinaryJava").asText(),"JAVA",false,null);
        }catch(HybridArtifacts.Invalid e){fail(jdbc,id,e.getMessage());}
    }
    private static String source(JsonNode input,JsonNode artifacts,String language){return language.equals("JAVA")?input.path("reference").asText():artifacts.path(language.equals("CPP")?"cpp":"python").asText();}
    private static void submit(JdbcClient jdbc,UUID id,String role,String source,String language,boolean validator,String limits) {
        var row=jdbc.sql("SELECT input_json,artifacts_json,problem_version FROM generation_resource_check WHERE id=?").param(id).query((r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3)}).single();
        var input=JudgeJson.parse(row[0]);var artifacts=JudgeJson.parse(row[1]);
        var plan=(ObjectNode)input.path("package").deepCopy();plan.remove("api");plan.remove("generated");
        if(validator)for(var test:plan.path("tests"))((ObjectNode)test).put("output","VALID\n");
                var original=input.path("package");String helperReference=input.path("reference").asText();
        if(original.has("api")) {
            var api=original.path("api").path("api");
            helperReference=CallablePrograms.executable(api,helperReference);
            if(!validator)plan.set("callable",NativeCallablePrograms.bundleForProblem(original,language,false));
        }
        plan.set("generated",HybridRulePackage.generated(artifacts.path("maximumGenerator").asText(),List.of("0","1","2","3"),helperReference,validator?"VALID":"REFERENCE"));
        String raw=JudgeJson.canonical(plan);UUID submission=UUID.randomUUID();
        var profile=(ObjectNode)LanguageProfiles.profile(language,limits);if(limits==null)profile.put("testWallSeconds",180);
        jdbc.sql("INSERT INTO submission(id,user_id,problem_version,source_code,source_sha256,idempotency_key,runtime_image,runner_policy,language,execution_profile_json,run_input,run_package,run_package_sha256) SELECT ?,p.owner_id,?,?,?,?,?,?,?,?,?,?,? FROM problem_version p WHERE p.id=?")
            .param(submission).param(row[2]).param(source).param(JudgeJson.hash(source)).param(submission).param(profile.path("image").asText()).param(profile.path("policy").asText()).param(language).param(JudgeJson.canonical(profile)).param("resource-qualification").param(raw).param(JudgeJson.hash(raw)).param(row[2]).update();
        var origin=jdbc.sql("SELECT pipeline,job_id FROM generation_resource_check WHERE id=?").param(id).query((r,n)->new String[]{r.getString(1),r.getString(2)}).single();
        String marker=origin[0].equals("TAG")?"generation_job_id":origin[0].equals("DIRECT")?"spec_draft_id":"hybrid_branch_id";
        UUID originId=origin[0].equals("RULE")?UUID.fromString(row[2].substring("hybrid-check-".length())):UUID.fromString(origin[1]);
        jdbc.sql("UPDATE submission SET "+marker+"=? WHERE id=?").param(originId).param(submission).update();
        jdbc.sql("INSERT INTO judge_job(submission_id,priority,execution_mode) VALUES (?,1,'EXCLUSIVE')").param(submission).update();
        jdbc.sql("INSERT INTO generation_resource_execution(check_id,role,submission_id) VALUES (?,?,?)").param(id).param(role).param(submission).update();
    }
    static void advance(JdbcClient jdbc) {
        jdbc.sql("UPDATE generation_resource_check SET status='NEEDS_REVIEW',error_code='GENERATION_INTERRUPTED' WHERE status='GENERATING' AND lease_until<CURRENT_TIMESTAMP").update();
        for(var id:jdbc.sql("SELECT id FROM generation_resource_check WHERE status IN ('MEASURING','REPLAYING')").query(UUID.class).list()) {
            var invalidProfiles=jdbc.sql("SELECT s.id,s.runtime_image,s.runner_policy,s.execution_profile_json FROM generation_resource_execution e JOIN submission s ON s.id=e.submission_id WHERE e.check_id=?").param(id).query((r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4)}).list().stream().filter(r->{var p=JudgeJson.parse(r[3]);return !p.path("image").asText().equals(r[1])||!p.path("policy").asText().equals(r[2]);}).toList();
            if(!invalidProfiles.isEmpty()) {
                // These snapshots are rejected before a compatible worker starts a sandbox. Keep them as superseded evidence.
                String report="{\"verdict\":\"IE\",\"error\":\"resource execution profile superseded\"}";
                for(var invalid:invalidProfiles) {
                    UUID submission=UUID.fromString(invalid[0]);
                    jdbc.sql("UPDATE judge_attempt SET status='SUPERSEDED',result_json=?,finished_at=CURRENT_TIMESTAMP WHERE submission_id=? AND status='RUNNING'").param(report).param(submission).update();
                    jdbc.sql("UPDATE judge_job SET status='FINISHED',verdict='IE',result_json=?,result_sha256=?,finished_at=CURRENT_TIMESTAMP WHERE submission_id=? AND status<>'FINISHED'").param(report).param(JudgeJson.hash(report)).param(submission).update();
                }
                // Let valid in-flight jobs settle before beginning another exclusive measurement batch.
                if(jdbc.sql("SELECT count(*) FROM generation_resource_execution e JOIN judge_job j ON j.submission_id=e.submission_id WHERE e.check_id=? AND j.status<>'FINISHED'").param(id).query(Integer.class).single()==0)fail(jdbc,id,"RESOURCE_EXECUTION_PROFILE_MISMATCH");
                continue;
            }
            var rows=jdbc.sql("SELECT e.role,j.status,j.verdict,j.result_json FROM generation_resource_execution e JOIN judge_job j ON j.submission_id=e.submission_id WHERE e.check_id=? ORDER BY e.role").param(id).query((r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4)}).list();
            if(rows.isEmpty()||rows.stream().anyMatch(r->!"FINISHED".equals(r[1])))continue;
            try {
                for(var row:rows)HybridArtifacts.require("AC".equals(row[2]),"RESOURCE_"+row[0]+"_"+row[2]);
                String state=jdbc.sql("SELECT status FROM generation_resource_check WHERE id=?").param(id).query(String.class).single();
                if(state.equals("MEASURING")) {
                    HybridArtifacts.require(rows.size()==9,"RESOURCE_INCOMPLETE_EVIDENCE");
                    var limits=limits(rows);
                    String raw=JudgeJson.canonical(limits);jdbc.sql("UPDATE generation_resource_check SET limits_json=?,status='REPLAYING' WHERE id=?").param(raw).param(id).update();
                    var input=JudgeJson.parse(jdbc.sql("SELECT input_json FROM generation_resource_check WHERE id=?").param(id).query(String.class).single());var artifacts=JudgeJson.parse(jdbc.sql("SELECT artifacts_json FROM generation_resource_check WHERE id=?").param(id).query(String.class).single());
                    for(var language:ProblemTimeLimits.LANGUAGES)submit(jdbc,id,"replay-"+language,source(input,artifacts,language),language,false,raw);
                    submit(jdbc,id,"replay-ordinary-JAVA",artifacts.path("ordinaryJava").asText(),"JAVA",false,raw);
                } else {
                    HybridArtifacts.require(rows.size()==13,"RESOURCE_INCOMPLETE_REPLAY");
                    // Replays must also report actual positive cgroup observations, never zero/estimated memory.
                    limits(rows.stream().filter(r->r[0].startsWith("replay-")).toList());
                    var report=JudgeJson.JSON.createObjectNode().put("policy","GENERATION_VALIDATION_V1").put("scope","Bounded maximum-shape verification, not proof of worst-case completeness").put("threeLanguagesMeasured",true).put("ordinaryJavaMeasured",true);
                    var frozen=JudgeJson.parse(jdbc.sql("SELECT artifacts_json FROM generation_resource_check WHERE id=?").param(id).query(String.class).single());report.set("coverage",frozen.path("coverage"));
                    var evidence=report.putArray("results");for(var row:rows)evidence.addObject().put("role",row[0]).put("verdict",row[2]).put("reportHash",JudgeJson.hash(row[3]));
                    jdbc.sql("UPDATE generation_resource_check SET status='PASSED',report_json=? WHERE id=?").param(report.toString()).param(id).update();
                }
            }catch(HybridArtifacts.Invalid e){fail(jdbc,id,e.getMessage());}
        }
    }
    private static ObjectNode limits(List<String[]> rows) {
        var limits=JudgeJson.JSON.createObjectNode();var memory=limits.putObject("memory");
        for(var language:ProblemTimeLimits.LANGUAGES) {
            long maximum=0,peak=0;int observations=0;
            for(var row:rows)if(!row[0].equals("validator")&&row[0].contains(language))for(var test:JudgeJson.parse(row[3]).path("tests")) {
                HybridArtifacts.require(test.path("memory_peak_bytes").isIntegralNumber()&&test.path("memory_peak_bytes").asLong()>0&&"cgroup-peak-observed".equals(test.path("memory_measurement").asText()),"RESOURCE_MEMORY_OBSERVATION_MISSING");
                maximum=Math.max(maximum,test.path("wall_ms").asLong());peak=Math.max(peak,test.path("memory_peak_bytes").asLong());observations++;
            }
            HybridArtifacts.require(observations>0&&maximum>0,"RESOURCE_TIME_OBSERVATION_MISSING");
            int floor=language.equals("CPP")?3:language.equals("JAVA")?5:8;
            double seconds=Math.max(floor,Math.ceil((maximum*3+1000)/250.0)*.25);
            HybridArtifacts.require(seconds<=180,"TIME_LIMIT_CAPACITY_EXCEEDED");limits.put(language,seconds);
            int memoryFloor=language.equals("JAVA")?96:language.equals("PYTHON")?48:32;
            int mb=Math.max(memoryFloor,(int)(Math.ceil((peak/1048576.0*1.2+8)/16)*16));
            HybridArtifacts.require(mb<=LanguageProfiles.profile(language).path("memoryMb").asInt(),"MEMORY_LIMIT_CAPACITY_EXCEEDED");memory.put(language,mb);
        }
        limits.put("analysis","Three-language cgroup measurements and ordinary Java alternative; GENERAL 3x + startup allowance, replayed within final budgets.");
        ProblemTimeLimits.validate(limits);return limits;
    }
    private static void fail(JdbcClient jdbc,UUID id,String error) {
        var row=jdbc.sql("SELECT retries,completion_json,artifacts_json,token FROM generation_resource_check WHERE id=? FOR UPDATE").param(id).query((r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4)}).single();
        int attempts=Integer.parseInt(row[0]);
        if(error.matches("RESOURCE_(validator|measure-JAVA-[01]|replay-JAVA)_(RE|CE|TLE|MLE)")) {
            var history=JudgeJson.JSON.createObjectNode().put("failure",error).put("recoveryScope","IMPLEMENTATION");var links=history.putArray("executions");
            jdbc.sql("SELECT role,submission_id FROM generation_resource_execution WHERE check_id=?").param(id).query((r,n)->JudgeJson.JSON.createObjectNode().put("role",r.getString(1)).put("submissionId",r.getString(2))).list().forEach(links::add);
            if(jdbc.sql("SELECT count(*) FROM generation_resource_attempt WHERE check_id=? AND attempt=?").param(id).param(attempts).query(Integer.class).single()==0)jdbc.sql("INSERT INTO generation_resource_attempt(check_id,attempt,completion_json,artifacts_json,report_json) VALUES (?,?,?,?,?)").param(id).param(attempts).param(row[1]).param(row[2]).param(history.toString()).update();
            jdbc.sql("UPDATE generation_resource_check SET status='FAILED',error_code=? WHERE id=?").param("RESOURCE_REFERENCE_"+error.substring("RESOURCE_".length()).toUpperCase(Locale.ROOT)).param(id).update();return;
        }
        boolean telemetry=error.equals("RESOURCE_EXECUTION_PROFILE_MISMATCH")||error.equals("RESOURCE_MEMORY_OBSERVATION_MISSING")||error.equals("RESOURCE_TIME_OBSERVATION_MISSING");
        if(!GenerationDraftRecovery.stopped(error)&&attempts<2) {
            var history=JudgeJson.JSON.createObjectNode().put("failure",error);var links=history.putArray("executions");
            jdbc.sql("SELECT role,submission_id FROM generation_resource_execution WHERE check_id=?").param(id).query((r,n)->JudgeJson.JSON.createObjectNode().put("role",r.getString(1)).put("submissionId",r.getString(2))).list().forEach(links::add);
            jdbc.sql("INSERT INTO generation_resource_attempt(check_id,attempt,completion_json,artifacts_json,report_json) VALUES (?,?,?,?,?)").param(id).param(attempts).param(row[1]).param(row[2]).param(history.toString()).update();
            if(!telemetry&&row[1]!=null&&row[3]!=null&&jdbc.sql("SELECT count(*) FROM generation_recovery_receipt WHERE job_id=? AND token=?").param(id).param(UUID.fromString(row[3])).query(Integer.class).single()==0)jdbc.sql("INSERT INTO generation_recovery_receipt(job_id,token,completion_json) VALUES (?,?,?)").param(id).param(UUID.fromString(row[3])).param(row[1]).update();
            jdbc.sql("DELETE FROM generation_resource_execution WHERE check_id=?").param(id).update();
            if(telemetry) {
                jdbc.sql("UPDATE generation_resource_check SET retries=retries+1,status='MEASURING',limits_json=NULL,error_code=? WHERE id=?").param(error).param(id).update();
                var input=JudgeJson.parse(jdbc.sql("SELECT input_json FROM generation_resource_check WHERE id=?").param(id).query(String.class).single());var artifacts=JudgeJson.parse(row[2]);
                submit(jdbc,id,"validator",input.path("validator").asText(),"JAVA",true,null);
                for(int repeat=0;repeat<2;repeat++)for(String language:ProblemTimeLimits.LANGUAGES)submit(jdbc,id,"measure-"+language+"-"+repeat,source(input,artifacts,language),language,false,null);
                for(int repeat=0;repeat<2;repeat++)submit(jdbc,id,"ordinary-JAVA-"+repeat,artifacts.path("ordinaryJava").asText(),"JAVA",false,null);
            }else jdbc.sql("UPDATE generation_resource_check SET retries=retries+1,status='QUEUED',completion_json=NULL,artifacts_json=NULL,limits_json=NULL,token=NULL,error_code=? WHERE id=?").param(error).param(id).update();
        } else jdbc.sql("UPDATE generation_resource_check SET status='NEEDS_REVIEW',error_code=? WHERE id=?").param(error.substring(0,Math.min(100,error.length()))).param(id).update();
    }
    private GenerationResources(){}
}
