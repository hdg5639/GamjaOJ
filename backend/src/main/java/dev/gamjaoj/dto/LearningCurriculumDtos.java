package dev.gamjaoj.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public final class LearningCurriculumDtos {
  private LearningCurriculumDtos() {}

  public record Switch(
      @NotNull UUID planId,
      @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 80)
          String problemVersion,
      UUID activeSessionId,
      @NotNull @jakarta.validation.constraints.Size(max = 2000) String note) {}

  public record End(@NotNull @jakarta.validation.constraints.Size(max = 2000) String note) {}

  public record Create(@NotNull UUID evaluationId) {}
}
