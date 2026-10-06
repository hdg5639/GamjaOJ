package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamjaoj.ai.OpenAiResponses;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static dev.gamjaoj.HybridGeneration.Role.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:hybridpublication;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa","spring.datasource.password=","gamjaoj.invite-code=test",
        "HYBRID_CONTENT_REVIEW_ENABLED=true","HYBRID_VALIDATION_PROFILE=hybrid-zero-one-items-v3",
        "AI_HYBRID_REVIEW_MODEL=fixture-review","AI_HYBRID_REVIEW_REASONING=low",
        "AI_HYBRID_REVIEW_INPUT_USD_PER_M=1","AI_HYBRID_REVIEW_CACHED_USD_PER_M=0.1",
        "AI_HYBRID_REVIEW_OUTPUT_USD_PER_M=2","AI_HYBRID_REVIEW_MAX_OUTPUT_TOKENS=4096","AI_HYBRID_REVIEW_PRICING_VERSION=fixture",
        "AI_API_ENABLED=true","OPENAI_API_KEY=fixture-no-network","HYBRID_ADMISSION_ENABLED=true",
        "HYBRID_API_WORKER_ENABLED=false","AI_POLL_MS=3600000",
        "GENERATION_WORKER_TOKEN=fixture-generation-worker-token-12345678",
        "AI_HYBRID_WRITER_MODEL=fixture-writer","AI_HYBRID_WRITER_REASONING=low",
        "AI_HYBRID_WRITER_INPUT_USD_PER_M=1","AI_HYBRID_WRITER_CACHED_USD_PER_M=0.1",
        "AI_HYBRID_WRITER_OUTPUT_USD_PER_M=2","AI_HYBRID_WRITER_MAX_OUTPUT_TOKENS=4096","AI_HYBRID_WRITER_PRICING_VERSION=fixture",
        "AI_HYBRID_READER_MODEL=fixture-reader","AI_HYBRID_READER_REASONING=low",
        "AI_HYBRID_READER_INPUT_USD_PER_M=1","AI_HYBRID_READER_CACHED_USD_PER_M=0.1",
        "AI_HYBRID_READER_OUTPUT_USD_PER_M=2","AI_HYBRID_READER_MAX_OUTPUT_TOKENS=4096","AI_HYBRID_READER_PRICING_VERSION=fixture"})
