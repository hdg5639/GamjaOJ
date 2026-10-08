package dev.gamjaoj;
import dev.gamjaoj.dto.LearningProgressDtos;
import dev.gamjaoj.support.JudgeJson;
import dev.gamjaoj.service.learning.LearningProgress;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:learning;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","gamjaoj.invite-code=test","AI_POLL_MS=3600000"})
@AutoConfigureMockMvc
class LearningProgressIntegrationTest {
    @Autowired JdbcClient jdbc;@Autowired LearningProgress learning;@Autowired MockMvc mvc;
    UUID alice,bob;LocalDate today=LocalDate.of(2026,10,2);
    @BeforeEach void setup(){
        jdbc.sql("DELETE FROM submission").update();jdbc.sql("DELETE FROM problem_version WHERE id LIKE 'lp-%'").update();jdbc.sql("DELETE FROM app_user").update();
        alice=createUser("alice");bob=createUser("bob");
        problem("lp-arrays","배열·문자열",false,false,null);problem("lp-new-arrays","배열·문자열",false,false,null);
        problem("lp-bfs","bfs",false,false,null);problem("lp-dp","dp",false,false,null);
        problem("lp-diagnostic","진단",true,false,null);problem("lp-held","보류",false,true,null);problem("lp-hidden","비공개",false,false,bob);
    }
    UUID createUser(String name){UUID id=UUID.randomUUID();jdbc.sql("INSERT INTO app_user(id,username,password_hash,nickname) VALUES (?,?,?,?)").param(id).param(name).param("unused").param(name).update();return id;}
    void problem(String id,String category,boolean diagnostic,boolean held,UUID owner){
        String pkg="{\"version\":\""+id+"\",\"title\":\""+id+"\",\"statement\":\"입력의 합\",\"tests\":[{\"id\":\"sample\",\"input\":\"1\",\"output\":\"1\"}]}";
        jdbc.sql("INSERT INTO problem_version(id,package_json,package_sha256,runtime_image,runner_policy,ready,catalog_category,catalog_difficulty,diagnostic_only,review_hold,owner_id,shared) SELECT ?,?,?,runtime_image,runner_policy,true,?,'EASY',?,?,?,false FROM problem_version WHERE id='sum-v1'")
                .param(id).param(pkg).param(JudgeJson.hash(pkg)).param(category).param(diagnostic).param(held).param(owner).update();
    }
    UUID submit(UUID user,String version,String verdict,OffsetDateTime date,boolean run){
        UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO submission(id,user_id,problem_version,source_code,source_sha256,idempotency_key,runtime_image,runner_policy,created_at,run_input,run_package,run_package_sha256) SELECT ?,?,?,'class Main {}',?,?,runtime_image,runner_policy,?,?,?,? FROM problem_version WHERE id=?")
                .param(id).param(user).param(version).param(JudgeJson.hash("class Main {}")).param(UUID.randomUUID()).param(date).param(run?"1":null).param(run?"{}":null).param(run?JudgeJson.hash("{}"):null).param(version).update();
        jdbc.sql("INSERT INTO judge_job(submission_id,status,verdict,result_json,result_sha256,finished_at) VALUES (?,'FINISHED',?,'{}',?,?)")
                .param(id).param(verdict).param(JudgeJson.hash("{}")).param(date).update();return id;
    }
    OffsetDateTime at(LocalDate day){return day.atTime(12,0).atZone(LearningProgress.ZONE).toOffsetDateTime();}
    @Test void calendarDeduplicatesProblemsUsesSeoulMidnightAndExcludesNonPractice(){
        var yesterday=today.minusDays(1);submit(alice,"lp-arrays","AC",at(yesterday),false);submit(alice,"lp-arrays","AC",at(yesterday).plusHours(1),false);
        // 15:01 UTC belongs to the following Korean day.
        submit(alice,"lp-bfs","AC",OffsetDateTime.parse("2026-10-01T15:01:00Z"),false);
        submit(alice,"lp-dp","WA",at(today),false);submit(alice,"lp-dp","AC",at(today),true);
        submit(alice,"lp-diagnostic","AC",at(today),false);submit(alice,"lp-held","AC",at(today),false);submit(bob,"lp-dp","AC",at(today),false);
        submit(alice,"lp-dp","AC",at(today.plusDays(1)),false);submit(alice,"lp-dp","AC",at(today.minusDays(365)),false);
        var result=learning.dashboard("alice",today);assertThat(result.days()).hasSize(365);
        assertThat(result.days().getLast().solved()).isEqualTo(1);assertThat(result.days().get(363).solved()).isEqualTo(1);
        assertThat(result.activeDays()).isEqualTo(2);assertThat(result.currentStreak()).isEqualTo(2);assertThat(result.longestStreak()).isEqualTo(2);
        assertThat(result.categories()).extracting(LearningProgressDtos.Category::category).doesNotContain("진단","보류","비공개");
    }
    @Test void yesterdayStreakSurvivesUntilTodayIsOverAndMissingDaysReset(){
        submit(alice,"lp-arrays","AC",at(today.minusDays(1)),false);submit(alice,"lp-arrays","AC",at(today.minusDays(2)),false);
        submit(alice,"lp-arrays","AC",at(today.minusDays(4)),false);
        var result=learning.dashboard("alice",today);assertThat(result.currentStreak()).isEqualTo(2);assertThat(result.longestStreak()).isEqualTo(2);
        assertThat(learning.dashboard("bob",today).activeDays()).isZero();
    }
    @Test void recommendationsCountDistinctRecentProblemsAndNeverExposePrivateOrSolved(){
        for(int i=0;i<5;i++){String id="lp-extra-"+i;problem(id,"배열·문자열",false,false,null);submit(alice,id,"WA",at(today.minusDays(i)),false);}
        for(int i=0;i<9;i++)submit(alice,"lp-arrays","WA",at(today),false);
        submit(alice,"lp-arrays","AC",at(today.minusDays(100)),false);
        var result=learning.dashboard("alice",today);assertThat(result.practicedProblems()).isEqualTo(6);assertThat(result.dominantCategory()).isEqualTo("배열·문자열");
        assertThat(result.categories().stream().filter(c->c.category().equals("배열·문자열")).findFirst().orElseThrow().attempted()).isEqualTo(6);
        assertThat(result.explore()).extracting(LearningProgressDtos.Suggestion::version).doesNotContain("lp-arrays","lp-hidden","lp-held","lp-diagnostic");
        assertThat(result.explore().getFirst().category()).isNotEqualTo("배열·문자열");
        assertThat(result.explore()).extracting(LearningProgressDtos.Suggestion::category).doesNotHaveDuplicates();
    }
    @Test void reflectionsAreOwnedAcceptedOnlyPersistAndFeedReviewQueue()throws Exception{
        UUID ac=submit(alice,"lp-arrays","AC",at(today),false);UUID foreign=submit(bob,"lp-bfs","AC",at(today),false);
        UUID wa=submit(alice,"lp-dp","WA",at(today),false);UUID run=submit(alice,"lp-dp","AC",at(today),true);
        for(UUID invalid:List.of(foreign,wa,run))mvc.perform(put("/api/my/reflections").with(user("alice")).with(csrf()).contentType("application/json").content(body(invalid,"REVISIT",""))).andExpect(status().isNotFound());
        mvc.perform(put("/api/my/reflections").with(user("alice")).contentType("application/json").content(body(ac,"REVISIT","경계 조건"))).andExpect(status().isForbidden());
        mvc.perform(put("/api/my/reflections").with(user("alice")).with(csrf()).contentType("application/json").content(body(ac,"REVISIT","경계 조건"))).andExpect(status().isOk()).andExpect(jsonPath("$.confidence").value("REVISIT"));
        mvc.perform(get("/api/my/reflections?problemVersion=lp-arrays").with(user("bob"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/my/problems").with(user("alice"))).andExpect(jsonPath("$.items[?(@.version=='lp-arrays')].confidence").value(org.hamcrest.Matchers.hasItem("REVISIT")));
        assertThat(learning.dashboard("alice",today).revisit()).extracting(LearningProgressDtos.Suggestion::version).containsExactly("lp-arrays");
        UUID newer=submit(alice,"lp-arrays","AC",at(today).plusMinutes(1),false);
        var saved=learning.reflection("alice","lp-arrays");assertThat(saved.submissionId()).isEqualTo(ac);assertThat(saved.latestAcceptedSubmissionId()).isEqualTo(newer);
        mvc.perform(put("/api/my/reflections").with(user("alice")).with(csrf()).contentType("application/json").content(body(newer,"SOLID","정리했음"))).andExpect(status().isOk());
        assertThat(learning.dashboard("alice",today).revisit()).isEmpty();
        mvc.perform(put("/api/my/reflections").with(user("alice")).with(csrf()).contentType("application/json").content("{\"submissionId\":\""+ac+"\",\"confidence\":null,\"note\":\"\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.confidence").isEmpty());
        assertThat(jdbc.sql("SELECT count(*) FROM problem_reflection").query(Integer.class).single()).isZero();
    }
    @Test void invalidRequestsAndHeldOrDiagnosticSubmissionsCannotBeRated()throws Exception{
        UUID ac=submit(alice,"lp-arrays","AC",at(today),false);UUID diagnostic=submit(alice,"lp-diagnostic","AC",at(today),false);UUID held=submit(alice,"lp-held","AC",at(today),false);
        for(UUID invalid:List.of(diagnostic,held))mvc.perform(put("/api/my/reflections").with(user("alice")).with(csrf()).contentType("application/json").content(body(invalid,"SOLID",""))).andExpect(status().isNotFound());
        mvc.perform(put("/api/my/reflections").with(user("alice")).with(csrf()).contentType("application/json").content(body(ac,"BAD",""))).andExpect(status().isBadRequest());
        mvc.perform(put("/api/my/reflections").with(user("alice")).with(csrf()).contentType("application/json").content(body(ac,"SOLID","x".repeat(501)))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/my/learning")).andExpect(status().isUnauthorized());
        assertThat(jdbc.sql("SELECT count(*) FROM ai_task").query(Integer.class).single()).isZero();
    }
    String body(UUID id,String confidence,String note){return JudgeJson.JSON.createObjectNode().put("submissionId",id.toString()).put("confidence",confidence).put("note",note).toString();}
}
