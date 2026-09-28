package dev.gamjaoj;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HybridStatementQualityTest {
    final HybridGenerationIntegrationTest fixtures=new HybridGenerationIntegrationTest();
    @Test void englishContractProseIsRejectedButQuotedTokensAndVariablesPass() {
        assertThatThrownBy(()->HybridStatementQuality.korean("input","첫 줄에 vertices and undirected edges가 주어집니다."))
                .isInstanceOf(HybridArtifacts.Invalid.class).hasMessage("STATEMENT_NOT_KOREAN_INPUT");
        assertThatThrownBy(()->HybridStatementQuality.korean("context","at vertex S with distance 0")).isInstanceOf(HybridArtifacts.Invalid.class);
        HybridStatementQuality.korean("output","가능하면 `POSSIBLE`, 아니면 \"Impossible\"을 출력합니다. N, M, S, T와 BFS, DP, MOD는 그대로 씁니다.");
    }
    @Test void everyContractBoundMustAppearInAnyEquivalentNotation() {
        var semantics=fixtures.presentation().path("semantics");
        assertThat(HybridStatementQuality.contractBounds(semantics)).extracting(Number::longValue).containsExactly(100L,1000L,10000L);
        HybridStatementQuality.bounds(semantics,"N은 100 이하, W는 1,000 이하, 비용 1000 이하, 가치 10^4 이하");
        assertThatThrownBy(()->HybridStatementQuality.bounds(semantics,"N은 100 이하, W는 1000 이하입니다."))
                .isInstanceOf(HybridArtifacts.Invalid.class).hasMessage("STATEMENT_BOUND_MISSING");
        var dijkstra=HybridArtifacts.publicSemantics(HybridProfiles.DIJKSTRA.contract());
        HybridStatementQuality.bounds(dijkstra,"1 ≤ N ≤ 100, 0 ≤ M ≤ 200, 1 ≤ w ≤ 10⁹");
        HybridStatementQuality.bounds(dijkstra,"N ≤ 100, M ≤ 2×10^2, w ≤ 1e9");
    }
    @Test void writerSectionsReplaceTheContractDumpInTheStatement() {
        var presentation=HybridArtifacts.presentation(fixtures.presentation(),fixtures.contract());
        var statement=HybridPackagePlan.pack("v",List.of(),presentation).path("statement").asText();
        assertThat(statement).contains("입력\n첫 줄에 N과 W","출력\n고른 장비","제한\n1 ≤ N ≤ 100").doesNotContain("대상과 상태","행동 조건","at most once per item");
        var bad=fixtures.presentation();((com.fasterxml.jackson.databind.node.ObjectNode)bad.path("sections")).put("limits","1 ≤ N ≤ 100입니다.");
        assertThatThrownBy(()->HybridArtifacts.presentation(bad,fixtures.contract())).hasMessage("STATEMENT_BOUND_MISSING");
        var legacy=fixtures.presentation();legacy.remove("sections"); // artifacts written before sections still assemble
        assertThat(HybridPackagePlan.pack("v",List.of(),HybridArtifacts.presentation(legacy,fixtures.contract())).path("statement").asText()).contains("대상과 상태");
    }
}
