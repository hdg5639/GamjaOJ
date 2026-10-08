package dev.gamjaoj;

import static org.assertj.core.api.Assertions.*;

import dev.gamjaoj.generation.service.HybridArtifacts;
import dev.gamjaoj.generation.service.HybridPackagePlan;
import dev.gamjaoj.generation.service.HybridProfiles;
import dev.gamjaoj.generation.service.HybridStatementQuality;
import dev.gamjaoj.shared.domain.ArtifactValidation;
import dev.gamjaoj.shared.support.JudgeJson;
import java.util.List;
import org.junit.jupiter.api.Test;

class HybridStatementQualityTest {
  final HybridGenerationIntegrationTest fixtures = new HybridGenerationIntegrationTest();

  @Test
  void englishContractProseIsRejectedButQuotedTokensAndVariablesPass() {
    assertThatThrownBy(
            () ->
                HybridStatementQuality.korean(
                    "input", "첫 줄에 vertices and undirected edges가 주어집니다."))
        .isInstanceOf(ArtifactValidation.Invalid.class)
        .hasMessage("STATEMENT_NOT_KOREAN_INPUT");
    assertThatThrownBy(
            () -> HybridStatementQuality.korean("context", "at vertex S with distance 0"))
        .isInstanceOf(ArtifactValidation.Invalid.class);
    HybridStatementQuality.korean(
        "output", "가능하면 `POSSIBLE`, 아니면 \"Impossible\"을 출력합니다. N, M, S, T와 BFS, DP, MOD는 그대로 씁니다.");
  }

  @Test
  void callableSectionsAcceptOnlyDeclaredIdentifiersAlongsideKoreanProse() {
    var semantics = JudgeJson.JSON.createObjectNode();
    semantics.set("callable", CallableProgramsTest.multi());
    HybridStatementQuality.korean("input", "각 add의 value는 정수이며 UserSolution에서 처리합니다.", semantics);
    assertThatThrownBy(
            () -> HybridStatementQuality.korean("input", "각 unknownParameter는 정수입니다.", semantics))
        .hasMessage("STATEMENT_NOT_KOREAN_INPUT");
    assertThatThrownBy(
            () -> HybridStatementQuality.korean("input", "각 valueExtra는 정수입니다.", semantics))
        .hasMessage("STATEMENT_NOT_KOREAN_INPUT");
    assertThatThrownBy(
            () ->
                HybridStatementQuality.korean(
                    "input", "value follows after each operation", semantics))
        .hasMessage("STATEMENT_NOT_KOREAN_INPUT");
    assertThatThrownBy(
            () -> HybridStatementQuality.korean("input", "UserSolution value", semantics))
        .hasMessage("STATEMENT_NOT_KOREAN_INPUT");
    assertThatThrownBy(
            () ->
                HybridStatementQuality.korean(
                    "input", "각 value는 정수입니다.", JudgeJson.JSON.createObjectNode()))
        .hasMessage("STATEMENT_NOT_KOREAN_INPUT");
  }

  @Test
  void everyContractBoundMustAppearInAnyEquivalentNotation() {
    var semantics = fixtures.presentation().path("semantics");
    assertThat(HybridStatementQuality.contractBounds(semantics))
        .extracting(Number::longValue)
        .containsExactly(100L, 1000L, 10000L);
    HybridStatementQuality.bounds(semantics, "N은 100 이하, W는 1,000 이하, 비용 1000 이하, 가치 10^4 이하");
    assertThatThrownBy(() -> HybridStatementQuality.bounds(semantics, "N은 100 이하, W는 1000 이하입니다."))
        .isInstanceOf(ArtifactValidation.Invalid.class)
        .hasMessage("STATEMENT_BOUND_MISSING");
    var dijkstra = HybridArtifacts.publicSemantics(HybridProfiles.DIJKSTRA.contract());
    HybridStatementQuality.bounds(dijkstra, "1 ≤ N ≤ 100, 0 ≤ M ≤ 200, 1 ≤ w ≤ 10⁹");
    HybridStatementQuality.bounds(dijkstra, "N ≤ 100, M ≤ 2×10^2, w ≤ 1e9");
  }

  @Test
  void markdownFencesAroundModelJavaAreRemovedButPlainSourceIsUntouched() {
    assertThat(HybridArtifacts.unfence("```java\nimport java.io.*;\npublic class Main{}\n```\n"))
        .isEqualTo("import java.io.*;\npublic class Main{}\n");
    assertThat(HybridArtifacts.unfence("```\npublic class Main{}```"))
        .isEqualTo("public class Main{}");
    String plain = "public class Main { String s=\"```\"; }";
    assertThat(HybridArtifacts.unfence(plain)).isSameAs(plain);
    var reader =
        JudgeJson.JSON.createObjectNode().put("oracleSource", "```java\npublic class Main{}\n```");
    HybridArtifacts.unfence(reader, "oracleSource");
    assertThat(reader.path("oracleSource").asText()).isEqualTo("public class Main{}\n");
  }

  @Test
  void writerSectionsReplaceTheContractDumpInTheStatement() {
    var presentation = HybridArtifacts.presentation(fixtures.presentation(), fixtures.contract());
    var statement = HybridPackagePlan.pack("v", List.of(), presentation).path("statement").asText();
    assertThat(statement)
        .contains("입력\n첫 줄에 N과 W", "출력\n고른 장비", "제한\n1 ≤ N ≤ 100")
        .doesNotContain("대상과 상태", "행동 조건", "at most once per item");
    var bad = fixtures.presentation();
    ((com.fasterxml.jackson.databind.node.ObjectNode) bad.path("sections"))
        .put("limits", "1 ≤ N ≤ 100입니다.");
    assertThatThrownBy(() -> HybridArtifacts.presentation(bad, fixtures.contract()))
        .hasMessage("STATEMENT_BOUND_MISSING");
    var legacy = fixtures.presentation();
    legacy.remove("sections"); // artifacts written before sections still assemble
    assertThat(
            HybridPackagePlan.pack(
                    "v", List.of(), HybridArtifacts.presentation(legacy, fixtures.contract()))
                .path("statement")
                .asText())
        .contains("대상과 상태");
  }
}
