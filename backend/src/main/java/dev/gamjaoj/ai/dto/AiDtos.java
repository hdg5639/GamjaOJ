package dev.gamjaoj.ai.dto;

import jakarta.validation.constraints.*;
import java.util.UUID;

public final class AiDtos {
  private AiDtos() {}

  public record Request(
      @NotNull UUID submissionId,
      @NotNull @Pattern(regexp = "ANALYSIS|HINT") String kind,
      @NotNull @Size(max = 1000) String question,
      boolean strong) {}
}
