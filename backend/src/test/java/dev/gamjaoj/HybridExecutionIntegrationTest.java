package dev.gamjaoj;
import dev.gamjaoj.exception.AccountException;
import dev.gamjaoj.service.ai.AiTasks;
import dev.gamjaoj.infrastructure.ai.HybridApiProvider;
import dev.gamjaoj.infrastructure.worker.HybridApiWorker;
import dev.gamjaoj.service.generation.HybridArtifacts;
import dev.gamjaoj.service.generation.HybridExecution;
import dev.gamjaoj.service.generation.HybridGeneration;
import dev.gamjaoj.service.generation.HybridModels;
import dev.gamjaoj.support.JudgeJson;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamjaoj.infrastructure.ai.OpenAiResponses;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static dev.gamjaoj.service.generation.HybridGeneration.Role.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:hybridexecution;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa","spring.datasource.password=","gamjaoj.invite-code=test",
        "AI_API_ENABLED=true","OPENAI_API_KEY=fixture-no-network","HYBRID_ADMISSION_ENABLED=true",
        "HYBRID_API_WORKER_ENABLED=false","AI_POLL_MS=3600000",
        "GENERATION_WORKER_TOKEN=fixture-generation-worker-token-12345678",
        "AI_HYBRID_WRITER_MODEL=fixture-writer","AI_HYBRID_WRITER_REASONING=low",
        "AI_HYBRID_WRITER_INPUT_USD_PER_M=1","AI_HYBRID_WRITER_CACHED_USD_PER_M=0.1",
        "AI_HYBRID_WRITER_OUTPUT_USD_PER_M=2","AI_HYBRID_WRITER_MAX_OUTPUT_TOKENS=4096","AI_HYBRID_WRITER_PRICING_VERSION=fixture",
        "AI_HYBRID_READER_MODEL=fixture-reader","AI_HYBRID_READER_REASONING=low",
        "AI_HYBRID_READER_INPUT_USD_PER_M=1","AI_HYBRID_READER_CACHED_USD_PER_M=0.1",
        "AI_HYBRID_READER_OUTPUT_USD_PER_M=2","AI_HYBRID_READER_MAX_OUTPUT_TOKENS=4096","AI_HYBRID_READER_PRICING_VERSION=fixture"})
