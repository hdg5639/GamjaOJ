package dev.gamjaoj;
import dev.gamjaoj.domain.ArtifactValidation;
import dev.gamjaoj.service.generation.GenerationType;
import dev.gamjaoj.service.generation.GraphRecipe;
import dev.gamjaoj.support.JudgeJson;
import dev.gamjaoj.service.problem.ThinkingDifficulty;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ThinkingDifficultyTest {
    @Test void rejectsMissingFractionalOutOfRangeAndSpoilerSizedProfiles(){
        var valid=ThinkingDifficulty.template(GenerationType.SUM);
        assertThatCode(()->ThinkingDifficulty.validate(valid)).doesNotThrowAnyException();
        for(String raw:java.util.List.of("{}","null","{\"layer\":1.5,\"insight\":1,\"implementation\":1,\"edgeCases\":1,\"rationale\":\"설명\"}"))
            assertThatThrownBy(()->ThinkingDifficulty.validate(JudgeJson.parse(raw))).isInstanceOf(ArtifactValidation.Invalid.class);
        for(String key:java.util.List.of("layer","insight","implementation","edgeCases")){
            var bad=(com.fasterxml.jackson.databind.node.ObjectNode)valid.deepCopy();bad.put(key,10);
            assertThatThrownBy(()->ThinkingDifficulty.validate(bad)).isInstanceOf(ArtifactValidation.Invalid.class);
        }
        var bad=(com.fasterxml.jackson.databind.node.ObjectNode)valid.deepCopy();bad.put("rationale"," ");
        assertThatThrownBy(()->ThinkingDifficulty.validate(bad)).isInstanceOf(ArtifactValidation.Invalid.class);
        bad.put("rationale","가".repeat(301));
        assertThatThrownBy(()->ThinkingDifficulty.validate(bad)).isInstanceOf(ArtifactValidation.Invalid.class);
    }
    @Test void reviewedTemplatesGradeActualConditionsRatherThanWeightedLabel(){
        var a=ThinkingDifficulty.template(new GenerationType(new GraphRecipe(false,true,GraphRecipe.Query.COUNT)));
        var b=ThinkingDifficulty.template(new GenerationType(new GraphRecipe(false,false,GraphRecipe.Query.COUNT)));
        assertThat(a).isEqualTo(b);
        assertThat(ThinkingDifficulty.template(GenerationType.SUM).path("layer").asInt()).isEqualTo(1);
        assertThat(ThinkingDifficulty.template(GenerationType.PARENTHESES).path("layer").asInt()).isEqualTo(2);
    }
}
