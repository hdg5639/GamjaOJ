package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamjaoj.ai.OpenAiResponses;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:ai;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa","spring.datasource.password=",
        "gamjaoj.invite-code=test","AI_API_ENABLED=true","OPENAI_API_KEY=test-only","AI_OPERATOR_USERS=alice","AI_POLL_MS=3600000"})
@AutoConfigureMockMvc
class AiIntegrationTest {
    @Autowired AiTasks tasks; @Autowired AiWorker worker; @Autowired JdbcClient jdbc; @Autowired MockMvc mvc;
    @Autowired ConfigurableEnvironment environment;
    @MockitoBean AiProvider provider;
    UUID alice,bob,submission;
    @BeforeEach void setup() {
        TestPropertyValues.of("AI_API_ENABLED=true","AI_MONTHLY_BUDGET_USD=10","AI_DEFAULT_MODEL=gpt-5.6-luna").applyTo(environment);
        jdbc.sql("DELETE FROM ai_attempt").update();jdbc.sql("DELETE FROM ai_budget_notice").update();jdbc.sql("DELETE FROM app_user").update();
        alice=createUser("alice");bob=createUser("bob");submission=submission(alice,null);
    }
    UUID createUser(String name) { UUID id=UUID.randomUUID();jdbc.sql("INSERT INTO app_user (id,username,password_hash,nickname) VALUES (?,?,?,?)").param(id).param(name).param("unused").param(name).update();return id; }
    UUID submission(UUID user,UUID session) {
        UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO submission (id,user_id,problem_version,source_code,source_sha256,idempotency_key,runtime_image,runner_policy,training_session_id) SELECT ?,?,'total-v1','class Main {}',?,?,runtime_image,runner_policy,? FROM problem_version WHERE id='total-v1'")
                .param(id).param(user).param(JudgeJson.hash("class Main {}")).param(UUID.randomUUID()).param(session).update();
        jdbc.sql("INSERT INTO judge_job (submission_id,status,verdict,result_json,result_sha256,finished_at) VALUES (?,'FINISHED','WA','{}',?,CURRENT_TIMESTAMP)").param(id).param(JudgeJson.hash("{}" )).update();return id;
    }
    static JsonNode feedback() { return JudgeJson.parse("{\"summary\":\"확인한 피드백\",\"observations\":[\"코드 근거\"],\"nextSteps\":[\"입력을 확인하세요\"],\"uncertainty\":\"숨은 테스트는 알 수 없음\"}"); }
    OpenAiResponses.Result result() { return new OpenAiResponses.Result(feedback(),JudgeJson.parse("{\"input_tokens\":100,\"output_tokens\":50,\"input_tokens_details\":{\"cached_tokens\":20}}"),"response","request","gpt-5.6-luna"); }
    @Test void cacheIsPerUserSubmissionJudgeAndSettingsAndDoesNotChangeVerdict() {
        var first=tasks.request("alice",submission,"ANALYSIS","",false);
        assertThat(tasks.request("alice",submission,"ANALYSIS","",false).id()).isEqualTo(first.id());
        when(provider.feedback(any(),anyString())).thenReturn(result());worker.tick();
        assertThat(tasks.detail("alice",first.id()).result()).isEqualTo(feedback());
        assertThat(tasks.request("alice",submission,"ANALYSIS","",false).id()).isEqualTo(first.id());
        worker.tick();verify(provider,times(1)).feedback(any(),anyString());
        assertThat(jdbc.sql("SELECT verdict FROM judge_job WHERE submission_id=?").param(submission).query(String.class).single()).isEqualTo("WA");
        assertThat(tasks.budget("alice").spentUsd()).isEqualByComparingTo("0.0000764");
        assertThat(tasks.budget("alice").reservedUsd()).isZero();
        jdbc.sql("UPDATE judge_job SET result_sha256=? WHERE submission_id=?").param(JudgeJson.hash("new judge evidence")).param(submission).update();
        assertThat(tasks.request("alice",submission,"ANALYSIS","",false).id()).isNotEqualTo(first.id());
    }
    @Test void accountAndOperatorBoundariesApplyToRequestReadRetryAndBudget() throws Exception {
        var task=tasks.request("alice",submission,"ANALYSIS","",false);
        mvc.perform(get("/api/ai/tasks/"+task.id()).with(user("bob"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/ai/tasks").param("submissionId",submission.toString()).with(user("bob"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/ai/tasks/"+task.id()+"/retry").with(user("bob")).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get("/api/ai/budget").with(user("bob"))).andExpect(status().isForbidden());
        UUID b=submission(bob,null);
        assertThatThrownBy(()->tasks.request("bob",b,"ANALYSIS","",true)).isInstanceOf(AccountException.class);
        assertThatThrownBy(()->tasks.request("bob",submission,"HINT","힌트",false)).isInstanceOf(AccountException.class);
        assertThat(tasks.request("bob",b,"ANALYSIS","",false).id()).isNotEqualTo(task.id());
    }
    @Test void disabledAndBudgetBlockedTasksDoNotCallProviderAndLocalJudgeRemainsUsable() {
        TestPropertyValues.of("AI_API_ENABLED=false").applyTo(environment);
        var task=tasks.request("alice",submission,"ANALYSIS","",false);worker.tick();
        assertThat(tasks.detail("alice",task.id()).status()).isEqualTo("HELD_DISABLED");verifyNoInteractions(provider);
        TestPropertyValues.of("AI_API_ENABLED=true","AI_MONTHLY_BUDGET_USD=0").applyTo(environment);worker.tick();
        assertThat(tasks.detail("alice",task.id()).status()).isEqualTo("HELD_BUDGET");verifyNoInteractions(provider);
        assertThat(jdbc.sql("SELECT count(*) FROM problem_version WHERE ready=true").query(Integer.class).single()).isGreaterThan(0);
    }
    @Test void unknownChargeRetainsReservationAndExplicitRetryGetsSeparateAttempt() {
        var task=tasks.request("alice",submission,"ANALYSIS","",false);
        when(provider.feedback(any(),anyString())).thenThrow(new OpenAiResponses.Failure("TRANSPORT_USAGE_UNKNOWN",null,null));
        worker.tick();assertThat(tasks.detail("alice",task.id()).status()).isEqualTo("UNKNOWN");
        BigDecimal held=tasks.budget("alice").reservedUsd();assertThat(held).isPositive();worker.tick();verify(provider,times(1)).feedback(any(),anyString());
        tasks.retry("alice",task.id());doReturn(result()).when(provider).feedback(any(),anyString());worker.tick();
        assertThat(tasks.budget("alice").reservedUsd()).isEqualByComparingTo(held);
        assertThat(jdbc.sql("SELECT count(*) FROM ai_attempt").query(Integer.class).single()).isEqualTo(2);
    }
    @Test void concurrentAdmissionReservesServiceWideBudgetBeforeCalls() throws Exception {
        TestPropertyValues.of("AI_MONTHLY_BUDGET_USD=0.005").applyTo(environment);
        tasks.request("alice",submission,"ANALYSIS","",false);
        tasks.request("bob",submission(bob,null),"ANALYSIS","",false);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->tasks.claim());var b=pool.submit(()->tasks.claim());
            assertThat(java.util.stream.Stream.of(a.get(),b.get()).filter(java.util.Objects::nonNull).count()).isEqualTo(1);
        }
        assertThat(tasks.budget("alice").reservedUsd()).isLessThanOrEqualTo(new BigDecimal("0.005"));
    }
    @Test void sessionEndingSelectsLatestFinishedSubmissionOnceAndNotEveryWrongAnswer() {
        worker.tick();verifyNoInteractions(provider);
        UUID session=UUID.randomUUID();jdbc.sql("INSERT INTO training_session (id,user_id,problem_version,goal,status,ended_at) VALUES (?,?,'total-v1','입출력','ENDED',CURRENT_TIMESTAMP)").param(session).param(alice).update();
        UUID representative=submission(alice,session);
        when(provider.feedback(any(),anyString())).thenReturn(result());worker.tick();worker.tick();
        assertThat(tasks.list("alice",representative)).hasSize(1);
        verify(provider,times(1)).feedback(any(),anyString());
        assertThat(jdbc.sql("SELECT analysis_checked FROM training_session WHERE id=?").param(session).query(Boolean.class).single()).isTrue();
    }
    @Test void operatorStrongIsExplicitAndModelFailureNeverPromotesOrFallsBack() {
        var task=tasks.request("alice",submission,"ANALYSIS","",true);
        assertThat(task.model()).isEqualTo("gpt-5.6-terra");assertThat(task.effort()).isEqualTo("medium");
        when(provider.feedback(any(),anyString())).thenThrow(new OpenAiResponses.Failure("REQUEST_REJECTED",null,"model-not-available"));
        worker.tick();worker.tick();verify(provider,times(1)).feedback(argThat(s->s.model().equals("gpt-5.6-terra")),anyString());
        assertThat(tasks.detail("alice",task.id()).errorCode()).isEqualTo("REQUEST_REJECTED");
    }
    @Test void wildcardOperatorListOpensStrongAnalysisToEveryUser() {
        var own=submission(bob,null);
        assertThatThrownBy(()->tasks.request("bob",own,"ANALYSIS","",true)).isInstanceOf(AccountException.class);
        TestPropertyValues.of("AI_OPERATOR_USERS=*").applyTo(environment);
        try {
            assertThat(tasks.request("bob",own,"ANALYSIS","",true).model()).isEqualTo("gpt-5.6-terra");
            assertThat(tasks.budget("bob").limitUsd()).isNotNull();
        } finally { TestPropertyValues.of("AI_OPERATOR_USERS=alice").applyTo(environment); }
    }
    @Test void eightyPercentIsPersistentOperatorNotice() {
        TestPropertyValues.of("AI_MONTHLY_BUDGET_USD=0.004").applyTo(environment);
        tasks.request("alice",submission,"ANALYSIS","",false);tasks.claim();
        assertThat(tasks.budget("alice").warning()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM ai_budget_notice").query(Integer.class).single()).isEqualTo(1);
    }
}
