package dev.gamjaoj;
import dev.gamjaoj.domain.ArtifactValidation;
import dev.gamjaoj.service.generation.GenerationResources;
import dev.gamjaoj.service.generation.GenerationDraftRecovery;
import dev.gamjaoj.repository.generation.HybridGenerationRepository;
import dev.gamjaoj.exception.AccountException;
import dev.gamjaoj.config.AiSettings;
import dev.gamjaoj.service.generation.GenerationJobs;
import dev.gamjaoj.service.generation.HybridArtifacts;
import dev.gamjaoj.service.generation.HybridGeneration;
import dev.gamjaoj.support.JudgeJson;
import dev.gamjaoj.service.judge.Submissions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import static dev.gamjaoj.service.generation.HybridGeneration.Role.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:hybrid;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa","spring.datasource.password=","gamjaoj.invite-code=test",
        "AI_API_ENABLED=false","AI_POLL_MS=3600000"})
@AutoConfigureMockMvc
class HybridGenerationIntegrationTest {
 @Autowired GenerationResources generationResources;
 @Autowired GenerationDraftRecovery generationDraftRecovery;
    @Autowired HybridGeneration hybrid;@Autowired JdbcClient jdbc;@Autowired MockMvc mvc;
    @Autowired AiSettings aiSettings;
    @Autowired Submissions submissions;@Autowired GenerationJobs legacy;
    @BeforeEach void setup() {
        jdbc.sql("DELETE FROM hybrid_generation").update();jdbc.sql("DELETE FROM generation_attempt").update();
        jdbc.sql("DELETE FROM generation_job").update();jdbc.sql("DELETE FROM ai_task").update();jdbc.sql("DELETE FROM app_user").update();
        for(String name:List.of("owner","other"))jdbc.sql("INSERT INTO app_user(id,username,password_hash,nickname) VALUES (?,?,?,?)")
                .param(UUID.randomUUID()).param(name).param("unused").param(name).update();
    }
    ObjectNode contract() {
        return (ObjectNode)JudgeJson.parse("""
          {"schemaVersion":"1",
           "domain":{"entities":"indexed items","types":"positive integer cost and value","relationships":"each index is a distinct item"},
           "input":{"format":"N W then N lines of cost value","indexing":"1-based","caseCount":"one"},
           "state":{"initial":"no items chosen; W capacity","mutable":"remaining capacity and chosen items"},
           "actions":[{"id":"choose","preconditions":"cost <= remaining capacity","transition":"subtract cost and add value","reuse":"at most once per item","resources":"capacity cannot become negative"}],
           "goal":{"kind":"MAXIMIZE","definition":"total chosen value"},"termination":"after considering all items",
           "output":{"format":"one integer and newline","ties":"print value only","empty":"zero","impossible":"empty choice always feasible","numericRange":"0..1000000"},
           "limits":{"maxInputSize":"1 <= N <= 100; 1 <= W <= 1000","executionConstraints":"cost 1..1000; value 1..10000"},
           "obligations":{"smallDomain":"N <= 4; W <= 8; cost/value 1..3","boundaryCases":["nothing fits","exact capacity"],"invalidCases":["cost zero"],"stress":"N=100, W=1000","mutants":["unbounded item reuse"]}}
          """);
    }
    ObjectNode core() {
        var c=JudgeJson.JSON.createObjectNode().put("schemaVersion","1");
        // Deliberately not executable proof; this phase must never publish these source fixtures.
        for(String key:List.of("reference","generator","inputValidator"))c.put(key,"class Main { /* fixture only */ }");
        c.putObject("authorNotes").put("algorithm","PRIVATE_DYNAMIC_PROGRAMMING").put("complexity","O(NW)").put("edgeCases","descending updates");return c;
    }
    ObjectNode presentation() {
        var p=JudgeJson.JSON.createObjectNode().put("schemaVersion","1").put("title","탐사 장비 선택").put("context","장비를 선택합니다.");
        p.putObject("sections").put("input","첫 줄에 N과 W가 주어지고, 다음 N줄에 각 장비의 비용과 가치가 주어집니다.")
                .put("output","고른 장비 가치 합의 최댓값을 한 줄에 출력합니다. 아무것도 고르지 않으면 0입니다.")
                .put("limits","1 ≤ N ≤ 100, 1 ≤ W ≤ 1,000이고 비용은 1 이상 1,000 이하, 가치는 1 이상 10,000 이하입니다.");
        p.set("semantics",HybridArtifacts.publicSemantics(contract()));
        p.putArray("ruleExplanations").addObject().put("id","choose").put("text","각 장비는 최대 한 번 선택할 수 있습니다.");
        p.putArray("hints").add("PRIVATE_HINT_1").add("PRIVATE_HINT_2").add("PRIVATE_HINT_3");p.put("editorial","PRIVATE_EDITORIAL");return p;
    }
    ObjectNode reader() {
        var r=JudgeJson.JSON.createObjectNode().put("schemaVersion","1").put("oracleSource","class Main { /* independent fixture */ }");
        r.putArray("interpretedRules").add("각 항목은 최대 한 번 선택합니다.");r.putArray("ambiguities");
        r.putObject("oracleDomain").put("inputDomain","N <= 4, W <= 8").put("enumeration","all subsets").put("limitations","not independent evidence for large cases");
        r.putArray("adversarialInputs").addObject().put("input","1 4\n2 3\n").put("reason","unbounded reuse would change the answer");
        r.put("coverageNotes","bounded fixture, execution adapter still required");r.putArray("examples");return r;
    }
    UUID start() {UUID id=UUID.randomUUID();hybrid.start("owner",id,"NEW_RULES; PRIVATE_LEARNER_CONTEXT",false);return id;}
    HybridGeneration.Completion result(HybridGeneration.Assignment a,JsonNode payload) {
        return new HybridGeneration.Completion(a.branchId(),a.revision(),a.role(),a.token(),a.inputHash(),a.contractHash(),a.publicHash(),payload,
                JudgeJson.JSON.createObjectNode().put("billingMode","FIXTURE").put("actualCostKnown",false),null);
    }
    void finish(UUID id,HybridGeneration.Role role,JsonNode payload){assertThat(hybrid.complete(result(hybrid.claim(id,role),payload))).isTrue();}
    UUID designed(){UUID id=start();finish(id,CONTRACT,contract());return id;}
    UUID joined(){UUID id=designed();finish(id,CORE,core());finish(id,PRESENTATION,presentation());finish(id,READER,reader());return id;}
    int count(String table){return jdbc.sql("SELECT count(*) FROM "+table).query(Integer.class).single();}
    String branchStatus(UUID id){return jdbc.sql("SELECT status FROM hybrid_branch WHERE id=?").param(id).query(String.class).single();}

