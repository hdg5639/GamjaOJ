package dev.gamjaoj;
import dev.gamjaoj.service.account.AccountDeletion;
import dev.gamjaoj.exception.AccountException;
import dev.gamjaoj.service.generation.GenerationJobs;
import dev.gamjaoj.service.generation.GenerationSpecDrafts;
import dev.gamjaoj.dto.SubmissionDtos;
import dev.gamjaoj.service.judge.Submissions;
import dev.gamjaoj.dto.TrainingSessionDtos;
import dev.gamjaoj.service.learning.TrainingSessions;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:accountdeletion;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
 "spring.datasource.username=sa","spring.datasource.password=",
 "gamjaoj.invite-code=test-only","gamjaoj.worker-token=deletion-test-worker-32-characters","gamjaoj.submissions-enabled=true","AI_API_ENABLED=false","AI_POLL_MS=3600000"})
@AutoConfigureMockMvc
class AccountDeletionIntegrationTest {
    @Autowired JdbcClient jdbc; @Autowired MockMvc mvc; @Autowired Submissions submissions; @Autowired TrainingSessions training;
    @Autowired GenerationJobs generation; @Autowired GenerationSpecDrafts drafts; @Autowired PasswordEncoder passwords; @Autowired AccountDeletion deletion;
    static final String PASSWORD="correct-horse-42";
    UUID addUser(String name){var id=UUID.randomUUID();jdbc.sql("INSERT INTO app_user(id,username,password_hash,nickname) VALUES (?,?,?,?)").param(id).param(name).param(passwords.encode(PASSWORD)).param(name).update();return id;}
    void problem(String id,UUID owner,boolean shared){
        jdbc.sql("INSERT INTO problem_version(id,package_json,package_sha256,runtime_image,runner_policy,ready,owner_id,shared) SELECT ?,package_json,package_sha256,runtime_image,runner_policy,true,?,? FROM problem_version WHERE id='total-v1'")
                .param(id).param(owner).param(shared).update();
    }
    int count(String sql,Object value){return jdbc.sql(sql).param(value).query(Integer.class).single();}
    String body(String password,String confirmation){return "{\"password\":\""+password+"\",\"confirmation\":\""+confirmation+"\"}";}

    @Test void hardDeletionRemovesPrivateDataAndArchivesWhatOthersUse() throws Exception {
        String alice="a"+UUID.randomUUID().toString().substring(0,8),bob="b"+UUID.randomUUID().toString().substring(0,8);
        UUID owner=addUser(alice);addUser(bob);
        // Private problem, a shared problem generated from a template, and a formerly shared problem bob already solved.
        String personal="private-"+UUID.randomUUID(),solvedByBob="used-"+UUID.randomUUID();
        UUID job=UUID.randomUUID();generation.create(alice,job,"sequence-sum-v1","basics",null,true);
        jdbc.sql("UPDATE generation_job SET status='FAILED' WHERE id=?").param(job).update();
        String sharedGenerated="generated-"+job+"-r"+jdbc.sql("SELECT revision FROM generation_job WHERE id=?").param(job).query(Integer.class).single();
        problem(personal,owner,false);problem(sharedGenerated,owner,true);problem(solvedByBob,owner,true);
        var bobSubmission=submissions.submit(bob,UUID.randomUUID(),new SubmissionDtos.Request(solvedByBob,"class Main {}"));
        jdbc.sql("UPDATE problem_version SET shared=false WHERE id=?").param(solvedByBob).update();
        String category=submissions.problems(bob).stream().filter(p->p.version().equals(sharedGenerated)).findFirst().orElseThrow().category();
        // Alice's own activity.
        var mine=submissions.submit(alice,UUID.randomUUID(),new SubmissionDtos.Request("sum-v1","class Main {}"));
        training.start(alice,UUID.randomUUID(),new TrainingSessionDtos.Start(personal,"개인 연습"));
        UUID draft=UUID.randomUUID();drafts.create(alice,draft,"삭제될 초안",false);
        jdbc.sql("UPDATE generation_spec_draft SET status='FAILED' WHERE id=?").param(draft).update();

        mvc.perform(post("/api/me/delete").with(user(alice)).contentType("application/json").content(body(PASSWORD,alice))).andExpect(status().isForbidden()); // CSRF
        mvc.perform(post("/api/me/delete").with(user(alice)).with(csrf()).contentType("application/json").content(body("wrong-password",alice))).andExpect(status().isForbidden());
        mvc.perform(post("/api/me/delete").with(user(alice)).with(csrf()).contentType("application/json").content(body(PASSWORD,bob))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/me/delete").with(user(alice)).with(csrf()).contentType("application/json").content(body(PASSWORD,alice))).andExpect(status().isConflict()); // pending judge
        assertThat(count("SELECT count(*) FROM app_user WHERE id=?",owner)).isOne();
        jdbc.sql("UPDATE judge_job SET status='FINISHED',verdict='WA' WHERE submission_id=?").param(mine.id()).update();

        mvc.perform(post("/api/me/delete").with(user(alice)).with(csrf()).contentType("application/json").content(body(PASSWORD,alice)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.keptProblems").value(2));
        assertThat(count("SELECT count(*) FROM app_user WHERE id=?",owner)).isZero();
        assertThat(count("SELECT count(*) FROM submission WHERE user_id=?",owner)).isZero();
        assertThat(count("SELECT count(*) FROM training_session WHERE user_id=?",owner)).isZero();
        assertThat(count("SELECT count(*) FROM generation_job WHERE owner_id=?",owner)).isZero();
        assertThat(count("SELECT count(*) FROM generation_spec_draft WHERE owner_id=?",owner)).isZero();
        assertThat(count("SELECT count(*) FROM problem_version WHERE id=?",personal)).isZero();
        UUID archive=jdbc.sql("SELECT id FROM app_user WHERE username=?").param(AccountDeletion.ARCHIVE_USERNAME).query(UUID.class).single();
        for(String kept:new String[]{sharedGenerated,solvedByBob})
            assertThat(jdbc.sql("SELECT owner_id FROM problem_version WHERE id=?").param(kept).query(UUID.class).single()).isEqualTo(archive);
        var visible=submissions.problems(bob).stream().filter(p->p.version().equals(sharedGenerated)).findFirst().orElseThrow();
        assertThat(visible.category()).isEqualTo(category); // label survives the deleted generation job
        assertThat(visible.mine()).isFalse();
        assertThat(count("SELECT count(*) FROM submission WHERE id=?",bobSubmission.id())).isOne();
        // The archive account cannot sign in, cannot be deleted, and is reused for the next deletion.
        mvc.perform(post("/api/auth/login").with(csrf()).param("username",AccountDeletion.ARCHIVE_USERNAME).param("password","!")).andExpect(status().is4xxClientError());
        assertThatThrownBy(()->deletion.delete(AccountDeletion.ARCHIVE_USERNAME,"!",AccountDeletion.ARCHIVE_USERNAME)).isInstanceOf(AccountException.class);
        String carol="c"+UUID.randomUUID().toString().substring(0,8);addUser(carol);
        assertThat(deletion.delete(carol,PASSWORD,carol).keptProblems()).isZero();
        assertThat(count("SELECT count(*) FROM app_user WHERE username=?",AccountDeletion.ARCHIVE_USERNAME)).isOne();
    }
}
