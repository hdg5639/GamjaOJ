package dev.gamjaoj.service.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import dev.gamjaoj.domain.ArtifactValidation;
import dev.gamjaoj.repository.generation.ExampleEnrichmentRepository;
import dev.gamjaoj.support.JudgeJson;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Worked examples for published generated problems. The independent reader proposes moderate inputs
 * with its own expected output and a Korean explanation; after publication each one runs on the
 * Runner against the problem's input validator (must print VALID) and its reference (must print the
 * reader's output). Only examples both independent implementations agree on are shown, outside the
 * judged package.
 */
@Service
public class ExampleEnrichment {
  public static final int MAX_INPUT_BYTES = 1500,
      MAX_INPUT_LINES = 30,
      MAX_OUTPUT_BYTES = 600,
      MAX_EXPLANATION = 700;

  public record Example(String input, String output, String explanation) {}

  private final ExampleEnrichmentRepository repository;

  public ExampleEnrichment(ExampleEnrichmentRepository repository) {
    this.repository = repository;
  }

  @Scheduled(
      fixedDelayString = "${EXAMPLE_POLL_MS:${AI_POLL_MS:15000}}",
      initialDelayString = "${EXAMPLE_POLL_MS:${AI_POLL_MS:15000}}")
  @Transactional
  public void advance() {
    for (String version : repository.advanceProblemVersion()) start(version);
    for (String version : repository.advanceProblemVersion2()) finish(version);
  }

  /** Usable reader examples: bounded, Korean explanation, distinct inputs. */
  public static List<Example> proposals(JsonNode reader) {
    var out = new ArrayList<Example>();
    var seen = new java.util.HashSet<String>();
    for (var e : reader.path("examples")) {
      String input = e.path("input").asText(""),
          output = e.path("output").asText(""),
          explanation = e.path("explanation").asText("").strip();
      if (!input.endsWith("\n")) input += "\n";
      if (input.isBlank() || output.isBlank() || explanation.isBlank() || !seen.add(input))
        continue;
      if (input.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES
          || input.lines().count() > MAX_INPUT_LINES
          || output.getBytes(StandardCharsets.UTF_8).length > MAX_OUTPUT_BYTES
          || explanation.length() > MAX_EXPLANATION) continue;
      try {
        HybridStatementQuality.korean("example", explanation);
      } catch (ArtifactValidation.Invalid notKorean) {
        continue;
      }
      out.add(new Example(input, output.strip() + "\n", explanation));
    }
    return out.size() > 3 ? out.subList(0, 3) : out;
  }

  private JsonNode latest(UUID generation, int revision, String role) {
    return repository
        .latestHybridBranch(generation, revision, role)
        .findFirst()
        .map(JudgeJson::parse)
        .orElse(null);
  }

  private void start(String version) {
    var g =
        repository.startHybridGeneration(
            version,
            (r, n) ->
                new Object[] {r.getObject(1, UUID.class), r.getInt(2), r.getObject(3, UUID.class)});
    JsonNode reader = latest((UUID) g[0], (int) g[1], "READER"),
        core = latest((UUID) g[0], (int) g[1], "CORE");
    var examples = reader == null ? List.<Example>of() : proposals(reader);
    String validator = core == null ? "" : core.path("inputValidator").asText(""),
        reference = core == null ? "" : core.path("reference").asText("");
    if (examples.isEmpty() || validator.isBlank() || reference.isBlank()) {
      status(version, "NONE");
      return;
    }
    var runtime = repository.startProblemVersion(version);
    for (int i = 0; i < examples.size(); i++) {
      var e = examples.get(i);
      queue(version, (UUID) g[2], runtime, i, "VALID", validator, e.input(), "VALID\n");
      queue(version, (UUID) g[2], runtime, i, "REFERENCE", reference, e.input(), e.output());
    }
    repository.startProblemVersion2(
        JudgeJson.canonical(JudgeJson.JSON.valueToTree(examples)), version);
  }

  private void queue(
      String version,
      UUID owner,
      String runtime,
      int position,
      String role,
      String source,
      String input,
      String output) {
    var plan =
        JudgeJson.JSON
            .createObjectNode()
            .put("version", "example-check-" + version)
            .put("output_policy", "TOKEN_EXACT");
    plan.putArray("tests")
        .addObject()
        .put("id", "example-" + (position + 1))
        .put("input", input)
        .put("output", output);
    String payload = JudgeJson.canonical(plan);
    UUID id = UUID.randomUUID();
    repository.queueSubmission(
        id,
        owner,
        version,
        source,
        JudgeJson.hash(source),
        id,
        runtime,
        "java8-judge-v1",
        "example-check",
        payload,
        JudgeJson.hash(payload));
    repository.queueJudgeJob(id);
    repository.queueExampleCheck(version, position, role, id);
  }

  private void finish(String version) {
    var rows =
        repository.finishExampleCheck(
            version,
            (r, n) -> new Object[] {r.getInt(1), r.getString(2), r.getString(3), r.getString(4)});
    if (rows.isEmpty()) {
      status(version, "NONE");
      return;
    }
    if (rows.stream().anyMatch(r -> !"FINISHED".equals(r[2]))) return;
    var proposed = JudgeJson.parse(repository.finishProblemVersion(version));
    ArrayNode verified = JudgeJson.JSON.createArrayNode();
    for (int i = 0; i < proposed.size(); i++) {
      int position = i;
      boolean agreed =
          rows.stream().filter(r -> (int) r[0] == position).count() == 2
              && rows.stream().filter(r -> (int) r[0] == position).allMatch(r -> "AC".equals(r[3]));
      if (agreed) verified.add(proposed.get(i));
    }
    repository.finishProblemVersion2(
        verified.isEmpty() ? null : JudgeJson.canonical(verified),
        verified.isEmpty() ? "NONE" : "DONE",
        version);
  }

  private void status(String version, String status) {
    repository.statusProblemVersion(status, version);
  }
}
