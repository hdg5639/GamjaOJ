package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import static dev.gamjaoj.HybridGeneration.Role.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:hybridrunner;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa","spring.datasource.password=","gamjaoj.invite-code=test",
        "AI_API_ENABLED=false","AI_POLL_MS=3600000","HYBRID_VALIDATION_PROFILE=hybrid-execution-smoke-v1"})
class HybridRunnerIntegrationTest {
    @Autowired AiTasks ledger;
    @Autowired HybridRunnerChecks checks;@Autowired HybridGeneration hybrid;@Autowired JudgeQueue queue;
    @Autowired JdbcClient jdbc;@Autowired Submissions submissions;
    @Autowired org.springframework.core.env.ConfigurableEnvironment env;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    final HybridGenerationIntegrationTest f=new HybridGenerationIntegrationTest();
    @BeforeEach void setup(){
        env.getPropertySources().remove("finite-profile-test");
        jdbc.sql("DELETE FROM hybrid_execution_check").update();jdbc.sql("DELETE FROM submission").update();
        jdbc.sql("DELETE FROM hybrid_generation").update();jdbc.sql("DELETE FROM problem_version WHERE id LIKE 'hybrid-check-%'").update();
        jdbc.sql("DELETE FROM app_user").update();
        jdbc.sql("INSERT INTO app_user(id,username,password_hash,nickname) VALUES (?,'owner','unused','owner')").param(UUID.randomUUID()).update();
    }
    UUID joined(){
        UUID id=UUID.randomUUID();hybrid.start("owner",id,"new problem",false);
        for(var role:List.of(CONTRACT,CORE,PRESENTATION,READER)){
            var a=hybrid.claim(id,role);JsonNode payload=switch(role){case CONTRACT->f.contract();case CORE->f.core();case PRESENTATION->f.presentation();default->f.reader();};
            assertThat(hybrid.complete(f.result(a,payload))).isTrue();
        }
        return id;
    }
    String role(JudgeQueue.Assignment a){return jdbc.sql("SELECT role FROM hybrid_execution_check WHERE submission_id=?").param(a.submissionId()).query(String.class).single();}
    void complete(JudgeQueue.Assignment a,String verdict,String stdout){
        var r=new GenerationIntegrationTest().report(a,verdict);
        for(var t:r.path("tests"))((com.fasterxml.jackson.databind.node.ObjectNode)t).put("stdout",stdout).put("stderr","").put("stdout_truncated",false).put("wall_ms",10);
        queue.complete(a.submissionId(),a.token(),r);
    }
    void drain(boolean disagree){
        Optional<JudgeQueue.Assignment> next;
        while((next=queue.claim(UUID.randomUUID())).isPresent()){
            var a=next.get();String role=role(a);
            complete(a,role.equals("validator")?"AC":"OK",role.equals("generator")?"[\"1 4\\n2 3\\n\",\"1 1\\n2 3\\n\",\"1 3\\n2 3\\n\",\"1 2\\n2 3\\n\"]":disagree&&role.startsWith("oracle")?"4\n":"3\n");
        }
    }
    int jobs(){return jdbc.sql("SELECT count(*) FROM hybrid_execution_check").query(Integer.class).single();}
    @Test void realQueueStagesAreIdempotentAndSuccessfulProbesCannotPublish(){
        UUID id=joined();checks.advance();checks.advance();assertThat(jobs()).isEqualTo(1);
        drain(false);checks.advance();assertThat(jobs()).isEqualTo(2);
        drain(false);checks.advance();assertThat(jobs()).isEqualTo(10);
        drain(false);checks.advance();checks.advance();assertThat(jobs()).isEqualTo(10);
        assertThat(hybrid.view("owner",id).error()).isEqualTo("MISSING_PUBLICATION_EVIDENCE");
        var report=JudgeJson.parse(jdbc.sql("SELECT completion_json FROM hybrid_branch WHERE generation_id=? AND role='VALIDATION'").param(id).query(String.class).single());
        assertThat(report.path("publishable").asBoolean()).isFalse();assertThat(report.path("oracleDomainVerified").asBoolean()).isFalse();
        assertThat(report.path("executionProbes").size()).isEqualTo(4);assertThat(report.path("remaining").size()).isEqualTo(8);
        assertThat(jdbc.sql("SELECT count(*) FROM problem_version WHERE id LIKE 'hybrid-check-%' AND ready=true").query(Integer.class).single()).isZero();
    }
    @Test void invalidGeneratedEnvelopeCannotEnqueueDependentWork(){
        UUID id=joined();checks.advance();var a=queue.claim(UUID.randomUUID()).orElseThrow();complete(a,"OK","[\"one\"]");checks.advance();
        assertThat(jobs()).isEqualTo(1);assertThat(hybrid.view("owner",id).error()).isEqualTo("INVALID_GENERATOR_ENVELOPE");
    }
    @Test void invalidInputStopsBeforeReferenceOrOracleExecution(){
        UUID id=joined();checks.advance();drain(false);checks.advance();var a=queue.claim(UUID.randomUUID()).orElseThrow();complete(a,"WA","INVALID\n");checks.advance();
        assertThat(jobs()).isEqualTo(2);assertThat(hybrid.view("owner",id).error()).isEqualTo("RUNNER_WA");
    }
    @Test void disagreementDoesNotBecomeSampleOrPublicationEvidence(){
        UUID id=joined();checks.advance();drain(false);checks.advance();drain(false);checks.advance();drain(true);checks.advance();
        assertThat(hybrid.view("owner",id).error()).isEqualTo("REFERENCE_ORACLE_DISAGREEMENT");
    }
    @Test void cancellationPreventsQueuedRunnerClaimsAndHidesInternalSubmissions(){
        UUID id=joined();checks.advance();UUID submission=jdbc.sql("SELECT submission_id FROM hybrid_execution_check").query(UUID.class).single();
        assertThatThrownBy(()->submissions.runDetail("owner",submission)).isInstanceOf(AccountException.class);
        hybrid.cancel("owner",id);assertThat(queue.claim(UUID.randomUUID())).isEmpty();checks.advance();
        assertThat(hybrid.view("owner",id).status()).isEqualTo("CANCELLED");assertThat(jobs()).isEqualTo(1);
    }
    @Test void lateRunnerResultCannotAdvanceAfterCancellation(){
        UUID id=joined();checks.advance();var a=queue.claim(UUID.randomUUID()).orElseThrow();hybrid.cancel("owner",id);
        complete(a,"OK","[\"a\",\"b\",\"c\",\"d\"]");checks.advance();
        assertThat(jobs()).isEqualTo(1);assertThat(hybrid.view("owner",id).status()).isEqualTo("CANCELLED");
    }
    @Test void tamperedArtifactOrExecutionCannotSupplyEvidence(){
        UUID id=joined();checks.advance();
        jdbc.sql("UPDATE submission SET source_code='tampered' WHERE hybrid_branch_id IS NOT NULL").update();
        checks.advance();assertThat(hybrid.view("owner",id).error()).isEqualTo("RUNNER_INPUT_FENCE");
    }
    @Test void deadlineStopsNewClaimsBeforeRecoveryTick(){
        UUID id=joined();checks.advance();
        jdbc.sql("UPDATE hybrid_generation SET deadline_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE id=?").param(id).update();
        assertThat(queue.claim(UUID.randomUUID())).isEmpty();hybrid.expirePending();checks.advance();
        assertThat(hybrid.view("owner",id).status()).isEqualTo("DEADLINE_EXCEEDED");
    }
    void finite(){env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("finite-profile-test",Map.of("HYBRID_VALIDATION_PROFILE",HybridFiniteProfile.POLICY)));}
    @AfterEach void resetProfile(){env.getPropertySources().remove("finite-profile-test");}
    void drainFinite(String failingRole){
        Optional<JudgeQueue.Assignment> next;
        while((next=queue.claim(UUID.randomUUID())).isPresent()){
            var a=next.get();complete(a,role(a).equals(failingRole)?"WA":"AC","");
        }
    }
    @Test void finiteProfileEnumeratesTrustedAnswersAndPinsPolicyAcrossRestart(){
        finite();UUID id=joined();checks.advance();assertThat(jobs()).isEqualTo(2);
        drainFinite("");
        env.getPropertySources().remove("finite-profile-test"); // Global setting changes do not change in-flight policy.
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status->new HybridRunnerChecks(jdbc,new AiSettings(env),ledger).advance());assertThat(jobs()).isEqualTo(4);
        var a=queue.claim(UUID.randomUUID()).orElseThrow();
        assertThat(a.problem().path("tests").size()).isEqualTo(16);
        assertThat(a.problem().path("tests").path(0).path("output").asText()).isEqualTo("1\n");
        complete(a,"AC","");drainFinite("");checks.advance();checks.advance();
        assertThat(jobs()).isEqualTo(4);assertThat(hybrid.view("owner",id).error()).isEqualTo("MISSING_PUBLICATION_EVIDENCE");
        var report=JudgeJson.parse(jdbc.sql("SELECT completion_json FROM hybrid_branch WHERE generation_id=? AND role='VALIDATION'").param(id).query(String.class).single());
        assertThat(report.path("finiteCoverage").path("cases").asInt()).isEqualTo(16);
        assertThat(report.path("finiteCoverage").path("entireContractExhausted").asBoolean()).isFalse();
        assertThat(report.path("oracleDomainVerified").asBoolean()).isTrue();assertThat(report.path("publishable").asBoolean()).isFalse();
    }
    @Test void finiteProfileDoesNotInferSupportFromTagsOrNearlyIdenticalContract(){
        finite();UUID id=UUID.randomUUID();hybrid.start("owner",id,"knapsack",false);
        var contract=f.contract();((com.fasterxml.jackson.databind.node.ObjectNode)contract.path("actions").path(0)).put("reuse","exactly once per item");
        assertThat(hybrid.complete(f.result(hybrid.claim(id,CONTRACT),contract))).isTrue();
        assertThat(hybrid.complete(f.result(hybrid.claim(id,CORE),f.core()))).isTrue();
        var presentation=f.presentation();presentation.set("semantics",HybridArtifacts.publicSemantics(contract));
        assertThat(hybrid.complete(f.result(hybrid.claim(id,PRESENTATION),presentation))).isTrue();
        assertThat(hybrid.complete(f.result(hybrid.claim(id,READER),f.reader()))).isTrue();
        checks.advance();assertThat(jobs()).isZero();assertThat(hybrid.view("owner",id).error()).isEqualTo("UNSUPPORTED_FINITE_CONTRACT");
    }
    @Test void invalidInputAcceptanceStopsFiniteReferenceAndOracle(){
        finite();UUID id=joined();checks.advance();drainFinite("domain-invalid");checks.advance();
        assertThat(jobs()).isEqualTo(2);assertThat(hybrid.view("owner",id).error()).isEqualTo("RUNNER_WA");
    }
    @Test void sharedWrongAnswerCannotPassMerelyBecauseReferenceAndOracleAgree(){
        finite();UUID id=joined();checks.advance();drainFinite("");checks.advance();drainFinite("domain-reference");checks.advance();
        assertThat(hybrid.view("owner",id).error()).isEqualTo("RUNNER_WA");
    }
    @Test void tamperedFiniteProfileHashStopsAdditionalDispatch(){
        finite();UUID id=joined();checks.advance();
        jdbc.sql("UPDATE hybrid_validation_profile SET profile_hash=?").param("0".repeat(64)).update();
        checks.advance();assertThat(jobs()).isEqualTo(2);assertThat(hybrid.view("owner",id).error()).isEqualTo("PROFILE_HASH_MISMATCH");
        assertThat(queue.claim(UUID.randomUUID())).isEmpty();
    }

    UUID extendedToMutants(){
        env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("finite-profile-test",Map.of("HYBRID_VALIDATION_PROFILE",HybridFiniteProfile.EXTENDED_POLICY)));
        UUID id=joined();checks.advance();drainFinite("");checks.advance();drainFinite("");checks.advance();
        assertThat(jobs()).isEqualTo(7);return id;
    }
    void drainExtended(String failRole,String failVerdict){
        Optional<JudgeQueue.Assignment> next;
        while((next=queue.claim(UUID.randomUUID())).isPresent()){
            var a=next.get();String role=role(a);
            complete(a,role.equals(failRole)?failVerdict:role.startsWith("mutant-")?"WA":"AC","");
        }
    }
    @Test void extendedProfileStoresMutantAndMaximumInputEvidenceWithoutPublishing(){
        UUID id=extendedToMutants();checks.advance();assertThat(jobs()).isEqualTo(7);
        drainExtended("","");checks.advance();checks.advance();assertThat(jobs()).isEqualTo(9);
        drainExtended("","");checks.advance();checks.advance();assertThat(jobs()).isEqualTo(9);
        var report=JudgeJson.parse(jdbc.sql("SELECT completion_json FROM hybrid_branch WHERE generation_id=? AND role='VALIDATION'").param(id).query(String.class).single());
        assertThat(report.path("mutantWitnesses").size()).isEqualTo(2);
        assertThat(report.path("maximumInputChecks").path("repetitions").asInt()).isEqualTo(2);
        assertThat(report.path("remaining").size()).isEqualTo(4);assertThat(report.path("publishable").asBoolean()).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM problem_version WHERE id LIKE 'hybrid-check-%' AND ready=true").query(Integer.class).single()).isZero();
    }
    @Test void mutantCompileFailureIsNotAValidWitness(){
        UUID id=extendedToMutants();drainExtended("mutant-unbounded","CE");checks.advance();
        assertThat(jobs()).isEqualTo(7);assertThat(hybrid.view("owner",id).error()).isEqualTo("MUTANT_CE");
    }
    @Test void survivingMutantPreventsStressDispatch(){
        UUID id=extendedToMutants();drainExtended("mutant-strict-fit","AC");checks.advance();
        assertThat(jobs()).isEqualTo(7);assertThat(hybrid.view("owner",id).error()).isEqualTo("MUTANT_SURVIVED");
    }
    @Test void stressInputsMustPassValidatorBeforeExecution(){
        UUID id=extendedToMutants();drainExtended("stress-valid","WA");checks.advance();
        assertThat(jobs()).isEqualTo(7);assertThat(hybrid.view("owner",id).error()).isEqualTo("RUNNER_WA");
    }
    @Test void passingStressVerdictWithoutTimeMarginCannotPassResourceGate(){
        UUID id=extendedToMutants();drainExtended("","");checks.advance();
        var a=queue.claim(UUID.randomUUID()).orElseThrow();var report=new GenerationIntegrationTest().report(a,"AC");
        for(var test:report.path("tests"))((com.fasterxml.jackson.databind.node.ObjectNode)test).put("wall_ms",4001);
        queue.complete(a.submissionId(),a.token(),report);drainExtended("","");checks.advance();
        assertThat(hybrid.view("owner",id).error()).isEqualTo("STRESS_RESOURCE_MARGIN");
    }

    static final String GENERATED="[\"1 4\\n2 3\\n\",\"1 1\\n2 3\\n\",\"1 3\\n2 3\\n\",\"5 8\\n1 1\\n1 1\\n1 1\\n1 1\\n1 1\\n\"]";
    UUID packageToGenerator(){
        env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("finite-profile-test",Map.of("HYBRID_VALIDATION_PROFILE",HybridFiniteProfile.PACKAGE_POLICY)));
        UUID id=joined();checks.advance();drainFinite("");checks.advance();drainFinite("");checks.advance();
        drainExtended("","");checks.advance();drainExtended("","");checks.advance();assertThat(jobs()).isEqualTo(10);return id;
    }
    void drainPackage(){
        Optional<JudgeQueue.Assignment> next;
        while((next=queue.claim(UUID.randomUUID())).isPresent()) {
            var a=next.get();complete(a,role(a).equals("package-generator")?"OK":"AC",role(a).equals("package-generator")?GENERATED:"");
        }
    }
    UUID packageToReplay(){
        UUID id=packageToGenerator();drainPackage();checks.advance();assertThat(jobs()).isEqualTo(11);
        drainPackage();checks.advance();assertThat(jobs()).isEqualTo(13);
        drainPackage();checks.advance();assertThat(jobs()).isEqualTo(15);return id;
    }
    @Test void boundedFunctionalPairsOverlapButResourceChecksStayExclusiveAndPinned() {
        env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("finite-profile-test",Map.of("HYBRID_VALIDATION_PROFILE",HybridFiniteProfile.PACKAGE_POLICY,"HYBRID_FUNCTIONAL_ENABLED","true")));
        UUID id=joined();checks.advance();
        var a=queue.claim(UUID.randomUUID()).orElseThrow();var b=queue.claim(UUID.randomUUID()).orElseThrow();
        assertThat(a.executionMode()).isEqualTo("FUNCTIONAL");assertThat(b.executionMode()).isEqualTo("FUNCTIONAL");
        assertThat(queue.claim(UUID.randomUUID())).isEmpty();complete(a,"AC","");checks.advance();assertThat(jobs()).isEqualTo(2);
        complete(b,"AC","");
        env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("finite-profile-test",Map.of("HYBRID_VALIDATION_PROFILE",HybridFiniteProfile.PACKAGE_POLICY,"HYBRID_FUNCTIONAL_ENABLED","false")));
        checks.advance();a=queue.claim(UUID.randomUUID()).orElseThrow();b=queue.claim(UUID.randomUUID()).orElseThrow();
        assertThat(a.executionMode()).isEqualTo("FUNCTIONAL");assertThat(b.executionMode()).isEqualTo("FUNCTIONAL");
        complete(a,"AC","");complete(b,"AC","");checks.advance();drainExtended("","");checks.advance();
        a=queue.claim(UUID.randomUUID()).orElseThrow();assertThat(a.executionMode()).isEqualTo("EXCLUSIVE");
        assertThat(queue.claim(UUID.randomUUID())).isEmpty();complete(a,"AC","");drainExtended("","");checks.advance();
        a=queue.claim(UUID.randomUUID()).orElseThrow();assertThat(a.executionMode()).isEqualTo("EXCLUSIVE");
        assertThat(role(a)).isEqualTo("package-generator");
    }
    @Test void schedulingEvidenceCannotBeChangedAfterQueueing() {
        UUID id=packageToGenerator();
        jdbc.sql("UPDATE judge_job SET execution_mode='FUNCTIONAL' WHERE submission_id IN (SELECT submission_id FROM hybrid_execution_check WHERE role='stress-reference-0')").update();
        drainPackage();checks.advance();assertThat(hybrid.view("owner",id).error()).isEqualTo("RUNNER_SCHEDULING_FENCE");
    }
    @Test void packagePolicyCompletesExecutionAndReplaysExactImmutablePackageTwice(){
        UUID id=packageToReplay();checks.advance();assertThat(jobs()).isEqualTo(15);
        var a=queue.claim(UUID.randomUUID()).orElseThrow();
        var saved=jdbc.sql("SELECT package_json,package_sha256 FROM problem_version WHERE id=?").param(a.problem().path("version").asText())
                .query((r,n)->new String[]{r.getString(1),r.getString(2)}).single();
        assertThat(a.problem()).isEqualTo(JudgeJson.parse(saved[0]));assertThat(a.problemSha256()).isEqualTo(saved[1]);
        assertThat(a.problem().path("samples").size()).isBetween(2,3);complete(a,"AC","");drainPackage();checks.advance();
        assertThat(hybrid.view("owner",id).error()).isEqualTo("CONTENT_REVIEW_REQUIRED");
        var report=JudgeJson.parse(jdbc.sql("SELECT completion_json FROM hybrid_branch WHERE generation_id=? AND role='VALIDATION'").param(id).query(String.class).single());
        assertThat(report.path("executionChecksComplete").asBoolean()).isTrue();assertThat(report.path("remaining").size()).isEqualTo(2);
        assertThat(report.path("packageEvidence").path("packageHash").asText()).isEqualTo(saved[1]);
        assertThat(report.path("publishable").asBoolean()).isFalse();assertThat(hybrid.view("owner",id).branches().get(VALIDATION)).isEqualTo("CHECKED");
        assertThat(jdbc.sql("SELECT count(*) FROM problem_version WHERE id LIKE 'hybrid-check-%' AND ready=true").query(Integer.class).single()).isZero();
    }
    @Test void truncatedReaderInputStopsBeforeAnyRunnerJob(){
        env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("finite-profile-test",Map.of("HYBRID_VALIDATION_PROFILE",HybridFiniteProfile.PACKAGE_POLICY)));
        UUID id=UUID.randomUUID();hybrid.start("owner",id,"new problem",false);
        assertThat(hybrid.complete(f.result(hybrid.claim(id,CONTRACT),f.contract()))).isTrue();
        assertThat(hybrid.complete(f.result(hybrid.claim(id,CORE),f.core()))).isTrue();
        assertThat(hybrid.complete(f.result(hybrid.claim(id,PRESENTATION),f.presentation()))).isTrue();
        var reader=f.reader();
        reader.withArray("adversarialInputs").addObject().put("input","100 1000\n"+"1 1\n".repeat(98)).put("reason","maximum N");
        assertThat(hybrid.complete(f.result(hybrid.claim(id,READER),reader))).isTrue();
        checks.advance();checks.advance();
        assertThat(jobs()).isZero();assertThat(queue.claim(UUID.randomUUID())).isEmpty();
        assertThat(hybrid.view("owner",id).error()).isEqualTo("READER_INPUT_BOUND");
        assertThat(hybrid.view("owner",id).branches()).containsEntry(VALIDATION,"FAILED");
        assertThat(jdbc.sql("SELECT count(*) FROM problem_version WHERE id LIKE 'hybrid-check-%'").query(Integer.class).single()).isZero();
    }
    @Test void packageGeneratorInputsOutsideContractStopBeforeValidatorOrOracle(){
        UUID id=packageToGenerator();var a=queue.claim(UUID.randomUUID()).orElseThrow();
        complete(a,"OK","[\"101 1\\n\",\"1 1\\n1 1\\n\",\"1 2\\n1 1\\n\",\"1 3\\n1 1\\n\"]");checks.advance();
        assertThat(jobs()).isEqualTo(10);assertThat(hybrid.view("owner",id).error()).isEqualTo("PROFILE_INPUT_BOUND");
    }
    @Test void storedSeedsAndOracleBoundsSurviveCoordinatorRecreation(){
        UUID id=packageToGenerator();var seed=jdbc.sql("SELECT generator_seed FROM hybrid_package_evidence").query(Long.class).single();
        checks.advance();assertThat(jobs()).isEqualTo(10);assertThat(jdbc.sql("SELECT generator_seed FROM hybrid_package_evidence").query(Long.class).single()).isEqualTo(seed);
        drainPackage();checks.advance();drainPackage();
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status->new HybridRunnerChecks(jdbc,new AiSettings(env),ledger).advance());
        assertThat(jobs()).isEqualTo(13);
        var oracle=JudgeJson.parse(jdbc.sql("SELECT s.run_package FROM submission s JOIN hybrid_execution_check e ON e.submission_id=s.id WHERE e.role='batch-oracle'").query(String.class).single());
        assertThat(oracle.path("tests")).allMatch(t->HybridPackagePlan.parse(t.path("input").asText()).tiny());
        assertThat(hybrid.view("owner",id).status()).isEqualTo("VALIDATING");
    }
    @Test void finalPackageTamperingCannotBecomeChecked(){
        UUID id=packageToReplay();jdbc.sql("UPDATE problem_version SET package_json='{}' WHERE id LIKE 'hybrid-check-%'").update();
        drainPackage();checks.advance();assertThat(hybrid.view("owner",id).error()).isEqualTo("FINAL_PACKAGE_FENCE");
    }
    @Test void cancellationBeforeFinalReplayPreventsClaimAndContentReadyTransition(){
        UUID id=packageToReplay();hybrid.cancel("owner",id);assertThat(queue.claim(UUID.randomUUID())).isEmpty();checks.advance();
        assertThat(hybrid.view("owner",id).status()).isEqualTo("CANCELLED");
    }

    @Test void finalPackageTotalTimeBudgetIsCheckedEvenWhenEveryTestFitsIndividualLimit(){
        UUID id=packageToReplay();var a=queue.claim(UUID.randomUUID()).orElseThrow();
        var report=new GenerationIntegrationTest().report(a,"AC");
        assertThat(report.path("tests").size()).isGreaterThan(10);
        for(var test:report.path("tests"))((com.fasterxml.jackson.databind.node.ObjectNode)test).put("wall_ms",4000);
        queue.complete(a.submissionId(),a.token(),report);drainPackage();checks.advance();
        assertThat(hybrid.view("owner",id).error()).isEqualTo("FINAL_PACKAGE_TIME_BUDGET");
    }
    @Test void changedCandidatesCannotDispatchReferenceOrOracle(){
        UUID id=packageToGenerator();drainPackage();checks.advance();drainPackage();
        jdbc.sql("UPDATE hybrid_package_evidence SET candidates_json='[]'").update();checks.advance();
        assertThat(jobs()).isEqualTo(11);assertThat(hybrid.view("owner",id).error()).isEqualTo("CANDIDATE_FENCE");
    }

}
