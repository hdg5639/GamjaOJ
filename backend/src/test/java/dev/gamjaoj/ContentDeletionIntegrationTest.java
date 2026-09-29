package dev.gamjaoj;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:contentdeletion;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
 "spring.datasource.username=sa","spring.datasource.password=",
 "gamjaoj.invite-code=test-only","gamjaoj.worker-token=content-deletion-worker-32-characters","gamjaoj.submissions-enabled=true","AI_API_ENABLED=false","AI_POLL_MS=3600000"})
@AutoConfigureMockMvc
class ContentDeletionIntegrationTest {
    @Autowired JdbcClient jdbc; @Autowired MockMvc mvc; @Autowired Submissions submissions; @Autowired TrainingSessions training;
    UUID addUser(String name){var id=UUID.randomUUID();jdbc.sql("INSERT INTO app_user(id,username,password_hash,nickname) VALUES (?,?,?,?)").param(id).param(name).param("!").param(name).update();return id;}
    void problem(String id,UUID owner,boolean shared){
        jdbc.sql("INSERT INTO problem_version(id,package_json,package_sha256,runtime_image,runner_policy,ready,owner_id,shared) SELECT ?,package_json,package_sha256,runtime_image,runner_policy,true,?,? FROM problem_version WHERE id='total-v1'")
                .param(id).param(owner).param(shared).update();
    }
    void finish(UUID submission){jdbc.sql("UPDATE judge_job SET status='FINISHED',verdict='WA' WHERE submission_id=?").param(submission).update();}
    int count(String sql,Object value){return jdbc.sql(sql).param(value).query(Integer.class).single();}
    String name(String p){return p+UUID.randomUUID().toString().substring(0,8);}

