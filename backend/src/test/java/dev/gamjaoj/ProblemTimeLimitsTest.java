package dev.gamjaoj;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProblemTimeLimitsTest {
    static String limits(int java,int cpp,int python) {
        return JudgeJson.canonical(JudgeJson.JSON.createObjectNode().put("JAVA",java).put("CPP",cpp).put("PYTHON",python).put("analysis","Test estimates"));
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
