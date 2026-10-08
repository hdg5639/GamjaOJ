package dev.gamjaoj.service.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gamjaoj.exception.AccountException;
import dev.gamjaoj.repository.generation.GenerationStructuresRepository;
import dev.gamjaoj.support.JudgeJson;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Owner-local verified structures. Stories, teaching and learner feedback are never cached here.
 */
@Service
public class GenerationStructures {
  public static final List<String> CODE = List.of("reference", "generator", "inputValidator");
  private final VerificationLedger ledger;
  private final GenerationStructuresRepository repository;

  public GenerationStructures(
      GenerationStructuresRepository repository, VerificationLedger ledger) {
    this.repository = repository;
    this.ledger = ledger;
  }

  public static String category(GenerationType type) {
    if (type.recipe != null) return type.recipe.categoryLabel();
    return type != GenerationType.PARENTHESES ? "수열·수치" : "문자열·상태";
  }

  public static List<String> tags(GenerationType type, String focus) {
    if (type.recipe instanceof GraphRecipe) {
      var tags = new java.util.ArrayList<>(List.of("그래프", "경로"));
      tags.addAll(List.of(focus.split(",")));
      return List.copyOf(tags);
    }
    var tags =
        new java.util.ArrayList<>(
            type != GenerationType.PARENTHESES
                ? List.of("순회", "누적", "정수 범위")
                : List.of("순회", "접두 조건", "균형"));
    tags.addAll(List.of(focus.split(",")));
    return List.copyOf(tags);
  }

  public String contract(GenerationType type) {
    String runtime =
        repository.contractProblemVersion(
            type.baseProblem, (r, n) -> r.getString(1) + "\n" + r.getString(2));
    // Bump alongside semantic changes to the validation gates or author contract.
    return JudgeJson.hash(
        "structure-v1\n"
            + type.id
            + "-validation-v4\n"
            + runtime
            + "\n"
            + JudgeJson.canonical(type.spec()));
  }

  public void select(UUID job, UUID owner, GenerationType type, String focus) {
    String contract = contract(type);
    var required = tags(type, focus);
    JsonNode reuse = null;
    var candidates =
        repository.selectGenerationJob(
            owner,
            type.id,
            contract,
            (r, n) ->
                new String[] {
                  r.getString(1),
                  r.getString(2),
                  r.getString(3),
                  r.getString(4),
                  r.getString(5),
                  r.getString(6)
                });
    for (var candidate : candidates) {
      if (candidate[1] == null
          || candidate[2] == null
          || candidate[4] == null
          || candidate[5] == null) continue;
      var available = new java.util.HashSet<String>();
      JudgeJson.parse(candidate[5]).forEach(tag -> available.add(tag.asText()));
      if (!available.containsAll(required)) continue;
      var artifacts = JudgeJson.parse(candidate[1]);
      var oracle = JudgeJson.parse(candidate[2]);
      var validation = JudgeJson.parse(candidate[4]);
      String hash =
          JudgeJson.hash(JudgeJson.canonical(artifacts) + "\n" + JudgeJson.canonical(oracle));
      if (!hash.equals(candidate[3])
          || !hash.equals(validation.path("artifactHash").asText())
          || !validation.path("policy").asText().equals(type.id + "-validation-v4")
          || validation.path("executions").asInt() != type.executions()) continue;
      if (!ledger.bind(job, UUID.fromString(candidate[0]), artifacts, oracle, validation)) continue;
      var snapshot =
          JudgeJson.JSON
              .createObjectNode()
              .put("sourceJobId", candidate[0])
              .put("sourceArtifactHash", hash)
              .put("contract", contract);
      var code = snapshot.putObject("artifacts");
      CODE.forEach(field -> code.set(field, artifacts.path(field).deepCopy()));
      snapshot.set("oracle", oracle.deepCopy());
      var audit = validation.path("executionInputAudit");
      if (GenerationEvidence.intact(audit)) snapshot.set("executionInputAudit", audit.deepCopy());
      reuse = snapshot;
      break;
    }
    repository.selectGenerationJob2(
        contract,
        JudgeJson.JSON.valueToTree(required).toString(),
        reuse == null ? null : reuse.toString(),
        job);
  }

  public JsonNode snapshot(UUID job) {
    return repository.snapshotGenerationJob(job).map(JudgeJson::parse).orElse(null);
  }

  public ObjectNode summary(UUID job, GenerationType type) {
    var result = JudgeJson.JSON.createObjectNode().put("category", category(type));
    String raw = repository.summaryGenerationJob(job).orElse(null);
    result.set("tags", raw == null ? JudgeJson.JSON.createArrayNode() : JudgeJson.parse(raw));
    var requested = repository.summaryGenerationJob2(job);
    var labels = result.putArray("learningTags");
    for (String tag : requested.split(",")) labels.add(GenerationChoices.label(tag));
    var snapshot = snapshot(job);
    result.put("reused", snapshot != null);
    if (snapshot != null) result.put("sourceJobId", snapshot.path("sourceJobId").asText());
    return result;
  }

  public void enforce(UUID job, JsonNode artifacts, JsonNode oracle) {
    var reuse = snapshot(job);
    if (reuse == null) return;
    for (String field : CODE)
      if (!artifacts.path(field).equals(reuse.path("artifacts").path(field)))
        throw new AccountException(400, "재사용한 검증 코드가 변경됐어요.");
    if (!oracle.equals(reuse.path("oracle")))
      throw new AccountException(400, "재사용한 독립 oracle이 변경됐어요.");
  }
}
