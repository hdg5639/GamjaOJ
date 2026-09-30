package dev.gamjaoj;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProblemTimeLimitsTest {
    static String limits(int java,int cpp,int python) {
        return JudgeJson.canonical(JudgeJson.JSON.createObjectNode().put("JAVA",java).put("CPP",cpp).put("PYTHON",python).put("analysis","Test estimates"));
    }
    @Test void calibrationRoundsUpWithHeadroomAndRejectsMissingOrUnboundedEvidence() {
        assertThat(ProblemTimeLimits.calibratedJavaSeconds(10)).isEqualTo(1);
        assertThat(ProblemTimeLimits.calibratedJavaSeconds(501)).isEqualTo(2);
        assertThat(ProblemTimeLimits.calibratedJavaSeconds(6000)).isEqualTo(15);
        assertThat(ProblemTimeLimits.calibratedJavaSeconds(8000)).isEqualTo(20);
        assertThatThrownBy(()->ProblemTimeLimits.calibratedJavaSeconds(0)).hasMessage("TIME_LIMIT_EVIDENCE_MISSING");
        for(long ms:new long[]{8001,10001,Long.MAX_VALUE})assertThatThrownBy(()->ProblemTimeLimits.calibratedJavaSeconds(ms)).hasMessage("TIME_LIMIT_CAPACITY_EXCEEDED");
    }
    @Test void independentReplaysHaveRoomWithoutRelaxingThePublicationSafetyGate() {
        int seconds=ProblemTimeLimits.calibratedJavaSeconds(3443);
        assertThat(seconds).isEqualTo(9);
        var review=GenerationRequirementsTest.accepted();review.set("timeLimits",JudgeJson.parse(limits(seconds,5,12)));
        assertThat(ProblemTimeLimits.reviewed(review,4100)).isNotBlank();
        assertThatThrownBy(()->ProblemTimeLimits.reviewed(review,4501)).hasMessage("TIME_LIMIT_REFERENCE_MARGIN");
    }
    @Test void oldRegisteredTimingRemainsValidWhileNewTimingPinsItsPolicy() {
        var p=HybridAdmissionIntegrationTest.fixturePackage();
        var timing=p.putObject("timing").put("javaSeconds",12).put("referenceMaxWallMs",6000);
        assertThat(HybridRulePackage.parse("legacy-timing",p).qualifiedJavaSeconds()).isEqualTo(12);
        timing.put("calibrationPolicy","REPLAY_HEADROOM_V1").put("javaSeconds",15);
        assertThat(HybridRulePackage.parse("new-timing",p).qualifiedJavaSeconds()).isEqualTo(15);
        timing.put("javaSeconds",12);
        assertThatThrownBy(()->HybridRulePackage.parse("wrong-timing",p)).hasMessage("RULE_TIMING_EVIDENCE");
    }
    @Test void limitsAffectOnlyWallBudgetAndRejectInvalidOrUnsafeProposals() {
        var base=LanguageProfiles.profile("PYTHON");var proposed=LanguageProfiles.profile("PYTHON",limits(2,1,4));
        assertThat(proposed.path("testWallSeconds").asInt()).isEqualTo(4);
        var restored=proposed.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)restored).set("testWallSeconds",base.path("testWallSeconds"));
        assertThat(restored).isEqualTo(base);
        for(String raw:java.util.List.of(limits(0,1,2),limits(2,21,3),"{\"JAVA\":2}",limits(2,1,3).replace("\"CPP\":1","\"CPP\":1.5")))
            assertThatThrownBy(()->ProblemTimeLimits.parse(raw)).isInstanceOf(HybridArtifacts.Invalid.class);
        var review=GenerationRequirementsTest.accepted();review.set("timeLimits",JudgeJson.parse(limits(2,1,3)));
        assertThat(ProblemTimeLimits.reviewed(review,1000)).isEqualTo(limits(2,1,3));
        assertThatThrownBy(()->ProblemTimeLimits.reviewed(review,1001)).hasMessage("TIME_LIMIT_REFERENCE_MARGIN");
        assertThatThrownBy(()->ProblemTimeLimits.reviewed(review,10,1)).hasMessage("TIME_LIMIT_WITNESS_CEILING");
        assertThatThrownBy(()->ProblemTimeLimits.measured(0)).hasMessage("TIME_LIMIT_EVIDENCE_MISSING");
        assertThat(JudgeJson.parse(ProblemTimeLimits.measured(100)).path("PYTHON").asInt()).isEqualTo(2);
        assertThat(JudgeJson.parse(ProblemTimeLimits.measured(600)).path("PYTHON").asInt()).isEqualTo(8);
    }
}
