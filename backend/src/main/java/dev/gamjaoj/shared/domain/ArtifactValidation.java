package dev.gamjaoj.shared.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;

/** Structural validation shared by execution profiles and generated packages. */
public final class ArtifactValidation {
  private ArtifactValidation() {}

  public static class Invalid extends RuntimeException {
    public Invalid(String code) {
      super(code);
    }
  }

  public static void require(boolean test, String code) {
    if (!test) throw new Invalid(code);
  }

  public static void fields(JsonNode node, String... names) {
    require(node != null && node.isObject(), "INVALID_OBJECT");
    var actual = new HashSet<String>();
    node.fieldNames().forEachRemaining(actual::add);
    require(actual.equals(Set.of(names)), "INVALID_FIELDS");
  }

  public static void text(JsonNode node, int maximum) {
    require(
        node != null
            && node.isTextual()
            && !node.asText().isBlank()
            && node.asText().length() <= maximum,
        "INVALID_TEXT");
  }

  public static void texts(JsonNode node, int min, int max, int length) {
    require(
        node != null && node.isArray() && node.size() >= min && node.size() <= max, "INVALID_LIST");
    node.forEach(v -> text(v, length));
  }
}
