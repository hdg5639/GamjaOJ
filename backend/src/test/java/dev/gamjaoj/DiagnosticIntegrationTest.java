package dev.gamjaoj;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
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

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:diagnostics;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa","spring.datasource.password=","gamjaoj.invite-code=test-only",
        "gamjaoj.submissions-enabled=true","AI_POLL_MS=3600000","gamjaoj.worker-token=worker-test-credential-32-characters"})
@AutoConfigureMockMvc
class DiagnosticIntegrationTest {
    // Optional isolated PostgreSQL run exercises production row locks and migration syntax.
    @org.springframework.test.context.DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry registry) {
        String url=System.getenv("GAMJA_DIAGNOSTIC_TEST_DB");
        if(url!=null) {
            registry.add("spring.datasource.url",()->url);
            registry.add("spring.datasource.username",()->"diagnostic_test");
            registry.add("spring.datasource.password",()->"diagnostic_test");
        }
    }
    @Autowired JdbcClient jdbc;
    @Autowired Diagnostics diagnostics;
    @Autowired Submissions submissions;
    @Autowired TrainingSessions training;
    @Autowired JudgeQueue queue;
    @Autowired AiTasks ai;
    @Autowired DiagnosticEvaluations evaluations;
    @Autowired DiagnosticPlans plans;
    @Autowired org.springframework.core.env.ConfigurableEnvironment environment;
    @org.springframework.test.context.bean.override.mockito.MockitoBean AiProvider provider;
    @Autowired MockMvc mvc;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;
    String user,other,bank;
    static final String SOURCE="public class Main { public static void main(String[] args) { System.out.println(3); } }";
    @BeforeEach void fixture() {
        org.springframework.boot.test.util.TestPropertyValues.of("AI_API_ENABLED=false","OPENAI_API_KEY=","AI_MONTHLY_BUDGET_USD=10").applyTo(environment);
        jdbc.sql("DELETE FROM diagnostic_practice_plan").update();
        jdbc.sql("DELETE FROM generation_spec_draft").update();
        jdbc.sql("DELETE FROM ai_attempt").update();
        jdbc.sql("DELETE FROM app_user").update();
        user="a"+UUID.randomUUID().toString().substring(0,8);other="b"+UUID.randomUUID().toString().substring(0,8);
        for(String name:List.of(user,other))jdbc.sql("INSERT INTO app_user(id,username,password_hash,nickname) VALUES (?,?,?,?)")
                .param(UUID.randomUUID()).param(name).param("unused").param(name).update();
        bank="fixture-"+UUID.randomUUID();
        jdbc.sql("INSERT INTO diagnostic_bank(id,reviewed) VALUES (?,true)").param(bank).update();
        for(int n=0;n<2;n++) {
            String version=bank+"-"+n;
            var p=(ObjectNode)JudgeJson.parse(jdbc.sql("SELECT package_json FROM problem_version WHERE id='sum-v1'").query(String.class).single());p.put("version",version);
            String json=JudgeJson.canonical(p);
            jdbc.sql("INSERT INTO problem_version(id,package_json,package_sha256,runtime_image,runner_policy,ready,diagnostic_only) SELECT ?,?,?,runtime_image,runner_policy,true,true FROM problem_version WHERE id='sum-v1'")
                    .param(version).param(json).param(JudgeJson.hash(json)).update();
            jdbc.sql("INSERT INTO diagnostic_bank_item(bank_id,position,category,difficulty,problem_version,rubric_json) VALUES (?,?,'fixture',?,?,?)")
                    .param(bank).param(n).param(n==0?"EASY":"MEDIUM").param(version).param("{\"privateRubric\":true}").update();
        }
    }
    static com.fasterxml.jackson.databind.node.ObjectNode habit(com.fasterxml.jackson.databind.node.ObjectNode observation) {
        observation.put("pattern","입력을 읽지 않고 고정 값을 출력합니다.").put("risk","예제 외 입력에서는 항상 틀립니다.").put("tone","RISK").putArray("alsoSeenIn");
        return observation;
    }
    Diagnostics.View start() { return diagnostics.start(user,UUID.randomUUID(),bank); }
    @Test void diagnosticFreezesLimitsAtStartIncludingPublicDisplayAndLaterSubmission() {
        jdbc.sql("UPDATE problem_version SET time_limits_json=? WHERE id=?").param(ProblemTimeLimitsTest.limits(2,1,4)).param(bank+"-0").update();
        var diagnostic=start();var q=diagnostic.current();
        assertThat(q.languages()).extracting(LanguageProfiles.Option::timeLimitMs).containsExactly(2000,1000,4000);
        jdbc.sql("UPDATE problem_version SET time_limits_json=? WHERE id=?").param(ProblemTimeLimitsTest.limits(5,3,8)).param(q.problemVersion()).update();
        var saved=submissions.submit(user,UUID.randomUUID(),new SubmissionController.Request(q.problemVersion(),SOURCE,null,q.itemId(),"PYTHON"));
        assertThat(saved.execution().timeLimitMs()).isEqualTo(4000);
        var task=queue.claim(UUID.randomUUID()).orElseThrow();
        assertThat(task.executionProfile().path("testWallSeconds").asInt()).isEqualTo(4);
    }
    @Test void alignmentMigrationUpdatesOpenSessionsButPreservesAdmittedExecution() throws Exception {
        var diagnostic=start();var q=diagnostic.current();
        var admitted=submit(q);
        assertThat(admitted.execution().timeLimitMs()).isEqualTo(5000);
        String limits=ProblemTimeLimitsTest.limits(2,1,4);
        jdbc.sql("UPDATE problem_version SET time_limits_json=? WHERE id=?").param(limits).param(q.problemVersion()).update();
        String migration=new org.springframework.core.io.ClassPathResource("db/migration/V59__align_open_diagnostic_time_limits.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        jdbc.sql(migration).update();
        assertThat(jdbc.sql("SELECT time_limits_json FROM diagnostic_item WHERE id=?").param(q.itemId()).query(String.class).single()).isEqualTo(limits);
        assertThat(submissions.detail(user,admitted.id()).execution().timeLimitMs()).isEqualTo(5000);
        finish("WA");
        var later=submit(q);
        assertThat(later.execution().timeLimitMs()).isEqualTo(2000);
        var task=queue.claim(UUID.randomUUID()).orElseThrow();
        assertThat(task.submissionId()).isEqualTo(later.id());
        assertThat(task.executionProfile().path("testWallSeconds").asInt()).isEqualTo(2);
        assertThat(jdbc.sql(migration).update()).isZero();
    }
    SubmissionController.Request request(Diagnostics.Question q) { return new SubmissionController.Request(q.problemVersion(),SOURCE,null,q.itemId()); }
    Submissions.View submit(Diagnostics.Question q) { return submissions.submit(user,UUID.randomUUID(),request(q)); }
    void finish(String verdict) {
        var a=queue.claim(UUID.randomUUID()).orElseThrow();
        var r=JudgeJson.JSON.createObjectNode().put("verdict",verdict).put("policy",a.runnerPolicy()).put("image",a.runtimeImage())
                .put("source_sha256",a.sourceSha256()).put("problem_sha256",a.problemSha256()).put("problem_version",a.problem().path("version").asText())
                .put("execution_mode",a.executionMode());
        if(a.executionProfile()!=null){r.put("language",a.language());r.set("execution_profile",a.executionProfile().deepCopy());}
        r.putObject("runner_environment").put("dockerControl","engine").set("contract",a.runnerEnvironment());
        r.putObject("compile").put("stderr",verdict.equals("CE")?"compile failure":"");
        var tests=r.putArray("tests");
        if(!List.of("CE","IE").contains(verdict))for(var test:a.problem().path("tests")) {
            tests.addObject().put("id",test.path("id").asText()).put("verdict",verdict).put("stdout","").put("stderr","").put("stdout_truncated",false);
            if(!List.of("AC","OK").contains(verdict))break;
        }
        queue.complete(a.submissionId(),a.token(),r);queue.complete(a.submissionId(),a.token(),r);
    }
    @Test void changingLanguageDoesNotResetAttemptsAndAcAdvances() {
        var d=start();var q=d.current();
        assertThat(q.languages()).extracting(LanguageProfiles.Option::id).containsExactly("JAVA","CPP","PYTHON");
        int index=0;
        for(String language:List.of("CPP","PYTHON","JAVA","CPP","PYTHON")) {
            var saved=submissions.submit(user,UUID.randomUUID(),new SubmissionController.Request(q.problemVersion(),SOURCE,null,q.itemId(),language));
            assertThat(saved.language()).isEqualTo(language);
            finish(++index==5?"AC":"WA");
            assertThat(diagnostics.detail(user,d.id()).items().get(0).attempts()).isEqualTo(index);
        }
        var next=diagnostics.detail(user,d.id());
        assertThat(next.items().get(0).status()).isEqualTo("PASSED");
        assertThat(next.current().itemId()).isNotEqualTo(q.itemId());
        assertThatThrownBy(()->submissions.submit(user,UUID.randomUUID(),new SubmissionController.Request(q.problemVersion(),SOURCE,null,q.itemId(),"CPP")))
                .isInstanceOf(AccountException.class);
    }

    @Test void fiveResultsAdvanceWithIeRunAndReplayExcluded() {
        assertThat(diagnostics.banks()).anyMatch(b->b.id().equals(bank)&&b.questionCount()==2&&b.categories().equals(List.of("fixture")));
        var d=start();var q=d.current();UUID key=UUID.randomUUID();
        var first=submissions.submit(user,key,request(q));
        assertThatThrownBy(()->submit(q)).isInstanceOf(AccountException.class);
        finish("IE");assertThat(diagnostics.detail(user,d.id()).items().get(0).attempts()).isZero();
        for(int n=0;n<6;n++) {
            submissions.run(user,UUID.randomUUID(),new RunController.Request(q.problemVersion(),SOURCE,"1 2\n",null,q.itemId()));finish("OK");
        }
        for(String verdict:List.of("CE","WA","RE","TLE","WA")) { submit(q);finish(verdict); }
        var next=diagnostics.detail(user,d.id());
        assertThat(next.items().get(0).attempts()).isEqualTo(5);
        assertThat(next.items().get(0).status()).isEqualTo("EXHAUSTED");
        assertThat(next.current().itemId()).isNotEqualTo(q.itemId());
        assertThatThrownBy(()->submit(q)).isInstanceOf(AccountException.class);
        assertThat(submissions.submit(user,key,request(q)).id()).isEqualTo(first.id());
        assertThatThrownBy(()->submissions.submit(user,key,new SubmissionController.Request(q.problemVersion(),SOURCE))).isInstanceOf(AccountException.class);
        submit(next.current());finish("AC");
        assertThat(diagnostics.detail(user,d.id()).status()).isEqualTo("COMPLETED");
        assertThat(submissions.detail(user,first.id()).source()).isEqualTo(SOURCE);
    }
    @Test void finishingPausedSessionSkipsRemainingItemsAndAllowsNewSession() {
        var d=start();var q=d.current();submit(q);
        assertThatThrownBy(()->diagnostics.finish(user,d.id())).isInstanceOf(AccountException.class); // pending judge
        finish("WA");
        diagnostics.state(user,d.id(),"PAUSED");
        assertThatThrownBy(()->diagnostics.start(user,UUID.randomUUID(),bank)).isInstanceOf(AccountException.class);
        assertThatThrownBy(()->diagnostics.finish(other,d.id())).isInstanceOf(AccountException.class);
        var done=diagnostics.finish(user,d.id());
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(done.items()).extracting(Diagnostics.Item::status).containsExactly("SKIPPED","SKIPPED");
        assertThat(done.items().get(0).attempts()).isEqualTo(1); // recorded attempt is preserved
        assertThat(diagnostics.finish(user,d.id()).status()).isEqualTo("COMPLETED");
        assertThat(diagnostics.start(user,UUID.randomUUID(),bank).status()).isEqualTo("ACTIVE");
    }
    @Test void pauseResumeSkipAndOrdinaryPracticeRemainIndependent() {
        var d=start();var q=d.current();submit(q);
        diagnostics.state(user,d.id(),"PAUSED");finish("AC");
        var paused=diagnostics.detail(user,d.id());assertThat(paused.status()).isEqualTo("PAUSED");assertThat(paused.items().get(0).status()).isEqualTo("PASSED");
        assertThatThrownBy(()->submit(paused.current())).isInstanceOf(AccountException.class);
        assertThat(training.start(user,UUID.randomUUID(),new TrainingSessionController.Start("sum-v1","practice")).status()).isEqualTo("ACTIVE");
        submissions.submit(user,UUID.randomUUID(),new SubmissionController.Request("sum-v1",SOURCE));finish("AC");
        // New service instance has no in-memory session state to recover.
        assertThat(new Diagnostics(jdbc).detail(user,d.id()).items()).isEqualTo(paused.items());
        diagnostics.state(user,d.id(),"ACTIVE");
        var done=diagnostics.skip(user,d.id(),paused.current().itemId());assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(diagnostics.skip(user,d.id(),paused.current().itemId())).isEqualTo(done);
    }
    @Test void foreignStaleBypassAndHelpAreRejected() throws Exception {
        var d=start();var q=d.current();
        assertThatThrownBy(()->diagnostics.detail(other,d.id())).isInstanceOf(AccountException.class);
        assertThatThrownBy(()->submissions.submit(other,UUID.randomUUID(),request(q))).isInstanceOf(AccountException.class);
        assertThatThrownBy(()->submissions.submit(user,UUID.randomUUID(),new SubmissionController.Request(q.problemVersion(),SOURCE))).isInstanceOf(AccountException.class);
        assertThatThrownBy(()->training.start(user,UUID.randomUUID(),new TrainingSessionController.Start(q.problemVersion(),"bypass"))).isInstanceOf(AccountException.class);
        assertThat(submissions.problems(user)).noneMatch(p->p.version().startsWith("fixture-"));
        mvc.perform(get("/api/problems/"+q.problemVersion()+"/teaching").with(user(user))).andExpect(status().isNotFound());
        var s=submit(q);
        assertThatThrownBy(()->diagnostics.skip(user,d.id(),q.itemId())).isInstanceOf(AccountException.class);
        finish("WA");assertThatThrownBy(()->ai.request(user,s.id(),"HINT","help",false)).isInstanceOf(AccountException.class);
        String json=mvc.perform(get("/api/diagnostics/"+d.id()).with(user(user))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json).doesNotContain("privateRubric","package_sha256","runtime_image","tests");
        diagnostics.skip(user,d.id(),q.itemId());
        assertThat(diagnostics.skip(user,d.id(),q.itemId()).current().itemId()).isNotEqualTo(q.itemId());
        assertThatThrownBy(()->submissions.run(user,UUID.randomUUID(),new RunController.Request(q.problemVersion(),SOURCE,"",null,q.itemId()))).isInstanceOf(AccountException.class);
    }
    @Test void simultaneousAdmissionsHaveOnlyOnePendingFormalSubmission() throws Exception {
        var q=start().current();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var futures=pool.invokeAll(List.of(()->{try {submit(q);return true;}catch(AccountException e){return false;}},
                    ()->{try {submit(q);return true;}catch(AccountException e){return false;}}));
            int accepted=0;for(var f:futures)if(Boolean.TRUE.equals(f.get()))accepted++;
            assertThat(accepted).isEqualTo(1);
        }
    }
    @Test void bankEditsCannotChangePinnedRunnerPlanAndHeldProblemsStopAdmission() {
        UUID key=UUID.randomUUID();var d=diagnostics.start(user,key,bank);var q=d.current();
        assertThat(diagnostics.start(user,key,bank).items()).isEqualTo(d.items());
        String original=jdbc.sql("SELECT package_json FROM diagnostic_item WHERE id=?").param(q.itemId()).query(String.class).single();
        jdbc.sql("UPDATE problem_version SET package_json='{}',package_sha256=?,runtime_image='changed' WHERE id=?").param(JudgeJson.hash("{}")).param(q.problemVersion()).update();
        submit(q);var a=queue.claim(UUID.randomUUID()).orElseThrow();
        assertThat(a.problem()).isEqualTo(JudgeJson.parse(original));assertThat(a.runtimeImage()).isNotEqualTo("changed");
        jdbc.sql("UPDATE problem_version SET review_hold=true WHERE id=?").param(d.items().get(1).problemVersion()).update();
        // Pending work cannot be skipped even if current catalogue data changes.
        assertThatThrownBy(()->diagnostics.skip(user,d.id(),q.itemId())).isInstanceOf(AccountException.class);
        jdbc.sql("UPDATE judge_job SET status='FINISHED',verdict='AC',finished_at=CURRENT_TIMESTAMP WHERE submission_id=?").param(a.submissionId()).update();
        var next=diagnostics.detail(user,d.id()).current();
        assertThatThrownBy(()->submit(next)).isInstanceOf(AccountException.class);
    }
    @Test void fifthAcceptedResultWinsAndConcurrentSameKeyCountsOnce() throws Exception {
        var d=start();var q=d.current();UUID key=UUID.randomUUID();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var calls=pool.invokeAll(List.of(()->submissions.submit(user,key,request(q)),()->submissions.submit(user,key,request(q))));
            assertThat(((Submissions.View)calls.get(0).get()).id()).isEqualTo(((Submissions.View)calls.get(1).get()).id());
        }
        finish("WA");
        for(int n=0;n<3;n++){submit(q);finish("WA");}
        submit(q);finish("AC");
        var saved=diagnostics.detail(user,d.id());
        assertThat(saved.items().get(0).attempts()).isEqualTo(5);assertThat(saved.items().get(0).status()).isEqualTo("PASSED");
    }
    @Test void acceptedCompletionCannotBeOverwrittenByConcurrentSkip() throws Exception {
        var d=start();var q=d.current();submit(q);
        var saved=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var completion=pool.submit(()->transactions.executeWithoutResult(tx->{
                finish("AC");saved.countDown();
                try { if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("release timeout"); }
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
            }));
            assertThat(saved.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var skip=pool.submit(()->{try {diagnostics.skip(user,d.id(),q.itemId());return true;}catch(AccountException e){return false;}});
            release.countDown();completion.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(skip.get(10,java.util.concurrent.TimeUnit.SECONDS)).isFalse();
        } finally {release.countDown();}
        assertThat(diagnostics.detail(user,d.id()).items().get(0).status()).isEqualTo("PASSED");
    }
    @Test void selectedScopePinsWholePairsAndRejectsChangedReplay() {
        // An additional pair exists but is held. It must not block an unrelated selected scope.
        for(int n=0;n<2;n++) {
            String version=bank+"-extra-"+n;
            jdbc.sql("INSERT INTO problem_version(id,package_json,package_sha256,runtime_image,runner_policy,ready,diagnostic_only,review_hold) SELECT ?,package_json,package_sha256,runtime_image,runner_policy,true,true,true FROM problem_version WHERE id='sum-v1'").param(version).update();
            jdbc.sql("INSERT INTO diagnostic_bank_item(bank_id,position,category,difficulty,problem_version,rubric_json) VALUES (?,?,'extra',?,?,'{}')")
                    .param(bank).param(n+2).param(n==0?"EASY":"MEDIUM").param(version).update();
        }
        UUID key=UUID.randomUUID();
        assertThat(diagnostics.banks()).noneMatch(b->b.id().equals(bank));
        var selected=diagnostics.start(user,key,bank,List.of("fixture"));
        assertThat(selected.items()).hasSize(2).allMatch(item->item.category().equals("fixture"));
        assertThat(diagnostics.start(user,key,bank,List.of("fixture")).items()).isEqualTo(selected.items());
        assertThatThrownBy(()->diagnostics.start(user,key,bank,List.of("extra"))).isInstanceOf(AccountException.class);
        assertThatThrownBy(()->diagnostics.start(user,key,bank)).isInstanceOf(AccountException.class);
        assertThatThrownBy(()->diagnostics.start(other,UUID.randomUUID(),bank,List.of("missing"))).isInstanceOf(AccountException.class);
        assertThatThrownBy(()->diagnostics.start(other,UUID.randomUUID(),bank,List.of())).isInstanceOf(AccountException.class);
        assertThatThrownBy(()->diagnostics.start(other,UUID.randomUUID(),bank,List.of("fixture","fixture"))).isInstanceOf(AccountException.class);
        assertThat(diagnostics.history(other)).isEmpty();
    }
    @Test void evaluationSnapshotsArePrivateIdempotentAndOnlyClosedEvidenceIsSent() throws Exception {
        var d=start();
        assertThatThrownBy(()->evaluations.request(user,d.id())).isInstanceOf(AccountException.class);
        submit(d.current());
        assertThatThrownBy(()->evaluations.request(user,d.id())).isInstanceOf(AccountException.class);
        finish("AC");
        var partial=evaluations.request(user,d.id());
        assertThat(partial.status()).isEqualTo("FACTS_ONLY");assertThat(partial.interpretation()).isNull();
        assertThat(evaluations.request(user,d.id()).id()).isEqualTo(partial.id());
        assertThat(jdbc.sql("SELECT count(*) FROM ai_task").query(Integer.class).single()).isZero();
        String evidence=jdbc.sql("SELECT evidence_json FROM diagnostic_evaluation WHERE id=?").param(partial.id()).query(String.class).single();
        assertThat(JudgeJson.parse(evidence).path("items")).hasSize(1);
        var next=diagnostics.detail(user,d.id()).current();submit(next);finish("WA");diagnostics.skip(user,d.id(),next.itemId());
        var full=evaluations.request(user,d.id());
        assertThat(full.id()).isNotEqualTo(partial.id());assertThat(full.status()).isEqualTo("HELD_DISABLED");
        assertThat(evaluations.request(user,d.id()).id()).isEqualTo(full.id());
        assertThat(jdbc.sql("SELECT count(*) FROM ai_task").query(Integer.class).single()).isEqualTo(1);
        assertThatThrownBy(()->evaluations.list(other,d.id())).isInstanceOf(AccountException.class);
        String body=mvc.perform(get("/api/diagnostics/"+d.id()+"/evaluations").with(user(user))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("privateRubric","sourceHash","runtimeImage",SOURCE);
    }
    @Test void completedEvaluationValidatesCitationsAndHidesResultsDuringAnotherAssessment() {
        var d=start();var submission=submit(d.current());finish("AC");
        diagnostics.skip(user,d.id(),diagnostics.detail(user,d.id()).current().itemId());
        org.springframework.boot.test.util.TestPropertyValues.of("AI_API_ENABLED=true","OPENAI_API_KEY=test-only").applyTo(environment);
        var evaluation=evaluations.request(user,d.id());var work=ai.claim();assertThat(work).isNotNull();
        var output=JudgeJson.JSON.createObjectNode().put("summary","관측한 문항의 기본 동작을 확인했습니다.").put("uncertainty","다른 분야는 미평가입니다.").put("requiredScope","OBSERVED_ITEMS_ONLY");
        var observation=habit(output.putArray("observations").addObject().put("submissionId",submission.id().toString()).put("quote","System.out.println(3)")
                .put("interpretation","고정된 값을 출력합니다. 일반 입력 처리는 추가 확인이 필요합니다.").put("confidence","SUPPORTED").put("nextAction","ASSESS").put("recommendation","입력을 읽는 별도 문항으로 확인하세요."));
        assertThat(DiagnosticEvaluationContract.valid(output,JudgeJson.parse(work.input()))).isTrue();
        observation.put("quote","not present");assertThat(DiagnosticEvaluationContract.valid(output,JudgeJson.parse(work.input()))).isFalse();observation.put("quote","System.out.println(3)");
        var usage=JudgeJson.parse("{\"input_tokens\":100,\"output_tokens\":100}");
        ai.finish(work,new dev.gamjaoj.ai.OpenAiResponses.Result(output,usage,"fake-response","fake-request","fake-model"),null);
        assertThat(evaluations.list(user,d.id()).get(0).interpretation()).isEqualTo(output);
        assertThat(ai.detail(user,work.taskId()).result()).isNull(); // Generic AI endpoint cannot bypass diagnostic disclosure.
        assertThat(evaluations.request(user,d.id()).id()).isEqualTo(evaluation.id());
        UUID correctionKey=UUID.randomUUID();
        var corrected=evaluations.correct(user,d.id(),evaluation.id(),correctionKey,0,"입력 형식을 잘못 이해했습니다.");
        assertThat(corrected.corrections()).hasSize(1);assertThat(corrected.interpretation()).isEqualTo(output);
        assertThat(evaluations.correct(user,d.id(),evaluation.id(),correctionKey,0,"입력 형식을 잘못 이해했습니다.").corrections()).hasSize(1);
        assertThatThrownBy(()->evaluations.correct(user,d.id(),evaluation.id(),correctionKey,0,"다른 설명")).isInstanceOf(AccountException.class);
        assertThatThrownBy(()->evaluations.correct(other,d.id(),evaluation.id(),UUID.randomUUID(),0,"설명")).isInstanceOf(AccountException.class);
        assertThatThrownBy(()->evaluations.correct(user,d.id(),evaluation.id(),UUID.randomUUID(),99,"설명")).isInstanceOf(AccountException.class);
        assertThatThrownBy(()->evaluations.correct(user,d.id(),evaluation.id(),UUID.randomUUID(),0," ")).isInstanceOf(AccountException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM ai_task").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM ai_attempt").query(Integer.class).single()).isEqualTo(1);
        var otherSession=diagnostics.start(user,UUID.randomUUID(),bank);
        assertThat(evaluations.list(user,d.id()).get(0).status()).isEqualTo("HIDDEN_DURING_ASSESSMENT");
        assertThat(evaluations.list(user,d.id()).get(0).corrections()).isEmpty();
        assertThatThrownBy(()->evaluations.correct(user,d.id(),evaluation.id(),UUID.randomUUID(),0,"설명")).isInstanceOf(AccountException.class);
        assertThat(evaluations.list(user,d.id()).get(0).interpretation()).isNull();
        diagnostics.skip(user,otherSession.id(),otherSession.current().itemId());
        diagnostics.skip(user,otherSession.id(),diagnostics.detail(user,otherSession.id()).current().itemId());
        jdbc.sql("UPDATE problem_version SET review_hold=true WHERE id=?").param(d.current().problemVersion()).update();
        assertThat(evaluations.list(user,d.id()).get(0).status()).isEqualTo("HELD_REVIEW");
        assertThat(evaluations.list(user,d.id()).get(0).interpretation()).isNull();
    }
    @Test void oversizedEvidenceKeepsFirstAndLastTwoSourcesAndRejectsOmittedCitations() {
        var d=start();var ids=new java.util.ArrayList<List<UUID>>();
        for(int item=0;item<2;item++) {
            var q=diagnostics.detail(user,d.id()).current();var attempts=new java.util.ArrayList<UUID>();
            for(int n=0;n<5;n++) {
                String large="public class Main { public static void main(String[] args) { System.out.println(3); } } // attempt "+item+"-"+n+" "+"x".repeat(60000);
                attempts.add(submissions.submit(user,UUID.randomUUID(),new SubmissionController.Request(q.problemVersion(),large,null,q.itemId())).id());finish("WA");
            }
            ids.add(attempts);
        }
        assertThat(diagnostics.detail(user,d.id()).status()).isEqualTo("COMPLETED");
        var evaluation=evaluations.request(user,d.id());
        String json=jdbc.sql("SELECT evidence_json FROM diagnostic_evaluation WHERE id=?").param(evaluation.id()).query(String.class).single();
        assertThat(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(DiagnosticEvaluations.EVIDENCE_LIMIT);
        var evidence=JudgeJson.parse(json);
        assertThat(evidence.path("sourceCompaction").asText()).isEqualTo("FIRST_AND_LAST_TWO_PER_ITEM");
        for(int item=0;item<2;item++) {
            var attempts=evidence.path("items").get(item).path("submissions");assertThat(attempts).hasSize(5);
            for(int n=0;n<5;n++) {
                var attempt=attempts.get(n);assertThat(attempt.path("submissionId").asText()).isEqualTo(ids.get(item).get(n).toString());
                boolean kept=n==0||n>=3;
                assertThat(attempt.has("source")).isEqualTo(kept);assertThat(attempt.path("sourceOmitted").asBoolean()).isEqualTo(!kept);
                assertThat(attempt.path("verdict").asText()).isEqualTo("WA");assertThat(attempt.path("sourceHash").asText()).hasSize(64);
            }
        }
        var allowed=new java.util.ArrayList<String>();
        DiagnosticEvaluationContract.schema(evidence).path("properties").path("observations").path("items").path("properties").path("submissionId").path("enum").forEach(v->allowed.add(v.asText()));
        var expected=new java.util.ArrayList<String>();
        for(var attempts:ids)for(int n:List.of(0,3,4))expected.add(attempts.get(n).toString());
        assertThat(allowed).containsExactlyElementsOf(expected); // item IDs and omitted sources are not citable
        var output=JudgeJson.JSON.createObjectNode().put("summary","요약").put("uncertainty","일부 제출 코드는 축약되었습니다.").put("requiredScope","OBSERVED_ITEMS_ONLY");
        var observation=habit(output.putArray("observations").addObject().put("submissionId",ids.get(0).get(3).toString()).put("quote","System.out.println(3)")
                .put("interpretation","고정 값을 출력합니다.").put("confidence","SUPPORTED").put("nextAction","ASSESS").put("recommendation","입력 처리를 확인하세요."));
        assertThat(DiagnosticEvaluationContract.valid(output,evidence)).isTrue();
        observation.put("submissionId",ids.get(0).get(1).toString());
        assertThat(DiagnosticEvaluationContract.valid(output,evidence)).isFalse();
        assertThat(DiagnosticEvaluationContract.violation(output,evidence)).isEqualTo("OBSERVATION_0_UNKNOWN_SUBMISSION");
        observation.put("submissionId",ids.get(0).get(0).toString()).put("quote","System.out.println(4)");
        assertThat(DiagnosticEvaluationContract.violation(output,evidence)).isEqualTo("OBSERVATION_0_QUOTE_NOT_IN_SOURCE");
    }
    @Autowired DiagnosticProfiles profiles;
    @Test void profileGroupsObservationsByCitedCategoryMarksCrossCategoryRepeatsAndUnselectedFields() {
        String mixed="mixed-"+UUID.randomUUID();
        jdbc.sql("INSERT INTO diagnostic_bank(id,reviewed) VALUES (?,true)").param(mixed).update();
        var categories=List.of("bfs","bfs","dp","dp","greedy","greedy");
        for(int n=0;n<categories.size();n++) {
            String version=mixed+"-"+n;
            var p=(ObjectNode)JudgeJson.parse(jdbc.sql("SELECT package_json FROM problem_version WHERE id='sum-v1'").query(String.class).single());p.put("version",version);
            String json=JudgeJson.canonical(p);
            jdbc.sql("INSERT INTO problem_version(id,package_json,package_sha256,runtime_image,runner_policy,ready,diagnostic_only) SELECT ?,?,?,runtime_image,runner_policy,true,true FROM problem_version WHERE id='sum-v1'")
                    .param(version).param(json).param(JudgeJson.hash(json)).update();
            jdbc.sql("INSERT INTO diagnostic_bank_item(bank_id,position,category,difficulty,problem_version,rubric_json) VALUES (?,?,?,?,?,?)")
                    .param(mixed).param(n).param(categories.get(n)).param(n%2==0?"EASY":"MEDIUM").param(version).param("{\"privateRubric\":true}").update();
        }
        var d=diagnostics.start(user,UUID.randomUUID(),mixed,List.of("bfs","dp"));
        var byCategory=new java.util.HashMap<String,UUID>();
        for(int n=0;n<4;n++) {
            var q=diagnostics.detail(user,d.id()).current();var saved=submit(q);finish("AC");
            byCategory.putIfAbsent(jdbc.sql("SELECT category FROM diagnostic_item WHERE id=?").param(q.itemId()).query(String.class).single(),saved.id());
        }
        org.springframework.boot.test.util.TestPropertyValues.of("AI_API_ENABLED=true","OPENAI_API_KEY=test-only").applyTo(environment);
        var evaluation=evaluations.request(user,d.id());var work=ai.claim();var input=JudgeJson.parse(work.input());
        var output=JudgeJson.JSON.createObjectNode().put("summary","요약").put("uncertainty","범위 제한").put("requiredScope","OBSERVED_ITEMS_ONLY");
        var observation=habit(output.putArray("observations").addObject().put("submissionId",byCategory.get("bfs").toString()).put("quote","System.out.println(3)")
                .put("interpretation","고정 출력").put("confidence","SUPPORTED").put("nextAction","PRACTICE").put("recommendation","입력 처리 연습"));
        observation.withArray("alsoSeenIn").add(byCategory.get("dp").toString());
        assertThat(DiagnosticEvaluationContract.violation(output,input)).isNull();
        observation.put("tone","BAD");assertThat(DiagnosticEvaluationContract.violation(output,input)).isEqualTo("OBSERVATION_0_TONE");observation.put("tone","RISK");
        observation.withArray("alsoSeenIn").add(byCategory.get("bfs").toString());
        assertThat(DiagnosticEvaluationContract.violation(output,input)).isEqualTo("OBSERVATION_0_ALSO_SEEN_UNKNOWN"); // self citation
        observation.withArray("alsoSeenIn").remove(1);observation.remove("pattern");
        assertThat(DiagnosticEvaluationContract.violation(output,input)).isEqualTo("OBSERVATION_0_SHAPE");
        observation.put("pattern","입력을 읽지 않고 고정 값을 출력합니다.");
        var enumIds=new java.util.ArrayList<String>();
        DiagnosticEvaluationContract.schema(input).path("properties").path("observations").path("items").path("properties").path("alsoSeenIn").path("items").path("enum").forEach(v->enumIds.add(v.asText()));
        assertThat(enumIds).hasSize(4).contains(byCategory.get("dp").toString());
        ai.finish(work,new dev.gamjaoj.ai.OpenAiResponses.Result(output,JudgeJson.parse("{\"input_tokens\":100,\"output_tokens\":100}"),"fixture","fixture","fixture"),null);
        var profile=profiles.profile(user,d.id(),evaluation.id());
        assertThat(profile.categories()).extracting(DiagnosticProfiles.Category::id).containsExactly("bfs","dp","greedy");
        var bfs=profile.categories().get(0);var dp=profile.categories().get(1);var greedy=profile.categories().get(2);
        assertThat(bfs.selected()).isTrue();assertThat(bfs.items()).hasSize(2);
        assertThat(bfs.observations()).singleElement().satisfies(o->{assertThat(o.index()).isZero();assertThat(o.tone()).isEqualTo("RISK");assertThat(o.repeated()).isTrue();});
        assertThat(dp.observations()).isEmpty();assertThat(dp.alsoSeen()).containsExactly(0);
        assertThat(greedy.selected()).isFalse();assertThat(greedy.items()).isEmpty();
        assertThatThrownBy(()->profiles.profile(other,d.id(),evaluation.id())).isInstanceOf(AccountException.class);
        assertThat(plans.options(user,evaluation.id(),0).category()).isEqualTo("bfs");
    }
    @Test void ruleKeywordsMatchCatalogNamesOnlyForTheSameFamily() {
        var bfs=new HybridAdmission.Profile("r1","BFS · 무방향 그래프 최단 거리","",List.of(),false,"그래프 탐색",List.of("BFS","최단 거리"));
        var knapsack=new HybridAdmission.Profile("r2","0/1 배낭 · 물건 선택","",List.of(),false,"동적 계획법",List.of("0/1 배낭"));
        var dijkstra=new HybridAdmission.Profile("r3","다익스트라 · 가중치 최단 거리","",List.of(),false,"최단 경로",List.of("다익스트라"));
        var rules=List.of(bfs,knapsack,dijkstra);
        assertThat(DiagnosticProfiles.matchingRules("bfs",rules)).containsExactly("r1");
        assertThat(DiagnosticProfiles.matchingRules("dp",rules)).containsExactly("r2");
        assertThat(DiagnosticProfiles.matchingRules("graph",rules)).containsExactly("r1","r3"); // "그래프 탐색" names a graph family
        assertThat(DiagnosticProfiles.matchingRules("mst",rules)).isEmpty();
        assertThat(DiagnosticProfiles.matchingRules("unknown",rules)).isEmpty();
    }
    @Test void skippedOnlyEvaluationNeverQueuesModelAndUnknownUsageIsPreserved() {
        var d=start();diagnostics.skip(user,d.id(),d.current().itemId());diagnostics.skip(user,d.id(),diagnostics.detail(user,d.id()).current().itemId());
        assertThat(evaluations.request(user,d.id()).status()).isEqualTo("FACTS_ONLY");
        assertThat(jdbc.sql("SELECT count(*) FROM ai_task").query(Integer.class).single()).isZero();
        var next=diagnostics.start(user,UUID.randomUUID(),bank);submit(next.current());finish("WA");
        diagnostics.skip(user,next.id(),next.current().itemId());diagnostics.skip(user,next.id(),diagnostics.detail(user,next.id()).current().itemId());
        org.springframework.boot.test.util.TestPropertyValues.of("AI_API_ENABLED=true","OPENAI_API_KEY=test-only").applyTo(environment);
        evaluations.request(user,next.id());var work=ai.claim();
        ai.finish(work,null,new dev.gamjaoj.ai.OpenAiResponses.Failure("TRANSPORT_USAGE_UNKNOWN",null,null));
        assertThat(evaluations.list(user,next.id()).get(0).status()).isEqualTo("UNKNOWN");
        assertThat(jdbc.sql("SELECT count(*) FROM ai_attempt WHERE actual_usd IS NULL AND reserved_usd>0").query(Integer.class).single()).isEqualTo(1);
        assertThatThrownBy(()->ai.retry(user,work.taskId())).isInstanceOf(AccountException.class);
    }
    @Test void evaluationUsesBudgetReservationAndChargesInvalidProviderOutput() {
        var d=start();submit(d.current());finish("AC");diagnostics.skip(user,d.id(),diagnostics.detail(user,d.id()).current().itemId());
        org.springframework.boot.test.util.TestPropertyValues.of("AI_API_ENABLED=true","OPENAI_API_KEY=test-only","AI_MONTHLY_BUDGET_USD=0").applyTo(environment);
        evaluations.request(user,d.id());assertThat(ai.claim()).isNull();
        assertThat(evaluations.list(user,d.id()).get(0).status()).isEqualTo("HELD_BUDGET");
        assertThat(jdbc.sql("SELECT count(*) FROM ai_attempt").query(Integer.class).single()).isZero();
        org.springframework.boot.test.util.TestPropertyValues.of("AI_MONTHLY_BUDGET_USD=10").applyTo(environment);
        var work=ai.claim();assertThat(work).isNotNull();
        ai.finish(work,new dev.gamjaoj.ai.OpenAiResponses.Result(JudgeJson.parse("{}"),JudgeJson.parse("{\"input_tokens\":100,\"output_tokens\":100}"),"fake","fake","fake"),null);
        var failed=evaluations.list(user,d.id()).get(0);
        assertThat(failed.status()).isEqualTo("FAILED");assertThat(failed.errorCode()).isEqualTo("INVALID_DIAGNOSTIC_EVIDENCE");assertThat(failed.interpretation()).isNull();
        assertThat(jdbc.sql("SELECT count(*) FROM ai_attempt WHERE actual_usd>0").query(Integer.class).single()).isEqualTo(1);
    }
    @Test void diagnosticPlanConfirmsCorrectionsAndStartsExactlyOneOrdinaryTraining() {
        var d=start();var submitted=submit(d.current());finish("AC");diagnostics.skip(user,d.id(),diagnostics.detail(user,d.id()).current().itemId());
        org.springframework.boot.test.util.TestPropertyValues.of("AI_API_ENABLED=true","OPENAI_API_KEY=test-only").applyTo(environment);
        var evaluation=evaluations.request(user,d.id());var work=ai.claim();
        var output=JudgeJson.JSON.createObjectNode().put("summary","관측 범위 내 연습 제안").put("uncertainty","추가 근거 필요").put("requiredScope","OBSERVED_ITEMS_ONLY");
        var observations=output.putArray("observations");
        for(String action:List.of("PRACTICE","ASSESS"))habit(observations.addObject().put("submissionId",submitted.id().toString()).put("quote","System.out.println(3)")
                .put("interpretation","입력 처리를 확인할 필요가 있습니다.").put("confidence","UNCERTAIN").put("nextAction",action).put("recommendation","입력 처리 연습"));
        ai.finish(work,new dev.gamjaoj.ai.OpenAiResponses.Result(output,JudgeJson.parse("{\"input_tokens\":100,\"output_tokens\":100}"),"fixture","fixture","fixture"),null);
        var original=plans.options(user,evaluation.id(),0);
        assertThat(original.problems()).noneMatch(p->p.version().startsWith("fixture-"));
        UUID key=UUID.randomUUID();var plan=plans.confirm(user,key,evaluation.id(),0,original.reviewHash(),"입력 형식 읽기");
        assertThat(plans.confirm(user,key,evaluation.id(),0,original.reviewHash(),"입력 형식 읽기").id()).isEqualTo(plan.id());
        assertThatThrownBy(()->plans.confirm(user,key,evaluation.id(),0,original.reviewHash(),"변경된 목표")).isInstanceOf(AccountException.class);
        evaluations.correct(user,d.id(),evaluation.id(),UUID.randomUUID(),0,"알고리즘이 아니라 문제 설명을 잘못 읽었습니다.");
        assertThat(plans.list(user,evaluation.id()).get(0).status()).isEqualTo("NEEDS_REVIEW");
        assertThatThrownBy(()->plans.start(user,plan.id(),"sum-v1")).isInstanceOf(AccountException.class);
        assertThatThrownBy(()->plans.confirm(user,UUID.randomUUID(),evaluation.id(),0,original.reviewHash(),"목표")).isInstanceOf(AccountException.class);
        var revised=plans.options(user,evaluation.id(),0);assertThat(revised.corrections()).hasSize(1);
        var confirmed=plans.confirm(user,UUID.randomUUID(),evaluation.id(),0,revised.reviewHash(),"문제 설명에 맞춰 입력 읽기");
        assertThatThrownBy(()->plans.start(other,confirmed.id(),"sum-v1")).isInstanceOf(AccountException.class);
        assertThatThrownBy(()->plans.start(user,confirmed.id(),d.current().problemVersion())).isInstanceOf(AccountException.class);
        // Registered-rule generation is explicit and unavailable without hybrid admission for this learner.
        assertThat(revised.rules()).isEmpty();
        assertThatThrownBy(()->plans.generate(user,confirmed.id(),"bfs-shortest-path-v1")).isInstanceOf(AccountException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM hybrid_generation").query(Integer.class).single()).isZero();
        var generating=plans.generate(user,confirmed.id());
        assertThat(generating.generationId()).isEqualTo(confirmed.id());assertThat(generating.generationStatus()).isEqualTo("QUEUED");
        assertThat(generating.generatedVersion()).isNull();
        assertThat(plans.generate(user,confirmed.id()).generationId()).isEqualTo(generating.generationId());
        assertThat(jdbc.sql("SELECT count(*) FROM generation_spec_draft").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT request_text FROM generation_spec_draft WHERE id=?").param(confirmed.id()).query(String.class).single())
                .contains("문제 설명에 맞춰 입력 읽기").doesNotContain(SOURCE,"privateRubric");
        assertThatThrownBy(()->plans.generate(user,plan.id())).isInstanceOf(AccountException.class);
        jdbc.sql("UPDATE generation_spec_draft SET status='PUBLISHED' WHERE id=?").param(confirmed.id()).update();
        assertThat(plans.list(user,evaluation.id()).stream().filter(p->p.id().equals(confirmed.id())).findFirst().orElseThrow().generatedVersion()).isNull();
        String generatedVersion="experimental-check-"+confirmed.id();
        jdbc.sql("INSERT INTO problem_version(id,package_json,package_sha256,runtime_image,runner_policy,ready,owner_id) SELECT ?,package_json,package_sha256,runtime_image,runner_policy,false,? FROM problem_version WHERE id='sum-v1'")
                .param(generatedVersion).param(submissions.owner(user,false)).update();
        assertThat(plans.generate(user,confirmed.id()).generatedVersion()).isNull();
        jdbc.sql("UPDATE problem_version SET ready=true WHERE id=?").param(generatedVersion).update();
        assertThat(plans.generate(user,confirmed.id()).generatedVersion()).isEqualTo(generatedVersion);
        jdbc.sql("UPDATE problem_version SET review_hold=true WHERE id=?").param(generatedVersion).update();
        assertThat(plans.generate(user,confirmed.id()).generatedVersion()).isNull();
        jdbc.sql("DELETE FROM problem_version WHERE id=?").param(generatedVersion).update();
        var active=plans.start(user,confirmed.id(),"sum-v1");
        assertThatThrownBy(()->plans.nextRound(user,confirmed.id(),revised.reviewHash())).isInstanceOf(AccountException.class);
        assertThat(active.status()).isEqualTo("ACTIVE");assertThat(active.sessionId()).isEqualTo(confirmed.id());
        assertThat(plans.start(user,confirmed.id(),"sum-v1").sessionId()).isEqualTo(active.sessionId());
        assertThat(training.detail(user,active.sessionId()).session().goal()).isEqualTo("문제 설명에 맞춰 입력 읽기");
        assertThat(jdbc.sql("SELECT count(*) FROM training_session").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM ai_attempt").query(Integer.class).single()).isEqualTo(1);
        assertThatThrownBy(()->plans.reflect(user,confirmed.id(),false)).isInstanceOf(AccountException.class);
        submissions.submit(user,UUID.randomUUID(),new SubmissionController.Request("sum-v1",SOURCE,active.sessionId()));finish("AC");
        submissions.run(user,UUID.randomUUID(),new RunController.Request("sum-v1",SOURCE,"1 2",active.sessionId()));
        training.end(user,active.sessionId(),"입력을 확인했습니다.");
        assertThatThrownBy(()->plans.reflect(user,confirmed.id(),false)).isInstanceOf(AccountException.class);
        assertThatThrownBy(()->plans.nextRound(user,confirmed.id(),revised.reviewHash())).isInstanceOf(AccountException.class);
        finish("OK");
        var reflected=plans.reflect(user,confirmed.id(),false);assertThat(reflected.status()).isEqualTo("SELF_REPORTED_UNASSISTED_AC");
        assertThat(reflected.reviewedSubmissionId()).isNotNull();
        assertThat(plans.trainedScope(user,d.id())).containsExactly("fixture");
        assertThatThrownBy(()->plans.trainedScope(other,d.id())).isInstanceOf(AccountException.class);
        assertThat(plans.reflect(user,confirmed.id(),false).reviewedSubmissionId()).isEqualTo(reflected.reviewedSubmissionId());
        assertThatThrownBy(()->plans.reflect(user,confirmed.id(),true)).isInstanceOf(AccountException.class);
        assertThatThrownBy(()->plans.nextRound(other,confirmed.id(),revised.reviewHash())).isInstanceOf(AccountException.class);
        evaluations.correct(user,d.id(),evaluation.id(),UUID.randomUUID(),0,"다음 연습에서도 입력 설명을 먼저 확인하겠습니다.");
        assertThatThrownBy(()->plans.nextRound(user,confirmed.id(),revised.reviewHash())).isInstanceOf(AccountException.class);
        assertThat(plans.trainedScope(user,d.id())).isEmpty();
        var currentReview=plans.options(user,evaluation.id(),0);
        var second=plans.nextRound(user,confirmed.id(),currentReview.reviewHash());
        assertThat(second.previousPlanId()).isEqualTo(confirmed.id());assertThat(second.roundNumber()).isEqualTo(2);
        assertThat(second.goal()).isEqualTo(confirmed.goal());assertThat(second.status()).isEqualTo("READY");
        assertThat(second.sessionId()).isNull();assertThat(second.generationId()).isNull();assertThat(second.reviewedSubmissionId()).isNull();
        assertThat(plans.nextRound(user,confirmed.id(),currentReview.reviewHash()).id()).isEqualTo(second.id());
        assertThat(plans.reflect(user,confirmed.id(),false).reviewedSubmissionId()).isEqualTo(reflected.reviewedSubmissionId());
        var secondTraining=plans.start(user,second.id(),"sum-v1");
        submissions.submit(user,UUID.randomUUID(),new SubmissionController.Request("sum-v1",SOURCE,secondTraining.sessionId()));finish("WA");
        training.end(user,secondTraining.sessionId(),"아직 해결하지 못했습니다.");
        var third=plans.nextRound(user,second.id(),currentReview.reviewHash());
        assertThat(third.roundNumber()).isEqualTo(3);assertThat(third.previousPlanId()).isEqualTo(second.id());
        assertThat(plans.nextRound(user,confirmed.id(),currentReview.reviewHash()).id()).isEqualTo(second.id());
        assertThat(jdbc.sql("SELECT count(*) FROM training_session").query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM generation_spec_draft").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM ai_attempt").query(Integer.class).single()).isEqualTo(1);
        var originalOrder=plans.list(user,evaluation.id()).stream().map(DiagnosticPlans.Plan::id).toList();
        var reverse=new java.util.ArrayList<>(originalOrder);java.util.Collections.reverse(reverse);
        assertThat(plans.reorder(user,evaluation.id(),originalOrder,reverse).stream().map(DiagnosticPlans.Plan::id)).containsExactlyElementsOf(reverse);
        assertThat(plans.reorder(user,evaluation.id(),originalOrder,reverse).stream().map(DiagnosticPlans.Plan::id)).containsExactlyElementsOf(reverse);
        assertThatThrownBy(()->plans.reorder(user,evaluation.id(),originalOrder,originalOrder)).isInstanceOf(AccountException.class);
        assertThatThrownBy(()->plans.reorder(user,evaluation.id(),reverse,List.of(reverse.get(0)))).isInstanceOf(AccountException.class);
        assertThatThrownBy(()->plans.reorder(other,evaluation.id(),reverse,originalOrder)).isInstanceOf(AccountException.class);
        var assess=plans.options(user,evaluation.id(),1);assertThat(assess.problems()).isEmpty();
        assertThatThrownBy(()->plans.confirm(user,UUID.randomUUID(),evaluation.id(),1,assess.reviewHash(),"약점으로 단정한 목표")).isInstanceOf(AccountException.class);
        diagnostics.start(user,UUID.randomUUID(),bank);
        assertThatThrownBy(()->plans.nextRound(user,second.id(),currentReview.reviewHash())).isInstanceOf(AccountException.class);
        assertThat(plans.trainedScope(user,d.id())).isEmpty();
        assertThatThrownBy(()->plans.reorder(user,evaluation.id(),reverse,originalOrder)).isInstanceOf(AccountException.class);
        assertThat(plans.list(user,evaluation.id())).allMatch(p->p.status().equals("HELD")&&p.goal()==null);
    }
    String pairedBank(boolean alias) {
        String target="b-"+UUID.randomUUID();jdbc.sql("INSERT INTO diagnostic_bank(id,reviewed) VALUES (?,true)").param(target).update();
        for(int n=0;n<2;n++) {
            String source=bank+"-"+n,version=target+"-"+n;
            var p=(ObjectNode)JudgeJson.parse(jdbc.sql("SELECT package_json FROM problem_version WHERE id=?").param(source).query(String.class).single());
            p.put("version",version);if(!alias)p.put("statement","Unseen fixture "+n);
            String json=JudgeJson.canonical(p);
            jdbc.sql("INSERT INTO problem_version(id,package_json,package_sha256,runtime_image,runner_policy,ready,diagnostic_only) SELECT ?,?,?,runtime_image,runner_policy,true,true FROM problem_version WHERE id=?")
                    .param(version).param(json).param(JudgeJson.hash(json)).param(source).update();
            jdbc.sql("INSERT INTO diagnostic_bank_item(bank_id,position,category,difficulty,problem_version,rubric_json) SELECT ?,position,category,difficulty,?,rubric_json FROM diagnostic_bank_item WHERE problem_version=?")
                    .param(target).param(version).param(source).update();
            jdbc.sql("INSERT INTO diagnostic_reassessment_pair(source_version,target_version,source_sha256,target_sha256,reviewed) SELECT id,?,package_sha256,?,false FROM problem_version WHERE id=?")
                    .param(version).param(JudgeJson.hash(json)).param(source).update();
        }
        return target;
    }
    Diagnostics.View completeSkipped(Diagnostics.View d) {
        while(d.current()!=null)d=diagnostics.skip(user,d.id(),d.current().itemId());return d;
    }
    @Test void reassessmentPinsReviewedPairsAndRejectsPriorAssignmentAndChangedReplay() {
        var original=completeSkipped(start());String target=pairedBank(false);UUID key=UUID.randomUUID();
        assertThatThrownBy(()->diagnostics.reassess(user,key,original.id(),target,List.of("fixture"))).isInstanceOf(AccountException.class);
        jdbc.sql("UPDATE diagnostic_reassessment_pair SET reviewed=true WHERE target_version LIKE ?").param(target+"%").update();
        assertThatThrownBy(()->diagnostics.reassess(other,key,original.id(),target,List.of("fixture"))).isInstanceOf(AccountException.class);
        assertThatThrownBy(()->diagnostics.reassess(user,key,original.id(),target,List.of("unknown"))).isInstanceOf(AccountException.class);
        jdbc.sql("UPDATE problem_version SET review_hold=true WHERE id=?").param(bank+"-0").update();
        assertThatThrownBy(()->diagnostics.reassess(user,key,original.id(),target,List.of("fixture"))).isInstanceOf(AccountException.class);
        jdbc.sql("UPDATE problem_version SET review_hold=false WHERE id=?").param(bank+"-0").update();
        String originalHash=jdbc.sql("SELECT source_sha256 FROM diagnostic_reassessment_pair WHERE target_version=?").param(target+"-0").query(String.class).single();
        jdbc.sql("UPDATE diagnostic_reassessment_pair SET source_sha256=? WHERE target_version=?").param("0".repeat(64)).param(target+"-0").update();
        assertThatThrownBy(()->diagnostics.reassess(user,key,original.id(),target,List.of("fixture"))).isInstanceOf(AccountException.class);
        jdbc.sql("UPDATE diagnostic_reassessment_pair SET source_sha256=? WHERE target_version=?").param(originalHash).param(target+"-0").update();
        var second=diagnostics.reassess(user,key,original.id(),target,List.of("fixture"));
        assertThat(new Diagnostics(jdbc).detail(user,key).sourceSessionId()).isEqualTo(original.id());
        assertThat(second.sourceSessionId()).isEqualTo(original.id());assertThat(second.items()).hasSize(2);
        assertThat(diagnostics.reassess(user,key,original.id(),target,List.of("fixture")).id()).isEqualTo(key);
        assertThatThrownBy(()->diagnostics.start(user,key,target,List.of("fixture"))).isInstanceOf(AccountException.class);
        assertThat(jdbc.sql("SELECT correspondence_json FROM diagnostic_session WHERE id=?").param(key).query(String.class).single()).contains(bank+"-0",target+"-1");
        completeSkipped(second);
        var facts=evaluations.request(user,key).facts();
        assertThat(facts.path("sourceSessionId").asText()).isEqualTo(original.id().toString());
        assertThat(facts.path("exposureScope").asText()).isEqualTo("NO_PRIOR_DIAGNOSTIC_ASSIGNMENT");
        assertThatThrownBy(()->diagnostics.reassess(user,UUID.randomUUID(),original.id(),target,List.of("fixture"))).isInstanceOf(AccountException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM ai_task").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM diagnostic_exposure WHERE user_id=?").param(submissions.owner(user,false)).query(Integer.class).single()).isEqualTo(3);
    }
    @Test void reassessmentOptionsAndExternalExposureAreOwnerScopedAndDoNotDiagnoseKnownAnswers() {
        var original=completeSkipped(start());String target=pairedBank(false);
        assertThat(diagnostics.reassessmentOptions(user,original.id())).isEmpty();
        jdbc.sql("UPDATE diagnostic_reassessment_pair SET reviewed=true WHERE source_version LIKE ?").param(bank+"%").update();
        assertThat(diagnostics.reassessmentOptions(user,original.id())).anyMatch(b->b.id().equals(target)&&b.questionCount()==2);
        assertThat(diagnostics.banks()).noneMatch(b->b.id().equals(target));
        assertThatThrownBy(()->diagnostics.reassessmentOptions(other,original.id())).isInstanceOf(AccountException.class);
        var second=diagnostics.reassess(user,UUID.randomUUID(),original.id(),target,List.of("fixture"));
        assertThat(diagnostics.reassessmentOptions(user,original.id())).isEmpty();
        UUID item=second.current().itemId();
        assertThatThrownBy(()->diagnostics.reportExposure(other,second.id(),item)).isInstanceOf(AccountException.class);
        submit(second.current());
        assertThatThrownBy(()->diagnostics.reportExposure(user,second.id(),item)).isInstanceOf(AccountException.class);
        finish("WA");
        var reported=diagnostics.reportExposure(user,second.id(),item);
        assertThat(reported.items().get(0).externallySeen()).isTrue();assertThat(reported.items().get(0).attempts()).isEqualTo(1);
        assertThat(reported.items().get(0).status()).isEqualTo("SKIPPED");
        assertThat(diagnostics.reportExposure(user,second.id(),item).current().itemId()).isEqualTo(reported.current().itemId());
        assertThatThrownBy(()->submit(second.current())).isInstanceOf(AccountException.class);
        completeSkipped(reported);
        var evaluation=evaluations.request(user,second.id());assertThat(evaluation.status()).isEqualTo("FACTS_ONLY");
        assertThat(evaluation.facts().path("items").get(0).path("externallySeen").asBoolean()).isTrue();
        String evidence=jdbc.sql("SELECT evidence_json FROM diagnostic_evaluation WHERE id=?").param(evaluation.id()).query(String.class).single();
        assertThat(evidence).doesNotContain(SOURCE);assertThat(jdbc.sql("SELECT count(*) FROM ai_task").query(Integer.class).single()).isZero();
    }
    @Test void lateExposurePreservesVerdictAndOriginalEvidenceButFencesStaleEvaluationClaims() {
        var original=completeSkipped(start());String target=pairedBank(false);
        jdbc.sql("UPDATE diagnostic_reassessment_pair SET reviewed=true WHERE source_version LIKE ?").param(bank+"%").update();
        var second=diagnostics.reassess(user,UUID.randomUUID(),original.id(),target,List.of("fixture"));
        UUID item=second.current().itemId();submit(second.current());finish("AC");
        completeSkipped(diagnostics.detail(user,second.id()));
        var before=evaluations.request(user,second.id());
        String evidence=jdbc.sql("SELECT evidence_json FROM diagnostic_evaluation WHERE id=?").param(before.id()).query(String.class).single();
        assertThat(before.status()).isEqualTo("HELD_DISABLED");
        var corrected=diagnostics.reportExposure(user,second.id(),item);
        assertThat(corrected.status()).isEqualTo("COMPLETED");
        assertThat(corrected.items().get(0).status()).isEqualTo("PASSED");
        assertThat(corrected.items().get(0).externallySeen()).isTrue();
        diagnostics.reportExposure(user,second.id(),item);
        assertThat(jdbc.sql("SELECT exposure_revision FROM diagnostic_session WHERE id=?").param(second.id()).query(Integer.class).single()).isEqualTo(1);
        assertThat(evaluations.detail(user,before.id()).status()).isEqualTo("STALE_EXPOSURE");
        assertThat(evaluations.detail(user,before.id()).interpretation()).isNull();
        assertThatThrownBy(()->plans.options(user,before.id(),0)).isInstanceOf(AccountException.class);
        org.springframework.boot.test.util.TestPropertyValues.of("AI_API_ENABLED=true","OPENAI_API_KEY=test-only").applyTo(environment);
        assertThat(ai.claim()).isNull();
        var after=evaluations.request(user,second.id());assertThat(after.id()).isNotEqualTo(before.id());
        assertThat(after.status()).isEqualTo("FACTS_ONLY");
        assertThat(evaluations.request(user,second.id()).id()).isEqualTo(after.id());
        assertThat(jdbc.sql("SELECT evidence_json FROM diagnostic_evaluation WHERE id=?").param(before.id()).query(String.class).single()).isEqualTo(evidence);
        assertThat(jdbc.sql("SELECT count(*) FROM ai_attempt").query(Integer.class).single()).isZero();
    }
    @Test void reassessmentRejectsVersionOnlyAliasesAndLegacyAssignment() {
        var original=completeSkipped(start());String alias=pairedBank(true),target=pairedBank(false);
        jdbc.sql("UPDATE diagnostic_reassessment_pair SET reviewed=true WHERE source_version LIKE ?").param(bank+"%").update();
        assertThatThrownBy(()->diagnostics.reassess(user,UUID.randomUUID(),original.id(),alias,List.of("fixture"))).isInstanceOf(AccountException.class);
        var ordinary=diagnostics.start(user,UUID.randomUUID(),target);
        completeSkipped(ordinary); // Assigned, including skipped questions, is conservatively exposed.
        jdbc.sql("DELETE FROM diagnostic_exposure WHERE user_id=?").param(submissions.owner(user,false)).update();
        assertThatThrownBy(()->diagnostics.reassess(user,UUID.randomUUID(),original.id(),target,List.of("fixture"))).isInstanceOf(AccountException.class);
    }
    @Test void unreviewedOrIncompleteBanksCannotStart() {
        jdbc.sql("UPDATE diagnostic_bank SET reviewed=false WHERE id=?").param(bank).update();
        assertThat(diagnostics.banks()).noneMatch(b->b.id().equals(bank));
        assertThatThrownBy(this::start).isInstanceOf(AccountException.class);
        jdbc.sql("UPDATE diagnostic_bank SET reviewed=true WHERE id=?").param(bank).update();
        jdbc.sql("DELETE FROM diagnostic_bank_item WHERE bank_id=? AND difficulty='MEDIUM'").param(bank).update();
        assertThatThrownBy(this::start).isInstanceOf(AccountException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM diagnostic_session").query(Integer.class).single()).isZero();
    }
}
