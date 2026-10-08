package dev.gamjaoj;
import dev.gamjaoj.service.generation.ExampleEnrichment;
import dev.gamjaoj.support.JudgeJson;
import dev.gamjaoj.service.judge.JudgeQueue;
import dev.gamjaoj.dto.SubmissionDtos;
import dev.gamjaoj.service.judge.Submissions;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:examples;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
 "spring.datasource.username=sa","spring.datasource.password=",
 "gamjaoj.invite-code=test-only","gamjaoj.worker-token=example-enrichment-worker-32-chars","gamjaoj.submissions-enabled=true","AI_API_ENABLED=false","AI_POLL_MS=3600000"})
class ExampleEnrichmentIntegrationTest {
    @Autowired JdbcClient jdbc; @Autowired ExampleEnrichment enrichment; @Autowired Submissions submissions; @Autowired JudgeQueue queue;

    void branch(UUID generation,String role,String payload){
        UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO hybrid_branch(id,generation_id,revision,role,attempt,status,input_json,input_sha256,created_at) VALUES (?,?,0,?,0,'SUCCEEDED','{}',?,CURRENT_TIMESTAMP)")
                .param(id).param(generation).param(role).param("0".repeat(64)).update();
        jdbc.sql("INSERT INTO hybrid_artifact(branch_id,schema_version,prompt_version,payload_json,payload_sha256,created_at) VALUES (?,'1','test',?,?,CURRENT_TIMESTAMP)")
                .param(id).param(payload).param(JudgeJson.hash(payload)).update();
    }

    @Test void onlyExamplesTheValidatorAndReferenceBothConfirmAreShown() {
        String name="e"+UUID.randomUUID().toString().substring(0,8);UUID owner=UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user(id,username,password_hash,nickname) VALUES (?,?,'!',?)").param(owner).param(name).param(name).update();
        String version="hybrid-published-"+UUID.randomUUID();
        jdbc.sql("INSERT INTO problem_version(id,package_json,package_sha256,runtime_image,runner_policy,ready,owner_id,shared) SELECT ?,package_json,package_sha256,runtime_image,runner_policy,true,?,false FROM problem_version WHERE id='total-v1'")
                .param(version).param(owner).update();
        UUID generation=UUID.randomUUID();
        jdbc.sql("INSERT INTO hybrid_generation(id,owner_id,pipeline_version,request_json,request_sha256,status,created_at,deadline_at,updated_at,published_version_id) VALUES (?,?,'test','{}',?,'PUBLISHED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?)")
                .param(generation).param(owner).param("0".repeat(64)).param(version).update();
        branch(generation,"CORE","{\"reference\":\"class Main{}\",\"inputValidator\":\"class Validator{}\"}");
        var reader=JudgeJson.JSON.createObjectNode();var examples=reader.putArray("examples");
        examples.addObject().put("input","3\n1 2 3").put("output","6").put("explanation","세 수를 모두 더하면 1+2+3=6이 됩니다.");
        examples.addObject().put("input","2\n5 5\n").put("output","11").put("explanation","두 수를 더하면 10이지만 잘못 계산했습니다.");
        examples.addObject().put("input","1\n7\n").put("output","7").put("explanation","This explanation is written in English only.");
        branch(generation,"READER",reader.toString());

        enrichment.advance();
        assertThat(jdbc.sql("SELECT examples_status FROM problem_version WHERE id=?").param(version).query(String.class).single()).isEqualTo("CHECKING");
        var checks=jdbc.sql("SELECT c.position,c.role,c.submission_id FROM example_check c WHERE c.problem_version=? ORDER BY c.position,c.role").param(version)
                .query((r,n)->new Object[]{r.getInt(1),r.getString(2),r.getObject(3,UUID.class)}).list();
        assertThat(checks).hasSize(4); // the English-only proposal is never run
        assertThat(jdbc.sql("SELECT count(*) FROM submission WHERE problem_version=? AND example_check=true AND run_input IS NOT NULL").param(version).query(Integer.class).single()).isEqualTo(4);
        assertThat(submissions.history(name)).isEmpty(); // internal checks never appear as the owner's submissions
        // The owner's own submissions are not blocked by the example checks.
        submissions.submit(name,UUID.randomUUID(),new SubmissionDtos.Request(version,"class Main {}"));

        for(var c:checks)jdbc.sql("UPDATE judge_job SET status='FINISHED',verdict=? WHERE submission_id=?")
                .param((int)c[0]==1&&c[1].equals("REFERENCE")?"WA":"AC").param(c[2]).update();
        enrichment.advance();
        assertThat(jdbc.sql("SELECT examples_status FROM problem_version WHERE id=?").param(version).query(String.class).single()).isEqualTo("DONE");
        var problem=submissions.problems(name).stream().filter(p->p.version().equals(version)).findFirst().orElseThrow();
        var worked=problem.examples().stream().filter(e->e.explanation()!=null).toList();
        assertThat(worked).hasSize(1);
        assertThat(worked.get(0).input()).isEqualTo("3\n1 2 3\n");
        assertThat(worked.get(0).output()).isEqualTo("6\n");
        assertThat(problem.examples().get(0).explanation()).isNull(); // mechanically derived samples come first
    }
}
