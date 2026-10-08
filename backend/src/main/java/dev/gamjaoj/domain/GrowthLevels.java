package dev.gamjaoj.domain;

import java.util.*;

/** Personal evidence of repeated success, not a calibrated competitive ranking. */
public final class GrowthLevels {
  public static final int REQUIRED = 5;

  public record Solved(String version, String category, Integer layer, String source) {}

  public record Growth(
      int layer,
      String name,
      int solvedProblems,
      int eligibleProblems,
      int excludedProblems,
      int categories,
      Integer nextLayer,
      int nextSolved,
      int required,
      List<Integer> evidence) {}

  public static Growth calculate(List<Solved> solved) {
    var unique = new LinkedHashMap<String, Solved>();
    solved.forEach(p -> unique.putIfAbsent(p.version(), p));
    int[] evidence = new int[9];
    int eligible = 0;
    var categories = new HashSet<String>();
    for (var p : unique.values()) {
      if (p.layer() == null
          || p.layer() < 1
          || p.layer() > 9
          || !"CURATED_ESTIMATE".equals(p.source())) continue;
      eligible++;
      categories.add(p.category());
      for (int i = 0; i < p.layer(); i++) evidence[i]++;
    }
    int layer = 0;
    for (int i = 0; i < 9; i++) if (evidence[i] >= REQUIRED) layer = i + 1;
    var counts = Arrays.stream(evidence).boxed().toList();
    return new Growth(
        layer,
        layer == 0 ? "준비 중" : ThinkingLayers.name(layer),
        unique.size(),
        eligible,
        unique.size() - eligible,
        categories.size(),
        layer == 9 ? null : layer + 1,
        layer == 9 ? REQUIRED : Math.min(REQUIRED, evidence[layer]),
        REQUIRED,
        counts);
  }
}
