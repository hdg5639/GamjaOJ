package dev.gamjaoj.service.export;

import static dev.gamjaoj.infrastructure.export.ExportRemote.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gamjaoj.infrastructure.export.ExportRemote;
import java.util.*;
import java.util.function.Consumer;

/** Database rows reuse the existing delivery checkpoint, lease fence and managed-code contract. */
public final class NotionTables {
  public static final String NAME = "GamjaOJ 풀이", MARKER = "GamjaOJ accepted solutions table v1";
  public static final Map<String, String> COLUMNS =
      Map.of(
          "생각의 겹",
          "select",
          "언어",
          "select",
          "결과",
          "select",
          "문제 링크",
          "url",
          "통과 시각",
          "date",
          "GamjaOJ ID",
          "rich_text",
          "실행 시간 (ms)",
          "number",
          "최대 메모리 (MiB)",
          "number");
  final ExportRemote remote;

  public NotionTables(ExportRemote remote) {
    this.remote = remote;
  }

  public JsonNode api(String token, String method, String path, JsonNode body) {
    return remote.api("NOTION", token, method, path, body);
  }

  public JsonNode target(String token, String id) {
    var source = api(token, "GET", "/data_sources/" + id, null);
    check(source);
    titleProperty(source);
    return obj()
        .put("id", "data_source:" + id)
        .put("dataSourceId", id)
        .put("kind", "notion_table")
        .put("label", plain(source.path("title")).isBlank() ? NAME : plain(source.path("title")))
        .put(
            "url",
            source
                .path("url")
                .asText(
                    "https://www.notion.so/"
                        + source.path("parent").path("database_id").asText().replace("-", "")));
  }

  public static void check(JsonNode object) {
    if (object.path("archived").asBoolean() || object.path("in_trash").asBoolean())
      throw new Failure("TARGET_NOT_FOUND", false);
  }

  public static String titleProperty(JsonNode source) {
    for (var property : source.path("properties"))
      if (property.path("type").asText().equals("title"))
        return property.path("id").asText("title");
    throw new Failure("NOTION_SCHEMA_MISMATCH", false);
  }

  public static ObjectNode schema() {
    var properties = obj();
    properties.putObject("문제").putObject("title");
    COLUMNS.forEach((name, type) -> properties.putObject(name).putObject(type));
    return properties;
  }

  /** Add only missing managed columns. Never rename, remove, or convert user properties. */
  public JsonNode prepare(String token, String sourceId) {
    var source = api(token, "GET", "/data_sources/" + sourceId, null);
    check(source);
    titleProperty(source);
    var additions = obj();
    COLUMNS.forEach(
        (name, type) -> {
          var property = source.path("properties").path(name);
          if (property.isMissingNode()) additions.putObject(name).putObject(type);
          else if (!property.path("type").asText().equals(type))
            throw new Failure("NOTION_SCHEMA_MISMATCH", false);
        });
    if (!additions.isEmpty()) {
      var body = obj();
      body.set("properties", additions);
      api(token, "PATCH", "/data_sources/" + sourceId, body);
      var updated = api(token, "GET", "/data_sources/" + sourceId, null);
      check(updated);
      for (var e : COLUMNS.entrySet())
        if (!updated.path("properties").path(e.getKey()).path("type").asText().equals(e.getValue()))
          throw new Failure("NOTION_SCHEMA_MISMATCH", false);
      return updated;
    }
    return source;
  }

  public String source(
      String token,
      JsonNode target,
      ObjectNode state,
      Consumer<ObjectNode> checkpoint,
      Runnable fence) {
    if (target.hasNonNull("dataSourceId")) return target.path("dataSourceId").asText();
    if (state.hasNonNull("dataSourceId")) return state.path("dataSourceId").asText();
    String marker = MARKER + " · " + target.path("id").asText();
    JsonNode database = null;
    // All deliveries to this parent share the marked table; a lost create response is reconciled
    // here.
    for (var block : remote.children(token, target.path("id").asText())) {
      if (!block.path("type").asText().equals("child_database")
          || !block.path("child_database").path("title").asText().equals(NAME)) continue;
      var candidate = api(token, "GET", "/databases/" + block.path("id").asText(), null);
      if (!plain(candidate.path("description")).equals(marker)) continue;
      check(candidate);
      if (database != null) throw new Failure("NOTION_TABLE_AMBIGUOUS", false);
      database = candidate;
    }
    if (database == null) {
      if (state.path("creatingTable").asBoolean()) throw new Failure("DELIVERY_UNCERTAIN", false);
      var body = obj().put("is_inline", true);
      body.putObject("parent").put("type", "page_id").put("page_id", target.path("id").asText());
      body.set("title", rich(NAME));
      body.set("description", rich(marker));
      body.putObject("initial_data_source").set("properties", schema());
      state.put("creatingTable", true);
      checkpoint.accept(state);
      fence.run();
      try {
        database = api(token, "POST", "/databases", body);
      } catch (Failure e) {
        resetDefinite(state, "creatingTable", checkpoint, e);
        throw e;
      }
    }
    if (database.path("data_sources").size() != 1)
      throw new Failure("NOTION_TABLE_AMBIGUOUS", false);
    String id = database.path("data_sources").get(0).path("id").asText();
    if (id.isBlank()) throw new Failure("DELIVERY_UNCERTAIN", false);
    state.put("dataSourceId", id);
    checkpoint.accept(state);
    return id;
  }

