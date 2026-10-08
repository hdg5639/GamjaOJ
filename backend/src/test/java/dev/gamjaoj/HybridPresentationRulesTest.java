package dev.gamjaoj;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gamjaoj.generation.service.HybridArtifacts;
import dev.gamjaoj.generation.service.HybridFiniteProfile;
import dev.gamjaoj.generation.service.HybridPresentationRules;
import dev.gamjaoj.shared.domain.ArtifactValidation;
import dev.gamjaoj.shared.support.JudgeJson;
import java.util.List;
import org.junit.jupiter.api.Test;

class HybridPresentationRulesTest {
  final HybridGenerationIntegrationTest f = new HybridGenerationIntegrationTest();

  ObjectNode input() {
    var input = JudgeJson.JSON.createObjectNode().put("language", "ko");
    input.set("semantics", HybridArtifacts.publicSemantics(HybridFiniteProfile.contract()));
    input.set("serverRules", HybridPresentationRules.bundle());
    return input;
  }

  ObjectNode prose() {
    var p = f.presentation();
    p.remove(List.of("semantics", "ruleExplanations"));
    return p;
  }

  @Test
  void instanceMayRewriteStoryNamesButCannotChangeSemanticsOrActionIdentity() {
    var input = input();
    input.putObject("presentation").put("policy", "RETHEME_V1").put("theme", "해저 탐사 장비");
    var p = prose().put("title", "탐사 장비 선택").put("context", "장비를 골라 탐사 가치를 최대화합니다.");
    p.putArray("ruleExplanations")
        .addObject()
        .put("id", "choose")
        .put(
            "text",
            input
                .path("serverRules")
                .path("rules")
                .get(0)
                .path("text")
                .asText()
                .replace("물건", "장비"));
    var joined = HybridPresentationRules.assemble(input, p, f.contract());
    assertThat(joined.path("semantics")).isEqualTo(input.path("semantics"));
    assertThat(joined.path("ruleExplanations").toString()).contains("장비").doesNotContain("물건");
    assertThat(HybridArtifacts.publicSnapshot(joined).toString()).doesNotContain("RETHEME_V1");
    ((ObjectNode) p.path("ruleExplanations").get(0)).put("id", "warp");
    assertThatThrownBy(() -> HybridPresentationRules.assemble(input, p, f.contract()))
        .isInstanceOf(ArtifactValidation.Invalid.class);
    p.set("semantics", input.path("semantics"));
    assertThatThrownBy(() -> HybridPresentationRules.assemble(input, p, f.contract()))
        .isInstanceOf(ArtifactValidation.Invalid.class);
  }

  @Test
  void fixedRulesReachPublicSnapshotWithoutHidingConflictingContextOrTeaching() {
    var input = input();
    var p = prose().put("context", "비용이 남은 용량 이상인 물건만 선택한다.");
    var joined = HybridPresentationRules.assemble(input, p, f.contract());
    var snapshot = HybridArtifacts.publicSnapshot(joined);
    assertThat(snapshot.path("ruleExplanations"))
        .isEqualTo(input.path("serverRules").path("rules"));
    assertThat(snapshot.path("ruleExplanations").get(0).path("text").asText())
        .contains("비용 ≤ 남은 용량", "최대 한 번", "총 가치의 최댓값");
    assertThat(snapshot.path("context")).isEqualTo(p.path("context"));
    assertThat(joined.path("editorial")).isEqualTo(p.path("editorial"));
    assertThat(snapshot.has("editorial")).isFalse();
    var rejected = f.reader();
    rejected
        .withArray("ambiguities")
        .add("Context contradicts the server rule: cost >= capacity versus cost <= capacity.");
    assertThatThrownBy(() -> HybridArtifacts.reader(rejected)).hasMessage("READER_AMBIGUITY");
  }

  @Test
  void writerCannotOverrideRulesAndContractChangesCannotReuseFixedProse() {
    var input = input();
    var p = prose();
    p.set("ruleExplanations", f.presentation().path("ruleExplanations"));
    assertThatThrownBy(() -> HybridPresentationRules.assemble(input, p, f.contract()))
        .hasMessage("INVALID_FIELDS");
    ((ObjectNode) input.path("semantics").path("actions").get(0))
        .put("preconditions", "cost >= remaining capacity");
    assertThatThrownBy(() -> HybridPresentationRules.assemble(input, prose(), f.contract()))
        .hasMessage("PRESENTATION_RULES_FENCE");
    assertThat(
            HybridPresentationRules.assemble(
                JudgeJson.JSON.createObjectNode(), f.presentation(), f.contract()))
        .isEqualTo(f.presentation());
  }
}
