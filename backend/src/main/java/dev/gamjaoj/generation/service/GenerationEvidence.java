package dev.gamjaoj.generation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gamjaoj.generation.repository.GenerationEvidenceRepository;
import dev.gamjaoj.judge.domain.RunnerEnvironment;
import dev.gamjaoj.shared.support.JudgeJson;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Audit only: matching execution inputs are not permission to skip a publication gate. */
@Service
public class GenerationEvidence {
  public static final String FORMAT = "execution-input-audit-v2";
  private final GenerationEvidenceRepository repository;

  public GenerationEvidence(GenerationEvidenceRepository repository) {
    this.repository = repository;
  }

  public ObjectNode capture(UUID job, int revision, GenerationType type) {
    var root =
        JudgeJson.JSON
            .createObjectNode()
            .put("format", FORMAT)
            .put("contractHash", JudgeJson.hash(JudgeJson.canonical(type.spec())))
            .put("validationPolicy", type.id + "-validation-v4")
            .put("reuseAuthorized", false);
    var gates = root.putArray("gates");
    repository
        .captureGenerationExecution(
            job,
            revision,
            (r, n) -> {
              var plan = JudgeJson.parse(r.getString(4));
              // Only version is an instance identity. Retain test order, IDs, bytes and output
              // policy.
              var inputs = ((ObjectNode) plan).deepCopy();
              inputs.remove("version");
              var gate =
                  JudgeJson.JSON
                      .createObjectNode()
                      .put("role", r.getString(1))
                      .put("expectedVerdict", r.getString(2))
                      .put("sourceHash", r.getString(3))
                      .put("inputPlanHash", JudgeJson.hash(JudgeJson.canonical(inputs)))
                      .put("runtimeImage", r.getString(6))
                      .put("runnerPolicy", r.getString(7))
                      .put("executionMode", r.getString(8));
              var report = JudgeJson.parse(r.getString(9));
              var declared = report.path("runner_environment");
              boolean environmentVerified =
                  r.getString(12) != null
                      && RunnerEnvironment.matches(JudgeJson.parse(r.getString(12)), declared);
              gate.put("environmentVerified", environmentVerified);
              gate.put(
                  "environmentHash",
                  environmentVerified ? JudgeJson.hash(JudgeJson.canonical(declared)) : "UNKNOWN");
              gate.put("executionInputHash", JudgeJson.hash(JudgeJson.canonical(gate)));
              // References to the exact accepted envelopes, separate from reusable input identity.
              gate.put("storedPlanHash", r.getString(5)).put("reportHash", r.getString(10));
              boolean hashesMatch =
                  JudgeJson.hash(r.getString(11)).equals(r.getString(3))
                      && JudgeJson.hash(JudgeJson.canonical(plan)).equals(r.getString(5))
                      && r.getString(9) != null
                      && JudgeJson.hash(r.getString(9)).equals(r.getString(10));
              gate.put("storedHashesMatch", hashesMatch);
              return gate;
            })
        .forEach(gates::add);
    root.put("manifestHash", JudgeJson.hash(JudgeJson.canonical(root)));
    return root;
  }

  public static boolean intact(JsonNode manifest) {
    if (manifest == null
        || !manifest.isObject()
        || !FORMAT.equals(manifest.path("format").asText())
        || !manifest.path("gates").isArray()
        || manifest.path("gates").isEmpty()) return false;
    var body = ((ObjectNode) manifest).deepCopy();
    body.remove("manifestHash");
    return JudgeJson.hash(JudgeJson.canonical(body)).equals(manifest.path("manifestHash").asText());
  }

  public static ObjectNode compare(JsonNode current, JsonNode source) {
    var result =
        JudgeJson.JSON.createObjectNode().put("mode", "AUDIT_ONLY").put("reuseAuthorized", false);
    var matches = result.putArray("matchingRoles");
    var changed = result.putArray("changedRoles");
    boolean comparable =
        intact(current)
            && intact(source)
            && current.path("contractHash").equals(source.path("contractHash"))
            && current.path("validationPolicy").equals(source.path("validationPolicy"));
    result.put("sourceComparable", comparable);
    result.put("currentManifestHash", current.path("manifestHash").asText());
    result.put("sourceManifestHash", source == null ? "" : source.path("manifestHash").asText());
    for (var gate : current.path("gates")) {
      boolean match = false;
      if (comparable
          && gate.path("environmentVerified").asBoolean()
          && gate.path("storedHashesMatch").asBoolean())
        for (var old : source.path("gates")) {
          if (old.path("environmentVerified").asBoolean()
              && old.path("storedHashesMatch").asBoolean()
              && old.path("role").equals(gate.path("role"))
              && old.path("executionInputHash").equals(gate.path("executionInputHash"))) {
            match = true;
            break;
          }
        }
      (match ? matches : changed).add(gate.path("role").asText());
    }
    // Missing attestations are explicit even if every execution input matches.
    var unresolved = result.putArray("unresolvedRequirements");
    boolean environments = comparable;
    for (var gate : current.path("gates"))
      environments &= gate.path("environmentVerified").asBoolean();
    if (source != null)
      for (var gate : source.path("gates"))
        environments &= gate.path("environmentVerified").asBoolean();
    if (!environments) unresolved.add("RUNNER_BUILD_AND_LIMITS_ATTESTATION");
    unresolved
        .add("STORY_CONTRACT_REVIEW")
        .add("EVIDENCE_REVOCATION_PROPAGATION")
        .add("VALIDATION_POLICY_APPROVAL");
    return result;
  }
}
