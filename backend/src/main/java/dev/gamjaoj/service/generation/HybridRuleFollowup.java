package dev.gamjaoj.service.generation;

import dev.gamjaoj.exception.AccountException;
import dev.gamjaoj.repository.generation.HybridRuleFollowupRepository;
import dev.gamjaoj.support.JudgeJson;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * After a rule qualifies, continue into one problem generation from it when the request asked to
 * publish. The generation id is derived from the onboarding id, so retries admit the same request
 * exactly once.
 */
@Service
public class HybridRuleFollowup {
  private final HybridRuleFollowupRepository repository;
  private final HybridAdmission admission;

  public HybridRuleFollowup(HybridRuleFollowupRepository repository, HybridAdmission admission) {
    this.repository = repository;
    this.admission = admission;
  }

  public static UUID generationId(UUID onboarding) {
    return UUID.nameUUIDFromBytes(("rule-followup:" + onboarding).getBytes(StandardCharsets.UTF_8));
  }

  public void advance() {
    var rows =
        repository.advanceHybridRuleOnboarding(
            (r, n) ->
                new Object[] {
                  r.getObject(1, UUID.class),
                  r.getString(2),
                  JudgeJson.parse(r.getString(3)),
                  r.getString(4)
                });
    for (var row : rows) {
      var request = (com.fasterxml.jackson.databind.JsonNode) row[2];
      if (!request.path("publish").asBoolean(false)) continue;
      UUID id = (UUID) row[0], generation = generationId(id);
      var body =
          JudgeJson.JSON
              .createObjectNode()
              .put("profileId", (String) row[1])
              .put("shared", request.path("shared").asBoolean(false))
              .put("publishOnSuccess", true);
      try {
        admission.create((String) row[3], generation, body);
        repository.advanceHybridRuleOnboarding2(generation, id);
      } catch (AccountException refused) {
        // Another generation of this member is still running: try again on a later tick.
        if (refused.status == 409 && refused.getMessage().contains("진행 중")) continue;
        repository.advanceHybridRuleOnboarding3(
            refused.getMessage().length() > 160
                ? refused.getMessage().substring(0, 160)
                : refused.getMessage(),
            id);
      }
    }
  }
}
