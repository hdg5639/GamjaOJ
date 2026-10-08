package dev.gamjaoj.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public final class JudgeDtos {
  private JudgeDtos() {}

  public record Claim(@NotNull UUID workerId) {}

  public record Heartbeat(@NotNull UUID token) {}

  public record Completion(@NotNull UUID token, @NotNull JsonNode report) {}
}
