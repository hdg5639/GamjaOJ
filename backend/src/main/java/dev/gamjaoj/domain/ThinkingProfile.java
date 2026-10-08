package dev.gamjaoj.domain;

import jakarta.validation.constraints.*;

public final class ThinkingProfile {
  private ThinkingProfile() {}

  public record Input(
      @NotNull @Min(1) @Max(9) Integer layer,
      @NotNull @Min(1) @Max(5) Integer insight,
      @NotNull @Min(1) @Max(5) Integer implementation,
      @NotNull @Min(1) @Max(5) Integer edgeCases,
      @NotBlank @Size(max = 300) String rationale) {}

  public record Profile(
      int layer,
      String name,
      int insight,
      int implementation,
      int edgeCases,
      String rationale,
      String source) {
    public String label() {
      return layer + "겹 · " + name;
    }
  }
}
