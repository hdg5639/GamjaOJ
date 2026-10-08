package dev.gamjaoj.generation.service;

import dev.gamjaoj.shared.exception.AccountException;
import java.util.Arrays;
import java.util.List;

/** Public learning choices are separate from the reviewed execution contracts. */
public final class GenerationChoices {
  public record Tag(String id, String label, String group) {}

  public record Category(
      String id, String label, String template, String contract, List<Tag> tags) {}

  public record Selection(String template, String focus, String title, String statement) {}

  public static String categoryId(GenerationType type) {
    if (type.recipe != null) return type.recipe.categoryId();
    return type != GenerationType.PARENTHESES ? "sequences" : "strings";
  }

  public static String label(String tag) {
    return switch (tag) {
      case "directed" -> "방향 간선";
      case "weighted" -> "가중치·0 비용";
      case "reachable-count" -> "도달 정점 수";
      case "max-distance" -> "최대 최단 거리";
      case "filter-positive" -> "양수 선택";
      case "filter-negative" -> "음수 선택";
      case "filter-even" -> "짝수 선택";
      case "filter-odd" -> "홀수 선택";
      case "absolute-values" -> "절댓값";
      case "squares" -> "제곱";
      case "count" -> "개수 세기";
      case "basics" -> "기초 순회";
      case "overflow" -> "정수 범위·오버플로";
      case "prefix-balance" -> "접두 구간·균형";
      case "edge-cases" -> "경계값·테스트 설계";
      default -> tag;
    };
  }

  public static List<Category> catalog() {
    return Arrays.stream(GenerationType.values())
        .map(
            type ->
                new Category(
                    categoryId(type),
                    GenerationStructures.category(type),
                    type.id,
                    type.title,
                    availableTags(type).stream()
                        .map(
                            tag ->
                                new Tag(
                                    tag,
                                    label(tag),
                                    List.of("directed", "weighted").contains(tag)
                                        ? "그래프 조건"
                                        : List.of("reachable-count", "max-distance").contains(tag)
                                            ? "질의"
                                            : tag.startsWith("filter-")
                                                ? "선택 조건"
                                                : List.of("absolute-values", "squares")
                                                        .contains(tag)
                                                    ? "값 변환"
                                                    : tag.equals("count") ? "집계" : "연습 목표"))
                        .toList()))
        .toList();
  }

  public static List<String> availableTags(GenerationType type) {
    var tags = new java.util.ArrayList<>(type.focuses());
    if (type == GenerationType.SUM)
      tags.addAll(
          List.of(
              "filter-positive",
              "filter-negative",
              "filter-even",
              "filter-odd",
              "absolute-values",
              "squares",
              "count"));
    if (type.recipe instanceof GraphRecipe)
      tags.addAll(List.of("directed", "weighted", "reachable-count", "max-distance"));
    return tags;
  }

  public static Selection resolve(String category, List<String> tags) {
    var choice =
        catalog().stream()
            .filter(item -> item.id().equals(category))
            .findFirst()
            .orElseThrow(() -> new AccountException(400, "지원하는 카테고리를 선택해 주세요."));
    if (tags == null
        || tags.isEmpty()
        || tags.size() > 6
        || tags.stream().anyMatch(tag -> tag == null || tag.contains(",")))
      throw new AccountException(400, "연습 태그를 1개 이상, 최대 6개 선택해 주세요.");
    var type = GenerationType.of(choice.template());
    if (!availableTags(type).containsAll(tags))
      throw new AccountException(400, "선택한 카테고리에서 지원하지 않는 태그예요.");
    if (type == GenerationType.SUM) {
      var filters = tags.stream().filter(tag -> tag.startsWith("filter-")).distinct().toList();
      if (filters.size() > 1 || (tags.contains("absolute-values") && tags.contains("squares")))
        throw new AccountException(400, "선택 조건과 값 변환은 각각 하나만 선택해 주세요.");
      if (!filters.isEmpty()
          || tags.contains("absolute-values")
          || tags.contains("squares")
          || tags.contains("count")) {
        var filter =
            filters.isEmpty()
                ? SequenceRecipe.Filter.ALL
                : SequenceRecipe.Filter.valueOf(
                    filters.get(0).substring(7).toUpperCase(java.util.Locale.ROOT));
        var transform =
            tags.contains("absolute-values")
                ? SequenceRecipe.Transform.ABS
                : tags.contains("squares")
                    ? SequenceRecipe.Transform.SQUARE
                    : SequenceRecipe.Transform.IDENTITY;
        type =
            new GenerationType(
                new SequenceRecipe(
                    filter,
                    transform,
                    tags.contains("count")
                        ? SequenceRecipe.Reduction.COUNT
                        : SequenceRecipe.Reduction.SUM));
      }
    }
    if (type.recipe instanceof GraphRecipe) {
      if (tags.contains("reachable-count") && tags.contains("max-distance"))
        throw new AccountException(400, "그래프 질의는 하나만 선택해 주세요.");
      type =
          new GenerationType(
              new GraphRecipe(
                  tags.contains("directed"),
                  tags.contains("weighted"),
                  tags.contains("reachable-count")
                      ? GraphRecipe.Query.COUNT
                      : tags.contains("max-distance")
                          ? GraphRecipe.Query.MAX
                          : GraphRecipe.Query.DISTANCE));
    }
    return new Selection(
        type.id, normalize(type, String.join(",", tags)), type.title, type.statement());
  }

  public static String normalize(GenerationType type, String focus) {
    if (focus == null) throw new AccountException(400, "연습 태그를 확인해 주세요.");
    var tags = Arrays.asList(focus.split(",", -1));
    if (tags.isEmpty() || tags.size() > 6 || !type.focuses().containsAll(tags))
      throw new AccountException(400, "선택한 카테고리에서 지원하지 않는 연습 태그예요.");
    return String.join(",", tags.stream().distinct().sorted().toList());
  }
}
