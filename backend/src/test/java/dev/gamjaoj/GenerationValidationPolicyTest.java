package dev.gamjaoj;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class GenerationValidationPolicyTest {
    @Test void graphsAndCommandsReceiveMatchingChecksWithoutCopyingHistoricalAnswers(){
        var definition=JudgeJson.parse("{\"category\":\"너비 우선 탐색\",\"tags\":[\"명령 처리\"]}");
        var policy=GenerationValidationPolicy.forProblem(definition);
        assertThat(policy.path("profiles").toString()).contains("bfs","command","input-contract");
        assertThat(policy.path("timePolicy").path("floors").path("PYTHON").asInt()).isEqualTo(8);
        assertThat(policy.path("scope").asText()).contains("do not qualify");
    }
    @Test void unknownTopicsStillRequireCommonDomainOverflowAndResetChecks(){
        var policy=GenerationValidationPolicy.forProblem(JudgeJson.parse("{\"category\":\"새로운 분야\"}"));
        assertThat(policy.path("profiles").size()).isEqualTo(1);
        assertThat(policy.path("commonChecks").toString()).contains("overflow","reset-isolation","batch-bound");
    }
}
