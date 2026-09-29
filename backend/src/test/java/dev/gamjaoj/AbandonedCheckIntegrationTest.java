package dev.gamjaoj;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:abandoned;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
 "spring.datasource.username=sa","spring.datasource.password=",
 "gamjaoj.invite-code=test-only","gamjaoj.worker-token=abandoned-check-worker-32-characters","gamjaoj.submissions-enabled=true","AI_API_ENABLED=false","AI_POLL_MS=3600000"})
class AbandonedCheckIntegrationTest {
    @Autowired JdbcClient jdbc; @Autowired JudgeQueue queue;

    UUID check(UUID owner,String generationStatus,String deadline,String branchStatus,String jobStatus) {
        UUID generation=UUID.randomUUID(),branch=UUID.randomUUID(),submission=UUID.randomUUID();
        jdbc.sql("INSERT INTO hybrid_generation(id,owner_id,pipeline_version,request_json,request_sha256,status,created_at,deadline_at,updated_at) VALUES (?,?,'test','{}',?,?,CURRENT_TIMESTAMP,"+deadline+",CURRENT_TIMESTAMP)")
                .param(generation).param(owner).param("0".repeat(64)).param(generationStatus).update();
        jdbc.sql("INSERT INTO hybrid_branch(id,generation_id,revision,role,attempt,status,input_json,input_sha256,created_at) VALUES (?,?,0,'VALIDATION',0,?,'{}',?,CURRENT_TIMESTAMP)")
                .param(branch).param(generation).param(branchStatus).param("0".repeat(64)).update();
        String plan="{\"output_policy\":\"TOKEN_EXACT\",\"tests\":[{\"id\":\"t\",\"input\":\"1\",\"output\":\"1\"}],\"version\":\"hybrid-check\"}";
        jdbc.sql("INSERT INTO submission(id,user_id,problem_version,source_code,source_sha256,idempotency_key,runtime_image,runner_policy,run_input,run_package,run_package_sha256,hybrid_branch_id) SELECT ?,?,'sum-v1','class Main{}',?,?,runtime_image,'java8-judge-v1','hybrid-check',?,?,? FROM problem_version WHERE id='sum-v1'")
                .param(submission).param(owner).param("0".repeat(64)).param(submission).param(plan).param(JudgeJson.hash(plan)).param(branch).update();
        jdbc.sql("INSERT INTO judge_job(submission_id,priority,execution_mode,status,attempt,lease_until) VALUES (?,1,'EXCLUSIVE',?,?,DATEADD('MINUTE',-5,CURRENT_TIMESTAMP))")
                .param(submission).param(jobStatus).param(jobStatus.equals("RUNNING")?1:0).update();
        return submission;
    }
    String status(UUID id){return jdbc.sql("SELECT status||':'||COALESCE(verdict,'') FROM judge_job WHERE submission_id=?").param(id).query(String.class).single();}

    @Test void checksOfEndedGenerationsAreClosedAndLiveOnesKept() {
        UUID owner=UUID.randomUUID();String name="h"+UUID.randomUUID().toString().substring(0,8);
        jdbc.sql("INSERT INTO app_user(id,username,password_hash,nickname) VALUES (?,?,'!',?)").param(owner).param(name).param(name).update();
        UUID expiredRunning=check(owner,"VALIDATING","DATEADD('HOUR',-1,CURRENT_TIMESTAMP)","RUNNING","RUNNING");
        UUID cancelledQueued=check(owner,"CANCELLED","DATEADD('HOUR',1,CURRENT_TIMESTAMP)","RUNNING","QUEUED");
        UUID live=check(owner,"VALIDATING","DATEADD('HOUR',1,CURRENT_TIMESTAMP)","RUNNING","QUEUED");
        var claimed=queue.claim(UUID.randomUUID());
        assertThat(status(expiredRunning)).isEqualTo("FINISHED:IE");
        assertThat(status(cancelledQueued)).isEqualTo("FINISHED:IE");
        assertThat(claimed).map(JudgeQueue.Assignment::submissionId).contains(live);
    }
}