class HybridPublicationIntegrationTest {
    @Autowired HybridExecution execution;@Autowired HybridGeneration jobs;@Autowired HybridPublication publication;
    @Autowired HybridRunnerChecks checks;@Autowired JudgeQueue queue;@Autowired JdbcClient jdbc;
    @Autowired AiTasks ledger;@Autowired ConfigurableEnvironment env;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @MockitoBean HybridApiProvider provider;
    final HybridGenerationIntegrationTest f=new HybridGenerationIntegrationTest();
    final HybridRunnerIntegrationTest runner=new HybridRunnerIntegrationTest();
    final Map<String,Object> overrides=new HashMap<>();
    @BeforeEach void setup() {
        env.getPropertySources().remove("publication-test");overrides.clear();
        env.getPropertySources().addFirst(new MapPropertySource("publication-test",overrides));
        runner.jdbc=jdbc;runner.checks=checks;runner.hybrid=jobs;runner.queue=queue;runner.env=env;
        jdbc.sql("DELETE FROM generation_recovery_receipt").update();jdbc.sql("DELETE FROM generation_recovery_attempt").update();
        runner.setup();jdbc.sql("DELETE FROM ai_attempt").update();jdbc.sql("DELETE FROM ai_task").update();
    }
    @AfterEach void reset(){env.getPropertySources().remove("publication-test");verifyNoInteractions(provider);}
    UUID admit(boolean shared){UUID id=UUID.randomUUID();execution.admit("owner",id,"new problem",shared);return id;}
    void codex(JsonNode payload){var request=execution.claimCodex();var a=JudgeJson.JSON.convertValue(request.spec().path("assignment"),HybridGeneration.Assignment.class);execution.completeCodex(f.result(a,payload));}
    OpenAiResponses.Result result(JsonNode payload){return new OpenAiResponses.Result(payload,JudgeJson.JSON.createObjectNode().put("input_tokens",100).put("output_tokens",50),"fixture-response","fixture-request","fixture-model");}
    UUID checked(boolean shared) {
        UUID id=admit(shared);codex(f.contract());codex(f.core());
        var writer=execution.claimApi();execution.finish(writer.attemptId(),result(f.presentation()),null);
        var reader=execution.claimApi();assertThat(reader.request().input()).doesNotContain("PRIVATE_");execution.finish(reader.attemptId(),result(f.reader()),null);
        assertThat(ledger.budget().reservedUsd()).isPositive();
        for(int stage=0;stage<9;stage++) {
            checks.advance();Optional<JudgeQueue.Assignment> next;
            while((next=queue.claim(UUID.randomUUID())).isPresent()) {
                var a=next.get();String role=runner.role(a);
                runner.complete(a,role.equals("package-generator")?"OK":role.startsWith("mutant-")?"WA":"AC",role.equals("package-generator")?HybridRunnerIntegrationTest.GENERATED:"");
            }
        }
        checks.advance();assertThat(jobs.view("owner",id).error()).isEqualTo("CONTENT_REVIEW_REQUIRED");assertThat(runner.jobs()).isEqualTo(15);return id;
    }
    /** Runs validation stages; reader-code checks answer WA while failures remain, everything else passes. */
    int[] validate(int[] failures) {
        for(int stage=0;stage<9;stage++) {
            checks.advance();Optional<JudgeQueue.Assignment> next;
            while((next=queue.claim(UUID.randomUUID())).isPresent()) {
                var a=next.get();String role=runner.role(a);
                boolean fail=HybridRunnerChecks.readerCode(role)&&failures[0]>0;if(fail)failures[0]--;
                runner.complete(a,role.equals("package-generator")?"OK":role.startsWith("mutant-")||fail?"WA":"AC",role.equals("package-generator")?HybridRunnerIntegrationTest.GENERATED:"");
            }
            checks.advance();
            if(!"VALIDATING".equals(jobs.view("owner",lastId).status())&&!"HELD".equals(jobs.view("owner",lastId).status()))break;
            if("HELD".equals(jobs.view("owner",lastId).status())&&!"VALIDATION_ADAPTER_NOT_CONNECTED".equals(jobs.view("owner",lastId).error()))break;
        }
        return failures;
    }
    UUID lastId;
    int readers(UUID id){return jdbc.sql("SELECT count(*) FROM hybrid_branch WHERE generation_id=? AND role='READER'").param(id).query(Integer.class).single();}
    @Test void readerCodeFailureRetriesAFreshReaderAndContinues() {
        UUID id=admit(false);lastId=id;codex(f.contract());codex(f.core());
        var writer=execution.claimApi();execution.finish(writer.attemptId(),result(f.presentation()),null);
        var reader=execution.claimApi();execution.finish(reader.attemptId(),result(f.reader()),null);
        validate(new int[]{1});
        assertThat(jobs.view("owner",id).status()).isEqualTo("BUILDING");assertThat(readers(id)).isEqualTo(2);
        var retry=execution.claimApi();assertThat(retry.request().assignment().role()).isEqualTo(READER);
        assertThat(retry.request().settings().effort()).isEqualTo("low"); // first retry: same reader model
        execution.finish(retry.attemptId(),result(f.reader()),null);
        validate(new int[]{0});
        assertThat(jobs.view("owner",id).error()).isEqualTo("CONTENT_REVIEW_REQUIRED");
        assertThat(jdbc.sql("SELECT count(*) FROM hybrid_branch WHERE generation_id=? AND role='VALIDATION' AND status='SUPERSEDED'").param(id).query(Integer.class).single()).isOne();
    }
    @Test void secondRetryUsesTheStrongerReaderAndAThirdFailureHolds() {
        UUID id=admit(false);lastId=id;codex(f.contract());codex(f.core());
        var writer=execution.claimApi();execution.finish(writer.attemptId(),result(f.presentation()),null);
        var reader=execution.claimApi();execution.finish(reader.attemptId(),result(f.reader()),null);
        validate(new int[]{1});
        var first=execution.claimApi();execution.finish(first.attemptId(),result(f.reader()),null);
        validate(new int[]{1});
        var second=execution.claimApi();assertThat(second.request().settings().effort()).isEqualTo("high"); // escalated
        execution.finish(second.attemptId(),result(f.reader()),null);
        validate(new int[]{1});
        assertThat(readers(id)).isEqualTo(3);
        assertThat(jobs.view("owner",id).status()).isEqualTo("HELD");assertThat(jobs.view("owner",id).error()).isEqualTo("RUNNER_WA");
        assertThat(execution.claimApi()).isNull();
    }
    HybridExecution.Work review(){publication.advance();var work=execution.claimApi();assertThat(work).isNotNull();assertThat(work.request().assignment().role()).isEqualTo(CONTENT_REVIEW);return work;}
    com.fasterxml.jackson.databind.node.ObjectNode accepted(HybridExecution.Work work) {
        var p=JudgeJson.JSON.createObjectNode().put("schemaVersion","1").put("inputHash",work.request().assignment().inputHash())
                .put("proseEquivalent",true).put("teachingCorrect",true).put("implementationAligned",true).put("reasoning","Fixture content review, not provider semantic evidence.");p.putArray("issues");if(work.request().assignment().input().has("requirements"))p.set("requirementsReview",GenerationRequirementsTest.accepted());if(work.request().assignment().input().has("thinkingRubric"))p.set("thinking",ThinkingDifficulty.template(GenerationType.SUM));return p;
    }
    @Test void originalRequestIsFrozenIntoReviewAndFidelityRejectionPreventsPublication() {
        UUID id=checked(false);var work=review();
        assertThat(work.request().assignment().input().has("requirements")).isTrue();
        assertThat(work.request().schema().path("properties").has("requirementsReview")).isTrue();
        String frozen=jdbc.sql("SELECT input_json FROM hybrid_branch WHERE id=?").param(work.request().assignment().branchId()).query(String.class).single();
        publication.advance();assertThat(jdbc.sql("SELECT input_json FROM hybrid_branch WHERE id=?").param(work.request().assignment().branchId()).query(String.class).single()).isEqualTo(frozen);
        var payload=accepted(work);var assessment=(com.fasterxml.jackson.databind.node.ObjectNode)payload.path("requirementsReview");
        assessment.put("satisfied",false);assessment.withArray("issues").add("방문 개수와 복합 알고리즘 요구를 축소했습니다.");
        var response=result(payload);execution.finish(work.attemptId(),response,null);execution.finish(work.attemptId(),response,null);publication.advance();
        assertThat(jobs.view("owner",id).status()).isEqualTo("HELD");assertThat(jobs.view("owner",id).error()).isEqualTo("REQUIREMENTS_NOT_MET");
        assertThat(published()).isZero();assertThat(execution.claimApi()).isNull();
    }
    @Test void explicitReviewRetryPreservesPriorReceiptAndPublishesWithoutRepeatingRunnerChecks() {
        UUID id=checked(false);var first=review();var rejected=accepted(first);
        var assessment=(com.fasterxml.jackson.databind.node.ObjectNode)rejected.path("requirementsReview");
        assessment.put("satisfied",false);assessment.withArray("issues").add("Fixture rejected interpretation");
        execution.finish(first.attemptId(),result(rejected),null);
        String oldReceipt=jdbc.sql("SELECT receipt_json FROM hybrid_api_reservation WHERE attempt_id=?").param(first.attemptId()).query(String.class).single();
        int checksBefore=runner.jobs();UUID attempt=UUID.randomUUID();
        jdbc.sql("INSERT INTO ai_attempt(id,month_key,status,reserved_usd,settings_json) SELECT ?,month_key,'HYBRID_RESERVED',reserved_usd,settings_json FROM ai_attempt WHERE id=?").param(attempt).param(first.attemptId()).update();
        jdbc.sql("INSERT INTO hybrid_api_reservation(attempt_id,generation_id,revision,role,retry) VALUES (?,?,0,'CONTENT_REVIEW',1)").param(attempt).param(id).update();
        jdbc.sql("UPDATE hybrid_generation SET status='REVIEWING',error_code=NULL WHERE id=?").param(id).update();
        publication.advance();var second=execution.claimApi();assertThat(second).isNotNull();
        assertThat(second.request().assignment().branchId()).isNotEqualTo(first.request().assignment().branchId());
        assertThat(jdbc.sql("SELECT attempt FROM hybrid_branch WHERE id=?").param(second.request().assignment().branchId()).query(Integer.class).single()).isEqualTo(1);
        assertThat(second.request().assignment().inputHash()).isEqualTo(first.request().assignment().inputHash());
        execution.finish(second.attemptId(),result(accepted(second)),null);publication.advance();
        assertThat(jobs.view("owner",id).status()).isEqualTo("PUBLISHED");assertThat(runner.jobs()).isEqualTo(checksBefore);
        assertThat(jdbc.sql("SELECT receipt_json FROM hybrid_api_reservation WHERE attempt_id=?").param(first.attemptId()).query(String.class).single()).isEqualTo(oldReceipt);
        assertThat(jdbc.sql("SELECT status FROM hybrid_branch WHERE id=?").param(first.request().assignment().branchId()).query(String.class).single()).isEqualTo("FAILED");
    }
    @Test void writerFailureBeforeAnyReaderKeepsUnusedReservationsAndCanResumeWithoutSlotCollision() {
        UUID id=admit(false);jdbc.sql("UPDATE hybrid_generation SET resource_validation=true WHERE id=?").param(id).update();codex(f.contract());codex(f.core());
        var writer=execution.claimApi();execution.finish(writer.attemptId(),result(JudgeJson.JSON.createObjectNode()),null);
        execution.recover();var retry=execution.claimApi();assertThat(retry.request().assignment().role()).isEqualTo(PRESENTATION);
        execution.finish(retry.attemptId(),result(f.presentation()),null);
        var reader=execution.claimApi();assertThat(reader.request().assignment().role()).isEqualTo(READER);
        assertThat(jdbc.sql("SELECT retry FROM hybrid_api_reservation WHERE attempt_id=?").param(reader.attemptId()).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM hybrid_api_reservation r JOIN ai_attempt a ON a.id=r.attempt_id WHERE r.generation_id=? AND r.retry=0 AND r.role IN ('READER','CONTENT_REVIEW') AND a.status='HYBRID_RELEASED'").param(id).query(Integer.class).single()).isEqualTo(2);
    }
    @Test void automaticProseRepairPreservesCoreAndReservesOnlyWriterReaderAndReview() {
        UUID id=checked(false);jdbc.sql("UPDATE hybrid_generation SET resource_validation=true WHERE id=?").param(id).update();
        String core=jdbc.sql("SELECT payload_sha256 FROM hybrid_artifact a JOIN hybrid_branch b ON b.id=a.branch_id WHERE b.generation_id=? AND b.role='CORE'").param(id).query(String.class).single();
        var work=review();var payload=accepted(work);payload.put("proseEquivalent",false);payload.withArray("issues").add("본문의 경계 규칙 설명을 보완하세요.");
        execution.finish(work.attemptId(),result(payload),null);String receipt=jdbc.sql("SELECT receipt_json FROM hybrid_api_reservation WHERE attempt_id=?").param(work.attemptId()).query(String.class).single();
        execution.recover();assertThat(jobs.view("owner",id).status()).isEqualTo("BUILDING");
        assertThat(jdbc.sql("SELECT count(*) FROM hybrid_branch WHERE generation_id=? AND role='CORE'").param(id).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT payload_sha256 FROM hybrid_artifact a JOIN hybrid_branch b ON b.id=a.branch_id WHERE b.generation_id=? AND b.role='CORE'").param(id).query(String.class).single()).isEqualTo(core);
        var writer=execution.claimApi();assertThat(writer.request().assignment().role()).isEqualTo(PRESENTATION);assertThat(writer.request().input()).contains("recoveryFeedback");
        assertThat(jdbc.sql("SELECT count(*) FROM hybrid_api_reservation WHERE generation_id=? AND retry=1").param(id).query(Integer.class).single()).isEqualTo(3);
        assertThat(ledger.budget().reservedUsd()).isPositive();
        execution.finish(work.attemptId(),result(payload),null);assertThat(jdbc.sql("SELECT receipt_json FROM hybrid_api_reservation WHERE attempt_id=?").param(work.attemptId()).query(String.class).single()).isEqualTo(receipt);
        assertThat(jdbc.sql("SELECT count(*) FROM generation_recovery_attempt WHERE job_id=?").param(id).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM problem_version WHERE id LIKE 'hybrid-check-%' AND ready=true").query(Integer.class).single()).isZero();
    }
    @Test void repairedEditorialIsRevalidatedAndReviewedWithFreshBindings() {
        UUID id=checked(false);var first=review();var rejected=accepted(first);
        var assessment=(com.fasterxml.jackson.databind.node.ObjectNode)rejected.path("requirementsReview");
        assessment.put("satisfied",false);assessment.withArray("issues").add("Editorial storage mismatch");
        execution.finish(first.attemptId(),result(rejected),null);
        publication.advance();assertThat(execution.claimApi()).isNull(); // No automatic paid retry.
        UUID presentation=UUID.randomUUID(),validation=UUID.randomUUID(),attempt=UUID.randomUUID();
        var payload=f.presentation().deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)payload).put("editorial","Corrected storage explanation");
        String raw=JudgeJson.canonical(payload),hash=JudgeJson.hash(raw);
        jdbc.sql("INSERT INTO hybrid_branch(id,generation_id,revision,role,attempt,status,input_json,input_sha256,contract_sha256,public_sha256,output_sha256,created_at) SELECT ?,generation_id,revision,role,1,status,input_json,input_sha256,contract_sha256,public_sha256,?,CURRENT_TIMESTAMP FROM hybrid_branch WHERE generation_id=? AND role='PRESENTATION'")
                .param(presentation).param(hash).param(id).update();
        jdbc.sql("INSERT INTO hybrid_artifact(branch_id,schema_version,prompt_version,payload_json,payload_sha256,created_at) SELECT ?,schema_version,prompt_version,?,?,CURRENT_TIMESTAMP FROM hybrid_artifact WHERE branch_id=(SELECT id FROM hybrid_branch WHERE generation_id=? AND role='PRESENTATION' AND attempt=0)")
                .param(presentation).param(raw).param(hash).param(id).update();
        var manifest=JudgeJson.parse(jdbc.sql("SELECT input_json FROM hybrid_branch WHERE generation_id=? AND role='VALIDATION'").param(id).query(String.class).single());
        ((com.fasterxml.jackson.databind.node.ObjectNode)manifest.path("artifacts")).put("PRESENTATION",hash);
        String manifestRaw=JudgeJson.canonical(manifest);
        jdbc.sql("INSERT INTO hybrid_branch(id,generation_id,revision,role,attempt,status,input_json,input_sha256,contract_sha256,public_sha256,created_at) SELECT ?,generation_id,revision,role,1,'BLOCKED',?,?,contract_sha256,public_sha256,CURRENT_TIMESTAMP FROM hybrid_branch WHERE generation_id=? AND role='VALIDATION' AND attempt=0")
                .param(validation).param(manifestRaw).param(JudgeJson.hash(manifestRaw)).param(id).update();
        jdbc.sql("UPDATE hybrid_branch SET status='SUPERSEDED' WHERE generation_id=? AND role='VALIDATION' AND attempt=0").param(id).update();
        jdbc.sql("INSERT INTO ai_attempt(id,month_key,status,reserved_usd,settings_json) SELECT ?,month_key,'HYBRID_RESERVED',reserved_usd,settings_json FROM ai_attempt WHERE id=?").param(attempt).param(first.attemptId()).update();
        jdbc.sql("INSERT INTO hybrid_api_reservation(attempt_id,generation_id,revision,role,retry) VALUES (?,?,0,'CONTENT_REVIEW',1)").param(attempt).param(id).update();
        jdbc.sql("UPDATE hybrid_generation SET status='HELD',error_code='VALIDATION_ADAPTER_NOT_CONNECTED' WHERE id=?").param(id).update();
        lastId=id;validate(new int[]{0});publication.advance();var second=execution.claimApi();assertThat(second).isNotNull();
        assertThat(second.request().assignment().inputHash()).isNotEqualTo(first.request().assignment().inputHash());
        assertThat(second.request().input()).contains("Corrected storage explanation");
        assertThat(runner.jobs()).isEqualTo(30);
        execution.finish(second.attemptId(),result(accepted(second)),null);publication.advance();
        assertThat(jobs.view("owner",id).status()).isEqualTo("PUBLISHED");
        assertThat(jobs.view("owner",id).publishedVersionId()).isEqualTo("hybrid-check-"+validation);
        assertThat(jdbc.sql("SELECT status FROM hybrid_branch WHERE id=?").param(first.request().assignment().branchId()).query(String.class).single()).isEqualTo("FAILED");
    }
    @Test void missingFidelityResultCannotPassNewReview() {
        UUID id=checked(false);var work=review();var payload=accepted(work);payload.remove("requirementsReview");
        execution.finish(work.attemptId(),result(payload),null);publication.advance();
        assertThat(jobs.view("owner",id).status()).isEqualTo("HELD");assertThat(published()).isZero();
    }
    @Test void reviewedLanguageLimitsAreRequiredAndPersistWithPublishedVersion() {
        UUID id=checked(false);var work=review();
        assertThat(work.request().assignment().input().path("requirements").path("timeEvidence").path("javaMaxWallMs").asLong()).isPositive();
        var payload=accepted(work);
        ((com.fasterxml.jackson.databind.node.ObjectNode)payload.path("requirementsReview")).set("timeLimits",JudgeJson.parse(ProblemTimeLimitsTest.limits(2,1,4)));
        execution.finish(work.attemptId(),result(payload),null);publication.advance();
        assertThat(jobs.view("owner",id).status()).isEqualTo("PUBLISHED");
        String version=jobs.view("owner",id).publishedVersionId();
        var publishedLimits=JudgeJson.parse(jdbc.sql("SELECT time_limits_json FROM problem_version WHERE id=?").param(version).query(String.class).single());
        assertThat(publishedLimits.path("CPP").asDouble()).isEqualTo(3);
        assertThat(publishedLimits.path("JAVA").asDouble()).isEqualTo(5);
        assertThat(publishedLimits.path("PYTHON").asDouble()).isEqualTo(8);
    }
    int published(){return jdbc.sql("SELECT count(*) FROM problem_version WHERE id LIKE 'hybrid-check-%' AND ready=true").query(Integer.class).single();}
    @Test void allThreeRolesMustFitBudgetAndReviewConfigurationBeforeAdmission() {
        overrides.put("AI_MONTHLY_BUDGET_USD","0.7");assertThatThrownBy(()->admit(false)).isInstanceOf(AccountException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM ai_attempt").query(Integer.class).single()).isZero();
        overrides.put("AI_MONTHLY_BUDGET_USD","10");overrides.put("AI_HYBRID_REVIEW_MODEL","");
        assertThatThrownBy(()->admit(false)).isInstanceOf(AccountException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM hybrid_generation").query(Integer.class).single()).isZero();
    }
    @Test void missingThinkingReviewCannotPublishOrSpendAnotherCall() {
        UUID id=checked(false);var work=review();var payload=accepted(work);payload.remove("thinking");
        execution.finish(work.attemptId(),result(payload),null);publication.advance();
        assertThat(published()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM problem_thinking_profile WHERE problem_version LIKE 'hybrid-check-%'").query(Integer.class).single()).isZero();
        assertThat(execution.claimApi()).isNull();
    }
    @ParameterizedTest @ValueSource(booleans={false,true}) void publishesFrozenPackageOnceWithOwnerVisibilityAndPinnedBudget(boolean shared) {
        UUID id=checked(shared);var deadline=jobs.view("owner",id).deadlineAt();
        overrides.put("AI_HYBRID_REVIEW_MODEL","changed");overrides.put("HYBRID_CONTENT_REVIEW_ENABLED","false");
        var work=review();assertThat(work.request().settings().model()).isEqualTo("fixture-review");assertThat(work.deadlineAt()).isEqualTo(deadline);
        assertThat(work.request().input()).contains("PRIVATE_");assertThat(published()).isZero();
        var result=result(accepted(work));execution.finish(work.attemptId(),result,null);execution.finish(work.attemptId(),result,null);
        publication.advance();checks.advance();execution.recover();
        assertThat(jobs.view("owner",id).status()).isEqualTo("PUBLISHED");assertThat(published()).isEqualTo(1);assertThat(runner.jobs()).isEqualTo(15);
        String version=jobs.view("owner",id).publishedVersionId();assertThat(version).startsWith("hybrid-check-");
        assertThat(jdbc.sql("SELECT count(*) FROM problem_thinking_profile t JOIN problem_version p ON p.id=t.problem_version AND p.package_sha256=t.package_sha256 WHERE p.id=? AND t.assessment_kind='MODEL'").param(version).query(Integer.class).single()).isOne();
        publication.advance();assertThat(jdbc.sql("SELECT count(*) FROM problem_thinking_profile WHERE problem_version=?").param(version).query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT shared FROM problem_version WHERE id=?").param(version).query(Boolean.class).single()).isEqualTo(shared);
        assertThat(jdbc.sql("SELECT count(*) FROM problem_version p JOIN hybrid_generation g ON p.owner_id=g.owner_id AND p.id=g.published_version_id WHERE g.id=?").param(id).query(Integer.class).single()).isEqualTo(1);
        assertThat(ledger.budget().spentUsd()).isEqualByComparingTo("0.0006");assertThat(ledger.budget().reservedUsd()).isZero();assertThat(execution.claimApi()).isNull();
        jobs.cancel("owner",id);assertThat(jobs.view("owner",id).status()).isEqualTo("PUBLISHED");
    }
    @ParameterizedTest @ValueSource(strings={"rejected","issues","missing","wrong-hash"}) void rejectedOrIncompleteReviewSettlesCostWithoutPublishing(String failure) {
        UUID id=checked(false);var work=review();var payload=accepted(work);
        switch(failure){case "rejected"->payload.put("teachingCorrect",false);case "issues"->payload.withArray("issues").add("unsupported explanation");case "missing"->payload.remove("implementationAligned");default->payload.put("inputHash","wrong");}
        execution.finish(work.attemptId(),result(payload),null);
        assertThat(jobs.view("owner",id).status()).isEqualTo("HELD");assertThat(published()).isZero();assertThat(runner.jobs()).isEqualTo(15);
        assertThat(ledger.budget().spentUsd()).isEqualByComparingTo("0.0006");assertThat(execution.claimApi()).isNull();
    }
    @ParameterizedTest @ValueSource(strings={"package","teaching","source","report","runtime","shared","contract","profile","missing-evidence","owner","language"}) void changedDependenciesCannotPublishOrLosePaidReceipt(String change) {
        UUID id=checked(false);var work=review();
        switch(change) {
            case "package"->jdbc.sql("UPDATE problem_version SET package_json='{}' WHERE id LIKE 'hybrid-check-%'").update();
            case "teaching"->jdbc.sql("UPDATE problem_version SET teaching_json='{}' WHERE id LIKE 'hybrid-check-%'").update();
            case "source"->jdbc.sql("UPDATE submission SET source_code='changed' WHERE hybrid_branch_id IS NOT NULL").update();
            case "report"->jdbc.sql("UPDATE judge_job SET result_json='{}' WHERE submission_id IN (SELECT submission_id FROM hybrid_execution_check)").update();
            case "runtime"->jdbc.sql("UPDATE problem_version SET runtime_image='changed' WHERE id LIKE 'hybrid-check-%'").update();
            case "shared"->jdbc.sql("UPDATE hybrid_generation SET share_on_publish=true WHERE id=?").param(id).update();
            case "contract"->jdbc.sql("UPDATE hybrid_generation SET contract_sha256=? WHERE id=?").param("0".repeat(64)).param(id).update();
            case "missing-evidence"->jdbc.sql("DELETE FROM hybrid_package_evidence").update();
            case "owner"->jdbc.sql("UPDATE problem_version SET owner_id=NULL WHERE id LIKE 'hybrid-check-%'").update();
            case "language"->jdbc.sql("UPDATE submission SET language='CPP' WHERE hybrid_branch_id IS NOT NULL").update();
            default->jdbc.sql("UPDATE hybrid_validation_profile SET profile_hash=?").param("0".repeat(64)).update();
        }
        execution.finish(work.attemptId(),result(accepted(work)),null);
        assertThat(jobs.view("owner",id).status()).isEqualTo("HELD");assertThat(published()).isZero();assertThat(runner.jobs()).isEqualTo(15);
        assertThat(ledger.budget().spentUsd()).isEqualByComparingTo("0.0006");
        assertThat(jdbc.sql("SELECT receipt_json FROM hybrid_api_reservation WHERE attempt_id=?").param(work.attemptId()).query(String.class).single()).isNotBlank();
    }
    @Test void noReservationCannotDispatchReviewAndExpiredHeldReservationIsReleased() {
        UUID id=checked(false);jdbc.sql("UPDATE hybrid_generation SET deadline_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE id=?").param(id).update();
        execution.recover();assertThat(jobs.view("owner",id).status()).isEqualTo("DEADLINE_EXCEEDED");assertThat(execution.claimApi()).isNull();assertThat(ledger.budget().reservedUsd()).isZero();assertThat(published()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"cancel","deadline","unknown"}) void lateOrUnknownReviewCannotPublish(String stop) {
        UUID id=checked(false);var work=review();
        if(stop.equals("cancel"))jobs.cancel("owner",id);
        if(stop.equals("deadline")){jdbc.sql("UPDATE hybrid_generation SET deadline_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE id=?").param(id).update();execution.recover();}
        if(stop.equals("unknown"))execution.finish(work.attemptId(),null,new OpenAiResponses.Failure("TRANSPORT_USAGE_UNKNOWN",null,null));
        else execution.finish(work.attemptId(),result(accepted(work)),null);
        assertThat(published()).isZero();assertThat(runner.jobs()).isEqualTo(15);
        if(stop.equals("unknown")){assertThat(ledger.budget().reservedUsd()).isPositive();assertThat(ledger.budget().spentUsd()).isEqualByComparingTo("0.0004");}
        else {assertThat(ledger.budget().reservedUsd()).isZero();assertThat(ledger.budget().spentUsd()).isEqualByComparingTo("0.0006");}
    }
    @Test void restartAfterReviewQueuedUsesStoredInputAndOneReservedCall() {
        UUID id=checked(false);publication.advance();
        String before=jdbc.sql("SELECT input_sha256 FROM hybrid_branch WHERE role='CONTENT_REVIEW'").query(String.class).single();
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->new HybridPublication(jdbc,checks,event->{},new HybridRuleRegistry(jdbc)).advance());
        var work=execution.claimApi();assertThat(work.request().assignment().inputHash()).isEqualTo(before);
        execution.finish(work.attemptId(),result(accepted(work)),null);publication.advance();
        assertThat(jobs.view("owner",id).status()).isEqualTo("PUBLISHED");assertThat(runner.jobs()).isEqualTo(15);
        assertThat(jdbc.sql("SELECT count(*) FROM hybrid_branch WHERE role='CONTENT_REVIEW'").query(Integer.class).single()).isEqualTo(1);
    }
    @Test void unreservedReviewCannotBeAddedAfterChecksFinish() {
        overrides.put("HYBRID_CONTENT_REVIEW_ENABLED","false");
        UUID id=admit(false);codex(f.contract());codex(f.core());
        var writer=execution.claimApi();execution.finish(writer.attemptId(),result(f.presentation()),null);
        var reader=execution.claimApi();execution.finish(reader.attemptId(),result(f.reader()),null);
        for(int stage=0;stage<9;stage++) {
            checks.advance();Optional<JudgeQueue.Assignment> next;
            while((next=queue.claim(UUID.randomUUID())).isPresent()) {
                var a=next.get();String role=runner.role(a);runner.complete(a,role.equals("package-generator")?"OK":role.startsWith("mutant-")?"WA":"AC",role.equals("package-generator")?HybridRunnerIntegrationTest.GENERATED:"");
            }
        }
        overrides.put("HYBRID_CONTENT_REVIEW_ENABLED","true");publication.advance();
        assertThat(jobs.view("owner",id).error()).isEqualTo("CONTENT_REVIEW_REQUIRED");assertThat(execution.claimApi()).isNull();
        assertThat(jdbc.sql("SELECT count(*) FROM ai_attempt").query(Integer.class).single()).isEqualTo(2);assertThat(published()).isZero();
    }
    @Test void publicationAndCancellationRaceIsAtomic() throws Exception {
        UUID id=checked(false);var work=review();var result=result(accepted(work));
        try(var pool=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);
            var a=pool.submit(()->{start.await();return jobs.cancel("owner",id);});
            var b=pool.submit(()->{start.await();execution.finish(work.attemptId(),result,null);return true;});
            start.countDown();a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
        }
        String status=jobs.view("owner",id).status();assertThat(status).isIn("CANCELLED","PUBLISHED");
        assertThat(published()).isEqualTo(status.equals("PUBLISHED")?1:0);assertThat(ledger.budget().spentUsd()).isEqualByComparingTo("0.0006");
    }
}
