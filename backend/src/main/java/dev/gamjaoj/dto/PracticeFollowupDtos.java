package dev.gamjaoj.dto;

import jakarta.validation.constraints.*;
import java.util.*;

public final class PracticeFollowupDtos {
  private PracticeFollowupDtos() {}

  public record Confirm(@NotNull UUID analysisId, @Min(0) int stepIndex, @NotBlank String focus) {}

  public record Start(@NotBlank @Size(max = 80) String problemVersion, @Min(1) Integer round) {}

  public record Reflection(@NotNull Boolean usedHelp, @Min(1) Integer round) {}

  public record Round(@NotNull @Min(1) Integer round) {}
}
