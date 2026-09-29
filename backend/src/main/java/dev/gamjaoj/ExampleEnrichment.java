package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Worked examples for published generated problems. The independent reader proposes moderate inputs with its own
 * expected output and a Korean explanation; after publication each one runs on the Runner against the problem's
 * input validator (must print VALID) and its reference (must print the reader's output). Only examples both
 * independent implementations agree on are shown, outside the judged package.
 */
@Service
class ExampleEnrichment {
    static final int MAX_INPUT_BYTES=1500,MAX_INPUT_LINES=30,MAX_OUTPUT_BYTES=600,MAX_EXPLANATION=700;
    record Example(String input,String output,String explanation) {}
    private final JdbcClient jdbc;
    ExampleEnrichment(JdbcClient jdbc){this.jdbc=jdbc;}

    @Scheduled(fixedDelayString="${EXAMPLE_POLL_MS:${AI_POLL_MS:15000}}",initialDelayString="${EXAMPLE_POLL_MS:${AI_POLL_MS:15000}}")
    @Transactional
    public void advance() {
        for(String version:jdbc.sql("""
                SELECT p.id FROM problem_version p JOIN hybrid_generation g ON g.published_version_id=p.id
                WHERE p.ready=true AND p.examples_status IS NULL AND g.status='PUBLISHED' ORDER BY g.updated_at LIMIT 3""").query(String.class).list())
            start(version);
        for(String version:jdbc.sql("SELECT id FROM problem_version WHERE examples_status='CHECKING' ORDER BY id LIMIT 10").query(String.class).list())
            finish(version);
    }

    /** Usable reader examples: bounded, Korean explanation, distinct inputs. */
    static List<Example> proposals(JsonNode reader) {
        var out=new ArrayList<Example>();var seen=new java.util.HashSet<String>();
        for(var e:reader.path("examples")) {
            String input=e.path("input").asText(""),output=e.path("output").asText(""),explanation=e.path("explanation").asText("").strip();
            if(!input.endsWith("\n"))input+="\n";
            if(input.isBlank()||output.isBlank()||explanation.isBlank()||!seen.add(input))continue;
            if(input.getBytes(StandardCharsets.UTF_8).length>MAX_INPUT_BYTES||input.lines().count()>MAX_INPUT_LINES
                    ||output.getBytes(StandardCharsets.UTF_8).length>MAX_OUTPUT_BYTES||explanation.length()>MAX_EXPLANATION)continue;
            try{HybridStatementQuality.korean("example",explanation);}catch(HybridArtifacts.Invalid notKorean){continue;}
            out.add(new Example(input,output.strip()+"\n",explanation));
        }
        return out.size()>3?out.subList(0,3):out;
    }

    private JsonNode latest(UUID generation,int revision,String role) {
        return jdbc.sql("SELECT a.payload_json FROM hybrid_branch b JOIN hybrid_artifact a ON a.branch_id=b.id WHERE b.generation_id=? AND b.revision=? AND b.role=? AND b.status='SUCCEEDED' ORDER BY b.attempt DESC")
                .param(generation).param(revision).param(role).query(String.class).list().stream().findFirst().map(JudgeJson::parse).orElse(null);
    }

    private void start(String version) {
        var g=jdbc.sql("SELECT g.id,g.revision,g.owner_id FROM hybrid_generation g WHERE g.published_version_id=?").param(version)
                .query((r,n)->new Object[]{r.getObject(1,UUID.class),r.getInt(2),r.getObject(3,UUID.class)}).single();
        JsonNode reader=latest((UUID)g[0],(int)g[1],"READER"),core=latest((UUID)g[0],(int)g[1],"CORE");
        var examples=reader==null?List.<Example>of():proposals(reader);
        String validator=core==null?"":core.path("inputValidator").asText(""),reference=core==null?"":core.path("reference").asText("");
        if(examples.isEmpty()||validator.isBlank()||reference.isBlank()) {status(version,"NONE");return;}
        var runtime=jdbc.sql("SELECT runtime_image FROM problem_version WHERE id=?").param(version).query(String.class).single();
        for(int i=0;i<examples.size();i++) {
            var e=examples.get(i);
            queue(version,(UUID)g[2],runtime,i,"VALID",validator,e.input(),"VALID\n");
            queue(version,(UUID)g[2],runtime,i,"REFERENCE",reference,e.input(),e.output());
        }
        jdbc.sql("UPDATE problem_version SET examples_json=?,examples_status='CHECKING' WHERE id=?")
                .param(JudgeJson.canonical(JudgeJson.JSON.valueToTree(examples))).param(version).update();
    }

    private void queue(String version,UUID owner,String runtime,int position,String role,String source,String input,String output) {
        var plan=JudgeJson.JSON.createObjectNode().put("version","example-check-"+version).put("output_policy","TOKEN_EXACT");
        plan.putArray("tests").addObject().put("id","example-"+(position+1)).put("input",input).put("output",output);
        String payload=JudgeJson.canonical(plan);UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO submission(id,user_id,problem_version,source_code,source_sha256,idempotency_key,runtime_image,runner_policy,run_input,run_package,run_package_sha256,example_check) VALUES (?,?,?,?,?,?,?,?,?,?,?,true)")
                .param(id).param(owner).param(version).param(source).param(JudgeJson.hash(source)).param(id).param(runtime).param("java8-judge-v1")
                .param("example-check").param(payload).param(JudgeJson.hash(payload)).update();
        jdbc.sql("INSERT INTO judge_job(submission_id,priority,execution_mode) VALUES (?,1,'FUNCTIONAL')").param(id).update();
        jdbc.sql("INSERT INTO example_check(problem_version,position,role,submission_id) VALUES (?,?,?,?)").param(version).param(position).param(role).param(id).update();
    }

    private void finish(String version) {
        var rows=jdbc.sql("SELECT c.position,c.role,j.status,j.verdict FROM example_check c JOIN judge_job j ON j.submission_id=c.submission_id WHERE c.problem_version=?")
                .param(version).query((r,n)->new Object[]{r.getInt(1),r.getString(2),r.getString(3),r.getString(4)}).list();
        if(rows.isEmpty()){status(version,"NONE");return;}
        if(rows.stream().anyMatch(r->!"FINISHED".equals(r[2])))return;
        var proposed=JudgeJson.parse(jdbc.sql("SELECT examples_json FROM problem_version WHERE id=?").param(version).query(String.class).single());
        ArrayNode verified=JudgeJson.JSON.createArrayNode();
        for(int i=0;i<proposed.size();i++) {
            int position=i;
            boolean agreed=rows.stream().filter(r->(int)r[0]==position).count()==2
                    &&rows.stream().filter(r->(int)r[0]==position).allMatch(r->"AC".equals(r[3]));
            if(agreed)verified.add(proposed.get(i));
        }
        jdbc.sql("UPDATE problem_version SET examples_json=?,examples_status=? WHERE id=?")
                .param(verified.isEmpty()?null:JudgeJson.canonical(verified)).param(verified.isEmpty()?"NONE":"DONE").param(version).update();
    }
    private void status(String version,String status) {
        jdbc.sql("UPDATE problem_version SET examples_status=? WHERE id=?").param(status).param(version).update();
    }
}
