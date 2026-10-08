package dev.gamjaoj.service.problem;

import dev.gamjaoj.domain.ThinkingLayers;
import dev.gamjaoj.domain.ThinkingProfile.Profile;
import dev.gamjaoj.exception.AccountException;
import dev.gamjaoj.service.generation.GenerationRequirements;
import dev.gamjaoj.service.generation.GenerationType;
import dev.gamjaoj.service.generation.GraphRecipe;
import dev.gamjaoj.service.generation.HybridArtifacts;
import dev.gamjaoj.service.generation.SequenceRecipe;
import dev.gamjaoj.support.JudgeJson;
import jakarta.validation.constraints.*;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Public, provisional reasoning ratings. Independent of judge identity and personal confidence. */
public final class ThinkingDifficulty {
  public static String authoringBand(Integer layer) {
    if (layer == null || layer < 1 || layer > 9)
      throw new AccountException(400, "생각의 겹은 1~9에서 골라 주세요.");
    return layer <= 2 ? "EASY" : layer <= 5 ? "MEDIUM" : layer <= 7 ? "HARD" : "EXPERT";
  }

  public static Profile read(ResultSet r) throws SQLException {
    int layer = r.getInt("thinking_layer");
    if (r.wasNull()) return null;
    return new Profile(
        layer,
        ThinkingLayers.name(layer),
        r.getInt("thinking_insight"),
        r.getInt("thinking_implementation"),
        r.getInt("thinking_edge_cases"),
        r.getString("thinking_rationale"),
        r.getString("thinking_source"));
  }

  public static final String REVIEW = GenerationRequirements.read("thinking-review.txt");

  public static com.fasterxml.jackson.databind.node.ObjectNode schema() {
    return (com.fasterxml.jackson.databind.node.ObjectNode)
        JudgeJson.parse(GenerationRequirements.read("thinking-schema.json"));
  }

  public static void addSchema(com.fasterxml.jackson.databind.node.ObjectNode schema) {
    ((com.fasterxml.jackson.databind.node.ObjectNode) schema.path("properties"))
        .set("thinking", schema());
    ((com.fasterxml.jackson.databind.node.ArrayNode) schema.path("required")).add("thinking");
  }

  public static void validate(com.fasterxml.jackson.databind.JsonNode value) {
    HybridArtifacts.require(value.isObject() && value.size() == 5, "INVALID_THINKING_REVIEW");
    for (String key : java.util.List.of("layer", "insight", "implementation", "edgeCases")) {
      var n = value.path(key);
      HybridArtifacts.require(
          n.isIntegralNumber()
              && n.canConvertToInt()
              && n.asInt() >= 1
              && n.asInt() <= (key.equals("layer") ? 9 : 5),
          "INVALID_THINKING_REVIEW");
    }
    var rationale = value.path("rationale");
    HybridArtifacts.require(
        rationale.isTextual()
            && !rationale.asText().isBlank()
            && rationale.asText().length() <= 300,
        "INVALID_THINKING_REVIEW");
  }

  /**
   * Caller holds the publication transaction; bind the assessment to exactly the judged package.
   */
  public static com.fasterxml.jackson.databind.JsonNode template(GenerationType type) {
    int layer = 1, insight = 1, implementation = 1, edgeCases = 2;
    String rationale = "주어진 처리 순서를 옮기고 누적값의 수 범위를 챙기는 문제예요.";
    if (type == GenerationType.PARENTHESES) {
      layer = 2;
      insight = 2;
      edgeCases = 3;
      rationale = "처리 도중의 유효성과 마지막 상태를 함께 살펴야 해요.";
    }
    if (type.recipe instanceof SequenceRecipe s) {
      layer =
          s.filter == SequenceRecipe.Filter.ALL && s.transform == SequenceRecipe.Transform.IDENTITY
              ? 1
              : 2;
      insight = layer;
      edgeCases = s.transform == SequenceRecipe.Transform.SQUARE ? 3 : 2;
      rationale = layer == 1 ? rationale : "각 값에 적용할 조건과 처리 순서를 구분하고 수 범위를 챙겨야 해요.";
    }
    if (type.recipe instanceof GraphRecipe g) {
      boolean cost = g.weighted && g.query != GraphRecipe.Query.COUNT;
      layer = cost ? 4 : 3;
      insight = layer;
      implementation = cost ? 3 : 2;
      edgeCases = 3;
      rationale =
          cost
              ? "관계의 방향과 비용 조건을 함께 반영하고 도달할 수 없는 경우도 처리해야 해요."
              : "연결 관계에 맞는 도구를 골라 쓰고 중복 연결과 도달 여부를 챙겨야 해요.";
    }
    return JudgeJson.JSON
        .createObjectNode()
        .put("layer", layer)
        .put("insight", insight)
        .put("implementation", implementation)
        .put("edgeCases", edgeCases)
        .put("rationale", rationale);
  }
}
