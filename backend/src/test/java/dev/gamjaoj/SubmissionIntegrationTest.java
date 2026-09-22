package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
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

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:submissions;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "gamjaoj.invite-code=test-only",
        "gamjaoj.worker-token=worker-test-credential-32-characters", "server.servlet.session.cookie.secure=false"})
@AutoConfigureMockMvc
class SubmissionIntegrationTest {
    @Autowired JdbcClient jdbc;
    @Autowired Submissions submissions;
    @Autowired JudgeQueue queue;
    @Autowired MockMvc mvc;
    @Autowired TrainingSessions sessions;
    String alice, bob;
    static final String SOURCE = "public class Main { public static void main(String[] args) { System.out.println(3); } }";

    @BeforeEach
    void users() {
        jdbc.sql("DELETE FROM app_user").update();
        alice = "a" + UUID.randomUUID().toString().substring(0,8);
        bob = "b" + UUID.randomUUID().toString().substring(0,8);
        for (String name : List.of(alice, bob)) {
            UUID id = UUID.randomUUID();
            jdbc.sql("INSERT INTO app_user (id,username,password_hash,nickname) VALUES (?,?,?,?)")
                    .param(id).param(name).param("not-used-for-login").param(name).update();
            jdbc.sql("INSERT INTO execution_grant (user_id,source_sha256) VALUES (?,?)")
                    .param(id).param(JudgeJson.hash(SOURCE)).update();
        }
    }
    Submissions.View submit(String name, UUID key) {
        return submissions.submit(name, key, new SubmissionController.Request("sum-v1", SOURCE));
    }
    void expire(UUID id) {
        jdbc.sql("UPDATE judge_job SET lease_until=? WHERE submission_id=?").param(OffsetDateTime.now().minusMinutes(1)).param(id).update();
    }
    ObjectNode report(JudgeQueue.Assignment assignment) {
        ObjectNode report = JudgeJson.JSON.createObjectNode();
        report.put("run_id", UUID.randomUUID().toString()).put("policy", assignment.runnerPolicy())
                .put("image", assignment.runtimeImage()).put("source_sha256", assignment.sourceSha256())
                .put("problem_sha256", assignment.problemSha256()).put("problem_version", "sum-v1").put("verdict", "AC");
        report.putObject("compile").put("exit_code",0).put("wall_ms",1).put("stderr", "");
        var tests = report.putArray("tests");
        assignment.problem().path("tests").forEach(test -> tests.addObject().put("id",test.path("id").asText())
                .put("verdict","AC").put("exit_code",0).put("oom_killed",false).put("wall_ms",1)
                .put("stdout_sha256",JudgeJson.hash(test.path("output").asText())).put("stderr", ""));
        return report;
    }

    @Test
    void admittedJobRetainsRuntimeWhenProblemDefaultsChange() {
        var saved=submit(alice,UUID.randomUUID());
        var original=jdbc.sql("SELECT runtime_image FROM problem_version WHERE id='sum-v1'").query(String.class).single();
        try {
            jdbc.sql("UPDATE problem_version SET runtime_image='different',runner_policy='different' WHERE id='sum-v1'").update();
            var assignment=queue.claim(UUID.randomUUID()).orElseThrow();
            assertThat(assignment.runtimeImage()).isEqualTo(original);
            assertThat(assignment.runnerPolicy()).isEqualTo("java8-judge-v1");
            queue.complete(saved.id(),assignment.token(),report(assignment));
        } finally {
            jdbc.sql("UPDATE problem_version SET runtime_image=?,runner_policy='java8-judge-v1' WHERE id='sum-v1'").param(original).update();
        }
    }

