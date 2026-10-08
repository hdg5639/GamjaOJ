package dev.gamjaoj.judge.domain;

import com.fasterxml.jackson.databind.JsonNode;

/** Server-owned allowlist. Unknown, mixed boundary and resource jobs stay exclusive. */
public final class JudgeScheduling {
  public static String generated(String role, JsonNode plan) {
    if (!role.matches("(?:reference|oracle|validator)-[0-9]+")) return "EXCLUSIVE";
    var tests = plan.path("tests");
    if (!tests.isArray() || tests.isEmpty()) return "EXCLUSIVE";
    for (var test : tests) {
      String id = test.path("id").asText();
      if (!id.equals("sample") && !id.startsWith("small-")) return "EXCLUSIVE";
    }
    return "FUNCTIONAL";
  }

  public static String experimental(String role) {
    return role.matches(
            "(?:reference|oracle)-(?:samples|generated-[0-9]+)|final-(?:small|seed)-(?:ref|oracle)-[0-9]+")
        ? "FUNCTIONAL"
        : "EXCLUSIVE";
  }
}
