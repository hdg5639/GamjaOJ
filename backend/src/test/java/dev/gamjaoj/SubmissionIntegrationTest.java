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
    @Test void callableCatalogAndSubmissionsPinTheDriverWithoutExposingTests() {
        String previous=jdbc.sql("SELECT package_json FROM problem_version WHERE id='sum-v1'").query(String.class).single();
        var plan=(com.fasterxml.jackson.databind.node.ObjectNode)JudgeJson.parse(previous);var bundle=CallablePrograms.bundle(CallableProgramsTest.multi());plan.set("api",bundle);
        try {
            jdbc.sql("UPDATE problem_version SET package_json=?,package_sha256=? WHERE id='sum-v1'").param(plan.toString()).param(JudgeJson.hash(plan.toString())).update();
            var catalog=submissions.problems(alice).stream().filter(p->p.version().equals("sum-v1")).findFirst().orElseThrow();
            assertThat(catalog.languages()).extracting(LanguageProfiles.Option::id).containsExactly("JAVA","CPP","PYTHON");assertThat(catalog.api()).isEqualTo(CallablePrograms.publicBundle(bundle));
            UUID key=UUID.randomUUID();var saved=submit(alice,key);
            var stored=JudgeJson.parse(jdbc.sql("SELECT callable_package FROM submission WHERE id=?").param(saved.id()).query(String.class).single());
            assertThat(stored.path("callable")).isEqualTo(bundle);assertThat(saved.input()).isNull();
            var task=queue.claim(UUID.randomUUID()).orElseThrow();assertThat(task.problem().path("callable")).isEqualTo(bundle);
            assertThat(task.problemSha256()).isEqualTo(JudgeJson.hash(JudgeJson.canonical(task.problem())));
            assertThat(submit(alice,key).id()).isEqualTo(saved.id());
            var run=submissions.run(alice,UUID.randomUUID(),new RunController.Request("sum-v1",SOURCE,"[[[\"init\",0],[\"query\"]]]",null,null,"JAVA"));
            var runPlan=JudgeJson.parse(jdbc.sql("SELECT run_package FROM submission WHERE id=?").param(run.id()).query(String.class).single());
            assertThat(runPlan.path("callable")).isEqualTo(bundle);assertThat(runPlan.path("output_policy").asText()).isEqualTo("RUN_ONLY");
            for(String language:List.of("CPP","PYTHON")) {
                var nativeSaved=submissions.submit(bob,UUID.randomUUID(),new SubmissionController.Request("sum-v1",SOURCE,null,null,language));
                var nativePlan=JudgeJson.parse(jdbc.sql("SELECT callable_package FROM submission WHERE id=?").param(nativeSaved.id()).query(String.class).single());
                assertThat(nativePlan.path("callable")).isEqualTo(NativeCallablePrograms.bundle(bundle.path("api"),language));
                assertThat(nativeSaved.language()).isEqualTo(language);
            }
        } finally {jdbc.sql("UPDATE problem_version SET package_json=?,package_sha256=? WHERE id='sum-v1'").param(previous).param(JudgeJson.hash(previous)).update();}
    }
    @Test void problemLimitsReachCatalogQueueAndRemainFrozenAcrossRetries() {
        String limits="{\"JAVA\":0.75,\"CPP\":0.35,\"PYTHON\":0.5,\"analysis\":\"Measured test fixture\",\"memory\":{\"JAVA\":192,\"CPP\":64,\"PYTHON\":64}}";
        try {
            jdbc.sql("UPDATE problem_version SET time_limits_json=? WHERE id='sum-v1'").param(limits).update();
            var catalog=submissions.problems(alice).stream().filter(p->p.version().equals("sum-v1")).findFirst().orElseThrow();
            assertThat(catalog.languages()).extracting(LanguageProfiles.Option::timeLimitMs).containsExactly(750,350,500);
            assertThat(catalog.languages()).extracting(LanguageProfiles.Option::memoryMb).containsExactly(192,64,64);
            UUID key=UUID.randomUUID();var saved=submit(alice,key);
            jdbc.sql("UPDATE problem_version SET time_limits_json=? WHERE id='sum-v1'").param(ProblemTimeLimitsTest.limits(4,2,6)).update();
            assertThat(submit(alice,key).execution().timeLimitMs()).isEqualTo(750);
            assertThat(submit(alice,key).execution().memoryMb()).isEqualTo(192);
            var task=queue.claim(UUID.randomUUID()).orElseThrow();
            assertThat(task.submissionId()).isEqualTo(saved.id());
            assertThat(task.executionProfile().path("testWallSeconds").asDouble()).isEqualTo(0.75);
            assertThat(task.executionProfile().path("memoryMb").asInt()).isEqualTo(192);
        } finally {jdbc.sql("UPDATE problem_version SET time_limits_json=NULL WHERE id='sum-v1'").update();}
    }
    @Autowired TransientRuns transientRuns;
    @Autowired Diagnostics diagnostics;
    @Autowired org.springframework.context.ApplicationEventPublisher events;
    @Test void configuredSlotsLetLearnerSubmissionsShareTheRunnerButExclusiveWorkStillDrains() {
        var parallel=new Submissions(jdbc,true,diagnostics,"FUNCTIONAL");var wide=new JudgeQueue(jdbc,events,3);
        var jobs=new java.util.ArrayList<Submissions.View>();
        for(String user:List.of(alice,alice,bob,bob))jobs.add(parallel.submit(user,UUID.randomUUID(),new SubmissionController.Request("sum-v1",SOURCE)));
        for(var job:jobs)assertThat(jdbc.sql("SELECT execution_mode FROM judge_job WHERE submission_id=?").param(job.id()).query(String.class).single()).isEqualTo("FUNCTIONAL");
        var claimed=new java.util.ArrayList<JudgeQueue.Assignment>();
        for(int i=0;i<3;i++)claimed.add(wide.claim(UUID.randomUUID()).orElseThrow());
        assertThat(claimed).extracting(JudgeQueue.Assignment::executionMode).containsOnly("FUNCTIONAL");
        assertThat(wide.claim(UUID.randomUUID())).isEmpty(); // fourth waits for a free slot
        // The default bean keeps the previous two-slot cap and exclusive learner work.
        assertThat(submit(alice,UUID.randomUUID())).isNotNull();
        assertThat(jdbc.sql("SELECT count(*) FROM judge_job WHERE execution_mode='EXCLUSIVE'").query(Integer.class).single()).isEqualTo(1);
        queue.complete(claimed.get(0).submissionId(),claimed.get(0).token(),report(claimed.get(0)));
        var fourth=wide.claim(UUID.randomUUID()).orElseThrow();assertThat(fourth.submissionId()).isEqualTo(jobs.get(3).id());
        for(var a:List.of(claimed.get(1),claimed.get(2),fourth))queue.complete(a.submissionId(),a.token(),report(a));
        assertThat(wide.claim(UUID.randomUUID()).orElseThrow().executionMode()).isEqualTo("EXCLUSIVE");
        assertThat(wide.claim(UUID.randomUUID())).isEmpty(); // exclusive runs alone
    }
    @Test void memoryEvidenceIsValidatedStoredAndExposedWithoutPrivateTestData()throws Exception {
        var saved=submit(alice,UUID.randomUUID());var task=queue.claim(UUID.randomUUID()).orElseThrow();var measured=report(task);
        for(var test:measured.path("tests"))((ObjectNode)test).put("memory_peak_bytes",33554432).put("memory_measurement","cgroup-peak-observed");
        ((ObjectNode)measured.path("tests").path(0)).put("memory_peak_bytes",-1);
        assertThatThrownBy(()->queue.complete(saved.id(),task.token(),measured)).isInstanceOf(AccountException.class);
        ((ObjectNode)measured.path("tests").path(0)).put("memory_peak_bytes",41943040);
        queue.complete(saved.id(),task.token(),measured);var detail=submissions.detail(alice,saved.id());
        assertThat(detail.memoryPeakBytes()).isEqualTo(41943040);assertThat(detail.tests().getFirst().memoryPeakBytes()).isEqualTo(41943040);
        mvc.perform(get("/api/submissions/"+saved.id()).with(user(alice))).andExpect(jsonPath("$.memoryPeakBytes").value(41943040)).andExpect(jsonPath("$.tests[0].memoryPeakBytes").value(41943040)).andExpect(jsonPath("$.tests[0].input").doesNotExist());
    }
    @Test void growthOnlyCountsFinishedOrdinaryExactHashReviewedSolutions() throws Exception {
        mvc.perform(get("/api/my/growth")).andExpect(status().isUnauthorized());
        var base=submit(alice,UUID.randomUUID());
        jdbc.sql("UPDATE judge_job SET status='FINISHED',verdict='AC',finished_at=CURRENT_TIMESTAMP WHERE submission_id=?").param(base.id()).update();
        for(int i=0;i<9;i++){
            String version="growth-"+UUID.randomUUID();var pack=(com.fasterxml.jackson.databind.node.ObjectNode)JudgeJson.parse(jdbc.sql("SELECT package_json FROM problem_version WHERE id='sum-v1'").query(String.class).single());pack.put("version",version);String raw=JudgeJson.canonical(pack),hash=JudgeJson.hash(raw);
            jdbc.sql("INSERT INTO problem_version(id,package_json,package_sha256,runtime_image,runner_policy,ready,shared,review_hold,diagnostic_only) SELECT ?,?,?,runtime_image,runner_policy,true,true,?,? FROM problem_version WHERE id='sum-v1'").param(version).param(raw).param(hash).param(i==7).param(i==8).update();
            jdbc.sql("INSERT INTO problem_thinking_profile(problem_version,package_sha256,layer,insight,implementation,edge_cases,rationale,source) VALUES (?,?,?,2,2,2,'검토 근거',?)").param(version).param(i==6?"0".repeat(64):hash).param(i<5?4:9).param(i==5?"AUTHOR_ESTIMATE":"CURATED_ESTIMATE").update();
            UUID id=UUID.randomUUID();jdbc.sql("INSERT INTO submission(id,user_id,problem_version,source_code,source_sha256,idempotency_key,runtime_image,runner_policy) SELECT ?,user_id,?,source_code,source_sha256,?,runtime_image,runner_policy FROM submission WHERE id=?").param(id).param(version).param(id).param(base.id()).update();
            jdbc.sql("INSERT INTO judge_job(submission_id,status,verdict,finished_at) VALUES (?,'FINISHED','AC',CURRENT_TIMESTAMP)").param(id).update();
        }
        mvc.perform(get("/api/my/growth").with(user(alice))).andExpect(status().isOk()).andExpect(jsonPath("$.layer").value(4)).andExpect(jsonPath("$.eligibleProblems").value(6)).andExpect(jsonPath("$.excludedProblems").value(2)).andExpect(jsonPath("$.nextLayer").value(5)).andExpect(jsonPath("$.nextSolved").value(0));
        mvc.perform(get("/api/my/growth").with(user(bob))).andExpect(jsonPath("$.layer").value(0)).andExpect(jsonPath("$.eligibleProblems").value(0));
    }
    @Test void personalHistoryFiltersBeforePaginationAndExcludesCustomRuns() throws Exception {
        mvc.perform(get("/api/my/summary")).andExpect(status().isUnauthorized());
        var base=submit(alice,UUID.randomUUID());
        jdbc.sql("UPDATE judge_job SET status='FINISHED',verdict='AC',finished_at=CURRENT_TIMESTAMP WHERE submission_id=?").param(base.id()).update();
        for(int i=0;i<51;i++) {
            UUID id=UUID.randomUUID();
            jdbc.sql("INSERT INTO submission(id,user_id,problem_version,source_code,source_sha256,idempotency_key,runtime_image,runner_policy) SELECT ?,user_id,'total-v1',source_code,source_sha256,?,runtime_image,runner_policy FROM submission WHERE id=?")
                .param(id).param(id).param(base.id()).update();
            jdbc.sql("INSERT INTO judge_job(submission_id,status,verdict,finished_at) VALUES (?,'FINISHED','WA',CURRENT_TIMESTAMP)").param(id).update();
        }
        assertThat(submissions.history(alice,"sum-v1",0)).extracting(Submissions.View::id).containsExactly(base.id());
        assertThat(submissions.history(alice,null,0)).hasSize(50);
        assertThat(submissions.history(alice,null,1)).hasSize(2);
        assertThat(submissions.history(bob,null,0)).isEmpty();
        var paged=new java.util.ArrayList<UUID>();for(int page=0;page<3;page++)paged.addAll(submissions.history(alice,null,page,20).stream().map(Submissions.View::id).toList());
        assertThat(paged).hasSize(52).doesNotHaveDuplicates();
        mvc.perform(get("/api/submissions?page=0&size=20").with(user(alice))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(20));
        mvc.perform(get("/api/submissions?page=1&size=20").with(user(alice))).andExpect(jsonPath("$.length()").value(20));
        mvc.perform(get("/api/submissions?page=2&size=20").with(user(alice))).andExpect(jsonPath("$.length()").value(12));
        mvc.perform(get("/api/submissions?size=0").with(user(alice))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/submissions?size=51").with(user(alice))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/my/summary").with(user(alice))).andExpect(status().isOk()).andExpect(jsonPath("$.submitted").value(52)).andExpect(jsonPath("$.attemptedProblems").value(2)).andExpect(jsonPath("$.solvedProblems").value(1));
        mvc.perform(get("/api/my/problems").with(user(alice))).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.items.length()").value(2));
        mvc.perform(get("/api/my/summary").with(user(bob))).andExpect(jsonPath("$.submitted").value(0));
        mvc.perform(get("/api/submissions?problemVersion=sum-v1").with(user(alice))).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/my/problems?page=-1").with(user(alice))).andExpect(status().isBadRequest());
    }
    @Test void customResultsExpireOnlyAfterCompletionRecoveryWindow() {
        var run=submissions.run(alice,UUID.randomUUID(),new RunController.Request("sum-v1",SOURCE,"1 2"));
        var pending=submissions.run(bob,UUID.randomUUID(),new RunController.Request("sum-v1",SOURCE,"1 2"));
        var formal=submit(alice,UUID.randomUUID());
        jdbc.sql("UPDATE judge_job SET status='FINISHED',finished_at=? WHERE submission_id IN (?,?)").param(OffsetDateTime.now().minusHours(25)).param(run.id()).param(formal.id()).update();
        transientRuns.clean();
        assertThatThrownBy(()->submissions.runDetail(alice,run.id())).isInstanceOf(AccountException.class);
        assertThat(submissions.runDetail(bob,pending.id()).id()).isEqualTo(pending.id());
        assertThat(submissions.detail(alice,formal.id()).id()).isEqualTo(formal.id());
    }
    void expire(UUID id) {
        jdbc.sql("UPDATE judge_job SET lease_until=? WHERE submission_id=?").param(OffsetDateTime.now().minusMinutes(1)).param(id).update();
    }
    ObjectNode report(JudgeQueue.Assignment assignment) {
        ObjectNode report = JudgeJson.JSON.createObjectNode();
        report.put("run_id", UUID.randomUUID().toString()).put("policy", assignment.runnerPolicy())
                .put("image", assignment.runtimeImage()).put("source_sha256", assignment.sourceSha256())
                .put("problem_sha256", assignment.problemSha256()).put("problem_version", "sum-v1").put("verdict", "AC")
                .put("execution_mode", assignment.executionMode());
        if(assignment.executionProfile()!=null){report.put("language",assignment.language());report.set("execution_profile",assignment.executionProfile().deepCopy());}
        report.putObject("runner_environment").put("dockerControl","engine").set("contract",assignment.runnerEnvironment()==null?null:assignment.runnerEnvironment().deepCopy());
        report.putObject("compile").put("exit_code",0).put("wall_ms",1).put("stderr", "");
        var tests = report.putArray("tests");
        assignment.problem().path("tests").forEach(test -> tests.addObject().put("id",test.path("id").asText())
                .put("verdict","AC").put("exit_code",0).put("oom_killed",false).put("wall_ms",1)
                .put("stdout_sha256",JudgeJson.hash(test.path("output").asText())).put("stderr", ""));
        return report;
    }

    @Test void formalSubmissionsJudgeEveryTestAndKeepTheFirstFailureVerdict() {
        var saved=submissions.submit(alice,UUID.randomUUID(),new SubmissionController.Request("sum-v1",SOURCE,null,null,"JAVA"));
        var assignment=queue.claim(UUID.randomUUID()).orElseThrow();
        assertThat(assignment.judgeAll()).isTrue();
        int count=assignment.problem().path("tests").size();
        var full=report(assignment).put("judge_all",true).put("verdict","WA");
        ((ObjectNode)full.path("tests").get(0)).put("verdict","WA");
        var lastVerdict=full.deepCopy().put("verdict","AC");
        assertThatThrownBy(()->queue.complete(saved.id(),assignment.token(),lastVerdict)).isInstanceOf(AccountException.class);
        queue.complete(saved.id(),assignment.token(),full);
        var detail=submissions.detail(alice,saved.id());
        assertThat(detail.verdict()).isEqualTo("WA");
        assertThat(detail.tests()).hasSize(count).extracting(Submissions.TestResult::verdict).first().isEqualTo("WA");
        assertThat(detail.testCount()).isEqualTo(count);
        var run=submissions.run(alice,UUID.randomUUID(),new RunController.Request("sum-v1",SOURCE,"1 2\n"));
        var runAssignment=queue.claim(UUID.randomUUID()).orElseThrow();
        assertThat(runAssignment.judgeAll()).isFalse();
        var forged=report(runAssignment).put("judge_all",true).put("verdict","OK");
        assertThatThrownBy(()->queue.complete(run.id(),runAssignment.token(),forged)).isInstanceOf(AccountException.class);
    }

    @Test void languagesPersistTrustedLimitsAndFenceReportsAndIdempotency() {
        assertThat(submissions.problems(alice).get(0).languages()).extracting(LanguageProfiles.Option::id)
                .containsExactly("JAVA","CPP","PYTHON");
        for(String language:List.of("JAVA","CPP","PYTHON")) {
            UUID key=UUID.randomUUID();
            var request=new SubmissionController.Request("sum-v1",SOURCE,null,null,language);
            var saved=submissions.submit(alice,key,request);
            assertThat(saved.language()).isEqualTo(language);
            assertThat(saved.execution()).isEqualTo(LanguageProfiles.option(LanguageProfiles.profile(language)));
            assertThat(submissions.submit(alice,key,request).id()).isEqualTo(saved.id());
            assertThatThrownBy(()->submissions.submit(alice,key,new SubmissionController.Request("sum-v1",SOURCE,null,null,language.equals("JAVA")?"CPP":"JAVA")))
                    .isInstanceOf(AccountException.class);
            var assignment=queue.claim(UUID.randomUUID()).orElseThrow();
            assertThat(assignment.language()).isEqualTo(language);
            assertThat(assignment.runtimeImage()).isEqualTo(LanguageProfiles.profile(language).path("image").asText());
            var bad=report(assignment);((ObjectNode)bad.path("execution_profile")).put("testWallSeconds",999);
            assertThatThrownBy(()->queue.complete(saved.id(),assignment.token(),bad)).isInstanceOf(AccountException.class);
            var wrongLanguage=report(assignment).put("language","FORGED");
            assertThatThrownBy(()->queue.complete(saved.id(),assignment.token(),wrongLanguage)).isInstanceOf(AccountException.class);
            queue.complete(saved.id(),assignment.token(),report(assignment));
            assertThat(submissions.detail(alice,saved.id()).language()).isEqualTo(language);
            assertThatThrownBy(()->submissions.detail(bob,saved.id())).isInstanceOf(AccountException.class);
        }
        assertThatThrownBy(()->submissions.submit(alice,UUID.randomUUID(),new SubmissionController.Request("sum-v1",SOURCE,null,null,"BASH")))
                .isInstanceOf(AccountException.class);
    }

    @Test void customRunsUseChosenLanguageAndItsOwnRunPolicy() {
        for(String language:List.of("CPP","PYTHON")) {
            var saved=submissions.run(alice,UUID.randomUUID(),new RunController.Request("sum-v1",SOURCE,"1 2",null,null,language));
            assertThat(saved.language()).isEqualTo(language);
            assertThat(saved.runnerPolicy()).isEqualTo(LanguageProfiles.profile(language).path("runPolicy").asText());
            var assignment=queue.claim(UUID.randomUUID()).orElseThrow();
            assertThat(assignment.executionProfile()).isEqualTo(LanguageProfiles.profile(language));
            assertThat(assignment.problem().path("output_policy").asText()).isEqualTo("RUN_ONLY");
            var report=report(assignment);report.put("verdict","OK");
            report.path("tests").forEach(test->((ObjectNode)test).put("verdict","OK").put("stdout","3").put("stdout_truncated",false));
            queue.complete(saved.id(),assignment.token(),report);
        }
    }

    @Test void runnerEnvironmentIsPinnedToAttemptAndRejectsMissingChangedOrUnknownReports() {
        var saved=submit(alice,UUID.randomUUID());
        UUID worker=UUID.randomUUID();var first=queue.claim(worker).orElseThrow();
        assertThat(first.runnerEnvironment()).isEqualTo(RunnerEnvironment.expected());
        for(String mismatch:List.of("missing","build","limits","transport")) {
            var bad=report(first);
            if(mismatch.equals("missing"))bad.remove("runner_environment");
            if(mismatch.equals("build"))((ObjectNode)bad.path("runner_environment").path("contract").path("files")).put("runner/judge.py","wrong");
            if(mismatch.equals("limits"))((ObjectNode)bad.path("runner_environment").path("contract").path("profile")).put("testWallSeconds",99);
            if(mismatch.equals("transport"))((ObjectNode)bad.path("runner_environment")).put("dockerControl","remote");
            assertThatThrownBy(()->queue.complete(saved.id(),first.token(),bad)).as(mismatch).isInstanceOf(AccountException.class);
        }
        // Simulate a contract from a previous app build: resume and completion use the persisted value.
        var old=(ObjectNode)first.runnerEnvironment().deepCopy();old.put("format","previous-build");
        jdbc.sql("UPDATE judge_attempt SET execution_environment_json=? WHERE submission_id=? AND attempt=1")
                .param(JudgeJson.canonical(old)).param(saved.id()).update();
        var resumed=queue.claim(worker).orElseThrow();assertThat(resumed.runnerEnvironment()).isEqualTo(old);
        var result=report(resumed);queue.complete(saved.id(),resumed.token(),result);queue.complete(saved.id(),resumed.token(),result);
    }
    @Test void legacyAttemptRemainsWithoutEnvironmentUntilAReplacementClaim() {
        var saved=submit(alice,UUID.randomUUID());UUID worker=UUID.randomUUID();
        var first=queue.claim(worker).orElseThrow();
        jdbc.sql("UPDATE judge_attempt SET execution_environment_json=NULL WHERE submission_id=?").param(saved.id()).update();
        assertThat(queue.claim(worker).orElseThrow().runnerEnvironment()).isNull();
        expire(saved.id());var next=queue.claim(worker).orElseThrow();
        assertThat(next.runnerEnvironment()).isEqualTo(RunnerEnvironment.expected());
        assertThat(next.token()).isNotEqualTo(first.token());
    }

    @Test
    void catalogPackagesAreImmutableAndPublicProjectionHidesTests() throws Exception {
        var publicItems = submissions.problems(alice);
        assertThat(publicItems).extracting(Submissions.Problem::version)
                .containsExactly("sum-v1", "total-v1", "valid-parentheses-v1");
        for (var item : publicItems) {
            String raw = java.nio.file.Files.readString(java.nio.file.Path.of("../problems", item.version()+".json"));
            String canonical = JudgeJson.canonical(JudgeJson.parse(raw));
            String stored = jdbc.sql("SELECT package_json FROM problem_version WHERE id=?")
                    .param(item.version()).query(String.class).single();
            assertThat(JudgeJson.canonical(JudgeJson.parse(stored))).isEqualTo(canonical);
            assertThat(jdbc.sql("SELECT package_sha256 FROM problem_version WHERE id=?")
                    .param(item.version()).query(String.class).single()).isEqualTo(JudgeJson.hash(canonical));
            assertThat(item.sampleInput()).isEqualTo(JudgeJson.parse(raw).path("tests").get(0).path("input").asText());
        }
        String publicJson = mvc.perform(get("/api/problems").with(user(alice))).andReturn().getResponse().getContentAsString();
        assertThat(publicJson).doesNotContain("positive-limit", "early-close", "package_sha256", "runtime_image", "tests");
        var key = UUID.randomUUID();
        var saved = submissions.submit(alice, key, new SubmissionController.Request("total-v1", SOURCE));
        var assignment = queue.claim(UUID.randomUUID()).orElseThrow();
        assertThat(assignment.problem().path("version").asText()).isEqualTo("total-v1");
        assertThat(assignment.problemSha256()).isEqualTo(JudgeJson.hash(JudgeJson.canonical(assignment.problem())));
        assertThat(saved.runnerPolicy()).isEqualTo("java8-judge-v1");
        assertThatThrownBy(() -> submissions.submit(alice, key,
                new SubmissionController.Request("valid-parentheses-v1", SOURCE))).isInstanceOf(AccountException.class);
        var session = sessions.start(alice, UUID.randomUUID(), new TrainingSessionController.Start("total-v1", "합계"));
        assertThatThrownBy(() -> submissions.submit(alice, UUID.randomUUID(),
                new SubmissionController.Request("valid-parentheses-v1", SOURCE, session.id()))).isInstanceOf(AccountException.class);
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
        // Put the formal job first; exclusive jobs no longer admit a second claim.
        jdbc.sql("UPDATE judge_job SET created_at=? WHERE submission_id=?")
                .param(OffsetDateTime.now().minusMinutes(1)).param(saved.id()).update();
        var assignment=queue.claim(UUID.randomUUID()).orElseThrow();
        assertThat(assignment.submissionId()).isEqualTo(saved.id());
        queue.complete(saved.id(),assignment.token(),report(assignment));
        assertThat(sessions.detail(alice,session).session().accepted()).isEqualTo(1);
        assertThat(sessions.detail(alice,session).session().pending()).isEqualTo(1);
        assertThat(sessions.detail(alice,session).entries()).hasSize(1);
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
        assertThat(submissions.runs(alice)).isEmpty();
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
        assertThat(submissions.runs(alice)).isEmpty();
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
    void twoFunctionalSlotsDrainForUserAndPreserveResumeAndFence() {
        // Synthetic scheduling fixtures; product admission is tested separately.
        var first=submit(alice,UUID.randomUUID());var second=submit(alice,UUID.randomUUID());
        var third=submit(bob,UUID.randomUUID());
        for(var item:List.of(first,second,third)) jdbc.sql("UPDATE judge_job SET priority=1,execution_mode='FUNCTIONAL' WHERE submission_id=?").param(item.id()).update();
        UUID worker1=UUID.randomUUID(),worker2=UUID.randomUUID();
        var a=queue.claim(worker1).orElseThrow();var b=queue.claim(worker2).orElseThrow();
        assertThat(a.submissionId()).isNotEqualTo(b.submissionId());
        assertThat(queue.claim(worker1).orElseThrow().token()).isEqualTo(a.token());
        assertThat(queue.claim(UUID.randomUUID())).isEmpty();
        var userJob=submit(bob,UUID.randomUUID());
        queue.complete(a.submissionId(),a.token(),report(a));
        assertThat(queue.claim(worker1)).isEmpty(); // user waits for b, no new validation.
        queue.complete(b.submissionId(),b.token(),report(b));
        var user=queue.claim(worker1).orElseThrow();
        assertThat(user.submissionId()).isEqualTo(userJob.id());
        assertThat(user.executionMode()).isEqualTo("EXCLUSIVE");
        assertThat(queue.claim(worker2)).isEmpty();
        queue.complete(user.submissionId(),user.token(),report(user));
        var last=queue.claim(worker2).orElseThrow();
        var wrong=report(last).put("execution_mode","EXCLUSIVE");
        assertThatThrownBy(()->queue.complete(last.submissionId(),last.token(),wrong)).isInstanceOf(AccountException.class);
        expire(last.submissionId());
        var replacement=queue.claim(worker1).orElseThrow();
        assertThatThrownBy(()->queue.complete(last.submissionId(),last.token(),report(last))).isInstanceOf(AccountException.class);
        var savedReport=report(replacement);
        queue.complete(replacement.submissionId(),replacement.token(),savedReport);
        queue.complete(replacement.submissionId(),replacement.token(),savedReport);
        assertThat(jdbc.sql("SELECT count(*) FROM judge_attempt WHERE status='COMPLETED'").query(Integer.class).single()).isEqualTo(4);
    }

    @Test
    void resourceQueueHeadIsNeverSkippedByLaterFunctionalWork() {
        var functional=submit(alice,UUID.randomUUID());
        jdbc.sql("UPDATE judge_job SET priority=1,execution_mode='FUNCTIONAL' WHERE submission_id=?").param(functional.id()).update();
        var active=queue.claim(UUID.randomUUID()).orElseThrow();
        var resource=submit(bob,UUID.randomUUID());var later=submit(alice,UUID.randomUUID());
        jdbc.sql("UPDATE judge_job SET priority=1,created_at=? WHERE submission_id=?").param(OffsetDateTime.now().minusMinutes(1)).param(resource.id()).update();
        jdbc.sql("UPDATE judge_job SET priority=1,execution_mode='FUNCTIONAL' WHERE submission_id=?").param(later.id()).update();
        assertThat(queue.claim(UUID.randomUUID())).isEmpty();
        queue.complete(active.submissionId(),active.token(),report(active));
        var exclusive=queue.claim(UUID.randomUUID()).orElseThrow();
        assertThat(exclusive.submissionId()).isEqualTo(resource.id());
        assertThat(queue.claim(UUID.randomUUID())).isEmpty();
    }

    @Test
    void simultaneousClaimersCannotExceedTwoFunctionalAssignments() throws Exception {
        for(int i=0;i<3;i++) {
            var saved=submit(alice,UUID.randomUUID());
            jdbc.sql("UPDATE judge_job SET priority=1,execution_mode='FUNCTIONAL' WHERE submission_id=?").param(saved.id()).update();
        }
        try(var executor=Executors.newFixedThreadPool(4)) {
            List<Callable<java.util.Optional<JudgeQueue.Assignment>>> calls=new ArrayList<>();
            for(int i=0;i<4;i++)calls.add(()->queue.claim(UUID.randomUUID()));
            var results=executor.invokeAll(calls);
            int claimed=0;var ids=new java.util.HashSet<UUID>();
            for(var result:results)if(result.get().isPresent()){claimed++;ids.add(result.get().get().submissionId());}
            assertThat(claimed).isEqualTo(2);assertThat(ids).hasSize(2);
            assertThat(jdbc.sql("SELECT count(*) FROM judge_job WHERE status='RUNNING'").query(Integer.class).single()).isEqualTo(2);
        }
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
