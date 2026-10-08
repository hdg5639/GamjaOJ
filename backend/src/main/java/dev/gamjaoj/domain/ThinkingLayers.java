package dev.gamjaoj.domain;

import java.util.List;

/**
 * Shared names for problem difficulty and personal growth; independent of persistence and
 * generation.
 */
public final class ThinkingLayers {
  private static final List<String> NAMES =
      List.of("", "그대로", "살펴보기", "골라쓰기", "이어붙이기", "뒤집어보기", "상태 만들기", "덜어내기", "꿰뚫기", "새로 짜기");

  private ThinkingLayers() {}

  public static String name(int layer) {
    return NAMES.get(layer);
  }
}
