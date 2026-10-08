package dev.gamjaoj.shared.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeSet;

public final class JudgeJson {
  public static final ObjectMapper JSON = new ObjectMapper();

  public static JsonNode parse(String value) {
    try {
      return JSON.readTree(value);
    } catch (Exception e) {
      throw new IllegalStateException("Invalid stored judge JSON", e);
    }
  }

  public static JsonNode sorted(JsonNode value) {
    if (value.isObject()) {
      ObjectNode result = JSON.createObjectNode();
      TreeSet<String> keys = new TreeSet<>();
      value.fieldNames().forEachRemaining(keys::add);
      keys.forEach(key -> result.set(key, sorted(value.get(key))));
      return result;
    }
    if (value.isArray()) {
      ArrayNode result = JSON.createArrayNode();
      value.forEach(item -> result.add(sorted(item)));
      return result;
    }
    return value;
  }

  public static String canonical(JsonNode value) {
    return sorted(value).toString();
  }

  public static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
