package dev.gamjaoj.editor.dto;

import jakarta.validation.constraints.*;

public final class CompletionDtos {
  private CompletionDtos() {}

  public record Request(
      @NotNull @Pattern(regexp = "JAVA|CPP|PYTHON") String language,
      @NotNull @Size(max = 65536) String source,
      @Min(0) @Max(65536) int offset) {}
}
