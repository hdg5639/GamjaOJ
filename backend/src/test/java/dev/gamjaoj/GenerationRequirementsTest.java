package dev.gamjaoj;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class GenerationRequirementsTest {
    /** Explicit model fixture, not evidence that a real provider honors a complex request. */
    static ObjectNode accepted() {
        var r=JudgeJson.JSON.createObjectNode().put("satisfied",true)
                .put("complexity","Fixture O(NW) time, O(W) memory at the fixed bounds; not measured.")
                .put("shortcuts","Fixture full enumeration grows as 2^N; no claim of a calibrated tier.");
        r.putArray("coverage").addObject().put("requirement","Each item at most once")
                .put("evidence","Contract choose action and single-use mutant witness.");
        r.putObject("timeLimits").put("JAVA",5).put("CPP",3).put("PYTHON",8).put("analysis","Fixture resource estimates, not real measurements.");
        r.putArray("issues");return r;
    }
    @Test void missingContradictoryAndRejectedAssessmentsCannotPass() {
        assertThatThrownBy(()->GenerationRequirements.validate(JudgeJson.parse("{}"),true)).isInstanceOf(HybridArtifacts.Invalid.class);
        var r=accepted();r.put("satisfied",false);r.withArray("issues").add("방문 장치를 2개로 축소했습니다.");
        GenerationRequirements.validate(r,false);
        assertThatThrownBy(()->GenerationRequirements.validate(r,true)).hasMessage("REQUIREMENTS_NOT_MET");
        r.put("satisfied",true);
        assertThatThrownBy(()->GenerationRequirements.validate(r,true)).hasMessage("INVALID_REQUIREMENTS_REVIEW");
        r.withArray("issues").removeAll();r.withArray("coverage").removeAll();
        assertThatThrownBy(()->GenerationRequirements.validate(r,true)).hasMessage("INVALID_REQUIREMENTS_REVIEW");
    }
}
