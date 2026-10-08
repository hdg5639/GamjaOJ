package dev.gamjaoj.generation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public final class GenerationDtos {
  private GenerationDtos() {}

  public record Create(
      @Size(max = 80) String template,
      @Size(max = 200) String focus,
      UUID sourceAnalysisId,
      @Size(max = 40) String category,
      @Size(max = 6) List<@NotBlank @Size(max = 40) String> tags,
      Boolean shared) {
    public Create(
        String template, String focus, UUID sourceAnalysisId, String category, List<String> tags) {
      this(template, focus, sourceAnalysisId, category, tags, false);
    }
  }

  public record DraftRequest(@NotBlank @Size(max = 2000) String request, Boolean shared) {}

  public record BuildRequest(@NotBlank String specHash) {}

  public record Review(@NotNull String artifactHash, boolean approve) {}

  public record Completion(
      @NotNull UUID token,
      JsonNode artifacts,
      JsonNode oracle,
      JsonNode usage,
      @Size(max = 80) String error) {}

  public record InputLayoutRepair(@NotBlank String packageHash) {}
}
