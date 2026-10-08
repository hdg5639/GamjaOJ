package dev.gamjaoj;

import static org.assertj.core.api.Assertions.*;

import dev.gamjaoj.generation.service.GenerationChoices;
import dev.gamjaoj.generation.service.GenerationTemplate;
import dev.gamjaoj.generation.service.GenerationType;
import dev.gamjaoj.generation.service.SequenceRecipe;
import dev.gamjaoj.shared.exception.AccountException;
import java.util.List;
import org.junit.jupiter.api.Test;

class SequenceRecipeTest {
  @Test
  void allTwentyRecipesHaveKnownAnswersBoundedArithmeticAndCompleteSmallInputs() {
    long[][] expected = {
      {-2, 8, 18, 5}, {3, 3, 5, 2}, {-5, 5, 13, 2}, {0, 4, 8, 3}, {-2, 4, 10, 2}
    };
    for (var filter : SequenceRecipe.Filter.values())
      for (int operation = 0; operation < 4; operation++) {
        var transform =
            operation == 3
                ? SequenceRecipe.Transform.IDENTITY
                : SequenceRecipe.Transform.values()[operation];
        var recipe =
            new SequenceRecipe(
                filter,
                transform,
                operation == 3 ? SequenceRecipe.Reduction.COUNT : SequenceRecipe.Reduction.SUM);
        var type = GenerationType.of(recipe.id());
        assertThat(
                type.test("known", GenerationTemplate.input(-3, -2, 0, 1, 2))
                    .path("output")
                    .asText())
            .isEqualTo(expected[filter.ordinal()][operation] + "\n");
        assertThat(type.spec().path("recipe").path("filter").asText()).isEqualTo(filter.name());
        assertThat(type.executions()).isEqualTo(15);
        assertThat(type.cases(1)).isEqualTo(type.cases(1));
        assertThat(type.cases(1)).isNotEqualTo(type.cases(2));
        assertThat(
                type.cases(1).stream()
                    .filter(t -> t.path("id").asText().startsWith("small-"))
                    .count())
            .isEqualTo(30);
        for (String invalid : type.invalidInputs())
          assertThatThrownBy(() -> type.test("bad", invalid)).isInstanceOf(AccountException.class);
        for (var test : type.cases(1))
          assertThat(test.path("output").asText()).matches("-?[0-9]+\\n");
      }
    long[] max = new long[1000];
    java.util.Arrays.fill(max, 10_000_000L);
    assertThat(
            GenerationType.of("sequence-recipe-v1-ALL-SQUARE-SUM")
                .test("bound", GenerationTemplate.input(max))
                .path("output")
                .asText())
        .isEqualTo("100000000000000000\n");
    assertThat(
            GenerationType.of("sequence-recipe-v1-NEGATIVE-ABS-SUM")
                .test("empty", GenerationTemplate.input(0, 1, 2))
                .path("output")
                .asText())
        .isEqualTo("0\n");
  }

  @Test
  void selectionComposesOperationsAndRejectsAmbiguousOrUnsupportedDeclarations() {
    var selection =
        GenerationChoices.resolve("sequences", List.of("filter-odd", "squares", "edge-cases"));
    assertThat(selection.template()).isEqualTo("sequence-recipe-v1-ODD-SQUARE-SUM");
    assertThat(selection.statement()).contains("원래 값", "제곱", "0을 출력");
    for (var tags :
        List.of(
            List.of("filter-odd", "filter-even"),
            List.of("squares", "absolute-values"),
            List.of("squares", "count")))
      assertThatThrownBy(() -> GenerationChoices.resolve("sequences", tags))
          .isInstanceOf(AccountException.class);
    assertThatThrownBy(() -> GenerationChoices.resolve("strings", List.of("squares")))
        .isInstanceOf(AccountException.class);
    for (String id :
        List.of(
            "sequence-recipe-v1-ALL-CUBE-SUM",
            "sequence-recipe-v1-ALL-SQUARE-COUNT",
            "sequence-recipe-v1-ALL-IDENTITY-SUM-extra"))
      assertThatThrownBy(() -> GenerationType.of(id)).isInstanceOf(AccountException.class);
  }
}
