package dev.gamjaoj.dto;

import dev.gamjaoj.domain.ThinkingProfile;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public final class ProblemCatalogDtos {
  private ProblemCatalogDtos() {}

  public record Settings(
      @NotNull Boolean shared,
      @NotBlank @Size(max = 80) String category,
      @NotNull @Size(max = 6) List<@NotBlank @Size(max = 80) String> tags,
      @NotNull @Pattern(regexp = "UNRATED|EASY|MEDIUM|HARD|EXPERT") String difficulty,
      @Valid ThinkingProfile.Input thinking,
      Boolean clearThinking) {}
}