    @Test
    void sessionStartIsConcurrentIdempotentAndOwnerOnly() throws Exception {
        UUID key=UUID.randomUUID();
        var request=new TrainingSessionController.Start("sum-v1","경계값 확인");
        try (var executor=Executors.newFixedThreadPool(4)) {
            List<Callable<UUID>> calls=new ArrayList<>();
            for(int i=0;i<4;i++) calls.add(()->sessions.start(alice,key,request).id());
            for(var result:executor.invokeAll(calls)) assertThat(result.get()).isEqualTo(key);
        }
        assertThat(sessions.history(alice)).hasSize(1);
        assertThatThrownBy(()->sessions.start(alice,UUID.randomUUID(),request)).isInstanceOf(AccountException.class);
        assertThatThrownBy(()->sessions.start(alice,key,new TrainingSessionController.Start("sum-v1","different"))).isInstanceOf(AccountException.class);
        mvc.perform(get("/api/training-sessions/"+key).with(user(bob))).andExpect(status().isNotFound());
        mvc.perform(get("/api/training-sessions/"+key)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/training-sessions/"+key+"/end").with(user(alice)).contentType("application/json").content("{\"note\":\"\"}"))
                .andExpect(status().isForbidden());
        assertThat(sessions.history(bob)).isEmpty();
    }

    @Test
    void endingSessionPreservesQueuedWorkAndReplayButRejectsNewWork() {
        UUID session=UUID.randomUUID(), key=UUID.randomUUID();
        sessions.start(alice,session,new TrainingSessionController.Start("sum-v1","target"));
        var request=new SubmissionController.Request("sum-v1",SOURCE,session);
        var saved=submissions.submit(alice,key,request);
        var custom=submissions.run(alice,UUID.randomUUID(),new RunController.Request("sum-v1",SOURCE,"1 2",session));
        var ended=sessions.end(alice,session,"다음에는 경계값부터");
        assertThat(ended.pending()).isEqualTo(2);
        assertThat(ended.submissions()).isEqualTo(1);
        assertThat(ended.runs()).isEqualTo(1);
        assertThat(ended.accepted()).isZero();
        assertThat(sessions.end(alice,session,ended.note()).endedAt()).isEqualTo(ended.endedAt());
        assertThatThrownBy(()->sessions.end(alice,session,"changed")).isInstanceOf(AccountException.class);
        assertThat(submissions.submit(alice,key,request).id()).isEqualTo(saved.id());
        assertThatThrownBy(()->submissions.submit(alice,UUID.randomUUID(),request)).isInstanceOf(AccountException.class);
        assertThatThrownBy(()->submissions.run(alice,UUID.randomUUID(),new RunController.Request("sum-v1",SOURCE,"",session))).isInstanceOf(AccountException.class);
        assertThatThrownBy(()->submissions.submit(alice,key,new SubmissionController.Request("sum-v1",SOURCE))).isInstanceOf(AccountException.class);
        var assignment=queue.claim(UUID.randomUUID()).orElseThrow();
        // Select the formal job independently of tied database timestamps.
        if (assignment.submissionId().equals(custom.id())) assignment=queue.claim(UUID.randomUUID()).orElseThrow();
        assertThat(assignment.submissionId()).isEqualTo(saved.id());
        queue.complete(saved.id(),assignment.token(),report(assignment));
        assertThat(sessions.detail(alice,session).session().accepted()).isEqualTo(1);
        assertThat(sessions.detail(alice,session).session().pending()).isEqualTo(1);
        assertThat(sessions.detail(alice,session).entries()).hasSize(2);
        var next=sessions.start(alice,UUID.randomUUID(),new TrainingSessionController.Start("sum-v1","next"));
        assertThat(next.id()).isNotEqualTo(session);
    }

    @Test
    void sessionAttachmentChecksOwnerAndKeepsLegacyRecordsUnassigned() {
        var old=submit(alice,UUID.randomUUID());
        UUID session=UUID.randomUUID();
        sessions.start(alice,session,new TrainingSessionController.Start("sum-v1",""));
        assertThatThrownBy(()->submissions.submit(bob,UUID.randomUUID(),new SubmissionController.Request("sum-v1",SOURCE,session)))
                .isInstanceOf(AccountException.class);
        assertThatThrownBy(()->sessions.end(bob,session,"")).isInstanceOf(AccountException.class);
        assertThat(submissions.detail(alice,old.id()).sessionId()).isNull();
        assertThat(sessions.detail(alice,session).entries()).isEmpty();
        assertThat(submit(alice,UUID.randomUUID()).sessionId()).isNull();
    }

