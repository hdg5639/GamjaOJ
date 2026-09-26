package dev.gamjaoj;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HybridPresentationRulesTest {
    final HybridGenerationIntegrationTest f=new HybridGenerationIntegrationTest();
    ObjectNode input() {
        var input=JudgeJson.JSON.createObjectNode().put("language","ko");
        input.set("semantics",HybridArtifacts.publicSemantics(HybridFiniteProfile.contract()));
        input.set("serverRules",HybridPresentationRules.bundle());return input;
    }
    ObjectNode prose() {
        var p=f.presentation();p.remove(List.of("semantics","ruleExplanations"));return p;
    }
    @Test void fixedRulesReachPublicSnapshotWithoutHidingConflictingContextOrTeaching() {
        var input=input();var p=prose().put("context","비용이 남은 용량 이상인 물건만 선택한다.");
        var joined=HybridPresentationRules.assemble(input,p,f.contract());
        var snapshot=HybridArtifacts.publicSnapshot(joined);
        assertThat(snapshot.path("ruleExplanations")).isEqualTo(input.path("serverRules").path("rules"));
        assertThat(snapshot.path("ruleExplanations").get(0).path("text").asText()).contains("비용 ≤ 남은 용량","최대 한 번","총 가치의 최댓값");
        assertThat(snapshot.path("context")).isEqualTo(p.path("context"));
        assertThat(joined.path("editorial")).isEqualTo(p.path("editorial"));
        assertThat(snapshot.has("editorial")).isFalse();
        var rejected=f.reader();rejected.withArray("ambiguities").add("Context contradicts the server rule: cost >= capacity versus cost <= capacity.");
        assertThatThrownBy(()->HybridArtifacts.reader(rejected)).hasMessage("READER_AMBIGUITY");
    }
    @Test void writerCannotOverrideRulesAndContractChangesCannotReuseFixedProse() {
        var input=input();var p=prose();p.set("ruleExplanations",f.presentation().path("ruleExplanations"));
        assertThatThrownBy(()->HybridPresentationRules.assemble(input,p,f.contract())).hasMessage("INVALID_FIELDS");
        ((ObjectNode)input.path("semantics").path("actions").get(0)).put("preconditions","cost >= remaining capacity");
        assertThatThrownBy(()->HybridPresentationRules.assemble(input,prose(),f.contract())).hasMessage("PRESENTATION_RULES_FENCE");
        assertThat(HybridPresentationRules.assemble(JudgeJson.JSON.createObjectNode(),f.presentation(),f.contract())).isEqualTo(f.presentation());
    }
}