  public ObjectNode properties(JsonNode source, JsonNode payload, String identity) {
    var properties = obj();
    properties.putObject(titleProperty(source)).set("title", rich(payload.path("title").asText()));
    for (var name : COLUMNS.keySet()) {
      String id = source.path("properties").path(name).path("id").asText();
      if (id.isBlank()) throw new Failure("NOTION_SCHEMA_MISMATCH", false);
      var value = properties.putObject(id);
      switch (name) {
        case "실행 시간 (ms)" -> {
          if (payload.hasNonNull("maxWallMs"))
            value.put("number", payload.path("maxWallMs").asLong());
          else value.putNull("number");
        }
        case "최대 메모리 (MiB)" -> {
          if (payload.hasNonNull("maxMemoryBytes"))
            value.put("number", payload.path("maxMemoryBytes").asLong() / 1048576.0);
          else value.putNull("number");
        }
        case "생각의 겹" -> value.putObject("select").put("name", GitHubSolutionLayout.rating(payload));
        case "언어" -> value.putObject("select").put("name", payload.path("language").asText());
        case "결과" -> value.putObject("select").put("name", "AC");
        case "문제 링크" -> value.put("url", payload.path("problemUrl").asText());
        case "통과 시각" -> value.putObject("date").put("start", payload.path("finishedAt").asText());
        case "GamjaOJ ID" -> value.set("rich_text", rich(identity));
      }
    }
    return properties;
  }

  public String findRow(String token, String sourceId, String key, String identity) {
    var body = obj().put("page_size", 100);
    body.putObject("filter").put("property", key).putObject("rich_text").put("equals", identity);
    var result = api(token, "POST", "/data_sources/" + sourceId + "/query", body);
    if (!result.path("results").isArray()) throw new Failure("REMOTE_ERROR", false);
    if (result.path("has_more").asBoolean()
        || result.path("request_status").path("type").asText().equals("incomplete")
        || result.path("results").size() > 1) throw new Failure("NOTION_TABLE_AMBIGUOUS", false);
    return result.path("results").isEmpty()
        ? null
        : result.path("results").get(0).path("id").asText();
  }

  public String publish(
      String token,
      JsonNode target,
      JsonNode payload,
      ObjectNode state,
      Consumer<ObjectNode> checkpoint,
      Runnable fence) {
    String sourceId = source(token, target, state, checkpoint, fence);
    var source = prepare(token, sourceId);
    String identity =
        "GamjaOJ · "
            + payload.path("username").asText()
            + " · "
            + payload.path("problemVersion").asText()
            + " · "
            + payload.path("language").asText();
    var properties = properties(source, payload, identity);
    if (!state.hasNonNull("pageId")) {
      String found =
          findRow(
              token,
              sourceId,
              source.path("properties").path("GamjaOJ ID").path("id").asText(),
              identity);
      if (found != null) {
        state.put("pageId", found);
        checkpoint.accept(state);
      } else {
        if (state.path("creating").asBoolean()) throw new Failure("DELIVERY_UNCERTAIN", false);
        var body = obj();
        body.putObject("parent").put("type", "data_source_id").put("data_source_id", sourceId);
        body.set("properties", properties);
        var blocks = body.putArray("children");
        blocks.add(block("paragraph", info(identity, payload)));
        blocks.add(code(payload));
        blocks.add(block("heading_2", "내 회고"));
        blocks.add(block("paragraph", ""));
        state.put("creating", true);
        checkpoint.accept(state);
        fence.run();
        JsonNode created;
        try {
          created = api(token, "POST", "/pages", body);
        } catch (Failure e) {
          resetDefinite(state, "creating", checkpoint, e);
          throw e;
        }
        String id = created.path("id").asText();
        if (id.isBlank()) throw new Failure("DELIVERY_UNCERTAIN", false);
        state.put("pageId", id);
        checkpoint.accept(state);
      }
    }
    String pageId = state.path("pageId").asText();
    var page = api(token, "GET", "/pages/" + pageId, null);
    check(page);
    if (!page.path("parent").path("data_source_id").asText().equals(sourceId))
      throw new Failure("TARGET_CHANGED", false);
    // Match the stored identity before updating a recovered or cached row.
    if (!plain(page.path("properties").path("GamjaOJ ID").path("rich_text")).equals(identity))
      throw new Failure("TARGET_CHANGED", false);
    var body = obj();
    body.set("properties", properties);
    fence.run();
    api(token, "PATCH", "/pages/" + pageId, body);
    // Reuse the legacy managed-block writer with this exact saved row; it never touches the
    // reflection.
    return remote.notion(token, target, payload, state, checkpoint, fence);
  }

  public static void resetDefinite(
      ObjectNode state, String flag, Consumer<ObjectNode> checkpoint, Failure e) {
    if (Set.of(
            "RECONNECT_REQUIRED",
            "PERMISSION_REQUIRED",
            "TARGET_NOT_FOUND",
            "RATE_LIMITED",
            "REMOTE_CONFLICT")
        .contains(e.code)) {
      state.put(flag, false);
      checkpoint.accept(state);
    }
  }
}
