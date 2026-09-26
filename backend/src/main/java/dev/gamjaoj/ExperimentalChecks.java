package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Bounded differential evidence, never a publisher or a proof of the drafted semantics. */
@Service
class ExperimentalChecks {
    private final JdbcClient jdbc;
    ExperimentalChecks(JdbcClient jdbc){this.jdbc=jdbc;}
    private String version(UUID id){return "experimental-check-"+id;}
    private ObjectNode plan(UUID id,List<JsonNode> cases,boolean run){
        var plan=JudgeJson.JSON.createObjectNode().put("version",version(id)).put("output_policy",run?"RUN_ONLY":"TOKEN_EXACT");
        var tests=plan.putArray("tests");cases.forEach(tests::add);return plan;
    }
    private ObjectNode test(String id,String input,String output){return JudgeJson.JSON.createObjectNode().put("id",id).put("input",input).put("output",output);}
    private void execute(UUID id,String role,String source,List<JsonNode> cases,boolean run){
        execute(id,role,source,cases,run,run?"OK":"AC");
    }
    void execute(UUID id,String role,String source,List<JsonNode> cases,boolean run,String expected){
        UUID submission=UUID.randomUUID();String json=JudgeJson.canonical(plan(id,cases,run));
        jdbc.sql("INSERT INTO submission (id,user_id,problem_version,source_code,source_sha256,idempotency_key,runtime_image,runner_policy,run_input,run_package,run_package_sha256,spec_draft_id) SELECT ?,d.owner_id,?,?,?,?,p.runtime_image,?,?,?,?,d.id FROM generation_spec_draft d JOIN problem_version p ON p.id=? WHERE d.id=?")
                .param(submission).param(version(id)).param(source).param(JudgeJson.hash(source)).param(submission)
                .param(run?"java8-run-v1":"java8-judge-v1").param("experimental-check").param(json).param(JudgeJson.hash(json)).param(version(id)).param(id).update();
        jdbc.sql("INSERT INTO judge_job (submission_id,priority,execution_mode) VALUES (?,1,?)")
                .param(submission).param(JudgeScheduling.experimental(role)).update();
        jdbc.sql("INSERT INTO generation_spec_execution (draft_id,role,submission_id,expected_verdict) VALUES (?,?,?,?)")
                .param(id).param(role).param(submission).param(expected).update();
    }
    void start(UUID id,JsonNode spec,JsonNode artifacts,JsonNode oracle){
        var cases=new ArrayList<JsonNode>();int index=0;
        for(var sample:spec.path("samples"))cases.add(test("sample-"+index++,sample.path("input").asText(),sample.path("output").asText()));
        String json=JudgeJson.canonical(plan(id,cases,false).put("title",spec.path("title").asText()).put("statement",spec.path("statement").asText()));
        jdbc.sql("INSERT INTO problem_version (id,package_json,package_sha256,runtime_image,runner_policy,ready,owner_id) SELECT ?,?,?,p.runtime_image,p.runner_policy,false,d.owner_id FROM problem_version p JOIN generation_spec_draft d ON d.id=? WHERE p.id='total-v1'")
                .param(version(id)).param(json).param(JudgeJson.hash(json)).param(id).update();
        execute(id,"reference-samples",artifacts.path("reference").asText(),cases,false);
        execute(id,"oracle-samples",oracle.path("source").asText(),cases,false);
        execute(id,"validator-samples",artifacts.path("inputValidator").asText(),cases.stream().map(t->(JsonNode)((ObjectNode)t.deepCopy()).put("output","VALID\n")).toList(),false);
        execute(id,"generator",artifacts.path("generator").asText(),List.of(test("custom-input",new java.security.SecureRandom().nextLong()+"\n","")),true);
    }
    private void fail(UUID id,String error){jdbc.sql("UPDATE generation_spec_draft SET status='BUILD_FAILED',error_code=?,updated_at=CURRENT_TIMESTAMP WHERE id=?").param(error).param(id).update();}
    void advance(){
        for(UUID id:jdbc.sql("SELECT id FROM generation_spec_draft WHERE status='CHECKING'").query(UUID.class).list()){
            var rows=jdbc.sql("SELECT e.role,e.expected_verdict,j.status,j.verdict,j.result_json FROM generation_spec_execution e JOIN judge_job j ON j.submission_id=e.submission_id WHERE e.draft_id=? ORDER BY e.role")
                    .param(id).query((r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5)}).list();
            if(rows.isEmpty()||rows.stream().anyMatch(r->!r[2].equals("FINISHED")))continue;
            var failed=rows.stream().filter(r->!r[1].equals(r[3])).findFirst();
            if(failed.isPresent()){fail(id,"CHECK_"+failed.get()[0]+"_"+failed.get()[3]);continue;}
            var saved=jdbc.sql("SELECT spec_json,spec_sha256,build_artifacts_json,build_oracle_json,build_sha256 FROM generation_spec_draft WHERE id=?")
                    .param(id).query((r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5)}).single();
            if(!JudgeJson.hash(saved[0]).equals(saved[1])||!JudgeJson.hash(saved[2]+"\n"+saved[3]).equals(saved[4])){fail(id,"ARTIFACT_FENCE_MISMATCH");continue;}
            if(rows.size()==4){
                try {
                    var result=rows.stream().filter(r->r[0].equals("generator")).findFirst().orElseThrow();
                    var output=JudgeJson.parse(result[4]).path("tests").path(0);
                    if(output.path("stdout_truncated").asBoolean())throw new IllegalArgumentException();
                    JsonNode inputs;try{inputs=JudgeJson.JSON.readTree(output.path("stdout").asText());}catch(Exception invalid){throw new IllegalArgumentException(invalid);}
                    if(inputs==null||!inputs.isArray()||inputs.size()!=4)throw new IllegalArgumentException();
                    var checks=new ArrayList<JsonNode>();int index=0;
                    var artifacts=JudgeJson.parse(saved[2]);var oracle=JudgeJson.parse(saved[3]);
                    for(var input:inputs){
                        if(!input.isTextual()||input.asText().isBlank()||input.asText().getBytes(StandardCharsets.UTF_8).length>4096)throw new IllegalArgumentException();
                        checks.add(test("generated-"+index++,input.asText(),"VALID\n"));
                    }
                    // Validate the entire generator envelope before queueing any dependent work.
                    for(int i=0;i<4;i++){
                        var run=List.<JsonNode>of(test("custom-input",inputs.get(i).asText(),""));
                        execute(id,"reference-generated-"+i,artifacts.path("reference").asText(),run,true);
                        execute(id,"oracle-generated-"+i,oracle.path("source").asText(),run,true);
                    }
                    execute(id,"validator-generated",artifacts.path("inputValidator").asText(),checks,false);
                    jdbc.sql("UPDATE generation_spec_draft SET build_inputs_json=? WHERE id=?").param(inputs.toString()).param(id).update();
                }catch(IllegalArgumentException e){fail(id,"INVALID_GENERATED_INPUT_ENVELOPE");}
            } else if(rows.size()==13){
                boolean matches=true;
                for(int i=0;i<4;i++){
                    final String suffix="generated-"+i;
                    var a=JudgeJson.parse(rows.stream().filter(r->r[0].equals("reference-"+suffix)).findFirst().orElseThrow()[4]).path("tests").path(0);
                    var b=JudgeJson.parse(rows.stream().filter(r->r[0].equals("oracle-"+suffix)).findFirst().orElseThrow()[4]).path("tests").path(0);
                    if(a.path("stdout_truncated").asBoolean()||b.path("stdout_truncated").asBoolean()||a.path("stdout").asText().isBlank()||
                            !Arrays.equals(a.path("stdout").asText().strip().split("(?U)\\s+"),b.path("stdout").asText().strip().split("(?U)\\s+")))matches=false;
                }
                if(!matches){fail(id,"REFERENCE_ORACLE_DISAGREEMENT");continue;}
                var report=JudgeJson.JSON.createObjectNode().put("policy","experimental-examples-differential-v1").put("specHash",saved[1]).put("artifactHash",saved[4]).put("executions",13).put("publishable",false);
                var results=report.putArray("results");for(var row:rows)results.addObject().put("role",row[0]).put("verdict",row[3]).put("reportHash",JudgeJson.hash(row[4]));
                report.putArray("remaining").add("independent semantic review").add("bounded exhaustive domain").add("invalid input rejection").add("verified mutant witnesses").add("resource envelope").add("independent final seed");
                jdbc.sql("UPDATE generation_spec_draft SET status='CHECKED',build_report_json=?,updated_at=CURRENT_TIMESTAMP WHERE id=?")
                        .param(report.toString()).param(id).update();
            }else fail(id,"INCOMPLETE_EXPERIMENTAL_CHECKS");
        }
    }
}
