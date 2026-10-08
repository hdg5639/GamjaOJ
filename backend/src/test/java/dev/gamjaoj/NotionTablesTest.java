package dev.gamjaoj;

import static dev.gamjaoj.export.infrastructure.ExportRemote.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gamjaoj.export.config.ExportSettings;
import dev.gamjaoj.export.infrastructure.ExportHttp;
import dev.gamjaoj.export.infrastructure.ExportRemote;
import dev.gamjaoj.export.service.NotionTables;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class NotionTablesTest {
  final ExportHttp http = mock(ExportHttp.class);
  final ExportRemote remote = new ExportRemote(new ExportSettings(new MockEnvironment()), http);

  ObjectNode payload() {
    return obj()
        .put("username", "learner")
        .put("problemVersion", "sum-v1")
        .put("title", "두 수의 합")
        .put("language", "JAVA")
        .put("source", "public class Main {}")
        .put("problemUrl", "https://example.test/?problem=sum-v1")
        .put("finishedAt", "2026-10-01T00:00:00Z")
        .put("maxWallMs", 123)
        .put("maxMemoryBytes", 33554432);
  }

  ObjectNode source() {
    var s = obj().put("object", "data_source").put("id", "source");
    s.set("title", rich("풀이 기록"));
    var props = s.putObject("properties");
    props.putObject("제목").put("id", "title-id").put("type", "title");
    NotionTables.COLUMNS.forEach(
        (name, type) -> props.putObject(name).put("id", "id-" + name).put("type", type));
    return s;
  }

  String identity() {
    return "GamjaOJ · learner · sum-v1 · JAVA";
  }

  ObjectNode row() {
    var p = obj().put("id", "row");
    p.putObject("parent").put("data_source_id", "source");
    p.putObject("properties").putObject("GamjaOJ ID").set("rich_text", rich(identity()));
    return p;
  }

  ObjectNode list(JsonNode... nodes) {
    var l = obj().put("has_more", false);
    var a = l.putArray("results");
    for (var n : nodes) a.add(n);
    return l;
  }

  ObjectNode children() {
    return list(
        block("paragraph", info(identity(), payload())).put("id", "info"),
        code(payload()).put("id", "code"),
        block("paragraph", "회고 원문").put("id", "reflection"));
  }

  ObjectNode target() {
    return obj()
        .put("kind", "notion_table")
        .put("id", "data_source:source")
        .put("dataSourceId", "source");
  }

  @Test
  void tableRowCreatesTypedPropertiesAndReplaysWithoutTouchingReflection() {
    var requests = new ArrayList<String>();
    var state = obj();
    when(http.request(anyString(), anyString(), anyMap(), nullable(JsonNode.class)))
        .thenAnswer(
            c -> {
              String m = c.getArgument(0), url = c.getArgument(1);
              JsonNode body = c.getArgument(3);
              requests.add(m + " " + url);
              assertThat(c.getArgument(2, Map.class).get("Notion-Version")).isEqualTo("2026-03-11");
              if (url.endsWith("/data_sources/source")) return source();
              if (url.endsWith("/query")) {
                assertThat(body.path("filter").path("rich_text").path("equals").asText())
                    .isEqualTo(identity());
                return list();
              }
              if (m.equals("POST") && url.endsWith("/pages")) {
                assertThat(state.path("creating").asBoolean()).isTrue();
                assertThat(body.path("parent").path("data_source_id").asText()).isEqualTo("source");
                var p = body.path("properties");
                assertThat(plain(p.path("title-id").path("title"))).isEqualTo("두 수의 합");
                assertThat(p.path("id-언어").path("select").path("name").asText()).isEqualTo("JAVA");
                assertThat(p.path("id-결과").path("select").path("name").asText()).isEqualTo("AC");
                assertThat(p.path("id-문제 링크").path("url").asText())
                    .startsWith("https://example.test/");
                assertThat(p.path("id-통과 시각").path("date").path("start").asText())
                    .isEqualTo("2026-10-01T00:00:00Z");
                assertThat(p.path("id-실행 시간 (ms)").path("number").asLong()).isEqualTo(123);
                assertThat(p.path("id-최대 메모리 (MiB)").path("number").asDouble()).isEqualTo(32.0);
                assertThat(body.path("children")).hasSize(4);
                return row();
              }
              if (m.equals("GET") && url.endsWith("/pages/row")) return row();
              if (m.equals("GET")) return children();
              assertThat(
                      url.endsWith("/pages/row")
                          || url.endsWith("/blocks/info")
                          || url.endsWith("/blocks/code"))
                  .isTrue();
              return obj();
            });
    remote.publish("NOTION", "token", target(), payload(), state, s -> {}, () -> {});
    remote.publish(
        "NOTION", "token", target(), payload().put("source", "new code"), state, s -> {}, () -> {});
    assertThat(
            requests.stream().filter(r -> r.equals("POST https://api.notion.com/v1/pages")).count())
        .isEqualTo(1);
    assertThat(requests).noneMatch(r -> r.contains("reflection"));
    assertThat(state.path("pageId").asText()).isEqualTo("row");
  }

  @Test
  void lostRowCreateIsRecoveredByIdentityAndUnknownAbsenceNeverCreatesAgain() {
    var state = obj().put("creating", true);
    when(http.request(anyString(), anyString(), anyMap(), nullable(JsonNode.class)))
        .thenAnswer(
            c -> {
              String m = c.getArgument(0), url = c.getArgument(1);
              if (url.endsWith("/data_sources/source")) return source();
              if (url.endsWith("/query")) return list(row());
              assertThat(m).isNotEqualTo("POST");
              if (url.endsWith("/pages/row") && m.equals("GET")) return row();
              return m.equals("GET") ? children() : obj();
            });
    remote.publish("NOTION", "token", target(), payload(), state, s -> {}, () -> {});
    assertThat(state.path("pageId").asText()).isEqualTo("row");
    when(http.request(eq("POST"), endsWith("/query"), anyMap(), any())).thenReturn(list());
    assertThatThrownBy(
            () ->
                remote.publish(
                    "NOTION",
                    "token",
                    target(),
                    payload(),
                    obj().put("creating", true),
                    s -> {},
                    () -> {}))
        .hasMessage("DELIVERY_UNCERTAIN");
  }

  @Test
  void layerColumnUsesTheSameRatingAsGithubAndUpgradesExistingTables() {
    var legacy = source();
    ((ObjectNode) legacy.path("properties")).remove("생각의 겹");
    when(http.request(eq("GET"), endsWith("/data_sources/source"), anyMap(), isNull()))
        .thenReturn(legacy, source());
    when(http.request(eq("PATCH"), endsWith("/data_sources/source"), anyMap(), any()))
        .thenAnswer(
            c -> {
              var additions = c.getArgument(3, JsonNode.class).path("properties");
              assertThat(additions.size()).isEqualTo(1);
              assertThat(additions.path("생각의 겹").has("select")).isTrue();
              return source();
            });
    var table = new NotionTables(remote);
    var prepared = table.prepare("token", "source");
    var p = payload().put("difficulty", "EASY");
    p.set("thinking", obj().put("layer", 1).put("name", "그대로"));
    assertThat(
            table
                .properties(prepared, p, identity())
                .path("id-생각의 겹")
                .path("select")
                .path("name")
                .asText())
        .isEqualTo("1겹 · 그대로");
    p.putNull("thinking");
    assertThat(
            table
                .properties(prepared, p, identity())
                .path("id-생각의 겹")
                .path("select")
                .path("name")
                .asText())
        .isEqualTo("겹 미배정");
  }

  @Test
  void existingTableAddsOnlyMissingColumnsAndRejectsConflictingTypes() {
    var existing = source();
    ((ObjectNode) existing.path("properties")).remove("언어");
    existing
        .withObject("/properties")
        .putObject("개인 메모")
        .put("id", "note")
        .put("type", "rich_text");
    when(http.request(eq("GET"), endsWith("/data_sources/source"), anyMap(), isNull()))
        .thenReturn(existing, source());
    when(http.request(eq("PATCH"), endsWith("/data_sources/source"), anyMap(), any()))
        .thenAnswer(
            c -> {
              assertThat(c.getArgument(3, JsonNode.class).path("properties").size()).isEqualTo(1);
              assertThat(c.getArgument(3, JsonNode.class).path("properties").has("언어")).isTrue();
              return source();
            });
    new NotionTables(remote).prepare("token", "source");
    var bad = source();
    ((ObjectNode) bad.path("properties").path("결과")).put("type", "rich_text");
    when(http.request(eq("GET"), endsWith("/data_sources/source"), anyMap(), isNull()))
        .thenReturn(bad);
    assertThatThrownBy(() -> new NotionTables(remote).prepare("token", "source"))
        .hasMessage("NOTION_SCHEMA_MISMATCH");
  }

  @Test
  void parentCreatesOneMarkedTableAndReconcilesLostCreation() {
    var target = obj().put("id", "parent").put("kind", "notion_table_parent");
    var state = obj();
    var db = obj().put("id", "database");
    db.set("description", rich(NotionTables.MARKER + " · parent"));
    db.putArray("data_sources").addObject().put("id", "source");
    when(http.request(eq("GET"), contains("/blocks/parent/children"), anyMap(), isNull()))
        .thenReturn(list());
    when(http.request(eq("POST"), endsWith("/databases"), anyMap(), any()))
        .thenAnswer(
            c -> {
              assertThat(state.path("creatingTable").asBoolean()).isTrue();
              var body = c.getArgument(3, JsonNode.class);
              assertThat(body.path("initial_data_source").path("properties").has("GamjaOJ ID"))
                  .isTrue();
              return db;
            });
    assertThat(remote.notionTableSource("token", target, state, s -> {}, () -> {}))
        .isEqualTo("source");
    var child = obj().put("type", "child_database").put("id", "database");
    child.putObject("child_database").put("title", NotionTables.NAME);
    when(http.request(eq("GET"), contains("/blocks/parent/children"), anyMap(), isNull()))
        .thenReturn(list(child));
    when(http.request(eq("GET"), endsWith("/databases/database"), anyMap(), isNull()))
        .thenReturn(db);
    assertThat(
            remote.notionTableSource(
                "token", target, obj().put("creatingTable", true), s -> {}, () -> {}))
        .isEqualTo("source");
    verify(http, times(1)).request(eq("POST"), endsWith("/databases"), anyMap(), any());
    when(http.request(eq("GET"), contains("/blocks/parent/children"), anyMap(), isNull()))
        .thenReturn(list());
    assertThatThrownBy(
            () ->
                remote.notionTableSource(
                    "token", target, obj().put("creatingTable", true), s -> {}, () -> {}))
        .hasMessage("DELIVERY_UNCERTAIN");
  }

  ObjectNode movedRow() {
    var page = row();
    page.withObject("/parent").put("data_source_id", "other");
    return page;
  }

  @Test
  void movedOrEditedRowAndStaleFenceAreRejectedBeforeWriting() {
    when(http.request(eq("GET"), endsWith("/data_sources/source"), anyMap(), isNull()))
        .thenReturn(source());
    when(http.request(eq("GET"), endsWith("/pages/row"), anyMap(), isNull()))
        .thenReturn(movedRow());
    assertThatThrownBy(
            () ->
                remote.publish(
                    "NOTION",
                    "token",
                    target(),
                    payload(),
                    obj().put("pageId", "row"),
                    s -> {},
                    () -> {}))
        .hasMessage("TARGET_CHANGED");
    verify(http, never()).request(eq("PATCH"), anyString(), anyMap(), any());
    clearInvocations(http);
    assertThatThrownBy(
            () ->
                remote.publish(
                    "NOTION",
                    "token",
                    target(),
                    payload(),
                    obj(),
                    s -> {},
                    () -> {
                      throw new Failure("CONNECTION_CHANGED", false);
                    }))
        .hasMessage("CONNECTION_CHANGED");
    verifyNoInteractions(http);
  }
}
