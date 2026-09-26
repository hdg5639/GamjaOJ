package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import java.util.List;
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

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:generation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa","spring.datasource.password=",
        "gamjaoj.invite-code=test","gamjaoj.submissions-enabled=true","AI_OPERATOR_USERS=operator","AI_POLL_MS=3600000",
        "gamjaoj.worker-token=judge-test-token-32-characters-long","GENERATION_WORKER_TOKEN=generation-test-token-32-characters-long"})
@AutoConfigureMockMvc
class GenerationIntegrationTest {
    @Autowired GenerationJobs generation; @Autowired JudgeQueue queue; @Autowired Submissions submissions;
    @Autowired JdbcClient jdbc; @Autowired MockMvc mvc;
    @Autowired AiTasks ai; @Autowired GenerationSpecDrafts drafts;
    @Autowired org.springframework.core.env.ConfigurableEnvironment environment;
    UUID owner;
    @BeforeEach void setup() {
        org.springframework.boot.test.util.TestPropertyValues.of("AI_API_ENABLED=false","OPENAI_API_KEY=test-only","AI_MONTHLY_BUDGET_USD=10").applyTo(environment);
        jdbc.sql("DELETE FROM generation_spec_execution").update();
        jdbc.sql("DELETE FROM ai_attempt").update();jdbc.sql("DELETE FROM ai_budget_notice").update();
        jdbc.sql("DELETE FROM generation_execution").update();jdbc.sql("DELETE FROM submission").update();jdbc.sql("DELETE FROM training_session").update();jdbc.sql("DELETE FROM generation_spec_draft").update();jdbc.sql("DELETE FROM generation_attempt").update();jdbc.sql("DELETE FROM generation_job").update();
        jdbc.sql("DELETE FROM problem_version WHERE id LIKE 'generated-%' OR id LIKE 'experimental-check-%'").update();jdbc.sql("DELETE FROM app_user").update();owner=UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user (id,username,password_hash,nickname) VALUES (?,'operator','unused','운영자')").param(owner).update();
        jdbc.sql("INSERT INTO app_user (id,username,password_hash,nickname) VALUES (?,'other','unused','친구')").param(UUID.randomUUID()).update();
    }
    @Autowired ProblemReview problemReview;
    @Autowired TrainingSessions training;
    String publishedReviewFixture() {
        UUID id=UUID.randomUUID();drafts.create("operator",id,"검토 보류 테스트");
        jdbc.sql("UPDATE generation_spec_draft SET status='PUBLISHED' WHERE id=?").param(id).update();
        String version="experimental-check-"+id;
        jdbc.sql("INSERT INTO problem_version (id,package_json,package_sha256,runtime_image,runner_policy,ready,owner_id) SELECT ?,package_json,package_sha256,runtime_image,runner_policy,true,? FROM problem_version WHERE id='total-v1'")
                .param(version).param(owner).update();return version;
    }
    @Test void reviewHoldIsOwnerOnlyIdempotentAndPreservesRecordsWhileBlockingNewWork() throws Exception {
        String version=publishedReviewFixture();UUID key=UUID.randomUUID(),sessionId=UUID.randomUUID();
        training.start("operator",sessionId,new TrainingSessionController.Start(version,"경계값"));
        var request=new SubmissionController.Request(version,"class Main {}",sessionId);
        var saved=submissions.submit("operator",key,request);
        jdbc.sql("UPDATE judge_job SET status='FINISHED',verdict='WA',result_sha256=?,result_json='{}' WHERE submission_id=?").param(JudgeJson.hash("{}")).param(saved.id()).update();
        var analysis=ai.request("operator",saved.id(),"ANALYSIS","",false);
        mvc.perform(post("/api/problems/"+version+"/review-hold").with(user("operator")).contentType("application/json").content("{\"reason\":\"예제 오류\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/problems/"+version+"/review-hold").with(user("other")).with(csrf()).contentType("application/json").content("{\"reason\":\"예제 오류\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/problems/"+version+"/review-hold").with(user("operator")).with(csrf()).contentType("application/json").content("{\"reason\":\"예제 오류\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.held").value(true));
        assertThat(problemReview.hold("operator",version,"다른 이유").reason()).isEqualTo("예제 오류");
        assertThat(drafts.list("operator").getFirst().problemHeld()).isTrue();
        assertThat(submissions.problems("operator").stream().filter(p->p.version().equals(version)).findFirst().orElseThrow().submissionsEnabled()).isFalse();
        assertThat(submissions.problems("other")).noneMatch(p->p.version().equals(version));
        assertThat(submissions.submit("operator",key,request).id()).isEqualTo(saved.id());
        assertThat(submissions.detail("operator",saved.id()).problemHeld()).isTrue();
        assertThat(submissions.detail("operator",saved.id()).verdict()).isEqualTo("WA");
        assertThatThrownBy(()->submissions.submit("operator",UUID.randomUUID(),request)).isInstanceOf(AccountException.class);
        assertThatThrownBy(()->submissions.run("operator",UUID.randomUUID(),new RunController.Request(version,"class Main {}","",null))).isInstanceOf(AccountException.class);
        assertThatThrownBy(()->ai.request("operator",saved.id(),"ANALYSIS","",false)).isInstanceOf(AccountException.class);
        training.end("operator",sessionId,"문제 검토 요청");ai.enqueueEndedSessions();
        assertThat(training.detail("operator",sessionId).session().problemHeld()).isTrue();
        assertThatThrownBy(()->training.start("operator",UUID.randomUUID(),new TrainingSessionController.Start(version,""))).isInstanceOf(AccountException.class);
        org.springframework.boot.test.util.TestPropertyValues.of("AI_API_ENABLED=true").applyTo(environment);
        assertThat(ai.claim()).isNull();
        assertThat(ai.detail("operator",analysis.id()).problemHeld()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM ai_attempt").query(Integer.class).single()).isZero();
        mvc.perform(get("/api/problems/"+version+"/teaching").with(user("operator"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/submissions/"+saved.id()).with(user("other"))).andExpect(status().isNotFound());
    }
    @Test void alreadyClaimedAnalysisSettlesUsageButIsNotLearningEvidenceAfterHold() {
        String version=publishedReviewFixture();
        var saved=submissions.submit("operator",UUID.randomUUID(),new SubmissionController.Request(version,"class Main {}"));
        jdbc.sql("UPDATE judge_job SET status='FINISHED',verdict='AC',result_sha256=?,result_json='{}' WHERE submission_id=?").param(JudgeJson.hash("{}")).param(saved.id()).update();
        var task=ai.request("operator",saved.id(),"ANALYSIS","",false);
        org.springframework.boot.test.util.TestPropertyValues.of("AI_API_ENABLED=true").applyTo(environment);
        var work=ai.claim();assertThat(work).isNotNull();
        problemReview.hold("operator",version,"정답 검토");
        ai.finish(work,new dev.gamjaoj.ai.OpenAiResponses.Result(AiIntegrationTest.feedback(),JudgeJson.parse("{\"input_tokens\":100,\"output_tokens\":50}"),"r","q","test"),null);
        assertThat(ai.detail("operator",task.id()).status()).isEqualTo("COMPLETED");
        assertThat(ai.detail("operator",task.id()).problemHeld()).isTrue();
        assertThat(ai.budget("operator").spentUsd()).isPositive();
        assertThat(ai.budget("operator").reservedUsd()).isZero();
        assertThatThrownBy(()->ai.retry("operator",task.id())).isInstanceOf(AccountException.class);
        assertThat(submissions.detail("operator",saved.id()).verdict()).isEqualTo("AC");
    }
    void readyThemes() {
        jdbc.sql("UPDATE ai_task SET status='COMPLETED',result_json=? WHERE kind='THEME'")
            .param("{\"setting\":\"별빛 관측\",\"scenario\":\"천체 신호의 변화를 살펴본다.\"}").update();
    }
    ObjectNode artifacts() {
        var value=JudgeJson.JSON.createObjectNode().put("title","감자 수열 합").put("context","수열에 적힌 모든 값을 더하세요.")
                .put("reference","public class Main {}").put("generator","public class Main {}").put("inputValidator","public class Main {}").put("editorial","long으로 모든 값을 더한다.");
        value.putArray("hints").add("N을 읽는다.").add("N개를 더한다.").add("long을 사용한다.");return value;
    }
    GenerationJobs.View authored() {
        var job=generation.create("operator",UUID.randomUUID(),GenerationTemplate.ID);readyThemes();var work=generation.claim();
        assertThat(generation.claim()).isNull();
        generation.complete(job.id(),work.token(),artifacts(),JudgeJson.JSON.createObjectNode().put("source","public class Main {}"),null,null);
        return generation.view("operator",job.id());
    }
    ObjectNode draftSpec() {
        var spec=JudgeJson.JSON.createObjectNode();
        for(String field:java.util.List.of("title","category","statement","inputDefinition","outputDefinition","constraints","referenceStrategy","oracleStrategy"))spec.put(field,field+" definition");
        for(String field:java.util.List.of("tags","boundaryClasses","mutantIdeas"))spec.putArray(field).add(field+" plan");
        var samples=spec.putArray("samples");
        samples.addObject().put("input","1").put("output","1").put("explanation","single");
        samples.addObject().put("input","2").put("output","2").put("explanation","boundary");return spec;
    }
    @Test void experimentalDraftUsesSharedWorkerButNeverPublishesOrCallsApi() throws Exception {
        int tasks=jdbc.sql("SELECT count(*) FROM ai_task").query(Integer.class).single();
        int problems=jdbc.sql("SELECT count(*) FROM problem_version").query(Integer.class).single();
        var id=UUID.randomUUID();
        mvc.perform(post("/api/generation/spec-drafts").with(user("other")).with(csrf()).header("Idempotency-Key",id)
                .contentType("application/json").content("{\"request\":\"DP 물건 중복 선택을 막는 문제\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("QUEUED"));
        assertThat(drafts.create("other",id,"DP 물건 중복 선택을 막는 문제").id()).isEqualTo(id);
        assertThatThrownBy(()->drafts.create("other",id,"changed")).isInstanceOf(AccountException.class);
        assertThatThrownBy(()->generation.create("other",UUID.randomUUID(),GenerationTemplate.ID)).isInstanceOf(AccountException.class);
        mvc.perform(get("/api/generation/spec-drafts/"+id).with(user("operator"))).andExpect(status().isNotFound());
        assertThat(drafts.list("operator")).isEmpty();
        var work=generation.claim();assertThat(work.id()).isEqualTo(id);
        assertThat(work.spec().path("phase").asText()).isEqualTo("EXPERIMENTAL_SPEC_DRAFT");
        assertThat(generation.claim()).isNull();
        assertThatThrownBy(()->generation.complete(id,UUID.randomUUID(),draftSpec(),null,null,null)).isInstanceOf(AccountException.class);
        generation.complete(id,work.token(),draftSpec(),null,null,null);
        generation.complete(id,work.token(),draftSpec(),null,null,null);
        var saved=drafts.view("other",id);assertThat(saved.status()).isEqualTo("DRAFT_READY");
        assertThat(saved.specHash()).isEqualTo(JudgeJson.hash(JudgeJson.canonical(draftSpec())));
        assertThatThrownBy(()->generation.complete(id,work.token(),draftSpec().put("title","changed"),null,null,null)).isInstanceOf(AccountException.class);
        generation.advance();
        assertThat(jdbc.sql("SELECT count(*) FROM problem_version").query(Integer.class).single()).isEqualTo(problems);
        assertThat(jdbc.sql("SELECT count(*) FROM ai_task").query(Integer.class).single()).isEqualTo(tasks);
        assertThat(jdbc.sql("SELECT count(*) FROM generation_job").query(Integer.class).single()).isZero();
        mvc.perform(post("/api/generation/"+id+"/review").with(user("other")).with(csrf()).contentType("application/json")
                .content("{\"artifactHash\":\""+saved.specHash()+"\",\"approve\":true}"))
            .andExpect(status().isNotFound());
    }
    @Test void invalidDraftAndExpiredLeaseRemainUnpublishedAndReleaseSlot() {
        var id=UUID.randomUUID();drafts.create("other",id,"새 문제");var work=generation.claim();
        generation.complete(id,work.token(),draftSpec().put("ready",true),null,null,null);
        assertThat(drafts.view("other",id).status()).isEqualTo("FAILED");
        assertThat(drafts.view("other",id).spec()).isNull();
        var expired=UUID.randomUUID();drafts.create("other",expired,"다른 문제");var lease=generation.claim();
        jdbc.sql("UPDATE generation_spec_draft SET lease_until=? WHERE id=?")
            .param(java.time.OffsetDateTime.now().minusMinutes(1)).param(expired).update();
        generation.advance();assertThat(drafts.view("other",expired).status()).isEqualTo("NEEDS_REVIEW");
        assertThatThrownBy(()->generation.complete(expired,lease.token(),draftSpec(),null,null,null)).isInstanceOf(AccountException.class);
        var normal=generation.create("other",UUID.randomUUID(),GenerationTemplate.ID);readyThemes();
        assertThat(generation.claim().id()).isEqualTo(normal.id());
        assertThatThrownBy(()->drafts.create("other",UUID.randomUUID(),"DP 문제")).isInstanceOf(AccountException.class);
    }
    UUID implementedDraft() {
        UUID id=UUID.randomUUID();drafts.create("other",id,"자유 문제");var author=generation.claim();
        generation.complete(id,author.token(),draftSpec(),null,null,null);
        var draft=drafts.view("other",id);
        assertThatThrownBy(()->drafts.build("operator",id,draft.specHash())).isInstanceOf(AccountException.class);
        assertThatThrownBy(()->drafts.build("other",id,"stale")).isInstanceOf(AccountException.class);
        drafts.build("other",id,draft.specHash());drafts.build("other",id,draft.specHash());
        var build=generation.claim();assertThat(build.spec().path("phase").asText()).isEqualTo("EXPERIMENTAL_IMPLEMENTATION");
        assertThat(generation.claim()).isNull();
        var oracle=JudgeJson.JSON.createObjectNode().put("source","public class Main {}");
        generation.complete(id,build.token(),artifacts(),oracle,null,null);
        generation.complete(id,build.token(),artifacts(),oracle,null,null);
        assertThat(drafts.view("other",id).status()).isEqualTo("CHECKING");
        return id;
    }
    void finishExperimental(boolean invalidGenerator,boolean disagree) {
        while(true){var next=queue.claim(UUID.randomUUID());if(next.isEmpty())break;
            var task=next.get();String role=jdbc.sql("SELECT role FROM generation_spec_execution WHERE submission_id=?").param(task.submissionId()).query(String.class).single();
            boolean run=task.problem().path("output_policy").asText().equals("RUN_ONLY");
            var result=report(task,run?"OK":"AC");
            if(run)((ObjectNode)result.path("tests").get(0)).put("stdout",role.equals("generator")?(invalidGenerator?"not JSON":"[\"1\",\"2\",\"3\",\"4\"]"):(disagree&&role.startsWith("oracle")?"2":"1"));
            queue.complete(task.submissionId(),task.token(),result);
        }
        generation.advance();
    }
    JsonNode independentReview(){
        var value=JudgeJson.JSON.createObjectNode().put("verdict","ACCEPT");value.putArray("issues");
        var cases=value.putArray("validCases");for(int i=1;i<=2;i++)cases.addObject().put("input",""+i).put("output","1").put("reason","boundary");
        value.putArray("invalidCases").add("").add("invalid");var mutants=value.putArray("mutants");
        for(int i=0;i<2;i++){var m=mutants.addObject().put("source","public class Main { /* mutant "+i+" */ }").put("explanation","logical mistake "+i);m.set("witness",cases.get(i).deepCopy());}return value;
    }
    UUID reviewableDraft(){var id=implementedDraft();finishExperimental(false,false);finishExperimental(false,false);return id;}
    void finishReview(String mutantVerdict){
        while(true){var next=queue.claim(UUID.randomUUID());if(next.isEmpty())break;var work=next.get();
            String role=jdbc.sql("SELECT role FROM generation_spec_execution WHERE submission_id=?").param(work.submissionId()).query(String.class).single();
            queue.complete(work.submissionId(),work.token(),report(work,role.contains("mutant")?mutantVerdict:"AC"));}generation.advance();
    }
    @Test void independentReviewIsPrivateFencedReplayableAndRequiresExecutedWrongAnswers() throws Exception {
        var id=reviewableDraft();var hash=drafts.view("other",id).specHash();
        mvc.perform(post("/api/generation/spec-drafts/"+id+"/review").with(user("operator")).with(csrf()).contentType("application/json").content("{\"specHash\":\""+hash+"\"}")).andExpect(status().isNotFound());
        assertThatThrownBy(()->drafts.review("other",id,"stale")).isInstanceOf(AccountException.class);
        drafts.review("other",id,hash);drafts.review("other",id,hash);var work=generation.claim();
        assertThat(work.spec().path("phase").asText()).isEqualTo("EXPERIMENTAL_REVIEW");
        assertThat(work.spec().path("definition").has("referenceStrategy")).isFalse();
        assertThat(work.spec().path("definition").has("oracleStrategy")).isFalse();assertThat(generation.claim()).isNull();
        var review=independentReview();generation.complete(id,work.token(),review,null,null,null);generation.complete(id,work.token(),review,null,null,null);
        assertThatThrownBy(()->generation.complete(id,work.token(),review,null,null,"CODEX_TIMEOUT")).isInstanceOf(AccountException.class);
        assertThat(drafts.view("other",id).status()).isEqualTo("REVIEW_CHECKING");finishReview("WA");
        var view=drafts.view("other",id);assertThat(view.status()).isEqualTo("REVIEW_CHECKED");assertThat(view.review().path("executions").asInt()).isEqualTo(6);
        assertThat(view.review().toString()).doesNotContain("public class Main");assertThat(view.review().path("publishable").asBoolean()).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM generation_spec_execution WHERE draft_id=?").param(id).query(Integer.class).single()).isEqualTo(19);
        assertThat(jdbc.sql("SELECT ready FROM problem_version WHERE id=?").param("experimental-check-"+id).query(Boolean.class).single()).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM ai_task").query(Integer.class).single()).isZero();
    }
    @Test void reviewRejectsCompileFailureAsMutantEvidence(){
        var id=reviewableDraft();drafts.review("other",id,drafts.view("other",id).specHash());var work=generation.claim();generation.complete(id,work.token(),independentReview(),null,null,null);
        finishReview("CE");assertThat(drafts.view("other",id).status()).isEqualTo("REVIEW_FAILED");
    }
    @Test void correctMutantsCannotPass(){
        var id=reviewableDraft();drafts.review("other",id,drafts.view("other",id).specHash());var work=generation.claim();generation.complete(id,work.token(),independentReview(),null,null,null);
        finishReview("AC");assertThat(drafts.view("other",id).status()).isEqualTo("REVIEW_FAILED");
    }
    @Test void reviewPayloadChangesAndExpiredLeaseCannotPass(){
        var id=reviewableDraft();drafts.review("other",id,drafts.view("other",id).specHash());var work=generation.claim();
        jdbc.sql("UPDATE generation_spec_draft SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1' SECOND WHERE id=?").param(id).update();
        assertThatThrownBy(()->generation.complete(id,work.token(),independentReview(),null,null,null)).isInstanceOf(AccountException.class);
        jdbc.sql("UPDATE generation_spec_draft SET lease_until=CURRENT_TIMESTAMP+INTERVAL '1' HOUR WHERE id=?").param(id).update();
        generation.complete(id,work.token(),independentReview(),null,null,null);
        jdbc.sql("UPDATE generation_spec_draft SET review_payload_json='{}' WHERE id=?").param(id).update();finishReview("WA");
        assertThat(drafts.view("other",id).error()).isEqualTo("REVIEW_ARTIFACT_FENCE_MISMATCH");
    }
    @Test void semanticRejectionPreservesIssuesAndDoesNotExecute(){
        var id=reviewableDraft();drafts.review("other",id,drafts.view("other",id).specHash());var work=generation.claim();
        var review=JudgeJson.JSON.createObjectNode().put("verdict","REVISE");review.putArray("issues").add("예제와 명세가 불일치합니다.");for(String field:List.of("validCases","invalidCases","mutants"))review.putArray(field);
        generation.complete(id,work.token(),review,null,null,null);assertThat(drafts.view("other",id).status()).isEqualTo("REVIEW_REJECTED");assertThat(queue.claim(UUID.randomUUID())).isEmpty();
        assertThat(drafts.view("other",id).review().path("issues").get(0).asText()).contains("불일치");
    }
    JsonNode finalPlan(){return JudgeJson.parse("{\"domainDescription\":\"four literals\",\"parts\":[[\"1\",\"2\"],[\"a\",\"b\"]],\"stressInput\":\"4\",\"stressReason\":\"maximum fixture\"}");}
    JsonNode planAcceptance(){return JudgeJson.parse("{\"accepted\":true,\"issues\":[]}");}
    UUID finalDraft(){
        var id=reviewableDraft();drafts.review("other",id,drafts.view("other",id).specHash());var review=generation.claim();generation.complete(id,review.token(),independentReview(),null,null,null);finishReview("WA");return id;
    }
    UUID finalChecking(){var id=finalDraft();drafts.publish("other",id,drafts.view("other",id).specHash());var work=generation.claim();generation.complete(id,work.token(),finalPlan(),planAcceptance(),null,null);return id;}
    void finishFinal(String fault){
        while(true){var next=queue.claim(UUID.randomUUID());if(next.isEmpty())break;var task=next.get();
            String role=jdbc.sql("SELECT role FROM generation_spec_execution WHERE submission_id=?").param(task.submissionId()).query(String.class).single();
            boolean run=task.runnerPolicy().equals("java8-run-v1");var result=report(task,role.equals(fault)?"WA":run?"OK":"AC");
            for(var row:result.path("tests"))((ObjectNode)row).put("wall_ms",fault.equals("slow")?4500:10);
            if(run)((ObjectNode)result.path("tests").get(0)).put("stdout",role.equals("final-generator")?(fault.equals("generator")?"invalid":"[\"1\",\"2\",\"3\",\"4\"]"):(fault.equals("oracle")&&role.contains("oracle")?"2":"1"));
            queue.complete(task.submissionId(),task.token(),result);
        }generation.advance();
    }
    @Test void finalPublicationUsesStoredEvidenceAndIsPrivatePlayableAndReplayable() throws Exception {
        var id=finalDraft();String hash=drafts.view("other",id).specHash();
        mvc.perform(post("/api/generation/spec-drafts/"+id+"/publish").with(user("operator")).with(csrf()).contentType("application/json").content("{\"specHash\":\""+hash+"\"}")).andExpect(status().isNotFound());
        assertThatThrownBy(()->drafts.publish("other",id,"stale")).isInstanceOf(AccountException.class);
        drafts.publish("other",id,hash);drafts.publish("other",id,hash);var work=generation.claim();
        assertThat(work.spec().path("phase").asText()).isEqualTo("EXPERIMENTAL_FINAL_PLAN");assertThat(work.spec().path("definition").has("referenceStrategy")).isFalse();assertThat(generation.claim()).isNull();
        generation.complete(id,work.token(),finalPlan(),planAcceptance(),null,null);generation.complete(id,work.token(),finalPlan(),planAcceptance(),null,null);
        var modes=jdbc.sql("SELECT e.role,j.execution_mode FROM generation_spec_execution e JOIN judge_job j ON j.submission_id=e.submission_id WHERE e.draft_id=? AND e.role LIKE 'final-%'")
                .param(id).query((r,n)->new String[]{r.getString(1),r.getString(2)}).list();
        assertThat(modes).anySatisfy(row->{assertThat(row[0]).isEqualTo("final-small-ref-0");assertThat(row[1]).isEqualTo("FUNCTIONAL");});
        assertThat(modes).filteredOn(row->row[0].startsWith("final-stress")).allSatisfy(row->assertThat(row[1]).isEqualTo("EXCLUSIVE"));
        finishFinal("");assertThat(drafts.view("other",id).status()).isEqualTo("FINAL_CHECKING");finishFinal("");
        String version="experimental-check-"+id;
        assertThat(jdbc.sql("SELECT ready FROM problem_version WHERE id=?").param(version).query(Boolean.class).single()).isFalse();
        // Simulate another coordinator instance resuming from the persisted stage/queues.
        new ExperimentalPublication(jdbc,new ExperimentalChecks(jdbc),new ExperimentalReview(jdbc,new ExperimentalChecks(jdbc))).advance();
        finishFinal("");var saved=drafts.view("other",id);assertThat(saved.status()).isEqualTo("PUBLISHED");assertThat(saved.publication().path("domainCases").asInt()).isEqualTo(4);assertThat(saved.publication().path("executions").asInt()).isEqualTo(24);
        assertThat(drafts.publish("other",id,hash).status()).isEqualTo("PUBLISHED");generation.advance();assertThat(queue.claim(UUID.randomUUID())).isEmpty();
        assertThat(submissions.problems("other").stream().anyMatch(p->p.version().equals(version)&&p.title().startsWith("[실험]"))).isTrue();assertThat(submissions.problems("operator").stream().anyMatch(p->p.version().equals(version))).isFalse();
        mvc.perform(get("/api/problems/"+version+"/teaching").with(user("operator"))).andExpect(status().isNotFound());mvc.perform(get("/api/problems/"+version+"/teaching").with(user("other"))).andExpect(status().isOk());
        assertThat(submissions.history("other")).isEmpty();assertThatThrownBy(()->submissions.submit("operator",UUID.randomUUID(),new SubmissionController.Request(version,"public class Main {}"))).isInstanceOf(AccountException.class);
        var submission=submissions.submit("other",UUID.randomUUID(),new SubmissionController.Request(version,"public class Main {}"));assertThat(submission.problemVersion()).isEqualTo(version);
    }
    @Test void explicitlySharedFreeformAppearsOnlyAfterFinalGates() {
        var id=finalChecking();
        jdbc.sql("UPDATE generation_spec_draft SET share_on_publish=true WHERE id=?").param(id).update();
        String version="experimental-check-"+id;
        assertThat(submissions.problems("operator")).noneMatch(p->p.version().equals(version));
        finishFinal("");finishFinal("");finishFinal("");
        assertThat(drafts.view("other",id).status()).isEqualTo("PUBLISHED");
        assertThat(submissions.problems("operator")).anyMatch(p->p.version().equals(version)&&p.shared()&&!p.mine());
        assertThat(submissions.submit("operator",UUID.randomUUID(),new SubmissionController.Request(version,"class Main {}"))).isNotNull();
    }
    @Test void explicitlySharedTagProblemAppearsOnlyAfterRunnerGates() {
        var job=authored();
        jdbc.sql("UPDATE generation_job SET share_on_publish=true WHERE id=?").param(job.id()).update();
        generation.review("operator",job.id(),job.artifactHash(),true);
        assertThat(submissions.problems("other")).hasSize(3);
        var first=queue.claim(UUID.randomUUID()).orElseThrow();queue.complete(first.submissionId(),first.token(),report(first,"OK"));generation.advance();
        assertThat(submissions.problems("other")).hasSize(3);
        while(true){var next=queue.claim(UUID.randomUUID());if(next.isEmpty())break;var task=next.get();
            var expected=jdbc.sql("SELECT expected_verdict FROM generation_execution WHERE submission_id=?").param(task.submissionId()).query(String.class).single();
            queue.complete(task.submissionId(),task.token(),report(task,expected));}
        generation.advance();var ready=generation.view("operator",job.id());assertThat(ready.status()).isEqualTo("READY");
        assertThat(submissions.problems("other")).anyMatch(p->p.version().equals(ready.problemVersion())&&p.shared()&&!p.mine()&&p.difficulty().equals("EASY"));
    }
    @Test void finalOracleMismatchAndMalformedGeneratorCannotPublish(){
        var id=finalChecking();finishFinal("oracle");assertThat(drafts.view("other",id).error()).isEqualTo("FINAL_ORACLE_DISAGREEMENT");
        var second=finalChecking();finishFinal("generator");assertThat(drafts.view("other",second).status()).isEqualTo("FINAL_FAILED");
    }
    @Test void finalResourceMarginAndValidatorFailureCannotPublish(){
        var id=finalChecking();finishFinal("slow");assertThat(drafts.view("other",id).error()).isEqualTo("FINAL_RESOURCE_MARGIN");
        var second=finalChecking();finishFinal("final-stress-validator");assertThat(drafts.view("other",second).status()).isEqualTo("FINAL_FAILED");
    }
    @Test void finalDomainRejectsExplosionsDuplicatesAndAmbiguousPlans(){
        assertThat(ExperimentalPublication.domain(finalPlan())).containsExactly("1a","1b","2a","2b");
        var handpicked=(ObjectNode)finalPlan();handpicked.set("parts",JudgeJson.parse("[[\"1\",\"2\",\"3\",\"4\"]]"));assertThatThrownBy(()->ExperimentalPublication.domain(handpicked)).isInstanceOf(IllegalArgumentException.class);
        var duplicate=(ObjectNode)finalPlan();duplicate.set("parts",JudgeJson.parse("[[\"1\",\"1\",\"2\",\"3\"]]"));assertThatThrownBy(()->ExperimentalPublication.domain(duplicate)).isInstanceOf(IllegalArgumentException.class);
        var huge=(ObjectNode)finalPlan();huge.set("parts",JudgeJson.parse("[[\"1\",\"2\",\"3\",\"4\"],[\"a\",\"b\",\"c\",\"d\"]]"));assertThatThrownBy(()->ExperimentalPublication.domain(huge)).isInstanceOf(IllegalArgumentException.class);
        var id=finalDraft();drafts.publish("other",id,drafts.view("other",id).specHash());var work=generation.claim();generation.complete(id,work.token(),finalPlan(),JudgeJson.parse("{\"accepted\":false,\"issues\":[\"maximum not covered\"]}"),null,null);
        assertThat(drafts.view("other",id).status()).isEqualTo("FINAL_REJECTED");assertThat(queue.claim(UUID.randomUUID())).isEmpty();
    }
    @Test void changedFinalPlanAndPackageCannotPublish(){
        var id=finalChecking();jdbc.sql("UPDATE generation_spec_draft SET final_plan_json='{}' WHERE id=?").param(id).update();finishFinal("");assertThat(drafts.view("other",id).error()).isEqualTo("FINAL_ARTIFACT_FENCE_MISMATCH");
        var next=finalChecking();finishFinal("");finishFinal("");
        jdbc.sql("UPDATE problem_version SET package_json='{}' WHERE id=?").param("experimental-check-"+next).update();finishFinal("");assertThat(drafts.view("other",next).error()).isEqualTo("FINAL_PACKAGE_FENCE");
    }
    @Test void experimentalChecksUseRunnerWithUserPriorityAndRemainPrivateUnpublished() throws Exception {
        var id=implementedDraft();
        var submitted=mvc.perform(post("/api/submissions").with(user("other")).with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json")
                .content("{\"problemVersion\":\"sum-v1\",\"source\":\"public class Main {}\"}"))
                .andExpect(status().isAccepted()).andReturn();
        var first=queue.claim(UUID.randomUUID()).orElseThrow();
        assertThat(first.submissionId().toString()).isEqualTo(JudgeJson.parse(submitted.getResponse().getContentAsString()).path("id").asText());
        queue.complete(first.submissionId(),first.token(),report(first,"AC"));
        assertThat(submissions.runs("other")).isEmpty();
        UUID hidden=jdbc.sql("SELECT submission_id FROM generation_spec_execution WHERE draft_id=? LIMIT 1").param(id).query(UUID.class).single();
        assertThatThrownBy(()->submissions.runDetail("other",hidden)).isInstanceOf(AccountException.class);
        finishExperimental(false,false);assertThat(drafts.view("other",id).status()).isEqualTo("CHECKING");
        finishExperimental(false,false);generation.advance();
        var checked=drafts.view("other",id);assertThat(checked.status()).isEqualTo("CHECKED");
        assertThat(checked.checks().path("executions").asInt()).isEqualTo(13);
        assertThat(checked.checks().path("publishable").asBoolean()).isFalse();
        assertThat(jdbc.sql("SELECT ready FROM problem_version WHERE id=?").param("experimental-check-"+id).query(Boolean.class).single()).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM generation_spec_execution WHERE draft_id=?").param(id).query(Integer.class).single()).isEqualTo(13);
        assertThat(submissions.problems("other").stream().anyMatch(p->p.version().equals("experimental-check-"+id))).isFalse();
    }
    @Test void malformedExperimentalGeneratorFailsBeforeDependentJobs() {
        var id=implementedDraft();finishExperimental(true,false);
        assertThat(drafts.view("other",id).status()).isEqualTo("BUILD_FAILED");
        assertThat(jdbc.sql("SELECT count(*) FROM generation_spec_execution WHERE draft_id=?").param(id).query(Integer.class).single()).isEqualTo(4);
    }
    @Test void experimentalOracleDisagreementCannotPass() {
        var id=implementedDraft();finishExperimental(false,false);finishExperimental(false,true);
        assertThat(drafts.view("other",id).error()).isEqualTo("REFERENCE_ORACLE_DISAGREEMENT");
        assertThat(drafts.view("other",id).checks()).isNull();
    }
    @Test void recommendationsRespectRequiredTagsAndOnlyReadOwnRecentHistory() throws Exception {
        var required=java.util.List.of("directed","weighted","edge-cases");
        var candidates=GenerationRecommendations.candidates("graphs",required);
        assertThat(candidates).hasSize(3);
        var count=candidates.stream().filter(c->c.template().endsWith("COUNT")).findFirst().orElseThrow();
        var history=generation.create("operator",UUID.randomUUID(),count.template(),count.focus());
        int tasks=jdbc.sql("SELECT count(*) FROM ai_task").query(Integer.class).single();
        mvc.perform(get("/api/generation/recommendations").with(user("operator")).param("category","graphs").param("tags",String.join(",",required)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.alternativeAvailable").value(true))
            .andExpect(jsonPath("$.suggestions.length()").value(2))
            .andExpect(jsonPath("$.suggestions[0].template").value("graph-recipe-v1-D-W-MAX"))
            .andExpect(jsonPath("$.suggestions[0].recentCount").value(0))
            .andExpect(jsonPath("$.suggestions[1].recentCount").value(1));
        var foreign=mvc.perform(get("/api/generation/recommendations").with(user("other")).param("category","graphs").param("tags",String.join(",",required)))
            .andExpect(status().isOk()).andReturn();
        for(var suggestion:JudgeJson.parse(foreign.getResponse().getContentAsString()).path("suggestions")) {
            assertThat(suggestion.path("recentCount").asInt()).isZero();
            var tags=new java.util.ArrayList<String>();suggestion.path("tags").forEach(t->tags.add(t.asText()));
            assertThat(tags).containsAll(required);
            assertThat(GenerationChoices.resolve("graphs",tags).template()).isEqualTo(suggestion.path("template").asText());
        }
        jdbc.sql("UPDATE generation_job SET status='FAILED' WHERE id=?").param(history.id()).update();
        var failed=mvc.perform(get("/api/generation/recommendations").with(user("operator")).param("category","graphs").param("tags",String.join(",",required)))
            .andExpect(status().isOk()).andReturn();
        for(var suggestion:JudgeJson.parse(failed.getResponse().getContentAsString()).path("suggestions"))
            assertThat(suggestion.path("recentCount").asInt()).isZero();
        mvc.perform(get("/api/generation/recommendations").with(user("other")).param("category","strings").param("tags","basics"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.alternativeAvailable").value(false));
        mvc.perform(get("/api/generation/recommendations").with(user("other")).param("category","graphs").param("tags","reachable-count,max-distance"))
            .andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT count(*) FROM ai_task").query(Integer.class).single()).isEqualTo(tasks);
        assertThat(jdbc.sql("SELECT count(*) FROM generation_job").query(Integer.class).single()).isEqualTo(1);
        assertThat(generation.view("operator",history.id()).status()).isEqualTo("FAILED");
    }
    @Test void recommendationCandidatesEnumerateContractsWithoutDroppingConstraints() {
        assertThat(GenerationRecommendations.candidates("sequences",java.util.List.of("basics"))).hasSize(20);
        assertThat(GenerationRecommendations.candidates("graphs",java.util.List.of("basics"))).hasSize(12);
        var fixed=GenerationRecommendations.candidates("sequences",java.util.List.of("filter-odd","squares","edge-cases"));
        assertThat(fixed).hasSize(1);
        assertThat(fixed.get(0).focus()).isEqualTo("edge-cases,filter-odd,squares");
        assertThatThrownBy(()->GenerationRecommendations.candidates("sequences",java.util.List.of("count","squares"))).isInstanceOf(AccountException.class);
    }
    @Test void themeUsesSharedBudgetAndMustFinishBeforeCodexWithPrivateHistory() {
        var key=UUID.randomUUID();var job=generation.create("operator",key,GenerationTemplate.ID);
        assertThat(job.theme().status()).isEqualTo("HELD_DISABLED");assertThat(generation.claim()).isNull();
        assertThat(generation.create("operator",key,GenerationTemplate.ID).id()).isEqualTo(key);
        assertThat(jdbc.sql("SELECT count(*) FROM ai_task WHERE kind='THEME'").query(Integer.class).single()).isEqualTo(1);
        org.springframework.boot.test.util.TestPropertyValues.of("AI_API_ENABLED=true","AI_MONTHLY_BUDGET_USD=0").applyTo(environment);
        assertThat(ai.claim()).isNull();assertThat(generation.view("operator",key).theme().status()).isEqualTo("HELD_BUDGET");
        org.springframework.boot.test.util.TestPropertyValues.of("AI_MONTHLY_BUDGET_USD=10").applyTo(environment);
        var task=ai.claim();assertThat(task).isNotNull();assertThat(ai.budget().reservedUsd()).isPositive();
        assertThatThrownBy(()->ai.detail("other",task.taskId())).isInstanceOf(AccountException.class);
        assertThat(JudgeJson.parse(task.input()).path("kind").asText()).isEqualTo("THEME");
        var theme=JudgeJson.parse("{\"setting\":\"유성 관측 기록\",\"scenario\":\"관측 신호의 증가와 감소를 기록한다.\"}");
        var usage=JudgeJson.parse("{\"input_tokens\":100,\"output_tokens\":50}");
        ai.finish(task,new dev.gamjaoj.ai.OpenAiResponses.Result(theme,usage,"theme-response","request","gpt-5.6-luna"),null);
        assertThat(ai.budget().reservedUsd()).isZero();assertThat(ai.budget().spentUsd()).isPositive();
        assertThat(generation.claim().spec().path("theme")).isEqualTo(theme);
    }
    @Test void recentThemesArePrivateAndRepeatedStoryRepairsOnlyText() {
        var first=authored();
        jdbc.sql("UPDATE generation_job SET status='FAILED' WHERE id=?").param(first.id()).update();
        var next=generation.create("operator",UUID.randomUUID(),GenerationTemplate.ID);
        assertThat(next.theme().domain()).isNotEqualTo(first.theme().domain());
        readyThemes();var work=generation.claim();
        assertThat(work.spec().path("recentStories").size()).isEqualTo(1);
        var oracle=JudgeJson.JSON.createObjectNode().put("source","public class Main {}");
        generation.complete(next.id(),work.token(),artifacts(),oracle,null,null);
        var retry=generation.claim();assertThat(retry.revision()).isEqualTo(1);
        assertThat(retry.repair().path("fields").toString()).isEqualTo("[\"context\",\"title\"]");
        var changed=artifacts().put("title","별빛 관측").put("context","밤하늘에서 포착한 신호의 증감을 기록한다.");
        generation.complete(next.id(),retry.token(),changed,oracle,null,null);
        assertThat(generation.view("operator",next.id()).status()).isEqualTo("VALIDATING");
        var foreign=generation.create("other",UUID.randomUUID(),GenerationTemplate.ID);
        readyThemes();var other=generation.claim();
        assertThat(other.id()).isEqualTo(foreign.id());assertThat(other.spec().path("recentStories")).isEmpty();
    }
    @Test void unknownThemeCallRetainsReservationAndRequiresExplicitRetry() {
        var job=generation.create("operator",UUID.randomUUID(),GenerationTemplate.ID);
        org.springframework.boot.test.util.TestPropertyValues.of("AI_API_ENABLED=true").applyTo(environment);
        var task=ai.claim();ai.finish(task,null,new dev.gamjaoj.ai.OpenAiResponses.Failure("TIMEOUT_USAGE_UNKNOWN",null,null));
        assertThat(ai.budget().reservedUsd()).isPositive();assertThat(ai.claim()).isNull();assertThat(generation.claim()).isNull();
        assertThatThrownBy(()->generation.retryTheme("other",job.id())).isInstanceOf(AccountException.class);
        generation.advance();assertThat(generation.view("operator",job.id()).status()).isEqualTo("THEME_FAILED");
        generation.retryTheme("operator",job.id());assertThat(ai.claim()).isNotNull();
        assertThat(jdbc.sql("SELECT count(*) FROM ai_attempt WHERE task_id=?").param(task.taskId()).query(Integer.class).single()).isEqualTo(2);
    }

    ObjectNode report(JudgeQueue.Assignment work,String verdict) {
        var report=JudgeJson.JSON.createObjectNode().put("source_sha256",work.sourceSha256()).put("problem_sha256",work.problemSha256())
                .put("image",work.runtimeImage()).put("policy",work.runnerPolicy()).put("problem_version",work.problem().path("version").asText()).put("verdict",verdict)
                .put("execution_mode",work.executionMode());
        if(work.executionProfile()!=null){report.put("language",work.language());report.set("execution_profile",work.executionProfile().deepCopy());}
        report.putObject("runner_environment").put("dockerControl","engine").set("contract",work.runnerEnvironment()==null?null:work.runnerEnvironment().deepCopy());
        var tests=report.putArray("tests");
        if(!java.util.Set.of("CE","IE").contains(verdict)) for(JsonNode test:work.problem().path("tests")) {
            var row=tests.addObject().put("id",test.path("id").asText()).put("verdict",verdict);
            if(work.runnerPolicy().equals("java8-run-v1"))row.put("stdout","1 0\n2 1 2\n1 -1\n3 1 -2 3\n").put("stderr","").put("stdout_truncated",false);
            if(verdict.equals("WA"))break;
        }
        // Generated large tests run only after every explicit test passed, like the Runner.
        if(!java.util.Set.of("CE","IE","WA").contains(verdict))for(JsonNode g:work.problem().path("generated").path("tests"))
            tests.addObject().put("id",g.path("id").asText()).put("kind","generated").put("verdict",verdict).put("wall_ms",10);
        return report;
    }
    @Test void parenthesesUsesItsOwnContractAndAllGatesBeforePrivatePublication() throws Exception {
        var key=UUID.randomUUID();
        var job=generation.create("other",key,"parentheses-v1","prefix-balance");
        assertThat(job.preview().path("templateId").asText()).isEqualTo("parentheses-v1");
        assertThatThrownBy(()->generation.create("other",key,"sequence-sum-v1","basics")).isInstanceOf(AccountException.class);
        assertThatThrownBy(()->generation.create("operator",UUID.randomUUID(),"parentheses-v1","overflow")).isInstanceOf(AccountException.class);
        readyThemes();var work=generation.claim();assertThat(work.spec().path("learningFocus").asText()).contains("prefix");
        generation.complete(key,work.token(),artifacts(),JudgeJson.JSON.createObjectNode().put("source","public class Main {}"),null,null);
        var first=queue.claim(UUID.randomUUID()).orElseThrow();
        var generated=report(first,"OK");((ObjectNode)generated.path("tests").get(0)).put("stdout","()\n)(\n(()\n(())\n");
        queue.complete(first.submissionId(),first.token(),generated);generation.advance();
        assertThat(generation.view("other",key).status()).isEqualTo("VALIDATING");
        assertThat(submissions.problems("other")).hasSize(3);
        while(true) {
            var next=queue.claim(UUID.randomUUID());if(next.isEmpty())break;var task=next.get();
            String expected=jdbc.sql("SELECT expected_verdict FROM generation_execution WHERE submission_id=?").param(task.submissionId()).query(String.class).single();
            queue.complete(task.submissionId(),task.token(),report(task,expected));
        }
        generation.advance();job=generation.view("other",key);
        assertThat(job.status()).isEqualTo("READY");assertThat(job.validation().path("executions").asInt()).isEqualTo(15);
        assertThat(submissions.problems("operator")).hasSize(3);assertThat(submissions.problems("other")).hasSize(4);
        var packageJson=JudgeJson.parse(jdbc.sql("SELECT package_json FROM problem_version WHERE id=?").param(job.problemVersion()).query(String.class).single());
        assertThat(packageJson.path("tests").size()).isLessThanOrEqualTo(20);
        assertThat(packageJson.path("tests").get(0).path("output").asText()).isEqualTo("YES\n");
        assertThat(packageJson.path("statement").asText()).contains(ParenthesesTemplate.STATEMENT);
        mvc.perform(get("/api/generation/learning-context?template=parentheses-v1").with(user("other"))).andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test void unpublishedArtifactNeedsExactReviewThenAllRunnerGatesBeforePublication() {
        var job=authored();assertThat(submissions.problems("other")).hasSize(3);
        assertThatThrownBy(()->generation.review("operator",job.id(),"stale",true)).isInstanceOf(AccountException.class);
        generation.review("operator",job.id(),job.artifactHash(),true);
        assertThat(submissions.problems("other")).hasSize(3);
        var first=queue.claim(UUID.randomUUID()).orElseThrow();queue.complete(first.submissionId(),first.token(),report(first,"OK"));
        generation.advance();
        assertThat(jdbc.sql("SELECT count(*) FROM generation_execution").query(Integer.class).single()).isEqualTo(15);
        assertThat(submissions.problems("other")).hasSize(3);
        while(true) {
            var next=queue.claim(UUID.randomUUID());if(next.isEmpty())break;var work=next.get();
            String expected=jdbc.sql("SELECT expected_verdict FROM generation_execution WHERE submission_id=?").param(work.submissionId()).query(String.class).single();
            queue.complete(work.submissionId(),work.token(),report(work,expected));
        }
        generation.advance();assertThat(generation.view("operator",job.id()).status()).isEqualTo("READY");
        assertThat(submissions.problems("other")).hasSize(3);
        assertThat(submissions.problems("operator")).hasSize(4);
        String ver="generated-"+job.id()+"-r0";
        String packed=jdbc.sql("SELECT package_json FROM problem_version WHERE id=?").param(ver).query(String.class).single();
        var tests=JudgeJson.parse(packed).path("tests");assertThat(tests.size()).isLessThanOrEqualTo(20);
        var ids=new java.util.HashSet<String>();tests.forEach(test->assertThat(ids.add(test.path("id").asText())).isTrue());
        assertThat(ids).contains("singleton-positive","singleton-negative","cancellation","mixed-sign");
        assertThat(submissions.history("operator")).isEmpty();assertThat(submissions.runs("operator")).isEmpty();
        assertThatThrownBy(()->submissions.submit("other",UUID.randomUUID(),new SubmissionController.Request(ver,"public class Main {}"))).isInstanceOf(AccountException.class);
        var submitted=submissions.submit("operator",UUID.randomUUID(),new SubmissionController.Request(ver,"public class Main {}"));
        assertThat(submitted.problemVersion()).isEqualTo(ver);
    }
    @Test void inputLayoutRepairPreservesOldEvidenceAndRerunsAllGatesWithoutModels() throws Exception {
        var job=authored();passGates();String oldVersion="generated-"+job.id()+"-r0";
        String original=jdbc.sql("SELECT package_json FROM problem_version WHERE id=?").param(oldVersion).query(String.class).single();
        var corrupt=JudgeJson.parse(original);((ObjectNode)corrupt.path("tests").get(0)).put("input","3 1 2 3\n");
        String broken=JudgeJson.canonical(corrupt),hash=JudgeJson.hash(broken);
        jdbc.sql("UPDATE problem_version SET package_json=?,package_sha256=? WHERE id=?").param(broken).param(hash).param(oldVersion).update();
        int models=jdbc.sql("SELECT count(*) FROM generation_attempt").query(Integer.class).single();
        mvc.perform(post("/internal/generation/"+job.id()+"/repair-input-layout").contentType("application/json").content("{\"packageHash\":\""+hash+"\"}"))
                .andExpect(status().isUnauthorized());
        assertThatThrownBy(()->generation.repairInputLayout(job.id(),"stale")).isInstanceOf(AccountException.class);
        var repairing=generation.repairInputLayout(job.id(),hash);
        assertThat(repairing.revision()).isEqualTo(1);assertThat(repairing.status()).isEqualTo("VALIDATING");
        assertThat(jdbc.sql("SELECT package_json FROM problem_version WHERE id=?").param(oldVersion).query(String.class).single()).isEqualTo(broken);
        assertThat(jdbc.sql("SELECT review_hold FROM problem_version WHERE id=?").param(oldVersion).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM generation_evidence_revocation").query(Integer.class).single()).isEqualTo(1);
        assertThatThrownBy(()->generation.repairInputLayout(job.id(),hash)).isInstanceOf(AccountException.class);
        passGates();assertThat(generation.view("operator",job.id()).status()).isEqualTo("READY");
        var corrected=JudgeJson.parse(jdbc.sql("SELECT package_json FROM problem_version WHERE id=?").param("generated-"+job.id()+"-r1").query(String.class).single());
        assertThat(InputLayout.matches(GenerationType.SUM,corrected)).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM generation_attempt").query(Integer.class).single()).isEqualTo(models);
        assertThat(jdbc.sql("SELECT count(*) FROM generation_evidence WHERE job_id=?").param(job.id()).query(Integer.class).single()).isEqualTo(2);
    }
    @Test void publicationRejectsFlatInputEvenAfterSuccessfulExecutionReports() {
        var job=authored();var first=queue.claim(UUID.randomUUID()).orElseThrow();
        queue.complete(first.submissionId(),first.token(),report(first,"OK"));generation.advance();
        while(true){var next=queue.claim(UUID.randomUUID());if(next.isEmpty())break;var work=next.get();
            String expected=jdbc.sql("SELECT expected_verdict FROM generation_execution WHERE submission_id=?").param(work.submissionId()).query(String.class).single();
            queue.complete(work.submissionId(),work.token(),report(work,expected));}
        String version="generated-"+job.id()+"-r0";
        var plan=JudgeJson.parse(jdbc.sql("SELECT package_json FROM problem_version WHERE id=?").param(version).query(String.class).single());
        ((ObjectNode)plan.path("tests").get(0)).put("input","3 1 2 3\n");
        jdbc.sql("UPDATE problem_version SET package_json=? WHERE id=?").param(plan.toString()).param(version).update();
        generation.advance();assertThat(generation.view("operator",job.id()).error()).isEqualTo("INPUT_LAYOUT_MISMATCH");
        assertThat(jdbc.sql("SELECT ready FROM problem_version WHERE id=?").param(version).query(Boolean.class).single()).isFalse();
    }
    void passGates() {
        var first=queue.claim(UUID.randomUUID()).orElseThrow();
        queue.complete(first.submissionId(),first.token(),report(first,"OK"));generation.advance();
        while(true) {
            var next=queue.claim(UUID.randomUUID());if(next.isEmpty())break;var task=next.get();
            String expected=jdbc.sql("SELECT expected_verdict FROM generation_execution WHERE submission_id=?").param(task.submissionId()).query(String.class).single();
            queue.complete(task.submissionId(),task.token(),report(task,expected));
        }
        generation.advance();
    }
    @Test void verifiedStructureIsOwnedSnapshottedAndRevalidatedWithCodeFence() {
        var first=authored();passGates();
        var next=generation.create("operator",UUID.randomUUID(),GenerationTemplate.ID);
        assertThat(next.preview().path("structure").path("reused").asBoolean()).isTrue();
        assertThat(next.preview().toString()).doesNotContain("public class");
        readyThemes();var work=generation.claim();
        assertThat(work.reuse().path("sourceJobId").asText()).isEqualTo(first.id().toString());
        assertThat(work.reuse().path("artifacts").size()).isEqualTo(3);
        // Selection is frozen; changing the source cannot silently change the assignment.
        jdbc.sql("UPDATE generation_job SET artifacts_json='{}' WHERE id=?").param(first.id()).update();
        var fresh=artifacts().put("title","새로운 공연").put("context","연주 신호의 증가량과 감소량을 기록한다.");
        var oracle=work.reuse().path("oracle");
        assertThatThrownBy(()->generation.complete(next.id(),work.token(),fresh.deepCopy().put("reference","changed"),oracle,null,null)).isInstanceOf(AccountException.class);
        assertThatThrownBy(()->generation.complete(next.id(),work.token(),fresh,JudgeJson.JSON.createObjectNode().put("source","changed"),null,null)).isInstanceOf(AccountException.class);
        generation.complete(next.id(),work.token(),fresh,oracle,null,null);
        assertThat(generation.view("operator",next.id()).status()).isEqualTo("VALIDATING");
        passGates();var validation=generation.view("operator",next.id()).validation();
        assertThat(validation.path("executions").asInt()).isEqualTo(15);
        assertThat(GenerationEvidence.intact(validation.path("executionInputAudit"))).isTrue();
        assertThat(validation.path("executionInputAudit").path("gates").size()).isEqualTo(15);
        assertThat(validation.path("reuseAudit").path("sourceComparable").asBoolean()).isTrue();
        assertThat(validation.path("reuseAudit").path("reuseAuthorized").asBoolean()).isFalse();
        assertThat(validation.path("reuseAudit").path("matchingRoles").toString()).contains("validator-invalid");
        assertThat(validation.path("reuseAudit").path("changedRoles").toString()).contains("generator");
        assertThat(jdbc.sql("SELECT count(*) FROM generation_execution WHERE job_id=?").param(next.id()).query(Integer.class).single()).isEqualTo(15);
        var foreign=generation.create("other",UUID.randomUUID(),GenerationTemplate.ID);
        assertThat(foreign.preview().path("structure").path("reused").asBoolean()).isFalse();
        var differentTags=generation.create("operator",UUID.randomUUID(),GenerationTemplate.ID,"overflow");
        assertThat(differentTags.preview().path("structure").path("reused").asBoolean()).isFalse();
    }
    @Autowired VerificationLedger ledger;
    GenerationJobs.View derived(String title,String context) {
        var job=generation.create("operator",UUID.randomUUID(),GenerationTemplate.ID);
        readyThemes();var work=generation.claim();assertThat(work.reuse()).isNotNull();
        generation.complete(job.id(),work.token(),artifacts().put("title",title).put("context",context),work.reuse().path("oracle"),null,null);
        passGates();
        jdbc.sql("UPDATE generation_job SET updated_at=? WHERE id=?").param(java.time.OffsetDateTime.now().plusSeconds(5)).param(job.id()).update();
        return generation.view("operator",job.id());
    }
    @Test void evidenceIsAppendOnlyAndCorruptionCannotBecomeAReusableSource() {
        var root=authored();passGates();root=generation.view("operator",root.id());
        var entry=ledger.entry(root.id(),root.revision());assertThat(entry).isNotNull();
        assertThat(ledger.freeze(root.id(),root.revision(),root.validation())).isEqualTo(entry.id());
        UUID id=root.id();int revision=root.revision();var changed=(ObjectNode)root.validation().deepCopy();changed.put("executions",0);
        assertThatThrownBy(()->ledger.freeze(id,revision,changed)).isInstanceOf(IllegalStateException.class);
        assertThat(ledger.entry(id,revision)).isEqualTo(entry);
        jdbc.sql("UPDATE generation_evidence SET snapshot_json='{}' WHERE id=?").param(entry.id()).update();
        var next=generation.create("operator",UUID.randomUUID(),GenerationTemplate.ID);
        assertThat(next.preview().path("structure").path("reused").asBoolean()).isFalse();
    }
    @Test void rootHoldPropagatesTransitivelyAndFencesPendingPublicationWithoutErasingHistory() {
        var root=authored();passGates();root=generation.view("operator",root.id());
        var child=derived("바닷속 탐사","잠수정 장비의 기록을 정리한다.");
        var grandchild=derived("숲속 관측","나무에 달린 측정 장치의 기록을 확인한다.");
        assertThat(jdbc.sql("SELECT source_job_id FROM generation_dependency WHERE job_id=?").param(grandchild.id()).query(UUID.class).single()).isEqualTo(child.id());
        UUID session=UUID.randomUUID();training.start("operator",session,new TrainingSessionController.Start(child.problemVersion(),"경계"));
        UUID key=UUID.randomUUID();var request=new SubmissionController.Request(child.problemVersion(),"class Main {}",session);
        var submission=submissions.submit("operator",key,request);
        var pending=generation.create("operator",UUID.randomUUID(),GenerationTemplate.ID);readyThemes();var work=generation.claim();
        generation.complete(pending.id(),work.token(),artifacts().put("title","궤도 변화").put("context","인공위성 장치의 기록을 확인한다."),work.reuse().path("oracle"),null,null);
        var snapshot=ledger.entry(root.id(),0).snapshotJson();
        String version=root.problemVersion();
        assertThatThrownBy(()->problemReview.hold("other",version,"오류")).isInstanceOf(AccountException.class);
        problemReview.hold("operator",version,"검증 코드 오류");
        assertThat(problemReview.hold("operator",version,"다른 이유").reason()).isEqualTo("검증 코드 오류");
        assertThat(jdbc.sql("SELECT count(*) FROM generation_evidence_revocation").query(Integer.class).single()).isEqualTo(3);
        assertThat(ledger.entry(root.id(),0).snapshotJson()).isEqualTo(snapshot);
        for(var job:List.of(root,child,grandchild))assertThat(generation.view("operator",job.id()).problemHeld()).isTrue();
        generation.advance();assertThat(generation.view("operator",pending.id()).status()).isEqualTo("NEEDS_REVIEW");
        assertThat(jdbc.sql("SELECT ready FROM problem_version WHERE id=?").param("generated-"+pending.id()+"-r0").query(Boolean.class).single()).isFalse();
        assertThat(submissions.submit("operator",key,request).id()).isEqualTo(submission.id());
        assertThat(submissions.detail("operator",submission.id()).problemHeld()).isTrue();
        assertThat(training.detail("operator",session).session().problemHeld()).isTrue();
        assertThatThrownBy(()->submissions.submit("operator",UUID.randomUUID(),request)).isInstanceOf(AccountException.class);
        assertThatThrownBy(()->training.start("operator",UUID.randomUUID(),new TrainingSessionController.Start(child.problemVersion(),""))).isInstanceOf(AccountException.class);
        var fresh=generation.create("operator",UUID.randomUUID(),GenerationTemplate.ID);
        assertThat(fresh.preview().path("structure").path("reused").asBoolean()).isFalse();
    }
    @Test void revokedInFlightGenerationRecordsUsageAndDoesNotPublishOrReexecute() {
        var root=authored();passGates();root=generation.view("operator",root.id());
        var pending=generation.create("operator",UUID.randomUUID(),GenerationTemplate.ID);readyThemes();var work=generation.claim();
        problemReview.hold("operator",root.problemVersion(),"원본 오류");
        assertThat(generation.view("operator",pending.id()).status()).isEqualTo("GENERATING");
        var usage=JudgeJson.parse("{\"cliVersion\":\"0.155.1\",\"output_tokens\":123}");
        var content=artifacts().put("title","도시 기록").put("context","도시의 신호를 살펴본다.");
        generation.complete(pending.id(),work.token(),content,work.reuse().path("oracle"),usage,null);
        generation.complete(pending.id(),work.token(),content,work.reuse().path("oracle"),usage,null);
        assertThat(generation.view("operator",pending.id()).status()).isEqualTo("NEEDS_REVIEW");
        assertThat(jdbc.sql("SELECT result_json FROM generation_attempt WHERE job_id=?").param(pending.id()).query(String.class).single()).contains("123");
        assertThat(jdbc.sql("SELECT count(*) FROM generation_execution WHERE job_id=?").param(pending.id()).query(Integer.class).single()).isZero();
    }
    @Test void concurrentPublicationAndRootHoldCannotLeaveAnAvailableDescendant() throws Exception {
        var root=authored();passGates();root=generation.view("operator",root.id());
        var next=generation.create("operator",UUID.randomUUID(),GenerationTemplate.ID);readyThemes();var author=generation.claim();
        generation.complete(next.id(),author.token(),artifacts().put("title","철도 관측").put("context","차량 장치의 기록을 살펴본다."),author.reuse().path("oracle"),null,null);
        var first=queue.claim(UUID.randomUUID()).orElseThrow();queue.complete(first.submissionId(),first.token(),report(first,"OK"));generation.advance();
        while(true) {var claim=queue.claim(UUID.randomUUID());if(claim.isEmpty())break;var task=claim.get();
            var verdict=jdbc.sql("SELECT expected_verdict FROM generation_execution WHERE submission_id=?").param(task.submissionId()).query(String.class).single();
            queue.complete(task.submissionId(),task.token(),report(task,verdict));}
        String rootVersion=root.problemVersion();var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var publish=pool.submit(()->{start.await();generation.advance();return true;});
            var hold=pool.submit(()->{start.await();problemReview.hold("operator",rootVersion,"동시 보류");return true;});
            start.countDown();publish.get(10,java.util.concurrent.TimeUnit.SECONDS);hold.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM problem_version WHERE id=? AND ready=true AND review_hold=false")
                .param("generated-"+next.id()+"-r0").query(Integer.class).single()).isZero();
        var result=generation.view("operator",next.id());assertThat(result.status()).isIn("READY","NEEDS_REVIEW");
        assertThat(result.problemHeld()).isTrue();
        assertThat(ledger.valid(next.id())).isFalse();
    }
    @Test void queuedDependencyHoldBlocksThemeAndAuthorClaims() {
        var root=authored();passGates();root=generation.view("operator",root.id());
        var pending=generation.create("operator",UUID.randomUUID(),GenerationTemplate.ID);
        problemReview.hold("operator",root.problemVersion(),"원본 오류");
        org.springframework.boot.test.util.TestPropertyValues.of("AI_API_ENABLED=true").applyTo(environment);
        assertThat(ai.claim()).isNull();assertThat(generation.claim()).isNull();
        assertThat(generation.view("operator",pending.id()).status()).isEqualTo("NEEDS_REVIEW");
        jdbc.sql("UPDATE generation_job SET status='THEME_FAILED' WHERE id=?").param(pending.id()).update();
        jdbc.sql("UPDATE ai_task SET status='FAILED' WHERE id=(SELECT theme_task_id FROM generation_job WHERE id=?)").param(pending.id()).update();
        assertThat(generation.retryTheme("operator",pending.id()).status()).isEqualTo("NEEDS_REVIEW");
        assertThat(jdbc.sql("SELECT status FROM ai_task WHERE id=(SELECT theme_task_id FROM generation_job WHERE id=?)").param(pending.id()).query(String.class).single()).isEqualTo("FAILED");
    }
    @Autowired GenerationEvidence executionEvidence;
    @Test void executionInputAuditTracksStoredInputsEnvironmentAndTampering() {
        var job=authored();passGates();
        var before=executionEvidence.capture(job.id(),0,GenerationType.SUM);
        var submission=jdbc.sql("SELECT submission_id FROM generation_execution WHERE job_id=? AND role='reference-0'").param(job.id()).query(UUID.class).single();
        var raw=jdbc.sql("SELECT run_package FROM submission WHERE id=?").param(submission).query(String.class).single();
        var plan=(ObjectNode)JudgeJson.parse(raw);
        plan.put("version","another-instance");
        String json=JudgeJson.canonical(plan);
        jdbc.sql("UPDATE submission SET run_package=?,run_package_sha256=? WHERE id=?").param(json).param(JudgeJson.hash(json)).param(submission).update();
        var after=executionEvidence.capture(job.id(),0,GenerationType.SUM);
        assertThat(GenerationEvidence.compare(after,before).path("matchingRoles").size()).isEqualTo(15);
        ((ObjectNode)plan.path("tests").get(0)).put("input","changed input");
        json=JudgeJson.canonical(plan);
        jdbc.sql("UPDATE submission SET run_package=?,run_package_sha256=? WHERE id=?").param(json).param(JudgeJson.hash(json)).param(submission).update();
        assertThat(GenerationEvidence.compare(executionEvidence.capture(job.id(),0,GenerationType.SUM),before).path("changedRoles").toString()).contains("reference-0");
        jdbc.sql("UPDATE submission SET run_package=?,run_package_sha256=? WHERE id=?").param(raw).param(JudgeJson.hash(raw)).param(submission).update();
        for(String field:List.of("runtime_image","runner_policy","source_sha256")) {
            String original=jdbc.sql("SELECT "+field+" FROM submission WHERE id=?").param(submission).query(String.class).single();
            jdbc.sql("UPDATE submission SET "+field+"='changed' WHERE id=?").param(submission).update();
            assertThat(GenerationEvidence.compare(executionEvidence.capture(job.id(),0,GenerationType.SUM),before).path("changedRoles").toString()).as(field).contains("reference-0");
            jdbc.sql("UPDATE submission SET "+field+"=? WHERE id=?").param(original).param(submission).update();
        }
        jdbc.sql("UPDATE judge_job SET result_sha256='corrupt' WHERE submission_id=?").param(submission).update();
        assertThat(GenerationEvidence.compare(executionEvidence.capture(job.id(),0,GenerationType.SUM),before).path("changedRoles").toString()).contains("reference-0");
    }
    @Test void structureRequiresReadyStateMatchingContractAndUntamperedEvidence() {
        var first=authored();passGates();
        String fingerprint=jdbc.sql("SELECT structure_contract FROM generation_job WHERE id=?").param(first.id()).query(String.class).single();
        for(String mismatch:java.util.List.of("policy","evidence","legacy","not-ready")) {
            jdbc.sql("UPDATE generation_job SET structure_contract=?,status='READY' WHERE id=?").param(fingerprint).param(first.id()).update();
            if(mismatch.equals("policy"))jdbc.sql("UPDATE generation_job SET structure_contract='old' WHERE id=?").param(first.id()).update();
            if(mismatch.equals("legacy"))jdbc.sql("UPDATE generation_job SET structure_contract=NULL WHERE id=?").param(first.id()).update();
            if(mismatch.equals("not-ready"))jdbc.sql("UPDATE generation_job SET status='FAILED' WHERE id=?").param(first.id()).update();
            if(mismatch.equals("evidence"))jdbc.sql("UPDATE generation_job SET artifacts_sha256='invalid' WHERE id=?").param(first.id()).update();
            var next=generation.create("operator",UUID.randomUUID(),GenerationTemplate.ID);
            assertThat(next.preview().path("structure").path("reused").asBoolean()).as(mismatch).isFalse();
            jdbc.sql("UPDATE generation_job SET status='FAILED' WHERE id=?").param(next.id()).update();
            jdbc.sql("UPDATE generation_job SET artifacts_sha256=? WHERE id=?").param(first.artifactHash()).param(first.id()).update();
        }
    }
    @Test void categoryTagsAreValidatedBeforeAnyPaidTaskAndOrderIsIdempotent() throws Exception {
        mvc.perform(get("/api/generation/options").with(user("other"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("sequences")).andExpect(jsonPath("$[1].id").value("strings"));
        for(String body:java.util.List.of(
                "{\"category\":\"sequences\",\"tags\":[\"prefix-balance\"]}",
                "{\"category\":\"unknown\",\"tags\":[\"basics\"]}",
                "{\"category\":\"sequences\",\"tags\":[]}",
                "{\"category\":\"sequences\",\"tags\":[\"overflow,edge-cases\"]}",
                "{\"category\":\"sequences\",\"template\":\"sequence-sum-v1\",\"tags\":[\"basics\"]}")) {
            mvc.perform(post("/api/generation").with(user("other")).with(csrf()).header("Idempotency-Key",UUID.randomUUID())
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        assertThat(jdbc.sql("SELECT count(*) FROM ai_task").query(Integer.class).single()).isZero();
        UUID key=UUID.randomUUID();
        for(String tags:java.util.List.of("\"overflow\",\"edge-cases\"","\"edge-cases\",\"overflow\"")) {
            mvc.perform(post("/api/generation").with(user("other")).with(csrf()).header("Idempotency-Key",key)
                    .contentType("application/json").content("{\"category\":\"sequences\",\"tags\":["+tags+"]}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.preview.structure.learningTags.length()").value(2));
        }
        assertThat(jdbc.sql("SELECT count(*) FROM ai_task").query(Integer.class).single()).isEqualTo(1);
        mvc.perform(post("/api/generation").with(user("other")).with(csrf()).header("Idempotency-Key",key)
                .contentType("application/json").content("{\"category\":\"sequences\",\"tags\":[\"basics\"]}"))
                .andExpect(status().isConflict());
        readyThemes();var assignment=generation.claim();
        assertThat(assignment.spec().path("learningFocus").asText()).contains("negative", "long");
    }
    @Test void reuseRequiresEverySelectedTagAndAcceptsVerifiedSupersets() {
        var first=generation.create("operator",UUID.randomUUID(),GenerationTemplate.ID,"overflow,edge-cases");
        readyThemes();var assignment=generation.claim();
        generation.complete(first.id(),assignment.token(),artifacts(),JudgeJson.JSON.createObjectNode().put("source","public class Main {}"),null,null);
        passGates();
        var missing=generation.create("operator",UUID.randomUUID(),GenerationTemplate.ID,"basics,overflow");
        assertThat(missing.preview().path("structure").path("reused").asBoolean()).isFalse();
        jdbc.sql("UPDATE generation_job SET status='FAILED' WHERE id=?").param(missing.id()).update();
        var subset=generation.create("operator",UUID.randomUUID(),GenerationTemplate.ID,"overflow");
        assertThat(subset.preview().path("structure").path("reused").asBoolean()).isTrue();
        assertThat(subset.preview().path("structure").path("sourceJobId").asText()).isEqualTo(first.id().toString());
    }
    @Test void composedRecipeUsesItsAnswersFullGatesAndOwnReusePartition() throws Exception {
        var selection=GenerationChoices.resolve("sequences",java.util.List.of("filter-odd","squares","edge-cases"));
        var job=generation.create("operator",UUID.randomUUID(),selection.template(),selection.focus());
        readyThemes();var work=generation.claim();
        assertThat(work.spec().path("recipe").path("transform").asText()).isEqualTo("SQUARE");
        var written=artifacts().put("title","별 관측").put("context","관측 신호 중 조건에 맞는 값을 기록한다.");
        generation.complete(job.id(),work.token(),written,JudgeJson.JSON.createObjectNode().put("source","public class Main {}"),null,null);
        passGates();job=generation.view("operator",job.id());assertThat(job.status()).isEqualTo("READY");
        assertThat(job.validation().path("executions").asInt()).isEqualTo(15);
        var packed=JudgeJson.parse(jdbc.sql("SELECT package_json FROM problem_version WHERE id=?").param(job.problemVersion()).query(String.class).single());
        assertThat(packed.path("tests").get(0).path("output").asText()).isEqualTo("10\n");
        assertThat(packed.path("statement").asText()).contains("홀수", "제곱", "10000000");
        var repeat=generation.create("operator",UUID.randomUUID(),selection.template(),selection.focus());
        assertThat(repeat.preview().path("structure").path("reused").asBoolean()).isTrue();
        jdbc.sql("UPDATE generation_job SET status='FAILED' WHERE id=?").param(repeat.id()).update();
        var distinct=GenerationChoices.resolve("sequences",java.util.List.of("filter-odd","absolute-values","edge-cases"));
        var different=generation.create("operator",UUID.randomUUID(),distinct.template(),distinct.focus());
        assertThat(different.preview().path("structure").path("reused").asBoolean()).isFalse();
        var foreign=generation.create("other",UUID.randomUUID(),selection.template(),selection.focus());
        assertThat(foreign.preview().path("structure").path("reused").asBoolean()).isFalse();
        mvc.perform(get("/api/generation/selection?category=sequences&tags=filter-odd,squares").with(user("other")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.template").value(selection.template()));
    }
    @Test void graphDeclarationRunsEveryGateBeforePublishingAndReusesOnlyMatchingOwnedContracts() {
        var selection=GenerationChoices.resolve("graphs",java.util.List.of("directed","weighted","edge-cases"));
        var first=generation.create("operator",UUID.randomUUID(),selection.template(),selection.focus());readyThemes();var author=generation.claim();
        assertThat(author.spec().path("contractFamily").asText()).isEqualTo("graph-recipe-v1");
        generation.complete(first.id(),author.token(),artifacts(),JudgeJson.JSON.createObjectNode().put("source","public class Main {}"),null,null);
        var generator=queue.claim(UUID.randomUUID()).orElseThrow();var generated=report(generator,"OK");
        ((ObjectNode)generated.path("tests").get(0)).put("stdout","1 0 1 1\n2 1 1 2 1 2 0\n2 0 2 1\n3 2 1 3 1 2 1000000000 2 3 1000000000\n");
        queue.complete(generator.submissionId(),generator.token(),generated);generation.advance();
        assertThat(generation.view("operator",first.id()).status()).isEqualTo("VALIDATING");
        while(true){var next=queue.claim(UUID.randomUUID());if(next.isEmpty())break;var task=next.get();
            String expected=jdbc.sql("SELECT expected_verdict FROM generation_execution WHERE submission_id=?").param(task.submissionId()).query(String.class).single();
            queue.complete(task.submissionId(),task.token(),report(task,expected));}
        generation.advance();var ready=generation.view("operator",first.id());assertThat(ready.status()).isEqualTo("READY");
        assertThat(ready.validation().path("executions").asInt()).isEqualTo(12);
        var pack=JudgeJson.parse(jdbc.sql("SELECT package_json FROM problem_version WHERE id=?").param(ready.problemVersion()).query(String.class).single());
        assertThat(pack.path("tests").size()).isLessThanOrEqualTo(20);assertThat(pack.path("tests").get(0).path("output").asText()).isEqualTo("7\n");
        var repeat=generation.create("operator",UUID.randomUUID(),selection.template(),selection.focus());assertThat(repeat.preview().path("structure").path("reused").asBoolean()).isTrue();
        jdbc.sql("UPDATE generation_job SET status='FAILED' WHERE id=?").param(repeat.id()).update();
        var alternative=GenerationChoices.resolve("graphs",java.util.List.of("weighted","edge-cases"));
        assertThat(generation.create("operator",UUID.randomUUID(),alternative.template(),alternative.focus()).preview().path("structure").path("reused").asBoolean()).isFalse();
        assertThat(generation.create("other",UUID.randomUUID(),selection.template(),selection.focus()).preview().path("structure").path("reused").asBoolean()).isFalse();
    }
    @Test void failedGateAllowsExactlyOneRepairAndNeverPublishes() {
        var job=authored();
        for(int revision=0;revision<2;revision++) {
            if(revision==1) {
                var assignment=generation.claim();assertThat(assignment.revision()).isEqualTo(1);
                assertThat(assignment.repair().path("fields").toString()).isEqualTo("[\"generator\"]");
                generation.complete(job.id(),assignment.token(),artifacts(),JudgeJson.JSON.createObjectNode().put("source","public class Main {}"),null,null);
            }
            job=generation.view("operator",job.id());generation.review("operator",job.id(),job.artifactHash(),true);
            var work=queue.claim(UUID.randomUUID()).orElseThrow();queue.complete(work.submissionId(),work.token(),report(work,"RE"));generation.advance();
        }
        assertThat(generation.view("operator",job.id()).status()).isEqualTo("FAILED");
        assertThat(generation.claim()).isNull();assertThat(submissions.problems("other")).hasSize(3);
    }
    @Test void userSubmissionsTakePriorityOverGenerationValidation() {
        var job=authored();generation.review("operator",job.id(),job.artifactHash(),true);
        var real=submissions.submit("other",UUID.randomUUID(),new SubmissionController.Request("total-v1","public class Main {}"));
        assertThat(queue.claim(UUID.randomUUID()).orElseThrow().submissionId()).isEqualTo(real.id());
    }
    @Test void personalGenerationSeparatesOwnersAndQueuesUsersWithoutOperatorPermission() throws Exception {
        var first=generation.create("other",UUID.randomUUID(),GenerationTemplate.ID,"overflow");
        assertThat(generation.list("other")).hasSize(1);
        assertThat(generation.list("operator")).isEmpty();
        assertThatThrownBy(()->generation.view("operator",first.id())).isInstanceOf(AccountException.class);
        assertThatThrownBy(()->generation.review("operator",first.id(),"anything",true)).isInstanceOf(AccountException.class);
        assertThatThrownBy(()->generation.create("operator",first.id(),GenerationTemplate.ID)).isInstanceOf(AccountException.class);
        assertThatThrownBy(()->generation.create("other",UUID.randomUUID(),GenerationTemplate.ID)).isInstanceOf(AccountException.class);
        generation.create("operator",UUID.randomUUID(),GenerationTemplate.ID);
        readyThemes();var work=generation.claim();
        assertThat(work.spec().path("learningFocus").asText()).contains("long");
        assertThat(generation.claim()).isNull();
        generation.complete(first.id(),work.token(),artifacts(),JudgeJson.JSON.createObjectNode().put("source","public class Main {}"),
                JudgeJson.JSON.createObjectNode().put("cliVersion","0.155.1"),null);
        assertThat(generation.view("other",first.id()).status()).isEqualTo("VALIDATING");
        assertThat(jdbc.sql("SELECT cli_version FROM generation_attempt WHERE job_id=?").param(first.id()).query(String.class).single()).isEqualTo("0.155.1");
        String version="generated-"+first.id()+"-r0";
        mvc.perform(get("/api/problems/"+version+"/teaching").with(user("operator"))).andExpect(status().isNotFound());
    }
    @Test void completedFeedbackIsOwnedCompatibleAndSnapshottedBeforeGeneration() {
        UUID submission=submissions.submit("other",UUID.randomUUID(),new SubmissionController.Request("total-v1","public class Main {}")).id();
        UUID analysis=UUID.randomUUID();
        jdbc.sql("INSERT INTO ai_task (id,user_id,submission_id,kind,cache_key,settings_json,input_json,status,result_json) VALUES (?,?,?,'ANALYSIS',?,'{}','{}','COMPLETED',?)")
                .param(analysis).param(submissions.owner("other",false)).param(submission).param(JudgeJson.hash(analysis.toString())).param(AiIntegrationTest.feedback().toString()).update();
        assertThat(generation.learningOptions("other")).hasSize(1);
        // Exercise eligibility independently from the owner-only experimental hold endpoint.
        jdbc.sql("UPDATE problem_version SET review_hold=true WHERE id='total-v1'").update();
        try {
            assertThat(generation.learningOptions("other")).isEmpty();
            assertThatThrownBy(()->generation.create("other",UUID.randomUUID(),GenerationTemplate.ID,"overflow",analysis)).isInstanceOf(AccountException.class);
        } finally { jdbc.sql("UPDATE problem_version SET review_hold=false WHERE id='total-v1'").update(); }

        assertThat(generation.learningOptions("operator")).isEmpty();
        assertThatThrownBy(()->generation.create("operator",UUID.randomUUID(),GenerationTemplate.ID,"overflow",analysis)).isInstanceOf(AccountException.class);
        jdbc.sql("UPDATE ai_task SET status='RUNNING' WHERE id=?").param(analysis).update();
        assertThatThrownBy(()->generation.create("other",UUID.randomUUID(),GenerationTemplate.ID,"overflow",analysis)).isInstanceOf(AccountException.class);
        jdbc.sql("UPDATE ai_task SET status='COMPLETED' WHERE id=?").param(analysis).update();
        UUID generationKey=UUID.randomUUID();
        assertThatThrownBy(()->generation.create("other",UUID.randomUUID(),"parentheses-v1","basics",analysis)).isInstanceOf(AccountException.class);
        generation.create("other",generationKey,GenerationTemplate.ID,"overflow",analysis);
        jdbc.sql("UPDATE ai_task SET result_json='{}' WHERE id=?").param(analysis).update();
        assertThat(generation.create("other",generationKey,GenerationTemplate.ID,"overflow",analysis).id()).isEqualTo(generationKey);
        assertThatThrownBy(()->generation.create("other",generationKey,GenerationTemplate.ID,"basics",null)).isInstanceOf(AccountException.class);
        readyThemes();var work=generation.claim();
        assertThat(work.spec().path("learnerFeedback").path("summary").asText()).isEqualTo("확인한 피드백");
        assertThat(work.spec().path("learnerFeedback").has("source")).isFalse();
    }
    @Test void oracleFailureRepairsOnlyOracleAndRejectsChangesToPreservedArtifacts() {
        var job=authored();
        var generator=queue.claim(UUID.randomUUID()).orElseThrow();
        queue.complete(generator.submissionId(),generator.token(),report(generator,"OK"));generation.advance();
        while(true) {
            var next=queue.claim(UUID.randomUUID());if(next.isEmpty())break;
            var work=next.get();
            String role=jdbc.sql("SELECT role FROM generation_execution WHERE submission_id=?").param(work.submissionId()).query(String.class).single();
            String expected=jdbc.sql("SELECT expected_verdict FROM generation_execution WHERE submission_id=?").param(work.submissionId()).query(String.class).single();
            queue.complete(work.submissionId(),work.token(),report(work,role.contains("oracle")?"CE":expected));
        }
        generation.advance();
        var repair=generation.claim();
        assertThat(repair.revision()).isEqualTo(1);
        assertThat(repair.repair().path("fields").toString()).isEqualTo("[\"oracle\"]");
        assertThat(repair.repair().path("artifacts")).isEqualTo(job.artifacts());
        var changed=artifacts().put("reference","tampered");
        var fixed=JudgeJson.JSON.createObjectNode().put("source","public class Main { /* fixed */ }");
        assertThatThrownBy(()->generation.complete(job.id(),repair.token(),changed,fixed,null,null)).isInstanceOf(AccountException.class);
        generation.complete(job.id(),repair.token(),job.artifacts(),fixed,null,null);
        assertThat(generation.view("operator",job.id()).status()).isEqualTo("VALIDATING");
        assertThat(generation.view("operator",job.id()).artifacts()).isEqualTo(job.artifacts());
        assertThat(submissions.problems("operator")).hasSize(3);
    }
    @Test void runnerInfrastructureFailureDoesNotSpendARepairOrRegenerateContent() {
        var job=authored();var work=queue.claim(UUID.randomUUID()).orElseThrow();
        queue.complete(work.submissionId(),work.token(),report(work,"IE"));generation.advance();
        assertThat(generation.view("operator",job.id()).status()).isEqualTo("NEEDS_REVIEW");
        assertThat(generation.view("operator",job.id()).revision()).isZero();
        assertThat(generation.claim()).isNull();
    }
    @Test void authenticationSeparatesGenerationWorkerJudgeWorkerAndOrdinaryUsers() throws Exception {
        mvc.perform(get("/api/generation").with(user("other"))).andExpect(status().isOk());
        mvc.perform(post("/internal/generation/claim").header("Authorization","Bearer judge-test-token-32-characters-long")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/judge/claim").header("Authorization","Bearer generation-test-token-32-characters-long").contentType("application/json").content("{\"workerId\":\""+UUID.randomUUID()+"\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/generation/claim").with(user("operator"))).andExpect(status().isForbidden());
    }
}
