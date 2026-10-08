package dev.gamjaoj;
import dev.gamjaoj.exception.AccountException;
import dev.gamjaoj.service.generation.GenerationJobs;
import dev.gamjaoj.service.generation.GenerationSpecDrafts;
import dev.gamjaoj.support.JudgeJson;
import dev.gamjaoj.domain.LanguageProfiles;
import dev.gamjaoj.dto.RunDtos;
import dev.gamjaoj.dto.SubmissionDtos;
import dev.gamjaoj.service.judge.Submissions;
import dev.gamjaoj.dto.TrainingSessionDtos;
import dev.gamjaoj.service.learning.TrainingSessions;

import java.util.*;
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

@SpringBootTest(properties={"spring.datasource.url=${GAMJA_CATALOG_TEST_DB:jdbc:h2:mem:sharedcatalog;MODE=PostgreSQL;DB_CLOSE_DELAY=-1}",
 "spring.datasource.username=${GAMJA_CATALOG_TEST_USER:sa}","spring.datasource.password=${GAMJA_CATALOG_TEST_PASSWORD:}",
 "gamjaoj.invite-code=test-only","gamjaoj.worker-token=catalog-test-worker-32-characters","gamjaoj.submissions-enabled=true","AI_API_ENABLED=false","AI_POLL_MS=3600000"})
