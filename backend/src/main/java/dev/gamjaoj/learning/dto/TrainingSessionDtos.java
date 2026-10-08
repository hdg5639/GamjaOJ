package dev.gamjaoj.learning.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class TrainingSessionDtos {
  private TrainingSessionDtos() {}

  public record Start(
      @NotBlank @Size(max = 80) String problemVersion, @NotNull @Size(max = 120) String goal) {}

  public record End(@NotNull @Size(max = 2000) String note) {}

  public record Entry(
      UUID id, String kind, String status, String verdict, OffsetDateTime createdAt) {}
}