    @Test void tagListIsNotAContractAndMissingBoundariesDoNotLaunchBranches() {
        UUID id=start();var design=hybrid.claim(id,CONTRACT);
        assertThat(hybrid.complete(result(design,JudgeJson.parse("{\"tags\":[\"dp\"],\"difficulty\":\"medium\"}")))).isFalse();
        assertThat(hybrid.view("owner",id).status()).isEqualTo("HELD");assertThat(count("hybrid_branch")).isEqualTo(1);
        for(String boundary:List.of("ties","empty","impossible")) {
            id=start();design=hybrid.claim(id,CONTRACT);ObjectNode c=contract();((ObjectNode)c.path("output")).remove(boundary);
            assertThat(hybrid.complete(result(design,c))).isFalse();assertThat(hybrid.claim(id,CORE)).isNull();
        }
        assertThat(count("ai_task")).isZero();assertThat(count("judge_job")).isZero();
    }
    @Test void acceptedContractDurablyUnblocksBothBranchesAndDuplicateDeliveryDispatchesOnce() throws Exception {
        UUID id=start();var design=hybrid.claim(id,CONTRACT);var completion=result(design,contract());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var barrier=new CountDownLatch(1);var a=pool.submit(()->{barrier.await();return hybrid.complete(completion);});
            var b=pool.submit(()->{barrier.await();return hybrid.complete(completion);});barrier.countDown();
            assertThat(a.get(10,TimeUnit.SECONDS)).isTrue();assertThat(b.get(10,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(count("hybrid_branch")).isEqualTo(3);assertThat(count("hybrid_artifact")).isEqualTo(1);
        var core=hybrid.claim(id,CORE);var writer=hybrid.claim(id,PRESENTATION);
        assertThat(core).isNotNull();assertThat(writer).isNotNull();assertThat(core.contractHash()).isEqualTo(writer.contractHash());
        assertThat(hybrid.view("owner",id).branches()).containsEntry(CORE,"RUNNING").containsEntry(PRESENTATION,"RUNNING");
        assertThat(hybrid.claim(id,CORE)).isNull();assertThat(hybrid.claim(id,READER)).isNull();
    }
    @Test void readerStartsBeforeCoreFinishesAndContainsOnlyTheAcceptedPublicSnapshot() {
        UUID id=designed();var core=hybrid.claim(id,CORE);finish(id,PRESENTATION,presentation());
        var reader=hybrid.claim(id,READER);assertThat(reader).isNotNull();
        assertThat(branchStatus(core.branchId())).isEqualTo("RUNNING");
        assertThat(reader.input()).isEqualTo(HybridArtifacts.publicSnapshot(presentation()));
        assertThat(reader.input().toString()).doesNotContain("PRIVATE_","oracleStrategy","sampleOutput","expected","attemptToken","obligations");
        assertThat(reader.publicHash()).isEqualTo(JudgeJson.hash(JudgeJson.canonical(reader.input())));
        assertThat(count("ai_attempt")).isZero();
    }
    @Test void canonicalPublicRuleChangesAndGuessedSampleOutputsAreRejected() {
        UUID id=designed();var writer=hybrid.claim(id,PRESENTATION);var p=presentation();
        ((ObjectNode)p.path("semantics").path("actions").get(0)).put("reuse","exactly once per item");
        assertThat(hybrid.complete(result(writer,p))).isFalse();
        assertThat(hybrid.view("owner",id).error()).isEqualTo("PUBLIC_CONTRACT_MISMATCH");assertThat(hybrid.claim(id,READER)).isNull();
        id=designed();writer=hybrid.claim(id,PRESENTATION);p=presentation();p.put("sampleOutput","model guessed answer");
        assertThat(hybrid.complete(result(writer,p))).isFalse();assertThat(hybrid.claim(id,READER)).isNull();
    }
    @Test void compatibleJoinPersistsManifestButCannotPublishWithoutRealValidation() {
        int problems=count("problem_version");UUID id=joined();var view=hybrid.view("owner",id);
        assertThat(view.status()).isEqualTo("HELD");assertThat(view.error()).isEqualTo("VALIDATION_ADAPTER_NOT_CONNECTED");
        assertThat(view.branches()).containsEntry(VALIDATION,"BLOCKED");assertThat(hybrid.claim(id,VALIDATION)).isNull();
        var manifest=JudgeJson.parse(jdbc.sql("SELECT input_json FROM hybrid_branch WHERE generation_id=? AND role='VALIDATION'").param(id).query(String.class).single());
        assertThat(manifest.path("contractHash").asText()).isEqualTo(view.contractHash());
        assertThat(manifest.path("publicHash").asText()).isEqualTo(view.publicHash());
        assertThat(manifest.path("artifacts").size()).isEqualTo(4);
        assertThat(count("problem_version")).isEqualTo(problems);assertThat(count("judge_job")).isZero();
        assertThat(count("ai_task")).isZero();assertThat(count("ai_attempt")).isZero();
    }
    @Test void wrongEnvelopeCannotCrossBranchOrRevisionAndStoredResultCannotBeReplaced() {
        UUID id=designed();var a=hybrid.claim(id,CORE);var c=result(a,core());
        var wrong=new HybridGeneration.Completion(c.branchId(),c.revision()+1,c.role(),c.token(),c.inputHash(),c.contractHash(),c.publicHash(),c.payload(),c.usage(),null);
        var wrongRevision=wrong;assertThatThrownBy(()->hybrid.complete(wrongRevision)).isInstanceOf(AccountException.class);
        wrong=new HybridGeneration.Completion(c.branchId(),c.revision(),c.role(),UUID.randomUUID(),c.inputHash(),c.contractHash(),c.publicHash(),c.payload(),c.usage(),null);
        var wrongToken=wrong;assertThatThrownBy(()->hybrid.complete(wrongToken)).isInstanceOf(AccountException.class);
        assertThat(hybrid.complete(c)).isTrue();
        var changed=core().put("reference","different source");assertThatThrownBy(()->hybrid.complete(result(a,changed))).isInstanceOf(AccountException.class);
        assertThat(hybrid.complete(c)).isTrue();assertThat(count("hybrid_artifact")).isEqualTo(2);
    }
    @Test void revisionChangeFencesOldResultsButRetainsTheirUncertainUsage() {
        UUID id=designed();var core=hybrid.claim(id,CORE);var old=hybrid.view("owner",id);
        hybrid.reviseContract("owner",id,0,old.contractHash());
        assertThat(hybrid.complete(result(core,core()))).isFalse();assertThat(hybrid.view("owner",id).revision()).isEqualTo(1);
        assertThat(hybrid.view("owner",id).branches()).containsEntry(CORE,"NOT_STARTED");
        var row=jdbc.sql("SELECT late_result,completion_json FROM hybrid_branch WHERE id=?").param(core.branchId()).query((r,n)->List.of(r.getBoolean(1),r.getString(2))).single();
        assertThat(row.get(0)).isEqualTo(true);assertThat(row.get(1).toString()).contains("actualCostKnown\":false");
        finish(id,CONTRACT,contract());assertThat(hybrid.claim(id,CORE).revision()).isEqualTo(1);
    }
    @Test void coreRepairPreservesReaderAndUsesTheOnlyGlobalRepairRound() {
        UUID id=joined();var before=hybrid.view("owner",id);
        String hash=jdbc.sql("SELECT input_sha256 FROM hybrid_branch WHERE generation_id=? AND role='CORE'").param(id).query(String.class).single();
        hybrid.repair("owner",id,0,CORE,hash);assertThat(hybrid.view("owner",id).publicHash()).isEqualTo(before.publicHash());
        assertThat(hybrid.view("owner",id).branches()).containsEntry(READER,"SUCCEEDED");
        finish(id,CORE,core().put("reference","class Main { /* repaired fixture */ }"));
        assertThat(hybrid.view("owner",id).error()).isEqualTo("VALIDATION_ADAPTER_NOT_CONNECTED");
        assertThatThrownBy(()->hybrid.repair("owner",id,0,PRESENTATION,hash)).isInstanceOf(AccountException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM hybrid_branch WHERE generation_id=? AND role='VALIDATION'").param(id).query(Integer.class).single()).isEqualTo(2);
    }
    @Test void everyPublicTextChangeInvalidatesReaderIncludingCosmeticEdits() {
        UUID id=designed();finish(id,CORE,core());finish(id,PRESENTATION,presentation());var oldReader=hybrid.claim(id,READER);
        String hash=jdbc.sql("SELECT input_sha256 FROM hybrid_branch WHERE generation_id=? AND role='PRESENTATION'").param(id).query(String.class).single();
        hybrid.repair("owner",id,0,PRESENTATION,hash);
        assertThat(hybrid.complete(result(oldReader,reader()))).isFalse();
        finish(id,PRESENTATION,presentation().put("title","새 제목"));var nextReader=hybrid.claim(id,READER);
        assertThat(nextReader.publicHash()).isNotEqualTo(oldReader.publicHash());assertThat(nextReader.input().path("title").asText()).isEqualTo("새 제목");
        assertThat(hybrid.view("owner",id).branches()).containsEntry(CORE,"SUCCEEDED");
        assertThat(hybrid.complete(result(nextReader,reader()))).isTrue();
    }
    @Test void failedWriterCanBeRepairedWithoutDiscardingOrRepeatingIndependentCoreWork() {
        UUID id=designed();var core=hybrid.claim(id,CORE);var writer=hybrid.claim(id,PRESENTATION);
        assertThat(hybrid.complete(result(writer,presentation().put("unexpected","invalid schema")))).isFalse();
        assertThat(hybrid.claim(id,READER)).isNull();
        assertThat(hybrid.complete(result(core,core()))).isTrue();
        assertThat(hybrid.view("owner",id).status()).isEqualTo("HELD");
        hybrid.repair("owner",id,0,PRESENTATION,writer.inputHash());
        finish(id,PRESENTATION,presentation());finish(id,READER,reader());
        assertThat(hybrid.view("owner",id).error()).isEqualTo("VALIDATION_ADAPTER_NOT_CONNECTED");
        assertThat(jdbc.sql("SELECT count(*) FROM hybrid_branch WHERE generation_id=? AND role='CORE'").param(id).query(Integer.class).single()).isEqualTo(1);
    }
    @Test void ownerCanCancelAHeldJobWithIndependentWorkStillRunning() {
        UUID id=designed();var core=hybrid.claim(id,CORE);var writer=hybrid.claim(id,PRESENTATION);
        assertThat(hybrid.complete(result(writer,presentation().put("unexpected","invalid")))).isFalse();
        assertThat(hybrid.cancel("owner",id).status()).isEqualTo("CANCELLED");
        assertThat(hybrid.complete(result(core,core()))).isFalse();
        assertThatThrownBy(()->hybrid.repair("owner",id,0,PRESENTATION,writer.inputHash())).isInstanceOf(AccountException.class);
    }
    @Test void readerCoverageLimitationsDoNotBlockButValidDomainAmbiguityStillDoes() {
        UUID id=designed();var core=hybrid.claim(id,CORE);finish(id,PRESENTATION,presentation());
        var r=reader().put("coverageNotes","Malformed, extra-token and out-of-range inputs are outside the task. Full-size cases are not covered by this tiny oracle.");
        ((ObjectNode)r.path("oracleDomain")).put("limitations","Only the declared tiny valid domain is enumerated.");
        assertThat(hybrid.complete(result(hybrid.claim(id,READER),r))).isTrue();
        assertThat(hybrid.view("owner",id).branches()).containsEntry(CORE,"RUNNING").containsEntry(READER,"SUCCEEDED").containsEntry(VALIDATION,"NOT_STARTED");
        assertThat(hybrid.complete(result(core,core()))).isTrue();
        assertThat(hybrid.view("owner",id).error()).isEqualTo("VALIDATION_ADAPTER_NOT_CONNECTED");

        UUID blocked=designed();finish(blocked,PRESENTATION,presentation());
        r.withArray("ambiguities").add("For one item of cost 1 and value 1 at capacity 2, reuse is unspecified: once gives 1, unlimited gives 2.");
        assertThat(hybrid.complete(result(hybrid.claim(blocked,READER),r))).isFalse();
        assertThat(hybrid.view("owner",blocked).error()).isEqualTo("READER_AMBIGUITY");
        assertThat(hybrid.view("owner",blocked).branches()).containsEntry(VALIDATION,"NOT_STARTED");
    }
    @Test void ambiguousReaderAndMissingOracleCannotJoin() {
        UUID id=designed();finish(id,CORE,core());finish(id,PRESENTATION,presentation());var r=reader();r.withArray("ambiguities").add("item reuse is unclear");
        assertThat(hybrid.complete(result(hybrid.claim(id,READER),r))).isFalse();assertThat(hybrid.view("owner",id).error()).isEqualTo("READER_AMBIGUITY");
        assertThat(hybrid.view("owner",id).branches()).containsEntry(VALIDATION,"NOT_STARTED");
        assertThatThrownBy(()->HybridArtifacts.reader(reader().put("oracleSource",""))).isInstanceOf(ArtifactValidation.Invalid.class);
    }
    @Test void cancellationAndCompletionRaceNeverPublishesAndNeverErasesUsage() throws Exception {
        UUID id=designed();var core=hybrid.claim(id,CORE);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var barrier=new CountDownLatch(1);var a=pool.submit(()->{barrier.await();return hybrid.cancel("owner",id);});
            var b=pool.submit(()->{barrier.await();return hybrid.complete(result(core,core()));});barrier.countDown();
            a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
        }
        assertThat(hybrid.view("owner",id).status()).isEqualTo("CANCELLED");assertThat(hybrid.claim(id,PRESENTATION)).isNull();
        assertThat(jdbc.sql("SELECT completion_json FROM hybrid_branch WHERE id=?").param(core.branchId()).query(String.class).single()).contains("actualCostKnown");
        assertThat(hybrid.cancel("owner",id).status()).isEqualTo("CANCELLED");
    }
    @Test void deadlineStopsNewDispatchAndFencesInFlightResults() {
        UUID id=designed();var core=hybrid.claim(id,CORE);
        jdbc.sql("UPDATE hybrid_generation SET deadline_at=? WHERE id=?").param(OffsetDateTime.now().minusSeconds(1)).param(id).update();
        assertThat(hybrid.complete(result(core,core()))).isFalse();assertThat(hybrid.claim(id,PRESENTATION)).isNull();
        assertThat(hybrid.view("owner",id).status()).isEqualTo("DEADLINE_EXCEEDED");
        id=start();jdbc.sql("UPDATE hybrid_generation SET deadline_at=? WHERE id=?").param(OffsetDateTime.now().minusSeconds(1)).param(id).update();
        hybrid.expirePending();assertThat(hybrid.view("owner",id).status()).isEqualTo("DEADLINE_EXCEEDED");
    }
    @Test void ownershipAndHttpAdmissionRemainClosedWithoutPaidWork() throws Exception {
        UUID id=start();
        mvc.perform(get("/api/generation/hybrid/"+id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/generation/hybrid/"+id).with(user("other"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/generation/hybrid/"+id).with(user("owner"))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("QUEUED")).andExpect(jsonPath("$.request").doesNotExist());
        mvc.perform(post("/api/generation/hybrid").with(user("owner")).with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{\"profileId\":\"zero-one-items-v1\",\"shared\":false,\"publishOnSuccess\":true}")).andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/generation/hybrid/"+id+"/cancel").with(user("owner"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/generation/hybrid/"+id+"/cancel").with(user("other")).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/api/generation/hybrid/"+id+"/cancel").with(user("owner")).with(csrf())).andExpect(status().isOk());
        assertThat(count("ai_task")).isZero();assertThat(count("ai_attempt")).isZero();
    }
    @Test void legacyGenerationRemainsAvailableAndHybridRequestIdentityIsOwnerScoped() {
        UUID id=start();assertThat(hybrid.start("owner",id,"NEW_RULES; PRIVATE_LEARNER_CONTEXT",false).id()).isEqualTo(id);
        assertThatThrownBy(()->hybrid.start("other",id,"NEW_RULES; PRIVATE_LEARNER_CONTEXT",false)).isInstanceOf(AccountException.class);
        assertThatThrownBy(()->hybrid.start("owner",id,"changed",false)).isInstanceOf(AccountException.class);
        assertThat(legacy.create("owner",UUID.randomUUID(),"sequence-sum-v1","basics",null).status()).isEqualTo("QUEUED");
        assertThat(count("hybrid_generation")).isEqualTo(1);
    }
    @Test void durableAssignmentsCanBeReadAfterServiceRecreationWithoutNewDispatch() {
        UUID id=designed();var core=hybrid.claim(id,CORE);var recreated=new HybridGeneration(new HybridGenerationRepository(jdbc),submissions,aiSettings,generationDraftRecovery,generationResources);
        assertThat(recreated.view("owner",id).branches()).containsEntry(CORE,"RUNNING").containsEntry(PRESENTATION,"QUEUED");
        assertThat(recreated.claim(id,CORE)).isNull();assertThat(count("hybrid_branch")).isEqualTo(3);
        assertThat(hybrid.complete(result(core,core()))).isTrue();
    }
    @Test void artifactTamperingCannotProduceAJoin() {
        UUID id=designed();finish(id,CORE,core());finish(id,PRESENTATION,presentation());
        jdbc.sql("UPDATE hybrid_artifact SET payload_json='{}' WHERE branch_id IN (SELECT id FROM hybrid_branch WHERE generation_id=? AND role='CORE')").param(id).update();
        assertThat(hybrid.complete(result(hybrid.claim(id,READER),reader()))).isFalse();
        assertThat(hybrid.view("owner",id).error()).isEqualTo("ARTIFACT_INTEGRITY_FAILURE");
    }
    @Test void sourceLimitsCountUtf8BytesAndOversizedCodeCannotUnblockValidation() {
        UUID id=designed();var code=core().put("reference","한".repeat(22000));
        assertThat(hybrid.complete(result(hybrid.claim(id,CORE),code))).isFalse();
        assertThat(hybrid.view("owner",id).error()).isEqualTo("SOURCE_TOO_LARGE");
        assertThatThrownBy(()->HybridArtifacts.reader(reader().put("oracleSource","한".repeat(22000))))
                .isInstanceOf(ArtifactValidation.Invalid.class).hasMessage("SOURCE_TOO_LARGE");
    }
}