    @Test
    void concurrentEndAndSubmitCannotAttachAfterClosure() throws Exception {
        UUID session=UUID.randomUUID();
        sessions.start(alice,session,new TrainingSessionController.Start("sum-v1",""));
        try (var executor=Executors.newFixedThreadPool(2)) {
            var end=executor.submit(()->sessions.end(alice,session,"done"));
            var submit=executor.submit(()-> {
                try { return submissions.submit(alice,UUID.randomUUID(),new SubmissionController.Request("sum-v1",SOURCE,session)).id(); }
                catch(AccountException expected) { return null; }
            });
            end.get();
            UUID id=submit.get();
            var detail=sessions.detail(alice,session);
            assertThat(detail.session().status()).isEqualTo("ENDED");
            assertThat(detail.entries()).hasSize(id==null ? 0 : 1);
        }
        assertThatThrownBy(()->submissions.submit(alice,UUID.randomUUID(),new SubmissionController.Request("sum-v1",SOURCE,session)))
                .isInstanceOf(AccountException.class);
    }

    @Test
    void customRunsKeepInputImmutableAndNeverReceiveHiddenTestsOrPolluteJudgments() throws Exception {
        UUID key = UUID.randomUUID();
        var request = new RunController.Request("sum-v1", SOURCE, "17 25\n");
        var saved = submissions.run(alice, key, request);
        assertThat(submissions.run(alice, key, request).id()).isEqualTo(saved.id());
        assertThatThrownBy(() -> submissions.run(alice,key,new RunController.Request("sum-v1",SOURCE,"")))
                .isInstanceOf(AccountException.class);
        assertThatThrownBy(() -> submit(alice,key)).isInstanceOf(AccountException.class);
        assertThat(submissions.history(alice)).isEmpty();
        assertThat(submissions.runs(alice)).hasSize(1);
        mvc.perform(get("/api/runs/"+saved.id()).with(user(bob))).andExpect(status().isNotFound());
        mvc.perform(get("/api/submissions/"+saved.id()).with(user(alice))).andExpect(status().isNotFound());
        mvc.perform(post("/api/runs").with(user(alice)).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        var assignment = queue.claim(UUID.randomUUID()).orElseThrow();
        assertThat(assignment.problem().path("tests")).hasSize(1);
        assertThat(assignment.problem().path("tests").get(0).path("input").asText()).isEqualTo("17 25\n");
        assertThat(assignment.problem().toString()).doesNotContain("positive-boundary", "2000000000");
        assertThat(assignment.runnerPolicy()).isEqualTo("java8-run-v1");
        ObjectNode result = report(assignment);
        assertThatThrownBy(() -> queue.complete(saved.id(),assignment.token(),result)).isInstanceOf(AccountException.class);
        result.put("verdict", "OK");
        ((ObjectNode) result.path("tests").get(0)).put("verdict", "OK").put("stdout", "42\n")
                .put("stdout_truncated", false);
        queue.complete(saved.id(), assignment.token(), result);
        queue.complete(saved.id(), assignment.token(), result);
        var done = submissions.runDetail(alice, saved.id());
        assertThat(done.verdict()).isEqualTo("OK");
        assertThat(done.input()).isEqualTo("17 25\n");
        assertThat(done.stdout()).isEqualTo("42\n");
        assertThat(submissions.runs(alice).getFirst().stdout()).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM judge_attempt WHERE status='COMPLETED'").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void customInputByteLimitAndCombinedPendingCapAreEnforced() throws Exception {
        assertThatThrownBy(() -> submissions.run(alice,UUID.randomUUID(),new RunController.Request("sum-v1",SOURCE,"가".repeat(6000))))
                .isInstanceOf(AccountException.class).hasMessageContaining("16 KiB");
        mvc.perform(post("/api/runs").with(user(alice)).with(csrf()).header("Idempotency-Key",UUID.randomUUID())
                .contentType("application/json").content("{\"problemVersion\":\"sum-v1\",\"source\":\"unapproved\",\"input\":\"\"}"))
                .andExpect(status().isServiceUnavailable());
        for (int i=0;i<2;i++) submit(alice,UUID.randomUUID());
        var saved = submissions.run(alice,UUID.randomUUID(),new RunController.Request("sum-v1",SOURCE,""));
        assertThat(submissions.runDetail(alice,saved.id()).input()).isEmpty();
        assertThatThrownBy(() -> submissions.run(alice,UUID.randomUUID(),new RunController.Request("sum-v1",SOURCE,"")))
                .isInstanceOf(AccountException.class).hasMessageContaining("진행 중");
        assertThatThrownBy(() -> submit(alice,UUID.randomUUID())).isInstanceOf(AccountException.class);
    }

    @Test
    void resumedCustomPlanKeepsInputAndFencesExpiredResults() {
        var saved = submissions.run(alice,UUID.randomUUID(),new RunController.Request("sum-v1",SOURCE,"saved input"));
        UUID worker = UUID.randomUUID();
        var first = queue.claim(worker).orElseThrow();
        assertThat(queue.claim(worker).orElseThrow().problemSha256()).isEqualTo(first.problemSha256());
        expire(saved.id());
        var replacement = queue.claim(UUID.randomUUID()).orElseThrow();
        assertThat(replacement.problem()).isEqualTo(first.problem());
        ObjectNode result = report(replacement);
        result.put("verdict","OK");
        var test = (ObjectNode) result.path("tests").get(0);
        test.put("verdict","OK").put("stdout","3\n").put("stdout_truncated",false);
        assertThatThrownBy(() -> queue.complete(saved.id(),first.token(),result)).isInstanceOf(AccountException.class);
        test.put("stdout","x".repeat(32769));
        assertThatThrownBy(() -> queue.complete(saved.id(),replacement.token(),result)).isInstanceOf(AccountException.class);
        test.put("stdout","3\n");
        queue.complete(saved.id(),replacement.token(),result);
        assertThat(submissions.runDetail(alice,saved.id()).input()).isEqualTo("saved input");
        assertThat(jdbc.sql("SELECT count(*) FROM judge_attempt WHERE status='COMPLETED'").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void concurrentSameKeyCreatesOneSnapshotAndOneJob() throws Exception {
        UUID key = UUID.randomUUID();
        try (var executor = Executors.newFixedThreadPool(6)) {
            List<Callable<UUID>> calls = new ArrayList<>();
            for (int i=0;i<6;i++) calls.add(() -> submit(alice,key).id());
            var values = executor.invokeAll(calls);
            UUID first = values.getFirst().get();
            for (var value : values) assertThat(value.get()).isEqualTo(first);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM submission").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM judge_job").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT source_code FROM submission").query(String.class).single()).isEqualTo(SOURCE);
        assertThatThrownBy(() -> submissions.submit(alice,key,new SubmissionController.Request("sum-v1",SOURCE+"\n")))
                .isInstanceOf(AccountException.class).hasMessageContaining("같은 요청 키");
        assertThat(submit(bob,key).id()).isNotEqualTo(submit(alice,key).id());
    }

    @Test
    void ownershipPublicProjectionAndRolloutGateAreEnforcedOverHttp() throws Exception {
        var saved = submit(alice,UUID.randomUUID());
        mvc.perform(get("/api/submissions/"+saved.id())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/submissions/"+saved.id()).with(user(bob))).andExpect(status().isNotFound());
        mvc.perform(get("/api/submissions/"+saved.id()).with(user(alice)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.source").value(SOURCE));
        mvc.perform(get("/api/submissions").with(user(bob))).andExpect(content().json("[]"));
        String publicJson = mvc.perform(get("/api/problems").with(user(alice))).andReturn().getResponse().getContentAsString();
        assertThat(publicJson).contains("sampleInput").doesNotContain("tests", "positive-boundary", "runtimeImage", "package_json");
        mvc.perform(post("/api/submissions").with(user(alice)).with(csrf()).header("Idempotency-Key",UUID.randomUUID())
                .contentType("application/json").content("{\"problemVersion\":\"sum-v1\",\"source\":\"unapproved\"}"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/submissions").with(user(alice)).header("Idempotency-Key",UUID.randomUUID())
                .contentType("application/json").content("{}" )).andExpect(status().isForbidden());
        assertThat(jdbc.sql("SELECT count(*) FROM submission").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void activeAttemptResumesAndCompletionReplayDoesNotCreateEffects() {
        var saved = submit(alice,UUID.randomUUID());
        UUID worker = UUID.randomUUID();
        var assignment = queue.claim(worker).orElseThrow();
        assertThat(queue.claim(worker).orElseThrow().token()).isEqualTo(assignment.token());
        assertThat(queue.claim(UUID.randomUUID())).isEmpty();
        assertThat(assignment.source()).isEqualTo(SOURCE);
        assertThat(assignment.problemSha256()).isEqualTo(JudgeJson.hash(JudgeJson.canonical(assignment.problem())));
        JsonNode report = report(assignment);
        queue.heartbeat(saved.id(),assignment.token());
        queue.complete(saved.id(),assignment.token(),report);
        queue.complete(saved.id(),assignment.token(),report);
        assertThat(submissions.detail(alice,saved.id()).verdict()).isEqualTo("AC");
        assertThat(jdbc.sql("SELECT count(*) FROM judge_attempt WHERE status='COMPLETED'").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void expiredAttemptCannotRenewOrOverwriteNewResult() {
        var saved = submit(alice,UUID.randomUUID());
        var first = queue.claim(UUID.randomUUID()).orElseThrow();
        expire(saved.id());
        assertThatThrownBy(() -> queue.heartbeat(saved.id(),first.token())).isInstanceOf(AccountException.class);
        var second = queue.claim(UUID.randomUUID()).orElseThrow();
        assertThat(second.attempt()).isEqualTo(2);
        assertThatThrownBy(() -> queue.complete(saved.id(),first.token(),report(first))).isInstanceOf(AccountException.class);
        queue.complete(saved.id(),second.token(),report(second));
        assertThat(jdbc.sql("SELECT status FROM judge_attempt WHERE token=?").param(first.token()).query(String.class).single()).isEqualTo("SUPERSEDED");
        assertThat(submissions.detail(alice,saved.id()).verdict()).isEqualTo("AC");
    }

    @Test
    void invalidSavedPlanOrEmptyAcEvidenceCannotFinishJob() {
        var saved = submit(alice,UUID.randomUUID());
        var assignment = queue.claim(UUID.randomUUID()).orElseThrow();
        ObjectNode wrong = report(assignment); wrong.put("source_sha256", "wrong");
        assertThatThrownBy(() -> queue.complete(saved.id(),assignment.token(),wrong)).isInstanceOf(AccountException.class);
        ObjectNode empty = report(assignment); empty.putArray("tests");
        assertThatThrownBy(() -> queue.complete(saved.id(),assignment.token(),empty)).isInstanceOf(AccountException.class);
        assertThat(submissions.detail(alice,saved.id()).status()).isEqualTo("RUNNING");
    }

    @Test
    void repeatedLostWorkersFinishAsInfrastructureErrorWithinRetryCap() {
        var saved = submit(alice,UUID.randomUUID());
        for (int i=1;i<=3;i++) {
            assertThat(queue.claim(UUID.randomUUID()).orElseThrow().attempt()).isEqualTo(i);
            expire(saved.id());
        }
        assertThat(queue.claim(UUID.randomUUID())).isEmpty();
        assertThat(submissions.detail(alice,saved.id()).verdict()).isEqualTo("IE");
        assertThat(jdbc.sql("SELECT count(*) FROM judge_attempt").query(Integer.class).single()).isEqualTo(3);
    }

    @Test
    void workerEndpointRequiresItsOwnCredentialAndDoesNotAcceptUserSession() throws Exception {
        String body = "{\"workerId\":\""+UUID.randomUUID()+"\"}";
        mvc.perform(post("/internal/judge/claim").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/judge/claim").with(user(alice)).contentType("application/json").content(body)).andExpect(status().is4xxClientError());
        mvc.perform(post("/internal/judge/claim").header("Authorization","Bearer worker-test-credential-32-characters")
                .contentType("application/json").content(body)).andExpect(status().isNoContent());
        mvc.perform(get("/api/submissions").header("Authorization","Bearer worker-test-credential-32-characters"))
                .andExpect(status().isUnauthorized());
    }
}
