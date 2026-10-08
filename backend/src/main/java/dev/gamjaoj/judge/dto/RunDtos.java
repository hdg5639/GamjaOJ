package dev.gamjaoj.judge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public final class RunDtos {
  private RunDtos() {}

  public record Request(
      @NotBlank @Size(max = 80) String problemVersion,
      @NotBlank @Size(max = 65536) String source,
      @Size(max = 16384) String input,
      UUID sessionId,
      UUID diagnosticItemId,
      String language,
      @Size(min = 1, max = 20) List<@NotNull @Size(max = 16384) String> inputs) {
    public Request(
        String version,
        String source,
        String input,
        UUID sessionId,
        UUID diagnosticItemId,
        String language) {
      this(version, source, input, sessionId, diagnosticItemId, language, null);
    }

    public Request(
        String version, String source, String input, UUID sessionId, UUID diagnosticItemId) {
      this(version, source, input, sessionId, diagnosticItemId, null);
    }

    public Request(String version, String source, String input, UUID sessionId) {
      this(version, source, input, sessionId, null);
    }

    public Request(String problemVersion, String source, String input) {
      this(problemVersion, source, input, null);
    }
  }
}