@AutoConfigureMockMvc
class SharedCatalogIntegrationTest {
    @Autowired JdbcClient jdbc; @Autowired MockMvc mvc; @Autowired Submissions submissions;
    @Autowired TrainingSessions training; @Autowired GenerationJobs generation; @Autowired GenerationSpecDrafts drafts;
    UUID addUser(String name){var id=UUID.randomUUID();jdbc.sql("INSERT INTO app_user(id,username,password_hash,nickname) VALUES (?,?,?,?)").param(id).param(name).param("unused").param(name).update();return id;}
    String settings(boolean shared){return "{\"shared\":"+shared+",\"category\":\"자료구조\",\"tags\":[\"스택\",\"경계값\"],\"difficulty\":\"MEDIUM\"}";}
    @Test void warmPublicPackageReuseNeverCachesVisibilityLimitsOrNewPackageContent() {
        String alice="cache-a"+UUID.randomUUID().toString().substring(0,8),bob="cache-b"+UUID.randomUUID().toString().substring(0,8);
        UUID owner=addUser(alice);addUser(bob);String version="cache-"+UUID.randomUUID();
        jdbc.sql("INSERT INTO problem_version(id,package_json,package_sha256,runtime_image,runner_policy,ready,owner_id,shared) SELECT ?,package_json,package_sha256,runtime_image,runner_policy,true,?,true FROM problem_version WHERE id='total-v1'").param(version).param(owner).update();
        var before=progress(bob,version);assertThat(before.shared()).isTrue();
        jdbc.sql("UPDATE problem_version SET time_limits_json=?,shared=false WHERE id=?").param("{\"JAVA\":7,\"CPP\":4,\"PYTHON\":9,\"analysis\":\"test publication\"}").param(version).update();
        assertThat(submissions.problems(bob)).noneMatch(p->p.version().equals(version));
        assertThat(progress(alice,version).languages()).filteredOn(l->l.id().equals("JAVA")).extracting(LanguageProfiles.Option::timeLimitMs).containsExactly(7000);
        var pkg=(com.fasterxml.jackson.databind.node.ObjectNode)JudgeJson.parse(jdbc.sql("SELECT package_json FROM problem_version WHERE id=?").param(version).query(String.class).single());
        pkg.put("title","새 패키지 제목");pkg.put("statement","바뀐 공개 본문");String json=JudgeJson.canonical(pkg);
        jdbc.sql("UPDATE problem_version SET package_json=?,package_sha256=? WHERE id=?").param(json).param(JudgeJson.hash(json)).param(version).update();
        assertThat(progress(alice,version).title()).isEqualTo("새 패키지 제목");assertThat(progress(alice,version).statement()).isEqualTo("바뀐 공개 본문");
        jdbc.sql("UPDATE problem_version SET review_hold=true WHERE id=?").param(version).update();
        assertThat(progress(alice,version).problemHeld()).isTrue();assertThat(progress(alice,version).submissionsEnabled()).isFalse();
    }
    @Test void thinkingRatingsAreValidatedOwnerMetadataAndNeverChangeJudgeOrConfidence() throws Exception {
        String name="t"+UUID.randomUUID().toString().substring(0,8),other="t"+UUID.randomUUID().toString().substring(0,8);
        UUID owner=addUser(name);addUser(other);String version="thinking-"+UUID.randomUUID();
        jdbc.sql("INSERT INTO problem_version(id,package_json,package_sha256,runtime_image,runner_policy,ready,owner_id,catalog_difficulty) SELECT ?,package_json,package_sha256,runtime_image,runner_policy,true,?,'HARD' FROM problem_version WHERE id='total-v1'").param(version).param(owner).update();
        assertThat(progress(name,version).thinking()).isNull(); // Legacy HARD does not become an invented layer.
        assertThat(progress(name,"total-v1").thinking().layer()).isEqualTo(1);
        String endpoint="/api/problems/"+version+"/catalog-settings",hash=jdbc.sql("SELECT package_sha256 FROM problem_version WHERE id=?").param(version).query(String.class).single();
        String body=settings(true).replace("\"MEDIUM\"","\"HARD\"").replace("}",",\"thinking\":{\"layer\":5,\"insight\":4,\"implementation\":2,\"edgeCases\":3,\"rationale\":\"질문의 방향을 바꿔 생각해야 해요.\"}}");
        mvc.perform(put(endpoint).with(user(other)).with(csrf()).contentType("application/json").content(body)).andExpect(status().isNotFound());
        mvc.perform(put(endpoint).with(user(name)).with(csrf()).contentType("application/json").content(body.replace("\"layer\":5","\"layer\":10"))).andExpect(status().isBadRequest());
        mvc.perform(put(endpoint).with(user(name)).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.thinking.layer").value(5)).andExpect(jsonPath("$.thinking.name").value("뒤집어보기")).andExpect(jsonPath("$.thinking.source").value("AUTHOR_ESTIMATE"));
        mvc.perform(put(endpoint).with(user(name)).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk());
        assertThat(progress(other,version).thinking().implementation()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM problem_thinking_profile WHERE problem_version=?").param(version).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT package_sha256 FROM problem_version WHERE id=?").param(version).query(String.class).single()).isEqualTo(hash);
        assertThat(jdbc.sql("SELECT count(*) FROM problem_reflection WHERE problem_version=?").param(version).query(Integer.class).single()).isZero();
        // Changing public sharing through an older client must preserve the new profile.
        mvc.perform(put(endpoint).with(user(name)).with(csrf()).contentType("application/json").content(settings(false))).andExpect(status().isOk()).andExpect(jsonPath("$.thinking.layer").value(5));
        assertThat(submissions.problems(other)).noneMatch(p->p.version().equals(version));
        mvc.perform(put(endpoint).with(user(name)).with(csrf()).contentType("application/json").content(body.replace("\"edgeCases\":3","\"edgeCases\":0"))).andExpect(status().isBadRequest());
        mvc.perform(put(endpoint).with(user(name)).with(csrf()).contentType("application/json").content(body.replace("\"thinking\":","\"clearThinking\":true,\"thinking\":"))).andExpect(status().isBadRequest());
        assertThat(progress(name,version).shared()).isFalse(); // Conflicting profile save rolls back sharing too.
        assertThat(progress(name,version).thinking().layer()).isEqualTo(5);
        jdbc.sql("UPDATE problem_version SET package_sha256=? WHERE id=?").param("0".repeat(64)).param(version).update();
        assertThat(progress(name,version).thinking()).isNull(); // A rating for another package identity is never reused.
        jdbc.sql("UPDATE problem_version SET package_sha256=? WHERE id=?").param(hash).param(version).update();
        mvc.perform(put(endpoint).with(user(name)).with(csrf()).contentType("application/json").content(settings(false).replace("}",",\"clearThinking\":true"+"}"))).andExpect(status().isOk()).andExpect(jsonPath("$.thinking").isEmpty());
        assertThat(progress(name,version).thinking()).isNull();
    }
    @Test void shareSolveTrainWithdrawAndHoldPreserveOwnerIsolation() throws Exception {
        String alice="a"+UUID.randomUUID().toString().substring(0,8),bob="b"+UUID.randomUUID().toString().substring(0,8);
        var owner=addUser(alice);addUser(bob);String version="shared-"+UUID.randomUUID();
        jdbc.sql("INSERT INTO problem_version(id,package_json,package_sha256,runtime_image,runner_policy,ready,owner_id) SELECT ?,package_json,package_sha256,runtime_image,runner_policy,true,? FROM problem_version WHERE id='total-v1'").param(version).param(owner).update();
        String endpoint="/api/problems/"+version+"/catalog-settings";
        assertThat(submissions.problems(bob)).noneMatch(p->p.version().equals(version));
        var request=new SubmissionDtos.Request(version,"class Main {}");
        assertThatThrownBy(()->submissions.submit(bob,UUID.randomUUID(),request)).isInstanceOf(AccountException.class);
        mvc.perform(put(endpoint).with(user(bob)).with(csrf()).contentType("application/json").content(settings(true))).andExpect(status().isNotFound());
        mvc.perform(put(endpoint).with(user(alice)).contentType("application/json").content(settings(true))).andExpect(status().isForbidden());
        mvc.perform(put(endpoint).with(user(alice)).with(csrf()).contentType("application/json").content(settings(true))).andExpect(status().isOk()).andExpect(jsonPath("$.mine").value(true)).andExpect(jsonPath("$.difficultySource").value("AUTHOR_ESTIMATE"));
        var shared=submissions.problems(bob).stream().filter(p->p.version().equals(version)).findFirst().orElseThrow();
        assertThat(shared.mine()).isFalse();assertThat(shared.shared()).isTrue();assertThat(shared.tags()).containsExactly("스택","경계값");
        mvc.perform(get("/api/problems").with(user(bob))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("package_json"))));
        mvc.perform(get("/api/problems/"+version+"/teaching").with(user(bob))).andExpect(status().isOk());
        UUID key=UUID.randomUUID();var saved=submissions.submit(bob,key,request);
        submissions.run(bob,UUID.randomUUID(),new RunDtos.Request(version,"class Main {}","1\n",null));
        training.start(bob,UUID.randomUUID(),new TrainingSessionDtos.Start(version,"공개 문제 연습"));
        assertThat(submissions.history(alice)).noneMatch(v->v.id().equals(saved.id()));
        mvc.perform(get("/api/submissions/"+saved.id()).with(user(alice))).andExpect(status().isNotFound());
        mvc.perform(put(endpoint).with(user(alice)).with(csrf()).contentType("application/json").content(settings(false))).andExpect(status().isOk());
        assertThat(submissions.problems(bob)).noneMatch(p->p.version().equals(version));
        assertThat(submissions.submit(bob,key,request).id()).isEqualTo(saved.id());
        assertThatThrownBy(()->submissions.submit(bob,UUID.randomUUID(),request)).isInstanceOf(AccountException.class);
        mvc.perform(get("/api/problems/"+version+"/teaching").with(user(bob))).andExpect(status().isNotFound());
        jdbc.sql("UPDATE problem_version SET shared=true,review_hold=true WHERE id=?").param(version).update();
        assertThat(submissions.problems(bob)).noneMatch(p->p.version().equals(version));
        mvc.perform(put(endpoint).with(user(alice)).with(csrf()).contentType("application/json").content(settings(true))).andExpect(status().isNotFound());
        jdbc.sql("UPDATE problem_version SET review_hold=false,diagnostic_only=true WHERE id=?").param(version).update();
        assertThat(submissions.problems(alice)).noneMatch(p->p.version().equals(version));
        mvc.perform(put(endpoint).with(user(alice)).with(csrf()).contentType("application/json").content(settings(true))).andExpect(status().isNotFound());
        assertThatThrownBy(()->submissions.submit(bob,UUID.randomUUID(),request)).isInstanceOf(AccountException.class);
    }
    @Test void legacyAndFutureCategoryWritesUseKoreanWithoutChangingProblemIdentity() throws Exception {
        String name="k"+UUID.randomUUID().toString().substring(0,8);UUID owner=addUser(name);String version="shared-"+UUID.randomUUID();
        jdbc.sql("INSERT INTO problem_version(id,package_json,package_sha256,runtime_image,runner_policy,ready,owner_id,catalog_category) SELECT ?,package_json,package_sha256,runtime_image,runner_policy,true,?,'basic-data-structures' FROM problem_version WHERE id='total-v1'").param(version).param(owner).update();
        assertThat(submissions.problems(name).stream().filter(p->p.version().equals(version)).findFirst().orElseThrow().category()).isEqualTo("기초 자료구조");
        String hash=jdbc.sql("SELECT package_sha256 FROM problem_version WHERE id=?").param(version).query(String.class).single();
        mvc.perform(put("/api/problems/"+version+"/catalog-settings").with(user(name)).with(csrf()).contentType("application/json").content(settings(false).replace("자료구조","bfs"))).andExpect(status().isOk()).andExpect(jsonPath("$.category").value("너비 우선 탐색"));
        assertThat(jdbc.sql("SELECT catalog_category FROM problem_version WHERE id=?").param(version).query(String.class).single()).isEqualTo("너비 우선 탐색");
        mvc.perform(put("/api/problems/"+version+"/catalog-settings").with(user(name)).with(csrf()).contentType("application/json").content(settings(false).replace("자료구조","unknown-new-category"))).andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT package_sha256 FROM problem_version WHERE id=?").param(version).query(String.class).single()).isEqualTo(hash);
    }
    @Test void generationSharingIsExplicitAndPartOfReplayContract() {
        String name="g"+UUID.randomUUID().toString().substring(0,8);addUser(name);
        UUID key=UUID.randomUUID();var first=generation.create(name,key,"sequence-sum-v1","basics",null,true);
        assertThat(generation.create(name,key,"sequence-sum-v1","basics",null,true).id()).isEqualTo(first.id());
        assertThatThrownBy(()->generation.create(name,key,"sequence-sum-v1","basics",null,false)).isInstanceOf(AccountException.class);
        assertThat(jdbc.sql("SELECT share_on_publish FROM generation_job WHERE id=?").param(key).query(Boolean.class).single()).isTrue();
        jdbc.sql("UPDATE generation_job SET status='FAILED' WHERE id=?").param(key).update();
        UUID draft=UUID.randomUUID();drafts.create(name,draft,"공개 범위 테스트",true);
        assertThat(drafts.create(name,draft,"공개 범위 테스트",true).id()).isEqualTo(draft);
        assertThatThrownBy(()->drafts.create(name,draft,"공개 범위 테스트",false)).isInstanceOf(AccountException.class);
        assertThat(jdbc.sql("SELECT share_on_publish FROM generation_spec_draft WHERE id=?").param(draft).query(Boolean.class).single()).isTrue();
    }
    UUID progressRecord(UUID owner,String version,String input,String verdict) {
        UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO submission(id,user_id,problem_version,source_code,source_sha256,idempotency_key,runtime_image,runner_policy,run_input) SELECT ?,?,id,'class Main {}',?,?,runtime_image,runner_policy,? FROM problem_version WHERE id=?")
                .param(id).param(owner).param(JudgeJson.hash("class Main {}")).param(id).param(input).param(version).update();
        jdbc.sql("INSERT INTO judge_job(submission_id,status,verdict) VALUES (?,?,?)").param(id).param(verdict==null?"QUEUED":"FINISHED").param(verdict).update();
        return id;
    }
    Submissions.Problem progress(String name,String version) {
        return submissions.problems(name).stream().filter(p->p.version().equals(version)).findFirst().orElseThrow();
    }
    @Test void progressUsesAllOwnedFormalSubmissionsAndNeverCustomOrValidationExecutions() throws Exception {
        String alice="p"+UUID.randomUUID().toString().substring(0,8),bob="q"+UUID.randomUUID().toString().substring(0,8);
        UUID owner=addUser(alice);addUser(bob);
        UUID old=progressRecord(owner,"sum-v1",null,"AC");
        jdbc.sql("UPDATE submission SET created_at=? WHERE id=?").param(java.time.OffsetDateTime.now().minusDays(1)).param(old).update();
        for(int n=0;n<55;n++)progressRecord(owner,"total-v1",null,"WA");
        progressRecord(owner,"sum-v1",null,"WA");
        assertThat(submissions.history(alice)).hasSize(50).noneMatch(v->v.id().equals(old));
        assertThat(progress(alice,"sum-v1").solveStatus()).isEqualTo("SOLVED");
        assertThat(progress(alice,"total-v1").solveStatus()).isEqualTo("ATTEMPTED");
        assertThat(progress(bob,"sum-v1").solveStatus()).isEqualTo("UNATTEMPTED");
        var run=submissions.run(alice,UUID.randomUUID(),new RunDtos.Request("valid-parentheses-v1","class Main {}","()"));
        jdbc.sql("UPDATE judge_job SET status='FINISHED',verdict='AC' WHERE submission_id=?").param(run.id()).update();
        UUID generated=UUID.randomUUID(),draft=UUID.randomUUID();
        jdbc.sql("INSERT INTO generation_job(id,owner_id,template_id,status,model,effort) VALUES (?,?,'parentheses-v1','FAILED','fixture','low')").param(generated).param(owner).update();
        jdbc.sql("INSERT INTO generation_spec_draft(id,owner_id,request_text,status,model,effort) VALUES (?,?,'fixture','FAILED','fixture','low')").param(draft).param(owner).update();
        UUID check=progressRecord(owner,"valid-parentheses-v1",null,"AC"),specCheck=progressRecord(owner,"valid-parentheses-v1",null,"AC");
        jdbc.sql("UPDATE submission SET generation_job_id=? WHERE id=?").param(generated).param(check).update();
        jdbc.sql("UPDATE submission SET spec_draft_id=? WHERE id=?").param(draft).param(specCheck).update();
        assertThat(progress(alice,"valid-parentheses-v1").solveStatus()).isEqualTo("UNATTEMPTED");
        UUID pending=progressRecord(owner,"valid-parentheses-v1",null,null);
        assertThat(progress(alice,"valid-parentheses-v1").solveStatus()).isEqualTo("ATTEMPTED");
        assertThat(progress(alice,"valid-parentheses-v1").pendingSubmissions()).isEqualTo(1);
        jdbc.sql("UPDATE judge_job SET status='FINISHED',verdict='IE' WHERE submission_id=?").param(pending).update();
        assertThat(progress(alice,"valid-parentheses-v1").solveStatus()).isEqualTo("ATTEMPTED");
        assertThat(progress(alice,"valid-parentheses-v1").pendingSubmissions()).isZero();
        mvc.perform(get("/api/problems").with(user(bob))).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.version == 'sum-v1')].solveStatus").value(org.hamcrest.Matchers.contains("UNATTEMPTED")));
    }

}