    @Test void ownProblemIsDeletedWithOwnRecordsButArchivedWhenAnotherMemberUsedIt() throws Exception {
        String alice=name("a"),bob=name("b");UUID owner=addUser(alice);addUser(bob);
        String personal="private-"+UUID.randomUUID(),solvedByBob="used-"+UUID.randomUUID();
        problem(personal,owner,false);problem(solvedByBob,owner,true);
        var mine=submissions.submit(alice,UUID.randomUUID(),new SubmissionController.Request(personal,"class Main {}"));
        training.start(alice,UUID.randomUUID(),new TrainingSessionController.Start(personal,"개인 연습"));
        finish(bobSubmission(bob,solvedByBob));

        mvc.perform(delete("/api/problems/"+personal).with(user(alice))).andExpect(status().isForbidden()); // CSRF
        mvc.perform(delete("/api/problems/"+personal).with(user(bob)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(delete("/api/problems/"+personal).with(user(alice)).with(csrf())).andExpect(status().isConflict()); // still judging
        finish(mine.id());
        mvc.perform(delete("/api/problems/"+personal).with(user(alice)).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("DELETED"));
        assertThat(count("SELECT count(*) FROM problem_version WHERE id=?",personal)).isZero();
        assertThat(count("SELECT count(*) FROM submission WHERE problem_version=?",personal)).isZero();
        assertThat(count("SELECT count(*) FROM training_session WHERE problem_version=?",personal)).isZero();

        mvc.perform(delete("/api/problems/"+solvedByBob).with(user(alice)).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("ARCHIVED"));
        UUID archive=jdbc.sql("SELECT id FROM app_user WHERE username=?").param(AccountDeletion.ARCHIVE_USERNAME).query(UUID.class).single();
        assertThat(jdbc.sql("SELECT owner_id FROM problem_version WHERE id=?").param(solvedByBob).query(UUID.class).single()).isEqualTo(archive);
        assertThat(submissions.problems(alice)).noneMatch(p->p.version().equals(solvedByBob));
        assertThat(submissions.problems(bob)).noneMatch(p->p.version().equals(solvedByBob));
        assertThat(count("SELECT count(*) FROM submission WHERE problem_version=? AND user_id<>'"+owner+"'",solvedByBob)).isOne();
        mvc.perform(delete("/api/problems/sum-v1").with(user(alice)).with(csrf())).andExpect(status().isNotFound()); // official
    }
    UUID bobSubmission(String bob,String version){return submissions.submit(bob,UUID.randomUUID(),new SubmissionController.Request(version,"class Main {}")).id();}

    String rule(UUID owner,boolean shared){
        String family="member-"+UUID.randomUUID().toString().substring(0,8),version=family+"-v1";
        jdbc.sql("INSERT INTO hybrid_rule_family(id,visibility,owner_id,shared) VALUES (?,'MEMBER',?,?)").param(family).param(owner).param(shared).update();
        jdbc.sql("INSERT INTO hybrid_rule_version(id,family_id,engine,validation_policy,status,sort_order) VALUES (?,?,'PACKAGE_V1','test',?,1000)").param(version).param(family).param("ACTIVE").update();
        jdbc.sql("INSERT INTO hybrid_rule_onboarding(id,owner_id,request_json,request_sha256,status,version_id,budget_usd,created_at,deadline_at,updated_at) VALUES (?,?,'{}',?,'ACTIVE',?,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")
                .param(UUID.randomUUID()).param(owner).param("0".repeat(64)).param(version).update();
        return version;
    }
    void generated(UUID owner,String version,String status){
        UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO hybrid_generation(id,owner_id,pipeline_version,request_json,request_sha256,status,created_at,deadline_at,updated_at) VALUES (?,?,'test','{}',?,?,CURRENT_TIMESTAMP,DATEADD('HOUR',1,CURRENT_TIMESTAMP),CURRENT_TIMESTAMP)")
                .param(id).param(owner).param("0".repeat(64)).param(status).update();
        jdbc.sql("INSERT INTO hybrid_public_request(generation_id,profile_id,profile_hash,contract_sha256,handoff_mode,rule_version_id) VALUES (?,?,?,?,'SERVER_FIXED_CONTRACT_V1',?)")
                .param(id).param(version).param("0".repeat(64)).param("0".repeat(64)).param(version).update();
    }

    @Test void ownRuleIsDeletedButRetiredToArchiveWhenAnotherMemberGeneratedFromIt() throws Exception {
        String alice=name("a"),bob=name("b");UUID owner=addUser(alice);UUID other=addUser(bob);
        String privateRule=rule(owner,false),usedRule=rule(owner,true);
        generated(owner,privateRule,"QUEUED");
        mvc.perform(delete("/api/rules/"+privateRule).with(user(bob)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(delete("/api/rules/"+privateRule).with(user(alice)).with(csrf())).andExpect(status().isConflict()); // generation running
        jdbc.sql("UPDATE hybrid_generation SET status='PUBLISHED' WHERE owner_id=?").param(owner).update();
        mvc.perform(delete("/api/rules/"+privateRule).with(user(alice)).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("DELETED"));
        assertThat(count("SELECT count(*) FROM hybrid_rule_version WHERE id=?",privateRule)).isZero();
        assertThat(count("SELECT count(*) FROM hybrid_rule_onboarding WHERE version_id=?",privateRule)).isZero();
        assertThat(count("SELECT count(*) FROM hybrid_public_request WHERE rule_version_id IS NULL AND profile_id=?",privateRule)).isOne();

        generated(other,usedRule,"PUBLISHED");
        mvc.perform(delete("/api/rules/"+usedRule).with(user(alice)).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("ARCHIVED"));
        assertThat(jdbc.sql("SELECT status FROM hybrid_rule_version WHERE id=?").param(usedRule).query(String.class).single()).isEqualTo("RETIRED");
        assertThat(count("SELECT count(*) FROM hybrid_rule_family f JOIN hybrid_rule_version v ON v.family_id=f.id WHERE v.id=? AND f.shared=false AND f.owner_id<>'"+owner+"'",usedRule)).isOne();
        assertThat(count("SELECT count(*) FROM hybrid_rule_onboarding WHERE version_id=?",usedRule)).isZero();
    }
}
