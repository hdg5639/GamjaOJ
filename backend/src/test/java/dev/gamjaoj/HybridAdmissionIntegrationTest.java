package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamjaoj.ai.OpenAiResponses;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static dev.gamjaoj.HybridGeneration.Role.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:hybridadmission;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa","spring.datasource.password=","gamjaoj.invite-code=test",
        "HYBRID_CONTENT_REVIEW_ENABLED=true","HYBRID_VALIDATION_PROFILE=hybrid-zero-one-items-v3",
        "AI_HYBRID_REVIEW_MODEL=fixture-review","AI_HYBRID_REVIEW_REASONING=low",
        "AI_HYBRID_REVIEW_INPUT_USD_PER_M=1","AI_HYBRID_REVIEW_CACHED_USD_PER_M=0.1",
        "AI_HYBRID_REVIEW_OUTPUT_USD_PER_M=2","AI_HYBRID_REVIEW_MAX_OUTPUT_TOKENS=4096","AI_HYBRID_REVIEW_PRICING_VERSION=fixture",
        "AI_API_ENABLED=true","OPENAI_API_KEY=fixture-no-network","HYBRID_ADMISSION_ENABLED=true",
        "HYBRID_API_WORKER_ENABLED=true","HYBRID_PUBLIC_ADMISSION_ENABLED=true","HYBRID_ALLOWED_USERS=owner","AI_POLL_MS=3600000",
        "GENERATION_WORKER_TOKEN=fixture-generation-worker-token-12345678",
        "AI_HYBRID_WRITER_MODEL=fixture-writer","AI_HYBRID_WRITER_REASONING=low",
        "AI_HYBRID_WRITER_INPUT_USD_PER_M=1","AI_HYBRID_WRITER_CACHED_USD_PER_M=0.1",
        "AI_HYBRID_WRITER_OUTPUT_USD_PER_M=2","AI_HYBRID_WRITER_MAX_OUTPUT_TOKENS=4096","AI_HYBRID_WRITER_PRICING_VERSION=fixture",
        "AI_HYBRID_READER_MODEL=fixture-reader","AI_HYBRID_READER_REASONING=low",
        "AI_HYBRID_READER_INPUT_USD_PER_M=1","AI_HYBRID_READER_CACHED_USD_PER_M=0.1",
        "AI_HYBRID_READER_OUTPUT_USD_PER_M=2","AI_HYBRID_READER_MAX_OUTPUT_TOKENS=4096","AI_HYBRID_READER_PRICING_VERSION=fixture"})
