package dev.gamjaoj;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gamjaoj.generation.service.GenerationEvidence;
import dev.gamjaoj.shared.support.JudgeJson;
import org.junit.jupiter.api.Test;

class GenerationEvidenceTest {
  ObjectNode manifest(String contract, String policy, String input, boolean hashes) {
    var m =
        JudgeJson.JSON
            .createObjectNode()
            .put("format", GenerationEvidence.FORMAT)
            .put("contractHash", contract)
            .put("validationPolicy", policy)
            .put("reuseAuthorized", false);
    m.putArray("gates")
        .addObject()
        .put("role", "reference-0")
        .put("environmentVerified", true)
        .put("executionInputHash", input)
        .put("storedHashesMatch", hashes);
    m.put("manifestHash", JudgeJson.hash(JudgeJson.canonical(m)));
    return m;
  }

  @Test
  void identicalInputsNeverAuthorizeReuseWithoutRemainingRequirements() {
    var current = manifest("contract", "v3", "same", true);
    var result = GenerationEvidence.compare(current, current.deepCopy());
    assertThat(result.path("matchingRoles").size()).isEqualTo(1);
    assertThat(result.path("reuseAuthorized").asBoolean()).isFalse();
    assertThat(result.path("unresolvedRequirements").size()).isEqualTo(3);
  }

  @Test
  void legacyTamperingContractPolicyAndInputChangesNeverCountAsMatches() {
    var current = manifest("contract", "v3", "same", true);
    var tampered = current.deepCopy().put("contractHash", "tampered");
    for (var source :
        java.util.List.of(
            JudgeJson.JSON.createObjectNode(),
            tampered,
            manifest("new-contract", "v3", "same", true),
            manifest("contract", "v4", "same", true),
            manifest("contract", "v3", "changed", true),
            manifest("contract", "v3", "same", false))) {
      assertThat(GenerationEvidence.compare(current, source).path("matchingRoles").size()).isZero();
    }
  }

  @Test
  void unverifiedEnvironmentCannotMatchEvenWhenExecutionInputHashMatches() {
    var verified = manifest("contract", "v3", "same", true);
    var unknown = verified.deepCopy();
    ((ObjectNode) unknown.path("gates").get(0)).put("environmentVerified", false);
    unknown.remove("manifestHash");
    unknown.put("manifestHash", JudgeJson.hash(JudgeJson.canonical(unknown)));
    var compared = GenerationEvidence.compare(verified, unknown);
    assertThat(compared.path("matchingRoles").size()).isZero();
    assertThat(compared.path("unresolvedRequirements").toString())
        .contains("RUNNER_BUILD_AND_LIMITS_ATTESTATION");
  }
}
