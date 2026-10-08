package dev.gamjaoj.generation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gamjaoj.generation.repository.HybridRuleAuthorStagesRepository;
import dev.gamjaoj.shared.support.JudgeJson;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * Validated immutable author checkpoints. Runner qualification remains mandatory after assembly.
 */
@Service
public class HybridRuleAuthorStages {
  public static final List<String> ORDER = List.of("DESIGN", "CODE", "TESTS");
  public static final Map<String, List<String>> FIELDS =
      Map.of(
          "DESIGN",
              List.of(
                  "contract",
                  "rules",
                  "catalog",
                  "oracleDomain",
                  "guidance",
                  "requirementsReview",
                  "solutionPlan"),
          "CODE", List.of("reference", "slowSolution", "mutants", "authorNotes"),
          "TESTS",
              List.of(
                  "generator",
                  "validator",
                  "largeGenerator",
                  "tinyInputs",
                  "invalidInputs",
                  "stressInputs"));
  private final HybridRuleAuthorStagesRepository repository;

  public HybridRuleAuthorStages(HybridRuleAuthorStagesRepository repository) {
    this.repository = repository;
  }

  public record Snapshot(String next, ObjectNode partial, List<String> completed) {}

  public Snapshot snapshot(UUID id, int round) {
    var rows =
        repository.snapshotHybridRuleAuthorStage(
            id, round, (r, n) -> new String[] {r.getString(1), r.getString(2), r.getString(3)});
    var values = new HashMap<String, JsonNode>();
    for (var r : rows) {
      HybridArtifacts.require(
          ORDER.contains(r[0]) && JudgeJson.hash(r[1]).equals(r[2]), "ONBOARDING_ARTIFACT_FENCE");
      values.put(r[0], JudgeJson.parse(r[1]));
    }
    var partial = JudgeJson.JSON.createObjectNode();
    var completed = new ArrayList<String>();
    String next = null;
    for (String stage : ORDER) {
      if (!values.containsKey(stage)) {
        if (next == null) next = stage;
        continue;
      }
      HybridArtifacts.require(next == null, "ONBOARDING_ARTIFACT_FENCE");
      partial.setAll((ObjectNode) values.get(stage));
      completed.add(stage);
    }
    return new Snapshot(next, partial, List.copyOf(completed));
  }

  public static JsonNode schema(String stage) {
    return schema(stage, "GENERAL");
  }

  public static JsonNode schema(String stage, String style) {
    var whole = HybridRuleOnboarding.authorSchema(style);
    var result =
        JudgeJson.JSON.createObjectNode().put("type", "object").put("additionalProperties", false);
    var properties = result.putObject("properties");
    var required = result.putArray("required");
    for (String field : FIELDS.get(stage)) {
      properties.set(
          field,
          field.equals("solutionPlan")
              ? JudgeJson.JSON.createObjectNode().put("type", "string")
              : whole.path("properties").path(field).deepCopy());
      required.add(field);
    }
    return result;
  }

  public static String instructions(String stage) {
    return instructions(stage, "GENERAL");
  }

  public static String instructions(String stage, String style) {
    return "STAGED RULE AUTHORING. The following requirements describe the final assembled package;"
        + " this call produces ONLY the current stage's output schema. "
        + HybridRuleOnboarding.AUTHOR_INSTRUCTIONS
        + HybridRuleOnboarding.AUTHOR_TARGETING
        + (CallablePrograms.style(style) ? CallablePrograms.INSTRUCTIONS : "")
        + " CURRENT STAGE: "
        + stage
        + ". Return only "
        + String.join(", ", FIELDS.get(stage))
        + ". "
        + switch (stage) {
          case "DESIGN" ->
              "Resolve the exact mathematical objective, legal domain and intended multi-step"
                  + " solution first. solutionPlan must explain algorithms, complexity at all joint"
                  + " maximum bounds, and why prohibited shortcuts fail. Do not write source code."
                  + " If user objectives and required algorithms cannot be reconciled without"
                  + " changing explicit requirements, mark requirementsReview unsatisfied with"
                  + " specific reasons; do not silently change the objective.";
          case "CODE" ->
              "Implement the frozen completedDesign exactly. Produce reference, the correct"
                  + " slowSolution, two mutants and authorNotes reflecting ACTUAL retained storage"
                  + " and operations. Do not change the saved contract, bounds or rules.";
          default ->
              "Produce validators, small/large generators and tiny/invalid/stress inputs consistent"
                  + " with frozen completedDesign and completedCode. Exercise the saved slow"
                  + " solution and both mutants. Do not rewrite saved rules or solutions.";
        }
        + " Saved stages are immutable dependencies. stageFailure, if present, describes an earlier"
        + " rejected output from THIS stage; correct only this stage.";
  }

