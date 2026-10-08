package dev.gamjaoj;

import static dev.gamjaoj.generation.service.HybridGeneration.Role.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamjaoj.ai.config.AiSettings;
import dev.gamjaoj.generation.service.HybridArtifacts;
import dev.gamjaoj.generation.service.HybridGeneration;
import dev.gamjaoj.generation.service.HybridModels;
import dev.gamjaoj.shared.domain.ArtifactValidation;
import dev.gamjaoj.shared.exception.AccountException;
import dev.gamjaoj.shared.support.JudgeJson;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class HybridModelsTest {
  final HybridGenerationIntegrationTest fixtures = new HybridGenerationIntegrationTest();

  HybridGeneration.Assignment assignment(HybridGeneration.Role role, JsonNode input) {
    String hash = JudgeJson.hash(JudgeJson.canonical(input));
    return new HybridGeneration.Assignment(
        UUID.randomUUID(),
        UUID.randomUUID(),
        0,
        role,
        UUID.randomUUID(),
        hash,
        JudgeJson.hash(JudgeJson.canonical(fixtures.contract())),
        role == READER ? hash : null,
        input);
  }

  MockEnvironment configured() {
    var env = new MockEnvironment();
    for (String slot : List.of("WRITER", "READER")) {
      String p = "AI_HYBRID_" + slot + "_";
      env.withProperty(p + "MODEL", "explicit-test-model")
          .withProperty(p + "REASONING", "low")
          .withProperty(p + "INPUT_USD_PER_M", "1")
          .withProperty(p + "CACHED_USD_PER_M", "0.1")
          .withProperty(p + "OUTPUT_USD_PER_M", "2")
          .withProperty(p + "MAX_OUTPUT_TOKENS", "4096")
          .withProperty(p + "PRICING_VERSION", "test-rates-v1");
    }
    return env;
  }

  @Test
  void mandatorySlotsNeverInheritFeedbackDefaultsOrInventRates() {
    assertThatThrownBy(() -> HybridModels.slot(new AiSettings(new MockEnvironment()), PRESENTATION))
        .isInstanceOf(AccountException.class);
    var env = configured();
    var config = new AiSettings(env);
    assertThat(HybridModels.slot(config, PRESENTATION).model()).isEqualTo("explicit-test-model");
    assertThat(config.enabled()).isFalse();
    assertThat(config.budget()).isEqualByComparingTo("10");
    for (String key :
        List.of(
            "MODEL",
            "REASONING",
            "INPUT_USD_PER_M",
            "CACHED_USD_PER_M",
            "OUTPUT_USD_PER_M",
            "MAX_OUTPUT_TOKENS",
            "PRICING_VERSION")) {
      var broken = configured().withProperty("AI_HYBRID_READER_" + key, " ");
      assertThatThrownBy(() -> HybridModels.slot(new AiSettings(broken), READER))
          .isInstanceOf(AccountException.class);
    }
    assertThatThrownBy(
            () ->
                HybridModels.slot(
                    new AiSettings(
                        configured().withProperty("AI_HYBRID_WRITER_CACHED_USD_PER_M", "2")),
                    PRESENTATION))
        .isInstanceOf(AccountException.class);
  }

  @Test
  void requestFactoryPreservesRoleIsolationAndFences() {
    var config = new AiSettings(configured());
    var writer =
        HybridModels.api(
            assignment(
                PRESENTATION,
                JudgeJson.JSON
                    .createObjectNode()
                    .put("language", "ko")
                    .set("semantics", HybridArtifacts.publicSemantics(fixtures.contract()))),
            config);
    var reader =
        HybridModels.api(
            assignment(READER, HybridArtifacts.publicSnapshot(fixtures.presentation())), config);
    assertThat(writer.input()).doesNotContain("obligations", "PRIVATE_", "authorNotes");
    assertThat(reader.input())
        .doesNotContain("PRIVATE_", "editorial", "hints", "obligations", "oracleStrategy");
    assertThat(
            reader
                .schema()
                .path("properties")
                .path("adversarialInputs")
                .path("items")
                .path("properties")
                .has("output"))
        .isFalse();
    var contaminated =
        HybridArtifacts.publicSnapshot(fixtures.presentation()).put("reference", "PRIVATE_SOURCE");
    assertThatThrownBy(() -> HybridModels.api(assignment(READER, contaminated), config))
        .isInstanceOf(ArtifactValidation.Invalid.class);
    var changed = assignment(READER, HybridArtifacts.publicSnapshot(fixtures.presentation()));
    ((com.fasterxml.jackson.databind.node.ObjectNode) changed.input())
        .put("title", "changed after assignment");
    assertThatThrownBy(() -> HybridModels.api(changed, config))
        .isInstanceOf(ArtifactValidation.Invalid.class);
    assertThatThrownBy(() -> HybridModels.api(assignment(CORE, fixtures.core()), config))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void codexRequestsAreSeparateCompleteCallsBoundToFrozenContract() {
    var config =
        new AiSettings(
            new MockEnvironment()
                .withProperty("CODEX_GENERATION_MODEL", "configured-core")
                .withProperty("CODEX_GENERATION_REASONING", "high"));
    var deadline = OffsetDateTime.now().plusSeconds(120);
    var design =
        HybridModels.codex(
            assignment(CONTRACT, JudgeJson.JSON.createObjectNode().put("request", "new problem")),
            config,
            deadline);
    var core =
        HybridModels.codex(
            assignment(
                CORE, JudgeJson.JSON.createObjectNode().set("contract", fixtures.contract())),
            config,
            deadline);
    assertThat(design.model()).isEqualTo("configured-core");
    assertThat(core.effort()).isEqualTo("high");
    assertThat(core.deadlineAt()).isEqualTo(deadline);
    assertThat(core.spec().path("input").path("contract")).isEqualTo(fixtures.contract());
    assertThat(core.outputSchema().path("properties").has("oracle")).isFalse();
    assertThat(core.outputSchema().path("properties").has("context")).isFalse();
    assertThat(design.outputSchema().path("properties").has("reference")).isFalse();
    assertThat(design.spec().path("assignment").path("branchId"))
        .isNotEqualTo(core.spec().path("assignment").path("branchId"));
    var bad =
        assignment(
            CORE,
            JudgeJson.JSON.createObjectNode().set("contract", fixtures.contract().deepCopy()));
    ((com.fasterxml.jackson.databind.node.ObjectNode) bad.input().path("contract"))
        .put("termination", "changed");
    assertThatThrownBy(() -> HybridModels.codex(bad, config, deadline))
        .isInstanceOf(ArtifactValidation.Invalid.class);
  }

  @Test
  void outputSchemasMatchAcceptedFixturesAndCloseEveryObject() {
    Map<HybridGeneration.Role, JsonNode> values =
        Map.of(
            CONTRACT,
            fixtures.contract(),
            CORE,
            fixtures.core(),
            PRESENTATION,
            fixtures.presentation(),
            READER,
            fixtures.reader());
    values.forEach((role, value) -> validate(HybridModels.schema(role), value));
  }

  private void validate(JsonNode schema, JsonNode value) {
    if (schema.has("enum")) assertThat(schema.path("enum")).anyMatch(value::equals);
    switch (schema.path("type").asText()) {
      case "object" -> {
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        var expected = new HashSet<String>();
        schema.path("properties").fieldNames().forEachRemaining(expected::add);
        var required = new HashSet<String>();
        schema.path("required").forEach(v -> required.add(v.asText()));
        var actual = new HashSet<String>();
        value.fieldNames().forEachRemaining(actual::add);
        assertThat(required).isEqualTo(expected);
        assertThat(actual).isEqualTo(expected);
        expected.forEach(k -> validate(schema.path("properties").path(k), value.path(k)));
      }
      case "array" -> {
        assertThat(value.isArray()).isTrue();
        assertThat(value.size())
            .isBetween(schema.path("minItems").asInt(), schema.path("maxItems").asInt());
        value.forEach(v -> validate(schema.path("items"), v));
      }
      case "string" -> assertThat(value.isTextual()).isTrue();
      default -> fail("Unsupported schema type");
    }
  }
}
