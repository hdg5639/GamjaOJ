package dev.gamjaoj.diagnostic.dto;

import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public final class DiagnosticPlanDtos {
  private DiagnosticPlanDtos() {}

  public record Confirm(
      @NotNull UUID evaluationId,
      @NotNull @Min(0) Integer observationIndex,
      @NotBlank @Size(min = 64, max = 64) String reviewHash,
      @NotBlank @Size(max = 120) String goal,
      @Pattern(regexp = "CODE_OBSERVATION|SELF_REPORT") String sourceKind) {}

  public record Reflection(@NotNull Boolean usedHelp) {}

  public record Generate(@Size(max = 80) String ruleVersionId) {}

  public record NextRound(@NotBlank @Size(min = 64, max = 64) String reviewHash) {}

  public record Order(
      @NotNull UUID evaluationId,
      @NotNull @Size(max = 500) List<@NotNull UUID> previous,
      @NotNull @Size(max = 500) List<@NotNull UUID> desired) {}

  public record Start(@NotBlank @Size(max = 80) String problemVersion) {}
}