  public static JsonNode validate(String stage, JsonNode value) {
    var v = HybridArtifacts.bounded(value);
    HybridArtifacts.fields(v, FIELDS.get(stage).toArray(String[]::new));
    if (stage.equals("DESIGN")) {
      GenerationRequirements.validate(v.path("requirementsReview"), true);
      HybridArtifacts.contract(v.path("contract"));
      HybridArtifacts.text(v.path("solutionPlan"), 12000);
      HybridRulePackage.metadata(
          v.path("catalog"),
          v.path("guidance"),
          v.path("oracleDomain").path("inputDomain"),
          v.path("oracleDomain").path("enumeration"));
      var ids = new HashSet<String>();
      v.path("contract").path("actions").forEach(a -> ids.add(a.path("id").asText()));
      HybridArtifacts.require(
          v.path("rules").isArray() && v.path("rules").size() == ids.size(), "RULE_PACKAGE_RULES");
      for (var rule : v.path("rules")) {
        HybridArtifacts.fields(rule, "id", "text");
        HybridArtifacts.text(rule.path("text"), 4000);
        HybridArtifacts.require(ids.remove(rule.path("id").asText()), "RULE_PACKAGE_RULES");
      }
    } else if (stage.equals("CODE")) {
      HybridArtifacts.unfence(v, "reference", "slowSolution");
      HybridRuleOnboarding.solutionSource(
          v.path("reference"),
          v.path("reference").asText().matches("(?s).*\\bclass\\s+UserSolution\\b.*"));
      HybridRuleOnboarding.solutionSource(
          v.path("slowSolution"),
          v.path("slowSolution").asText().matches("(?s).*\\bclass\\s+UserSolution\\b.*"));
      HybridArtifacts.fields(v.path("authorNotes"), "algorithm", "complexity", "edgeCases");
      for (String key : List.of("algorithm", "complexity", "edgeCases"))
        HybridArtifacts.text(v.path("authorNotes").path(key), 4000);
      HybridArtifacts.require(
          v.path("mutants").isArray() && v.path("mutants").size() == 2, "RULE_PACKAGE_MUTANTS");
      for (var m : v.path("mutants")) {
        HybridArtifacts.fields(m, "idea", "source");
        HybridArtifacts.text(m.path("idea"), 4000);
        HybridArtifacts.unfence(m, "source");
        HybridRuleOnboarding.solutionSource(
            m.path("source"),
            m.path("source").asText().matches("(?s).*\\bclass\\s+UserSolution\\b.*"));
      }
    } else {
      HybridArtifacts.unfence(v, "generator", "validator", "largeGenerator");
      for (String key : List.of("generator", "validator", "largeGenerator"))
        HybridRuleOnboarding.source(v.path(key));
      HybridRuleOnboarding.inputs(
          v.path("tinyInputs"), 8, HybridRuleOnboarding.AUTHOR_MAX_TINY, 1024, "RULE_TINY_INPUTS");
      HybridRuleOnboarding.inputs(
          v.path("invalidInputs"),
          2,
          HybridRulePackage.MAX_INVALID,
          1024,
          "RULE_INVALID_INPUTS",
          true);
      HybridRuleOnboarding.inputs(
          v.path("stressInputs"), 1, HybridRulePackage.MAX_STRESS, 4096, "RULE_STRESS_INPUTS");
    }
    return v;
  }

  public void save(UUID id, int round, String stage, UUID call, JsonNode value) {
    String raw = JudgeJson.canonical(value);
    repository.saveHybridRuleAuthorStage(id, round, stage, call, raw, JudgeJson.hash(raw));
  }

  public void copyPrefix(UUID id, int oldRound, int newRound, String from) {
    snapshot(id, oldRound); // Refuse corrupted saved work.
    for (String stage : ORDER) {
      if (stage.equals(from)) break;
      repository.copyPrefixHybridRuleAuthorStage(newRound, id, oldRound, stage);
    }
  }

  public static String repairFrom(String error, JsonNode detail) {
    if (error.equals("REQUIREMENTS_NOT_MET")) return "DESIGN";
    String role = detail == null ? "" : detail.path("role").asText();
    if (role.contains("valid") || role.equals("q-generator")) return "TESTS";
    if (role.contains("reference")
        || role.contains("slow")
        || role.contains("mutant")
        || Set.of(
                "STRESS_RESOURCE_MARGIN",
                "LARGE_TESTS_NOT_DISCRIMINATING",
                "SLOW_SOLUTION_INCORRECT",
                "MUTANT_SURVIVED")
            .contains(error)) return "CODE";
    return "DESIGN";
  }
}
