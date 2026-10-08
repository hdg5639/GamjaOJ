package dev.gamjaoj.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class SubmissionDtos {
  private SubmissionDtos() {}

  public record Request(
      @NotBlank @Size(max = 80) String problemVersion,
      @NotBlank @Size(max = 65536) String source,
      UUID sessionId,
      UUID diagnosticItemId,
      String language) {
    public Request(String version, String source, UUID sessionId, UUID diagnosticItemId) {
      this(version, source, sessionId, diagnosticItemId, null);
    }

    public Request(String version, String source, UUID sessionId) {
      this(version, source, sessionId, null);
    }

    public Request(String problemVersion, String source) {
      this(problemVersion, source, null);
    }
  }
}
