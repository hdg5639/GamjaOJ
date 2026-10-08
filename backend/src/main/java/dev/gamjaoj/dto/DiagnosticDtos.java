package dev.gamjaoj.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class DiagnosticDtos {
  private DiagnosticDtos() {}

  public record Correction(
      @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0)
          Integer observationIndex,
      @NotBlank @Size(max = 1000) String note) {}

  public record Start(
      @NotBlank @Size(max = 80) String bankId,
      @Size(max = 20) List<@NotBlank @Size(max = 80) String> categories) {}

  public record State(@NotBlank String status) {}

  public record Skip(@Size(max = 24) String reason) {}
}