@AutoConfigureMockMvc
class HybridExecutionIntegrationTest {
    @Autowired HybridExecution execution;@Autowired HybridGeneration jobs;@Autowired AiTasks ledger;
    @Autowired HybridApiWorker worker;@Autowired JdbcClient jdbc;@Autowired ConfigurableEnvironment env;@Autowired MockMvc mvc;
    @MockitoBean HybridApiProvider provider;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper httpJson;
    final HybridGenerationIntegrationTest fixtures=new HybridGenerationIntegrationTest();
    final Map<String,Object> overrides=new HashMap<>();
    @BeforeEach void setup() {
        env.getPropertySources().remove("hybrid-test-overrides");overrides.clear();
        env.getPropertySources().addFirst(new MapPropertySource("hybrid-test-overrides",overrides));
        jdbc.sql("DELETE FROM hybrid_codex_quota").update();jdbc.sql("DELETE FROM hybrid_generation").update();jdbc.sql("DELETE FROM ai_attempt").update();jdbc.sql("DELETE FROM ai_task").update();
        jdbc.sql("DELETE FROM app_user").update();
        for(String name:List.of("owner","other"))jdbc.sql("INSERT INTO app_user(id,username,password_hash,nickname) VALUES (?,?,?,?)")
                .param(UUID.randomUUID()).param(name).param("unused").param(name).update();
    }
    @AfterEach void resetConfig(){env.getPropertySources().remove("hybrid-test-overrides");}
    UUID admit(){UUID id=UUID.randomUUID();execution.admit("owner",id,"original problem",false);return id;}
    HybridGeneration.Assignment assignment(HybridModels.CodexRequest request) {
        return JudgeJson.JSON.convertValue(request.spec().path("assignment"),HybridGeneration.Assignment.class);
    }
    HybridGeneration.Completion completion(HybridModels.CodexRequest request,JsonNode payload) {
        var a=assignment(request);return new HybridGeneration.Completion(a.branchId(),a.revision(),a.role(),a.token(),a.inputHash(),a.contractHash(),a.publicHash(),payload,null,null);
    }
    UUID designed(){UUID id=admit();execution.completeCodex(completion(execution.claimCodex(),fixtures.contract()));return id;}
    OpenAiResponses.Result result(JsonNode payload) {
        return new OpenAiResponses.Result(payload,JudgeJson.JSON.createObjectNode().put("input_tokens",100).put("output_tokens",50),"response-fixture","request-fixture","fixture-provider-model");
    }
    int count(String table){return jdbc.sql("SELECT count(*) FROM "+table).query(Integer.class).single();}
    String attemptStatus(UUID attempt){return jdbc.sql("SELECT status FROM ai_attempt WHERE id=?").param(attempt).query(String.class).single();}
    @Test void reservesBothRolesAtomicallyAndIdempotentlyInExistingLedger() {
        UUID id=admit();BigDecimal reserved=ledger.budget().reservedUsd();
        assertThat(reserved).isGreaterThan(new BigDecimal("0.5"));assertThat(count("ai_attempt")).isEqualTo(2);
        assertThat(count("ai_task")).isZero();
        execution.admit("owner",id,"original problem",false);
        assertThat(count("ai_attempt")).isEqualTo(2);assertThat(ledger.budget().reservedUsd()).isEqualByComparingTo(reserved);
        assertThatThrownBy(()->execution.admit("other",id,"original problem",false)).isInstanceOf(AccountException.class);
        assertThatThrownBy(()->execution.admit("owner",id,"different",false)).isInstanceOf(AccountException.class);
        assertThatThrownBy(this::admit).isInstanceOf(AccountException.class);
        verifyNoInteractions(provider);
    }
    @Test void insufficientCombinedBudgetCreatesNeitherGenerationNorReservation() {
        overrides.put("AI_MONTHLY_BUDGET_USD","0.4");
        assertThatThrownBy(this::admit).isInstanceOf(AccountException.class);
        assertThat(count("hybrid_generation")).isZero();assertThat(count("ai_attempt")).isZero();assertThat(execution.claimCodex()).isNull();
        overrides.put("AI_MONTHLY_BUDGET_USD","10");overrides.put("AI_HYBRID_READER_MODEL","");
        assertThatThrownBy(this::admit).isInstanceOf(AccountException.class);assertThat(count("ai_attempt")).isZero();
    }
    @Test void concurrentAdmissionCannotOverspendOrCreateSecondActiveGeneration() throws Exception {
        overrides.put("AI_MONTHLY_BUDGET_USD","0.8");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            Callable<Boolean> task=()->{start.await();try{admit();return true;}catch(AccountException e){return false;}};
            var a=pool.submit(task);var b=pool.submit(task);start.countDown();
            assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(count("hybrid_generation")).isEqualTo(1);assertThat(count("ai_attempt")).isEqualTo(2);
    }
    @Test void realDispatcherJoinsFakeProvidersWhileCoreRemainsIndependent() {
        UUID id=designed();var core=execution.claimCodex();assertThat(core.spec().path("role").asText()).isEqualTo("CORE");
        when(provider.generate(any())).thenAnswer(call->{var work=call.getArgument(0,HybridExecution.Work.class);
            assertThat(work.request().input()).doesNotContain("PRIVATE_");
            return result(work.request().assignment().role()==PRESENTATION?fixtures.presentation():fixtures.reader());});
        assertThat(worker.runOnce()).isTrue();
        assertThat(jobs.view("owner",id).branches().get(CORE)).isEqualTo("RUNNING");
        assertThat(worker.runOnce()).isTrue();assertThat(worker.runOnce()).isFalse();
        execution.completeCodex(completion(core,fixtures.core()));
        assertThat(jobs.view("owner",id).error()).isEqualTo("VALIDATION_ADAPTER_NOT_CONNECTED");
        assertThat(ledger.budget().spentUsd()).isEqualByComparingTo("0.0004");assertThat(ledger.budget().reservedUsd()).isEqualByComparingTo("0");
        assertThat(count("hybrid_artifact")).isEqualTo(4);verify(provider,times(2)).generate(any());
        assertThat(jdbc.sql("SELECT count(*) FROM problem_version WHERE id LIKE 'hybrid%'").query(Integer.class).single()).isZero();
    }
    @Test void cancellationReleasesOnlyUnstartedWorkAndLateSuccessSettlesWithoutAcceptance() {
        UUID id=designed();var work=execution.claimApi();assertThat(work).isNotNull();
        jobs.cancel("owner",id);
        assertThat(attemptStatus(work.attemptId())).isEqualTo("HYBRID_RUNNING");
        assertThat(jdbc.sql("SELECT count(*) FROM ai_attempt WHERE status='HYBRID_RELEASED'").query(Integer.class).single()).isEqualTo(1);
        var result=result(fixtures.presentation());execution.finish(work.attemptId(),result,null);
        execution.finish(work.attemptId(),result,null);
        assertThat(ledger.budget().spentUsd()).isEqualByComparingTo("0.0002");assertThat(ledger.budget().reservedUsd()).isEqualByComparingTo("0");
        assertThat(jobs.view("owner",id).status()).isEqualTo("CANCELLED");assertThat(count("hybrid_artifact")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT late_result FROM hybrid_branch WHERE id=?").param(work.request().assignment().branchId()).query(Boolean.class).single()).isTrue();
        assertThatThrownBy(()->execution.finish(work.attemptId(),result(fixtures.reader()),null)).isInstanceOf(AccountException.class);
    }
    @Test void unknownUsageRemainsReservedAndCannotRetryThroughFeedback() {
        UUID id=designed();var work=execution.claimApi();
        execution.finish(work.attemptId(),null,new OpenAiResponses.Failure("TRANSPORT_USAGE_UNKNOWN",null,null));
        assertThat(attemptStatus(work.attemptId())).isEqualTo("HYBRID_UNKNOWN");
        assertThat(ledger.budget().reservedUsd()).isGreaterThan(BigDecimal.ZERO);assertThat(ledger.budget().spentUsd()).isEqualByComparingTo("0");
        assertThat(jobs.view("owner",id).status()).isEqualTo("HELD");assertThat(execution.claimApi()).isNull();assertThat(ledger.claim()).isNull();
        assertThatThrownBy(()->jobs.repair("owner",id,0,PRESENTATION,work.request().assignment().inputHash())).isInstanceOf(AccountException.class);
    }
    @Test void deadlineRecoveryRetainsUnknownReservationAndAcceptsOnlyLateAccounting() {
        UUID id=designed();var work=execution.claimApi();
        jdbc.sql("UPDATE hybrid_generation SET deadline_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE id=?").param(id).update();
        execution.recover();assertThat(jobs.view("owner",id).status()).isEqualTo("DEADLINE_EXCEEDED");
        assertThat(attemptStatus(work.attemptId())).isEqualTo("HYBRID_UNKNOWN");assertThat(execution.claimCodex()).isNull();assertThat(execution.claimApi()).isNull();
        execution.finish(work.attemptId(),result(fixtures.presentation()),null);
        assertThat(ledger.budget().reservedUsd()).isEqualByComparingTo("0");assertThat(count("hybrid_artifact")).isEqualTo(1);
    }
    @Test void admittedConfigurationIsPinnedAndDisablingAdmissionDoesNotRestartWork() {
        designed();overrides.put("AI_HYBRID_WRITER_MODEL","changed");overrides.put("HYBRID_ADMISSION_ENABLED","false");
        overrides.put("CODEX_GENERATION_MODEL","changed-core");
        assertThat(execution.claimCodex().model()).isEqualTo("gpt-6.1-sol");
        var work=execution.claimApi();assertThat(work.request().settings().model()).isEqualTo("fixture-writer");
        assertThat(execution.claimApi()).isNull();assertThat(ledger.claim()).isNull();
        assertThatThrownBy(this::admit).isInstanceOf(AccountException.class);
    }
    @Test void cancelBeforeDispatchReturnsBothReservationsAndNeverCallsModel() {
        UUID id=admit();jobs.cancel("owner",id);
        assertThat(ledger.budget().reservedUsd()).isEqualByComparingTo("0");
        assertThat(execution.claimCodex()).isNull();assertThat(worker.runOnce()).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM ai_attempt WHERE status='HYBRID_RELEASED' AND actual_usd=0").query(Integer.class).single()).isEqualTo(2);
        verifyNoInteractions(provider);
    }
    @Test void cancellationAndCompletionRacePreservesOneCostAndNoNewReaderDispatch() throws Exception {
        UUID id=designed();var work=execution.claimApi();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            var cancel=pool.submit(()->{start.await();return jobs.cancel("owner",id);});
            var finish=pool.submit(()->{start.await();execution.finish(work.attemptId(),result(fixtures.presentation()),null);return true;});
            start.countDown();cancel.get(10,TimeUnit.SECONDS);finish.get(10,TimeUnit.SECONDS);
        }
        assertThat(jobs.view("owner",id).status()).isEqualTo("CANCELLED");
        assertThat(ledger.budget().spentUsd()).isEqualByComparingTo("0.0002");assertThat(ledger.budget().reservedUsd()).isEqualByComparingTo("0");
        assertThat(execution.claimApi()).isNull();assertThat(count("ai_attempt")).isEqualTo(2);
    }
    @Test void oversizedOutputStillSettlesKnownCostAndHoldsPublication() {
        UUID id=designed();var work=execution.claimApi();var payload=fixtures.presentation();
        payload.put("context","x".repeat(HybridArtifacts.MAX_PAYLOAD_BYTES));
        execution.finish(work.attemptId(),result(payload),null);
        assertThat(jobs.view("owner",id).status()).isEqualTo("HELD");
        assertThat(ledger.budget().spentUsd()).isEqualByComparingTo("0.0002");assertThat(ledger.budget().reservedUsd()).isEqualByComparingTo("0");
        assertThat(count("hybrid_artifact")).isEqualTo(1);
    }
    @Test void oldMonthReservationRemainsInGlobalBudgetUntilDispatchOrRelease() {
        designed();var reserved=ledger.budget().reservedUsd();
        jdbc.sql("UPDATE ai_attempt SET month_key='2000-01'").update();
        assertThat(ledger.budget().reservedUsd()).isEqualByComparingTo(reserved);
        var work=execution.claimApi();execution.finish(work.attemptId(),result(fixtures.presentation()),null);
        assertThat(ledger.budget().spentUsd()).isEqualByComparingTo("0.0002");
        assertThat(ledger.budget().reservedUsd()).isGreaterThan(BigDecimal.ZERO).isLessThan(reserved);
    }
    void authorFallback() {
        overrides.put("HYBRID_CODEX_API_FALLBACK_ENABLED","true");
        for(var e:Map.of("MODEL","fixture-author","REASONING","medium","INPUT_USD_PER_M","1","CACHED_USD_PER_M","0.1",
                "OUTPUT_USD_PER_M","2","MAX_OUTPUT_TOKENS","8192","PRICING_VERSION","fixture").entrySet())overrides.put("AI_HYBRID_AUTHOR_"+e.getKey(),e.getValue());
    }
    HybridGeneration.Completion quota(HybridModels.CodexRequest request) {
        var a=assignment(request);return new HybridGeneration.Completion(a.branchId(),a.revision(),a.role(),a.token(),a.inputHash(),a.contractHash(),a.publicHash(),null,
                JudgeJson.JSON.createObjectNode().put("executor","CODEX_CLI"),"CODEX_QUOTA_EXHAUSTED");
    }
    @Test void codexQuotaReroutesSameCoreInputToReservedApiAuthorWithoutBlockingWriter() {
        authorFallback();UUID id=designed();var core=execution.claimCodex();
        String coreInput=jdbc.sql("SELECT input_sha256 FROM hybrid_branch WHERE id=?").param(assignment(core).branchId()).query(String.class).single();
        var quota=quota(core);execution.completeCodex(quota);execution.completeCodex(quota);
        assertThat(jobs.view("owner",id).status()).isEqualTo("BUILDING");
        assertThat(jdbc.sql("SELECT error_code FROM hybrid_branch WHERE id=?").param(assignment(core).branchId()).query(String.class).single()).isEqualTo("CODEX_QUOTA_EXHAUSTED");
        assertThat(jdbc.sql("SELECT count(*) FROM hybrid_api_reservation WHERE role='CORE'").query(Integer.class).single()).isEqualTo(1);
        assertThat(execution.claimCodex()).isNull();
        var writer=execution.claimApi();assertThat(writer.request().assignment().role()).isEqualTo(PRESENTATION);
        var author=execution.claimAuthorApi();assertThat(author).isNotNull();
        assertThat(author.request().assignment().role()).isEqualTo(CORE);assertThat(author.request().assignment().inputHash()).isEqualTo(coreInput);
        assertThat(author.request().settings().model()).isEqualTo("fixture-author");
        assertThat(author.request().instructions()).isEqualTo(core.spec().path("instructions").asText()).contains("REQUIREMENT FIDELITY v1");
        assertThat(execution.claimAuthorApi()).isNull();
        execution.finish(author.attemptId(),result(fixtures.core()),null);
        execution.finish(writer.attemptId(),result(fixtures.presentation()),null);
        assertThat(jobs.view("owner",id).branches().get(CORE)).isEqualTo("SUCCEEDED");
        String receipt=jdbc.sql("SELECT completion_json FROM hybrid_branch WHERE id=?").param(author.request().assignment().branchId()).query(String.class).single();
        assertThat(receipt).contains("\"fallbackFrom\":\"CODEX_CLI\"").contains("CODEX_QUOTA_EXHAUSTED");
        var reader=execution.claimApi();execution.finish(reader.attemptId(),result(fixtures.reader()),null);
        assertThat(jobs.view("owner",id).error()).isEqualTo("VALIDATION_ADAPTER_NOT_CONNECTED");
        assertThat(ledger.budget().reservedUsd()).isEqualByComparingTo("0");
    }
    @Test void quotaWithoutFallbackHoldsAndSpendsNoApiBudget() {
        UUID id=designed();execution.completeCodex(quota(execution.claimCodex()));
        assertThat(jobs.view("owner",id).status()).isEqualTo("HELD");assertThat(jobs.view("owner",id).error()).isEqualTo("PROVIDER_FAILED");
        assertThat(jdbc.sql("SELECT count(*) FROM hybrid_api_reservation WHERE role IN ('CONTRACT','CORE')").query(Integer.class).single()).isZero();
        assertThat(count("hybrid_codex_quota")).isEqualTo(1);
    }
    @Test void quotaFallbackBeyondBudgetHoldsInsteadOfOverspending() {
        authorFallback();UUID id=designed();var core=execution.claimCodex();
        overrides.put("AI_MONTHLY_BUDGET_USD",ledger.budget().reservedUsd().add(new BigDecimal("0.01")).toPlainString());
        execution.completeCodex(quota(core));
        assertThat(jobs.view("owner",id).status()).isEqualTo("HELD");
        assertThat(jdbc.sql("SELECT count(*) FROM hybrid_api_reservation WHERE role='CORE'").query(Integer.class).single()).isZero();
        assertThat(execution.claimAuthorApi()).isNull();
    }
    @Test void cooldownRoutesNewAuthorWorkToApiBeforeCodexClaims() {
        authorFallback();UUID first=designed();execution.completeCodex(quota(execution.claimCodex()));
        jobs.cancel("owner",first);
        UUID id=admit();assertThat(execution.claimCodex()).isNull();
        var contract=execution.claimAuthorApi();assertThat(contract.request().assignment().role()).isEqualTo(CONTRACT);
        assertThat(contract.request().assignment().generationId()).isEqualTo(id);
        jdbc.sql("UPDATE hybrid_codex_quota SET blocked_until=DATEADD('SECOND',-1,CURRENT_TIMESTAMP)").update();
        execution.finish(contract.attemptId(),result(fixtures.contract()),null);
        assertThat(execution.claimCodex().spec().path("role").asText()).isEqualTo("CORE");
    }
    @Test void boundedConcurrencyAdmitsDifferentOwnersButOneActiveRequestPerOwner() {
        overrides.put("HYBRID_MAX_ACTIVE","2");
        jdbc.sql("INSERT INTO app_user(id,username,password_hash,nickname) VALUES (?,'third','unused','third')").param(UUID.randomUUID()).update();
        admit();
        assertThatThrownBy(this::admit).isInstanceOf(AccountException.class).hasMessageContaining("진행 중인 출제");
        execution.admit("other",UUID.randomUUID(),"second problem",false);
        assertThatThrownBy(()->execution.admit("third",UUID.randomUUID(),"third problem",false)).isInstanceOf(AccountException.class).hasMessageContaining("다른 회원");
        assertThat(count("hybrid_generation")).isEqualTo(2);
        // Both generations' contracts are designed, then two ordinary API calls may run at once, not three.
        for(int i=0;i<2;i++)execution.completeCodex(completion(execution.claimCodex(),fixtures.contract()));
        var first=execution.claimApi();var second=execution.claimApi();
        assertThat(first).isNotNull();assertThat(second).isNotNull();assertThat(execution.claimApi()).isNull();
        assertThat(first.request().assignment().generationId()).isNotEqualTo(second.request().assignment().generationId());
        overrides.put("HYBRID_MAX_ACTIVE","1");assertThat(execution.claimApi()).isNull();
    }
    @Test void protectedCodexRoutesBindEnvelopeAndRejectApiRoleSpoofing() throws Exception {
        UUID id=admit();String token="Bearer fixture-generation-worker-token-12345678";
        mvc.perform(post("/internal/generation/hybrid/claim")).andExpect(status().isUnauthorized());
        String body=mvc.perform(post("/internal/generation/hybrid/claim").header("Authorization",token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var request=httpJson.readValue(body,HybridModels.CodexRequest.class);
        var result=completion(request,fixtures.contract());
        mvc.perform(post("/internal/generation/hybrid/result").header("Authorization",token).contentType("application/json").content(JudgeJson.JSON.writeValueAsString(result))).andExpect(status().isNoContent());
        assertThat(jobs.view("owner",id).revision()).isZero();
        var spoof=new HybridGeneration.Completion(result.branchId(),0,READER,result.token(),result.inputHash(),result.contractHash(),result.publicHash(),fixtures.reader(),null,null);
        mvc.perform(post("/internal/generation/hybrid/result").header("Authorization",token).contentType("application/json").content(JudgeJson.JSON.writeValueAsString(spoof))).andExpect(status().isBadRequest());
        verifyNoInteractions(provider);
    }
}
