package dev.gamjaoj.service.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gamjaoj.exception.AccountException;
import dev.gamjaoj.service.problem.ProblemContract;
import dev.gamjaoj.support.JudgeJson;
import java.util.List;

/** Small registry of reviewed generation contracts; no unvalidated free-form templates. */
public final class GenerationType {
  public static final GenerationType SUM =
      new GenerationType("sequence-sum-v1", "수열 합", "total-v1");
  public static final GenerationType PARENTHESES =
      new GenerationType("parentheses-v1", "올바른 괄호", "valid-parentheses-v1");
  public final String id, title, baseProblem;
  public final ProblemContract recipe;

  public static GenerationType[] values() {
    return new GenerationType[] {
      SUM,
      PARENTHESES,
      new GenerationType(new GraphRecipe(false, false, GraphRecipe.Query.DISTANCE))
    };
  }

  public GenerationType(ProblemContract recipe) {
    this.recipe = recipe;
    this.id = recipe.id();
    this.title = recipe.title();
    this.baseProblem = "total-v1";
  }

  public GenerationType(String id, String title, String baseProblem) {
    this.recipe = null;
    this.id = id;
    this.title = title;
    this.baseProblem = baseProblem;
  }

  public static GenerationType of(String id) {
    if (id != null && id.startsWith(SequenceRecipe.PREFIX))
      return new GenerationType(SequenceRecipe.parse(id));
    if (id != null && id.startsWith(GraphRecipe.PREFIX))
      return new GenerationType(GraphRecipe.parse(id));
    for (var type : values()) if (type.id.equals(id)) return type;
    throw new AccountException(400, "지원하는 문제 유형을 선택해 주세요.");
  }

  public List<String> focuses() {
    if (recipe != null) {
      var tags = new java.util.ArrayList<>(List.of("basics", "overflow", "edge-cases"));
      tags.addAll(recipe.operationTags());
      return tags;
    }
    return this == SUM
        ? List.of("basics", "overflow", "edge-cases")
        : List.of("basics", "prefix-balance", "edge-cases");
  }

  public String statement() {
    if (recipe != null) return recipe.statement();
    return this == SUM ? GenerationTemplate.STATEMENT : ParenthesesTemplate.STATEMENT;
  }

  public ObjectNode spec() {
    if (recipe != null) return recipe.spec();
    if (this == SUM) return GenerationTemplate.spec();
    return JudgeJson.JSON
        .createObjectNode()
        .put("templateId", id)
        .put("statement", statement())
        .put("runtime", "Java 8 / Main / STDIO")
        .put("sourceKind", "ORIGINAL_TEMPLATE")
        .put(
            "permissionNote",
            "GamjaOJ original reviewed template; do not copy external problem text")
        .put(
            "generatorContract",
            "Main reads one long seed; prints exactly four lines, each a nonempty parentheses-only"
                + " string of length 1..1000, without spaces or answers. Deterministic for the"
                + " seed; cover valid nesting, equal counts with invalid prefix, unclosed input and"
                + " a seeded case.")
        .put(
            "validatorContract",
            "Main reads one input, ignores surrounding whitespace, then prints VALID only for one"
                + " nonempty parentheses-only token of length 1..1000; INVALID for missing input,"
                + " extra tokens, internal whitespace, other characters or excessive length."
                + " Malformed input must not throw.");
  }

  public String focus(String focus) {
    if (recipe != null)
      return "Practice "
          + GenerationChoices.label(focus)
          + " under the exact declared contract. Explain its boundary cases, arithmetic bounds and"
          + " the actual algorithm without changing the rules.";
    if (focus.equals("edge-cases"))
      return this == SUM
          ? "Emphasize negative values, cancellation, singleton inputs and boundary testing."
          : "Emphasize single characters, reversed pairs, invalid prefixes, unclosed input and"
              + " maximum nesting.";
    if (focus.equals("overflow"))
      return "Emphasize why individual values fit int but the sum requires long.";
    if (focus.equals("prefix-balance"))
      return "Explain why equal total counts are insufficient: every prefix must have nonnegative"
          + " balance.";
    return this == SUM
        ? "Teach reading N values, streaming accumulation and a loop invariant for beginners."
        : "Teach left-to-right balance tracking, early invalid prefixes and checking zero final"
            + " balance.";
  }

  public JsonNode sample() {
    if (recipe != null) return recipe.sample();
    return test("sample", this == SUM ? GenerationTemplate.input(1, 2, 3) : "(())()\n");
  }

  public ObjectNode test(String id, String input) {
    if (recipe != null) return recipe.test(id, input);
    return this == SUM ? GenerationTemplate.test(id, input) : ParenthesesTemplate.test(id, input);
  }

  public List<JsonNode> cases(long seed) {
    if (recipe != null) return recipe.cases(seed);
    return this == SUM ? GenerationTemplate.cases(seed) : ParenthesesTemplate.cases(seed);
  }

  public List<String> invalidInputs() {
    if (recipe != null) return recipe.invalidInputs();
    return this == SUM
        ? List.of(
            "0\n",
            "1001\n",
            "2\n1\n",
            "1\n1000000001\n",
            "1\n-1000000001\n",
            "1\n0 1\n",
            "x\n",
            "",
            "1\n9223372036854775808\n",
            "1\n-9223372036854775808\n",
            "1\n1.5\n")
        : List.of(
            "",
            "\n",
            " ",
            "a\n",
            "(a)\n",
            "[()]\n",
            "() ()\n",
            "( )\n",
            "()\n()\n",
            "(".repeat(1001) + "\n",
            "1\n");
  }

  public List<JsonNode> mutantCases(List<JsonNode> cases) {
    if (recipe != null) return recipe.mutantCases(cases);
    return cases.stream()
        .filter(
            t ->
                List.of("sample", this == SUM ? "positive-boundary" : "reversed", "unclosed")
                    .contains(t.path("id").asText()))
        .toList();
  }

  public String mutant(boolean first) {
    if (recipe != null) return recipe.mutant(first);
    return this == SUM ? GenerationTemplate.mutant(first) : ParenthesesTemplate.mutant(first);
  }

  public String mutantRole(boolean first) {
    if (recipe != null) return recipe.mutantRole(first);
    return this == SUM
        ? (first ? "mutant-overflow" : "mutant-last")
        : (first ? "mutant-counts" : "mutant-prefix-only");
  }

  public String smallDomain() {
    if (recipe != null) return recipe.smallDomain();
    return this != PARENTHESES ? "all N=1..3 over {-1,0,1}" : "all lengths 1..4 over {(,)}";
  }

  public int executions() {
    return 1 + 3 * ((cases(0).size() + 4 + 19) / 20) + 1 + 2 + 2;
  }
}