@AutoConfigureMockMvc
class HybridAdmissionIntegrationTest {
    @Autowired HybridAdmission admission;@Autowired HybridExecution execution;@Autowired HybridGeneration jobs;
    @Autowired HybridPublication publication;@Autowired HybridRunnerChecks checks;@Autowired JudgeQueue queue;
    @Autowired JdbcClient jdbc;@Autowired ConfigurableEnvironment env;@Autowired MockMvc mvc;
    @Autowired GenerationJobs legacy;@Autowired GenerationSpecDrafts drafts;
    @MockitoBean HybridApiWorker worker;@MockitoBean HybridApiProvider provider;
    final HybridGenerationIntegrationTest f=new HybridGenerationIntegrationTest();
    final HybridRunnerIntegrationTest runner=new HybridRunnerIntegrationTest();
    final Map<String,Object> overrides=new HashMap<>();
    static final String BODY="{\"profileId\":\"zero-one-items-v1\",\"shared\":false,\"publishOnSuccess\":true}";
    @BeforeEach void setup(){
        env.getPropertySources().addFirst(new MapPropertySource("admission-test",overrides));
        runner.jdbc=jdbc;runner.checks=checks;runner.hybrid=jobs;runner.queue=queue;runner.env=env;runner.setup();
        jdbc.sql("DELETE FROM ai_attempt").update();jdbc.sql("DELETE FROM ai_task").update();
        jdbc.sql("INSERT INTO app_user(id,username,password_hash,nickname) VALUES (?,'other','unused','other')").param(UUID.randomUUID()).update();
    }
    @AfterEach void reset(){env.getPropertySources().remove("admission-test");verifyNoInteractions(provider);}
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postRequest(UUID id,String body){return post("/api/generation/hybrid").with(user("owner")).with(csrf()).header("Idempotency-Key",id).contentType("application/json").content(body);}
    OpenAiResponses.Result result(JsonNode p){return new OpenAiResponses.Result(p,JudgeJson.JSON.createObjectNode().put("input_tokens",100).put("output_tokens",50),"fixture-response","fixture-request","fixture-model");}
    @ParameterizedTest @ValueSource(strings={"HYBRID_PUBLIC_ADMISSION_ENABLED","HYBRID_CONTENT_REVIEW_ENABLED","HYBRID_API_WORKER_ENABLED","HYBRID_ADMISSION_ENABLED"}) void closedFlagsHaveNoAdmissionOrSpending(String flag) throws Exception {
        overrides.put(flag,"false");mvc.perform(get("/api/generation/hybrid/options").with(user("owner"))).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(postRequest(UUID.randomUUID(),BODY)).andExpect(status().isServiceUnavailable());
        assertThat(jdbc.sql("SELECT count(*) FROM ai_attempt").query(Integer.class).single()).isZero();
    }
    @Test void allowlistConfigurationConsentAndExactScopeAreRequiredBeforeSpending() throws Exception {
        mvc.perform(get("/api/generation/hybrid/options").with(user("other"))).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(postRequest(UUID.randomUUID(),BODY).with(user("other"))).andExpect(status().isServiceUnavailable());
        for(String body:List.of(BODY.replace("zero-one-items-v1","graphs"),BODY.replace("\"publishOnSuccess\":true","\"publishOnSuccess\":false"),BODY.replace("}",",\"request\":\"change the rules\"}"),"{}"))
            mvc.perform(postRequest(UUID.randomUUID(),body)).andExpect(status().isBadRequest());
        overrides.put("AI_HYBRID_REVIEW_MODEL","");mvc.perform(postRequest(UUID.randomUUID(),BODY)).andExpect(status().isServiceUnavailable());
        assertThat(jdbc.sql("SELECT count(*) FROM hybrid_generation").query(Integer.class).single()).isZero();assertThat(jdbc.sql("SELECT count(*) FROM ai_attempt").query(Integer.class).single()).isZero();
    }
    @Test void authenticatedCsrfProtectedAdmissionIsIdempotentOwnerScopedAndRecoverableWhenDisabled() throws Exception {
        UUID id=UUID.randomUUID();
        mvc.perform(get("/api/generation/hybrid/options")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/generation/hybrid").with(user("owner")).header("Idempotency-Key",id).contentType("application/json").content(BODY)).andExpect(status().isForbidden());
        mvc.perform(postRequest(id,BODY)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("BUILDING")).andExpect(jsonPath("$.branches.CONTRACT").value("SUCCEEDED"));
        assertThat(jdbc.sql("SELECT count(*) FROM ai_attempt").query(Integer.class).single()).isEqualTo(3);
        overrides.put("HYBRID_PUBLIC_ADMISSION_ENABLED","false");
        mvc.perform(postRequest(id,BODY)).andExpect(status().isOk());
        mvc.perform(postRequest(id,BODY.replace("\"shared\":false","\"shared\":true"))).andExpect(status().isConflict());
        mvc.perform(postRequest(id,BODY).with(user("other"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/generation/hybrid").with(user("owner"))).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/generation/hybrid").with(user("other"))).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/generation/hybrid/"+id).with(user("other"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/generation/hybrid/"+id+"/cancel").with(user("other")).with(csrf())).andExpect(status().isNotFound());
        assertThat(jdbc.sql("SELECT count(*) FROM ai_attempt").query(Integer.class).single()).isEqualTo(3);
        var core=execution.claimCodex();assertThat(core.spec().path("role").asText()).isEqualTo("CORE");
        assertThat(core.spec().path("input").path("contract")).isEqualTo(HybridFiniteProfile.contract());
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"false,zero-one-items-v1","true,zero-one-items-v1","true,bfs-shortest-path-v1","true,dijkstra-shortest-path-v1"}) void publicRequestRunsToPublishedProblemWithPinnedProfileAndPrivateReader(boolean functional,String profileId) throws Exception {
        var profile=HybridProfiles.byId(profileId);boolean bfs=profile.bfs(),weighted=profile.weighted();
        overrides.put("HYBRID_FUNCTIONAL_ENABLED",Boolean.toString(functional));
        UUID id=UUID.randomUUID();mvc.perform(postRequest(id,BODY.replace(HybridAdmission.PROFILE,profileId))).andExpect(status().isOk());
        assertThat(jobs.view("owner",id).profileId()).isEqualTo(profileId);
        var core=execution.claimCodex();var reduced=f.core();reduced.remove(List.of("generator","inputValidator"));
        assertThat(core.outputSchema().path("properties").has("generator")).isFalse();
        assertThat(core.spec().path("input").has("serverSupport")).isFalse();
        var completion=f.result(JudgeJson.JSON.convertValue(core.spec().path("assignment"),HybridGeneration.Assignment.class),reduced);
        execution.completeCodex(completion);execution.completeCodex(completion);
        var saved=JudgeJson.parse(jdbc.sql("SELECT a.payload_json FROM hybrid_artifact a JOIN hybrid_branch b ON b.id=a.branch_id WHERE b.generation_id=? AND b.role='CORE'").param(id).query(String.class).single());
        assertThat(saved.path("generator")).isEqualTo(core.spec().path("assignment").path("input").path("serverSupport").path("generator"));
        var writer=execution.claimApi();var prose=weighted?HybridDijkstraProfileTest.prose():bfs?HybridBfsProfileTest.prose():f.presentation();prose.remove(List.of("semantics","ruleExplanations"));
        assertThat(writer.request().schema().path("properties").has("ruleExplanations")).isFalse();
        execution.finish(writer.attemptId(),result(prose),null);execution.finish(writer.attemptId(),result(prose),null);
        var reader=execution.claimApi();
        assertThat(JudgeJson.parse(reader.request().input()).path("ruleExplanations")).isEqualTo(writer.request().assignment().input().path("serverRules").path("rules"));
        assertThat(reader.request().instructions()).contains(profile.graph()?"N <= 4":"N <= 4, W <= 8");assertThat(reader.request().input()).doesNotContain("PRIVATE_");execution.finish(reader.attemptId(),result(weighted?HybridDijkstraProfileTest.reader():bfs?HybridBfsProfileTest.reader():f.reader()),null);
        overrides.put("HYBRID_VALIDATION_PROFILE","");overrides.put("HYBRID_PUBLIC_ADMISSION_ENABLED","false");
        for(int stage=0;stage<9;stage++) {
            checks.advance();Optional<JudgeQueue.Assignment> next;
            while((next=queue.claim(UUID.randomUUID())).isPresent()) {
                var a=next.get();String role=runner.role(a);runner.complete(a,role.equals("package-generator")?"OK":role.startsWith("mutant-")?"WA":"AC",role.equals("package-generator")?(weighted?HybridDijkstraProfileTest.generated():bfs?HybridBfsProfileTest.generated():HybridRunnerIntegrationTest.GENERATED):"");
            }
        }
        checks.advance();publication.advance();var work=execution.claimApi();assertThat(work).isNotNull();
        var accepted=new HybridPublicationIntegrationTest().accepted(work);execution.finish(work.attemptId(),result(accepted),null);
        String version=jobs.view("owner",id).publishedVersionId();assertThat(jobs.view("owner",id).status()).isEqualTo("PUBLISHED");assertThat(runner.jobs()).isEqualTo(15);
        mvc.perform(get("/api/generation/hybrid/"+id).with(user("owner"))).andExpect(jsonPath("$.publishedVersionId").value(version));
        String own=mvc.perform(get("/api/problems").with(user("owner"))).andReturn().getResponse().getContentAsString();assertThat(own).contains(version).contains(profile.category()).contains(profile.tags().split(",")[0]);
        String others=mvc.perform(get("/api/problems").with(user("other"))).andReturn().getResponse().getContentAsString();assertThat(others).doesNotContain(version);
        jdbc.sql("UPDATE problem_version SET review_hold=true WHERE id=?").param(version).update();
        mvc.perform(get("/api/generation/hybrid").with(user("owner"))).andExpect(jsonPath("$[0].problemHeld").value(true));
    }
    @Test void readEndpointsNeverDispatchAndLegacyModesCannotCreateAlongsideAdmittedWork() throws Exception {
        for(int i=0;i<3;i++){mvc.perform(get("/api/generation/hybrid/options").with(user("owner"))).andExpect(status().isOk());mvc.perform(get("/api/generation/hybrid").with(user("owner"))).andExpect(status().isOk());}
        assertThat(jdbc.sql("SELECT count(*) FROM ai_attempt").query(Integer.class).single()).isZero();
        UUID id=UUID.randomUUID();mvc.perform(postRequest(id,BODY)).andExpect(status().isOk());
        assertThatThrownBy(()->legacy.create("owner",UUID.randomUUID(),"parentheses-v1","basics")).isInstanceOf(AccountException.class);
        assertThatThrownBy(()->drafts.create("owner",UUID.randomUUID(),"new graph problem")).isInstanceOf(AccountException.class);
        mvc.perform(post("/api/generation/hybrid/"+id+"/cancel").with(user("owner")).with(csrf())).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(execution.claimCodex()).isNull();assertThat(execution.claimApi()).isNull();
    }
}
