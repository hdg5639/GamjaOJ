package dev.gamjaoj.service.problem;

import dev.gamjaoj.repository.problem.ThinkingAssessmentPublicationRepository;
import jakarta.validation.constraints.*;

@org.springframework.stereotype.Service
public class ThinkingAssessmentPublication {
  private final ThinkingAssessmentPublicationRepository repository;

  public ThinkingAssessmentPublication(ThinkingAssessmentPublicationRepository repository) {
    this.repository = repository;
  }

  public void publish(String version, com.fasterxml.jackson.databind.JsonNode value, String kind) {
    if (value.isMissingNode()) return; // Frozen pre-upgrade reviews retain their original contract.
    ThinkingDifficulty.validate(value);
    repository.publishProblemThinkingProfile(
        value.path("layer").asInt(),
        value.path("insight").asInt(),
        value.path("implementation").asInt(),
        value.path("edgeCases").asInt(),
        value.path("rationale").asText(),
        kind,
        version);
  }
}
