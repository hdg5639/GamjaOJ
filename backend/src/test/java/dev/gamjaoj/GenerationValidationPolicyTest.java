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
    @Test void englishTopicsDoNotMatchInsideUnrelatedWords(){
        var policy=GenerationValidationPolicy.forProblem(JudgeJson.parse("{\"tags\":[\"overflow\"],\"description\":\"street card sorting\"}"));
        var ids=new java.util.HashSet<String>();policy.path("profiles").forEach(p->ids.add(p.path("id").asText()));
        assertThat(ids).contains("sorting").doesNotContain("flow","tree-range");
        var explicit=GenerationValidationPolicy.forProblem(JudgeJson.parse("{\"tags\":[\"flow\",\"binary search\",\"DP\"]}"));
        assertThat(explicit.path("profiles").toString()).contains("flow","binary-search","dp");
    }
    @Test void previousPolicyAndSmallTransportGeneratorCannotContaminateMaximumContract(){
        var definition=GenerationTemplate.spec();GenerationValidationPolicy.attach(definition,definition);
        var policy=GenerationValidationPolicy.forProblem(definition);
        assertThat(policy.path("profiles").toString()).doesNotContain("flow/residual", "hash/delete", "strings/unicode");
        var maximum=GenerationResources.resourceDefinition("TAG",definition);
        assertThat(maximum.has("generatorContract")).isFalse();assertThat(maximum.has("validationPolicy")).isFalse();
        assertThat(maximum.path("maximumInputContract").asText()).contains("ONE complete legal");
        assertThat(maximum.path("statement").asText()).contains("N ≤ 1000");assertThat(definition.has("generatorContract")).isTrue();
    }
    @Test void unknownTopicsStillRequireCommonDomainOverflowAndResetChecks(){
        var policy=GenerationValidationPolicy.forProblem(JudgeJson.parse("{\"category\":\"새로운 분야\"}"));
        assertThat(policy.path("profiles").size()).isEqualTo(1);
        assertThat(policy.path("commonChecks").toString()).contains("overflow","reset-isolation","batch-bound");
    }
}
