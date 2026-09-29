package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gamjaoj.ai.OpenAiResponses;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:ruleonboarding;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa","spring.datasource.password=","gamjaoj.invite-code=test",
        "AI_API_ENABLED=true","OPENAI_API_KEY=fixture-no-network","AI_POLL_MS=3600000","HYBRID_RULE_ONBOARDING_ENABLED=true","HYBRID_RULE_ONBOARDING_WORKER_ENABLED=false",
        "AI_HYBRID_AUTHOR_MODEL=fixture-author","AI_HYBRID_AUTHOR_REASONING=medium","AI_HYBRID_AUTHOR_INPUT_USD_PER_M=2",
        "AI_HYBRID_AUTHOR_CACHED_USD_PER_M=0.2","AI_HYBRID_AUTHOR_OUTPUT_USD_PER_M=10","AI_HYBRID_AUTHOR_MAX_OUTPUT_TOKENS=8192","AI_HYBRID_AUTHOR_PRICING_VERSION=fixture"})
@AutoConfigureMockMvc
class HybridRuleOnboardingIntegrationTest {
    @Autowired HybridRuleOnboarding onboarding;@Autowired HybridRuleOnboardingWorker worker;@Autowired HybridRuleRegistry registry;
    @Autowired JudgeQueue queue;@Autowired JdbcClient jdbc;@Autowired ConfigurableEnvironment env;@Autowired MockMvc mvc;@Autowired AiTasks ledger;
    @MockitoBean RuleOnboardingProvider provider;@MockitoBean HybridApiWorker hybridApi;
    final HybridGenerationIntegrationTest f=new HybridGenerationIntegrationTest();
    final Map<String,Object> overrides=new HashMap<>();
    List<HybridFiniteProfile.Case> tiny=HybridFiniteProfile.valid();
    @BeforeEach void setup() {
        env.getPropertySources().addFirst(new MapPropertySource("onboarding-test",overrides));overrides.clear();
        overrides.put("HYBRID_RULE_ONBOARDING_REPAIRS","0"); // existing cases check the immediate hold; the repair case enables one round
        jdbc.sql("DELETE FROM hybrid_rule_onboarding").update();jdbc.sql("DELETE FROM hybrid_execution_check").update();jdbc.sql("DELETE FROM submission").update();
        jdbc.sql("DELETE FROM hybrid_generation").update();jdbc.sql("DELETE FROM problem_version WHERE id LIKE 'rule-qualify-%'").update();
        jdbc.sql("DELETE FROM ai_attempt").update();jdbc.sql("DELETE FROM app_user").update();
        for(String name:List.of("owner","other"))jdbc.sql("INSERT INTO app_user(id,username,password_hash,nickname) VALUES (?,?,?,?)").param(UUID.randomUUID()).param(name).param("unused").param(name).update();
        HybridProfiles.all().stream().filter(d->d.pkg()!=null).forEach(d->HybridProfiles.unregister(d.id()));
    }
    @AfterEach void reset(){env.getPropertySources().remove("onboarding-test");}
    ObjectNode author() {
        var p=HybridAdmissionIntegrationTest.fixturePackage();var a=JudgeJson.JSON.createObjectNode();
        for(String k:List.of("contract","rules","catalog","generator","validator","guidance"))a.set(k,p.path(k));
        a.put("reference",f.core().path("reference").asText());a.set("authorNotes",f.core().path("authorNotes"));
        a.put("largeGenerator",p.path("generator").asText()).put("slowSolution",HybridFiniteProfile.mutant("mutant-unbounded"));
        var m=a.putArray("mutants");for(String id:List.of("mutant-unbounded","mutant-strict-fit"))m.addObject().put("idea",id).put("source",HybridFiniteProfile.mutant(id));
        var t=a.putArray("tinyInputs");tiny.forEach(c->t.add(c.input()));
        var i=a.putArray("invalidInputs");HybridFiniteProfile.invalid().forEach(c->i.add(c.input()));
        var s=a.putArray("stressInputs");HybridFiniteProfile.stress().forEach(c->s.add(c.input()));
        a.putObject("oracleDomain").put("inputDomain","N <= 4, W <= 8").put("enumeration","all subsets");
        a.set("requirementsReview",GenerationRequirementsTest.accepted());return a;
    }
    OpenAiResponses.Result result(JsonNode p){return new OpenAiResponses.Result(p,JudgeJson.JSON.createObjectNode().put("input_tokens",1000).put("output_tokens",2000),"r","q","fixture-author");}
    void provide(JsonNode author) {
        doAnswer(c->{var call=c.getArgument(0,HybridRuleOnboarding.Call.class);
            if(call.role().equals("ORACLE")){assertThat(call.input()).doesNotContain("reference").doesNotContain("mutant");return result(JudgeJson.JSON.createObjectNode().put("oracleSource","public class Main{public static void main(String[] a){}}"));}
            return result(author);}).when(provider).generate(any());
    }
    String batchOf(List<String> outs){var b=new StringBuilder();for(String o:outs)b.append(o.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).append('\n').append(o);return b.toString();}
    String role(JudgeQueue.Assignment a){return jdbc.sql("SELECT role FROM hybrid_execution_check WHERE submission_id=?").param(a.submissionId()).query(String.class).single();}
    /** Fake Runner: correct programs agree; mutant-a differs on case 4, mutant-b on case 0. */
    String slowVerdict="TLE",validVerdict="AC";
    int drain(boolean disagree,boolean survivor) {
        int n=0;Optional<JudgeQueue.Assignment> next;
        while((next=queue.claim(UUID.randomUUID())).isPresent()) {
            var a=next.get();String role=role(a);n++;var answers=new ArrayList<String>();tiny.forEach(c->answers.add(c.output()));
            String verdict="AC",stdout="";
            switch(role) {
                case "q-generator"->{verdict="OK";stdout="[\"1 1\\n1 1\\n\",\"2 2\\n1 1\\n1 1\\n\",\"1 2\\n2 2\\n\",\"2 1\\n1 1\\n2 2\\n\"]";}
                case "q-oracle-batch"->{verdict="OK";stdout=batchOf(answers);}
                case "q-reference-batch"->{verdict="OK";var r=new ArrayList<>(answers);if(disagree)r.set(3,"99\n");stdout=batchOf(r);}
                case "q-mutant-a-batch"->{verdict="OK";var r=new ArrayList<>(answers);if(!survivor)r.set(4,"0\n");stdout=batchOf(r);}
                case "q-mutant-b-batch"->{verdict="OK";var r=new ArrayList<>(answers);r.set(0,"7\n");stdout=batchOf(r);}
                case "q-mutant-a","q-mutant-b"->verdict="WA";
                case "q-slow"->verdict=slowVerdict;
                case "q-valid"->verdict=validVerdict;
                default->{if(role.startsWith("q-stress-run-")){verdict="OK";stdout=HybridFiniteProfile.stress().get(Integer.parseInt(role.substring(13))).output();}}
            }
            boolean timedOut=role.equals("q-slow")&&verdict.equals("TLE");
            var r=new GenerationIntegrationTest().report(a,timedOut?"AC":verdict);
            if(timedOut){var tests=(com.fasterxml.jackson.databind.node.ArrayNode)r.path("tests");((ObjectNode)tests.get(tests.size()-1)).put("verdict","TLE");r.put("verdict","TLE");}
            for(var t:r.path("tests"))((ObjectNode)t).put("stdout",stdout).put("stderr","").put("stdout_truncated",false).put("wall_ms",10);
            queue.complete(a.submissionId(),a.token(),r);
        }
        return n;
    }
    HybridRuleOnboarding.View view(UUID id){return onboarding.list("owner").stream().filter(v->v.id().equals(id)).findFirst().orElseThrow();}
    UUID request() throws Exception {
        UUID id=UUID.randomUUID();
        mvc.perform(post("/api/rules/onboarding").with(user("owner")).with(csrf()).header("Idempotency-Key",id).contentType("application/json")
                .content("{\"request\":\"물건을 한 번씩만 골라 가치 합을 최대로 만드는 규칙\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("QUEUED"));
        return id;
    }
    @Test void unmetRequirementsRepairFromOriginalRequestAndThenHoldWithoutQualification() throws Exception {
        overrides.put("HYBRID_RULE_ONBOARDING_REPAIRS","1");
        var a=author();var assessment=(ObjectNode)a.path("requirementsReview");
        assessment.put("satisfied",false);assessment.withArray("issues").add("요청한 여러 장치 방문을 두 장치로 축소했습니다.");
        provide(a);UUID id=request();worker.runOnce();
        assertThat(view(id).status()).isEqualTo("QUEUED");assertThat(view(id).repairs()).isEqualTo(1);
        var retry=onboarding.claimCall();assertThat(retry.input()).contains("물건을 한 번씩", "requirementsReview", "두 장치", "REQUIREMENTS_NOT_MET");
        assertThat(retry.schema().path("properties").has("requirementsReview")).isTrue();
        onboarding.finishCall(retry.attemptId(),result(a),null);
        assertThat(view(id).status()).isEqualTo("HELD");assertThat(view(id).error()).isEqualTo("REQUIREMENTS_NOT_MET");
        assertThat(jdbc.sql("SELECT count(*) FROM hybrid_execution_check").query(Integer.class).single()).isZero();
        assertThat(ledger.budget().spentUsd()).isPositive();
        onboarding.finishCall(retry.attemptId(),result(a),null);
        assertThat(jdbc.sql("SELECT count(*) FROM hybrid_rule_onboarding_call").query(Integer.class).single()).isEqualTo(2);
    }
    @Test void newAuthorCannotOmitAssessment() throws Exception {
        var a=author();a.remove("requirementsReview");provide(a);UUID id=request();
        var call=onboarding.claimCall();onboarding.finishCall(call.attemptId(),result(a),null);
        assertThat(view(id).status()).isEqualTo("HELD");assertThat(queue.claim(UUID.randomUUID())).isEmpty();
    }
    @Test void requestQualifiesPrivatePackageThroughIndependentOracleAndRunnerStages() throws Exception {
        provide(author());UUID id=request();
        mvc.perform(post("/api/rules/onboarding").with(user("owner")).with(csrf()).header("Idempotency-Key",id).contentType("application/json")
                .content("{\"request\":\"물건을 한 번씩만 골라 가치 합을 최대로 만드는 규칙\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/rules/onboarding").with(user("owner")).with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json")
                .content("{\"request\":\"다른 규칙을 하나 더 요청합니다\"}")).andExpect(status().isTooManyRequests());
        assertThat(worker.runOnce()).isTrue();assertThat(view(id).status()).isEqualTo("AUTHORED");
        assertThat(worker.runOnce()).isTrue();assertThat(view(id).status()).isEqualTo("QUALIFYING");assertThat(worker.runOnce()).isFalse();
        verify(provider,times(2)).generate(any());
        assertThat(drain(false,false)).isEqualTo(8);onboarding.advance();
        assertThat(drain(false,false)).isEqualTo(7);onboarding.advance();
        assertThat(drain(false,false)).isEqualTo(4);onboarding.advance();
        var v=view(id);assertThat(v.status()).isEqualTo("ACTIVE");assertThat(v.versionId()).startsWith("rule-");assertThat(v.label()).isEqualTo("등록 규칙 · 물건 고르기");
        assertThat(v.spentUsd()).isPositive();assertThat(ledger.budget().reservedUsd()).isEqualByComparingTo("0");
        var d=HybridProfiles.byId(v.versionId());assertThat(d.pkg().tiny()).hasSize(tiny.size());
        assertThat(d.pkg().witnesses().get("mutant-a").input()).isEqualTo(tiny.get(4).input());
        assertThat(d.pkg().witnesses().get("mutant-b").input()).isEqualTo(tiny.get(0).input());
        assertThat(registry.qualifiedReference(v.versionId())).isPresent();
        assertThat(d.pkg().hasLarge()).isTrue();assertThat(d.pkg().largeSeeds()).hasSize(2);
        mvc.perform(get("/api/rules/mine").with(user("owner"))).andExpect(jsonPath("$[0].id").value(v.versionId())).andExpect(jsonPath("$[0].shared").value(false));
        mvc.perform(get("/api/rules/mine").with(user("other"))).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(put("/api/rules/"+v.versionId()+"/sharing").with(user("other")).with(csrf()).contentType("application/json").content("{\"shared\":true}")).andExpect(status().isNotFound());
        mvc.perform(put("/api/rules/"+v.versionId()+"/sharing").with(user("owner")).with(csrf()).contentType("application/json").content("{\"shared\":true}")).andExpect(jsonPath("$.shared").value(true));
        // The same contract cannot be registered twice while active.
        UUID again=request();worker.runOnce();worker.runOnce();drain(false,false);onboarding.advance();drain(false,false);onboarding.advance();drain(false,false);onboarding.advance();
        assertThat(view(again).status()).isEqualTo("FAILED");assertThat(view(again).error()).isEqualTo("DUPLICATE_RULE_CONTRACT");
    }
    @Autowired HybridRuleFollowup followup;
    @Test void styledRequestReachesTheAuthorWithoutPublicationPreferencesAndFollowupRecordsRefusal() throws Exception {
        var calls=new ArrayList<HybridRuleOnboarding.Call>();
        doAnswer(c->{var call=c.getArgument(0,HybridRuleOnboarding.Call.class);calls.add(call);
            if(call.role().equals("ORACLE"))return result(JudgeJson.JSON.createObjectNode().put("oracleSource","public class Main{public static void main(String[] a){}}"));
            return result(author());}).when(provider).generate(any());
        for(String bad:List.of("{\"request\":\"\",\"difficulty\":\"HARD\"}","{\"request\":\"아무 문제나\",\"difficulty\":\"LEGENDARY\",\"category\":\"bfs\"}","{\"category\":\"quantum\",\"difficulty\":\"EASY\"}"))
            mvc.perform(post("/api/rules/onboarding").with(user("owner")).with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(bad)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/rules/onboarding").with(user("owner")).with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json")
                .content("{\"difficulty\":\"EASY\",\"evaluationId\":\""+UUID.randomUUID()+"\",\"observationIndex\":0}")).andExpect(status().isNotFound());
        UUID id=UUID.randomUUID();
        mvc.perform(post("/api/rules/onboarding").with(user("owner")).with(csrf()).header("Idempotency-Key",id).contentType("application/json")
                .content("{\"request\":\"\",\"difficulty\":\"HARD\",\"style\":\"COMMAND\",\"category\":\"bfs\",\"publish\":true,\"shared\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.difficulty").value("HARD")).andExpect(jsonPath("$.style").value("COMMAND")).andExpect(jsonPath("$.publish").value(true));
        assertThat(worker.runOnce()).isTrue();
        var authorCall=calls.get(0);var input=JudgeJson.parse(authorCall.input());
        assertThat(input.path("difficulty").asText()).isEqualTo("HARD");assertThat(input.path("style").asText()).isEqualTo("COMMAND");assertThat(input.path("category").asText()).isEqualTo("bfs");
        assertThat(input.has("publish")||input.has("shared")).isFalse();
        assertThat(authorCall.instructions()).contains("Never name the technique","style COMMAND","mutants[0] must be a realistic");
        assertThat(authorCall.model().maxOutputTokens()).isEqualTo(28000);
        worker.runOnce();drain(false,false);onboarding.advance();drain(false,false);onboarding.advance();drain(false,false);onboarding.advance();
        assertThat(view(id).status()).isEqualTo("ACTIVE");
        followup.advance(); // rule-based admission is disabled in this environment: the refusal is recorded, not retried forever
        var v=view(id);assertThat(v.followupGenerationId()).isNull();assertThat(v.followupError()).isNotBlank();
        assertThat(jdbc.sql("SELECT count(*) FROM hybrid_generation WHERE id=?").param(HybridRuleFollowup.generationId(id)).query(Integer.class).single()).isZero();
    }
    @Test void failedQualificationIsSentBackToTheAuthorOnceWithTheFailedCheck() throws Exception {
        var calls=new ArrayList<HybridRuleOnboarding.Call>();
        doAnswer(c->{var call=c.getArgument(0,HybridRuleOnboarding.Call.class);calls.add(call);
            if(call.role().equals("ORACLE"))return result(JudgeJson.JSON.createObjectNode().put("oracleSource","public class Main{public static void main(String[] a){}}"));
            return result(author());}).when(provider).generate(any());
        overrides.put("HYBRID_RULE_ONBOARDING_REPAIRS","1");
        UUID id=request();worker.runOnce();worker.runOnce();
        drain(false,true);onboarding.advance(); // mutant-a survives on every tiny input
        var v=view(id);assertThat(v.status()).isEqualTo("QUEUED");assertThat(v.repairs()).isEqualTo(1);
        assertThat(worker.runOnce()).isTrue();
        var repair=JudgeJson.parse(calls.get(2).input()).path("repair");
        assertThat(repair.path("failure").path("code").asText()).isEqualTo("MUTANT_SURVIVED");
        assertThat(repair.path("previousPackage").path("reference").asText()).isNotBlank();
        assertThat(calls.get(2).instructions()).contains("If repair is present");
        worker.runOnce();drain(false,true);onboarding.advance(); // the same failure again: the single repair is spent
        v=view(id);assertThat(v.status()).isEqualTo("HELD");assertThat(v.error()).isEqualTo("MUTANT_SURVIVED");assertThat(v.repairs()).isEqualTo(1);
        assertThat(HybridRuleOnboarding.repairable("DUPLICATE_RULE_CONTRACT")).isFalse();assertThat(HybridRuleOnboarding.repairable("RUNNER_CE")).isTrue();
    }
    @Test void restartReleasesAnInterruptedCallInsteadOfBlockingUntilTheDeadline() throws Exception {
        doAnswer(c->{throw new IllegalStateException("process stopped before the provider returned");}).when(provider).generate(any());
        UUID id=request();
        var call=onboarding.claimCall();assertThat(call).isNotNull(); // claimed but never finished: the process "restarts" here
        assertThat(onboarding.claimCall()).isNull(); // one running call blocks every other claim
        onboarding.releaseInterruptedCalls();
        var v=view(id);assertThat(v.status()).isEqualTo("HELD");assertThat(v.error()).isEqualTo("INTERRUPTED_BY_RESTART");
        assertThat(jdbc.sql("SELECT status FROM ai_attempt WHERE id=?").param(call.attemptId()).query(String.class).single()).isEqualTo("ONBOARD_UNKNOWN");
        UUID next=request();assertThat(onboarding.claimCall()).isNotNull();assertThat(view(next).status()).isEqualTo("AUTHORING");
    }
    @Test void authorSchemaPinsKebabCaseActionIdsAndRejectedCandidateIsKeptForDiagnosis() throws Exception {
        var schema=HybridRuleOnboarding.authorSchema();
        assertThat(schema.path("properties").path("contract").path("properties").path("actions").path("items").path("properties").path("id").path("pattern").asText()).isEqualTo("^[a-z][a-z0-9-]{0,39}$");
        assertThat(schema.path("properties").path("rules").path("items").path("properties").path("id").path("pattern").asText()).isEqualTo("^[a-z][a-z0-9-]{0,39}$");
        var bad=author();((ObjectNode)bad.path("contract").path("actions").get(0)).put("id","Range_Sum");provide(bad);UUID id=request();worker.runOnce();
        assertThat(view(id).error()).isEqualTo("INVALID_RULE_ID");
        assertThat(jdbc.sql("SELECT author_json FROM hybrid_rule_onboarding WHERE id=?").param(id).query(String.class).single()).contains("Range_Sum");
        assertThat(drain(false,false)).isZero();
    }
    @Test void disagreementOrSurvivingMutantHoldsWithoutActivation() throws Exception {
        provide(author());UUID id=request();worker.runOnce();worker.runOnce();drain(true,false);onboarding.advance();
        assertThat(view(id).status()).isEqualTo("HELD");assertThat(view(id).error()).isEqualTo("REFERENCE_ORACLE_DISAGREEMENT");
        assertThat(drain(false,false)).isZero();
        UUID next=request();worker.runOnce();worker.runOnce();drain(false,true);onboarding.advance();
        assertThat(view(next).error()).isEqualTo("MUTANT_SURVIVED");assertThat(jdbc.sql("SELECT count(*) FROM hybrid_rule_version WHERE engine='PACKAGE_V1'").query(Integer.class).single()).isZero();
    }
    @Test void emptyInvalidInputIsAcceptedAndFailedCheckIsReported() throws Exception {
        var a=author();((com.fasterxml.jackson.databind.node.ArrayNode)a.path("invalidInputs")).add("");provide(a);validVerdict="WA";
        UUID id=request();worker.runOnce();worker.runOnce();assertThat(view(id).status()).isEqualTo("QUALIFYING");
        drain(false,false);onboarding.advance();
        assertThat(view(id).error()).isEqualTo("DOMAIN_VALIDATOR_REJECTED");assertThat(view(id).failedCheck()).isEqualTo("q-valid · tiny-0 WA");
    }
    @Test void moreTinyInputsThanOneRunnerPlanHoldsBeforeRunnerWork() throws Exception {
        var a=author();var t=(com.fasterxml.jackson.databind.node.ArrayNode)a.path("tinyInputs");
        for(int n=0;t.size()<=HybridRuleOnboarding.AUTHOR_MAX_TINY;n++)t.add((n+1)+" "+(n+2)+"\n1 1\n");
        provide(a);UUID id=request();worker.runOnce();
        assertThat(view(id).status()).isEqualTo("HELD");assertThat(view(id).error()).isEqualTo("RULE_TINY_INPUTS");
        assertThat(jdbc.sql("SELECT count(*) FROM judge_job").query(Integer.class).single()).isZero();
    }
    @Test void easyProblemsDoNotNeedATimingOutSlowSolution() throws Exception {
        provide(author());slowVerdict="AC";UUID id=UUID.randomUUID();
        mvc.perform(post("/api/rules/onboarding").with(user("owner")).with(csrf()).header("Idempotency-Key",id).contentType("application/json")
                .content("{\"request\":\"물건을 한 번씩만 골라 가치 합을 최대로 만드는 규칙\",\"difficulty\":\"EASY\"}")).andExpect(status().isOk());
        worker.runOnce();worker.runOnce();drain(false,false);onboarding.advance();drain(false,false);onboarding.advance();drain(false,false);onboarding.advance();
        assertThat(view(id).status()).isEqualTo("ACTIVE");
    }
    @Test void largeTestsMustMakeTheSlowSolutionTimeOut() throws Exception {
        provide(author());slowVerdict="AC";UUID id=request();worker.runOnce();worker.runOnce();drain(false,false);onboarding.advance();drain(false,false);onboarding.advance();
        assertThat(view(id).error()).isEqualTo("LARGE_TESTS_NOT_DISCRIMINATING");
        slowVerdict="WA";UUID wrong=request();worker.runOnce();worker.runOnce();drain(false,false);onboarding.advance();drain(false,false);onboarding.advance();
        assertThat(view(wrong).error()).isEqualTo("SLOW_SOLUTION_INCORRECT");
        assertThat(jdbc.sql("SELECT count(*) FROM hybrid_rule_version WHERE engine='PACKAGE_V1'").query(Integer.class).single()).isZero();
    }
    @Test void malformedAuthorBudgetCapAndCancellationStopBeforeRunnerWork() throws Exception {
        var bad=author();bad.remove("stressInputs");provide(bad);UUID id=request();worker.runOnce();
        assertThat(view(id).status()).isEqualTo("HELD");assertThat(drain(false,false)).isZero();
        overrides.put("HYBRID_RULE_ONBOARDING_BUDGET_USD","0.01");provide(author());UUID capped=request();worker.runOnce();
        assertThat(view(capped).error()).isEqualTo("ONBOARDING_BUDGET_CAP");
        overrides.remove("HYBRID_RULE_ONBOARDING_BUDGET_USD");UUID cancelled=request();
        mvc.perform(post("/api/rules/onboarding/"+cancelled+"/cancel").with(user("owner")).with(csrf())).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(worker.runOnce()).isFalse();
        overrides.put("HYBRID_RULE_ONBOARDING_ENABLED","false");
        mvc.perform(post("/api/rules/onboarding").with(user("owner")).with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json")
                .content("{\"request\":\"물건을 한 번씩만 골라 가치 합을 최대로 만드는 규칙\"}")).andExpect(status().isServiceUnavailable());
    }
    @Test void harnessRunsEachInputInAFreshMainCall() throws Exception {
        var compiler=javax.tools.ToolProvider.getSystemJavaCompiler();assumeCompiler(compiler);
        var dir=java.nio.file.Files.createTempDirectory("harness");
        String program="import java.util.*;public class Main{public static void main(String[] a){Scanner s=new Scanner(System.in);int n=s.nextInt();long t=0;for(int i=0;i<n;i++)t+=s.nextLong();System.out.println(t);}}";
        java.nio.file.Files.writeString(dir.resolve("Main.java"),HybridRuleOnboarding.harness(program));
        assertThat(compiler.run(null,null,null,dir.resolve("Main.java").toString())).isZero();
        var inputs=List.of("2\n1 2\n","3 5 5 5","1\n-7\n");
        var p=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",dir.toString(),"Main").start();
        p.getOutputStream().write(HybridRuleOnboarding.batch(inputs).getBytes(java.nio.charset.StandardCharsets.UTF_8));p.getOutputStream().close();
        String out=new String(p.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);assertThat(p.waitFor()).isZero();
        assertThat(HybridRuleOnboarding.unbatch(out,3)).containsExactly("3\n","15\n","-7\n");
        assertThatThrownBy(()->HybridRuleOnboarding.unbatch(out+"x",3)).isInstanceOf(IllegalArgumentException.class);
    }
    static void assumeCompiler(Object compiler){org.junit.jupiter.api.Assumptions.assumeTrue(compiler!=null,"JDK compiler unavailable");}
}
