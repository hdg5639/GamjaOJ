package dev.gamjaoj;
import dev.gamjaoj.support.JudgeJson;
import dev.gamjaoj.domain.JudgeScheduling;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class JudgeSchedulingTest {
    @Test void tagBatchesWithAnyBoundaryOrUnknownCaseStayExclusive() {
        var small=JudgeJson.parse("{\"tests\":[{\"id\":\"sample\"},{\"id\":\"small-1-0\"}]}");
        for(String role:new String[]{"reference-0","oracle-20","validator-40"})
            assertThat(JudgeScheduling.generated(role,small)).isEqualTo("FUNCTIONAL");
        for(String id:new String[]{"max-edges","positive-boundary","disconnected","seed-0","unknown"}) {
            var plan=small.deepCopy();((com.fasterxml.jackson.databind.node.ArrayNode)plan.path("tests")).addObject().put("id",id);
            assertThat(JudgeScheduling.generated("reference-0",plan)).isEqualTo("EXCLUSIVE");
        }
        assertThat(JudgeScheduling.generated("final-reference",small)).isEqualTo("EXCLUSIVE");
        assertThat(JudgeScheduling.generated("reference-0",JudgeJson.parse("{\"tests\":[]}"))).isEqualTo("EXCLUSIVE");
    }
    @Test void experimentalResourcesPackagesAndUnknownRolesStayExclusive() {
        for(String role:new String[]{"final-stress-0","final-stress-1","final-package-0","final-package-1","final-stress-validator","unknown","review-mutant-0","generator"})
            assertThat(JudgeScheduling.experimental(role)).isEqualTo("EXCLUSIVE");
        for(String role:new String[]{"reference-samples","oracle-generated-0","final-small-ref-0","final-small-oracle-3","final-seed-ref-2"})
            assertThat(JudgeScheduling.experimental(role)).isEqualTo("FUNCTIONAL");
    }
}
