package dev.gamjaoj.export.dto;

import jakarta.validation.constraints.*;
import java.util.*;

public final class ExportDtos {
  private ExportDtos() {}

  public record Destination(
      @NotBlank @Size(max = 300) String targetId,
      @Size(max = 200) String branch,
      @Size(max = 120) String prefix,
      boolean autoEnabled,
      @Pattern(regexp = "legacy|problem-v1") String layout) {}

  public record Automatic(@NotNull Boolean enabled) {}

  public record Request(@NotBlank String provider, @NotNull UUID submissionId) {}
}
