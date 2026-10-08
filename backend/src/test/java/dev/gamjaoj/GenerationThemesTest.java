package dev.gamjaoj;
import dev.gamjaoj.service.generation.GenerationThemes;
import dev.gamjaoj.support.JudgeJson;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class GenerationThemesTest {
    @Test void repeatedAndWarehouseStoriesAreRejectedButDifferentSituationsAreAllowed() {
        var prior=JudgeJson.JSON.createArrayNode();prior.addObject().put("title","야간 공연").put("context","관객이 선택한 곡의 점수 변화를 합산하여 공연의 최종 점수를 구합니다.");
        assertThat(GenerationThemes.duplicate(JudgeJson.JSON.createObjectNode().put("title","야간  공연!").put("context","다른 본문"),prior)).isTrue();
        assertThat(GenerationThemes.duplicate(JudgeJson.JSON.createObjectNode().put("title","새 이름").put("context","관객이 선택한 곡의 점수 변화를 합산하여 공연의 최종 점수를 구합니다!"),prior)).isTrue();
        assertThat(GenerationThemes.duplicate(JudgeJson.JSON.createObjectNode().put("title","물류 센터").put("context","물건을 센다"),prior)).isTrue();
        assertThat(GenerationThemes.duplicate(JudgeJson.JSON.createObjectNode().put("title","유성 신호").put("context","우주 관측기가 기록한 신호의 증감량을 확인하세요."),prior)).isFalse();
        assertThat(GenerationThemes.valid(JudgeJson.parse("{\"setting\":\"창고\",\"scenario\":\"재고를 센다\"}"))).isFalse();
        assertThat(GenerationThemes.valid(JudgeJson.parse("{\"setting\":\"별빛\",\"scenario\":\"관측 신호의 증감을 기록한다\"}"))).isTrue();
    }
}
