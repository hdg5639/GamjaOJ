package dev.gamjaoj.admin.dto;

import java.time.Instant;
import java.util.Map;

public final class ControlDtos {
  private ControlDtos() {}

  public record Identity(
      String username, boolean verified, long verifiedUntil, boolean bootstrap) {}

  public record Verify(
      @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 100)
          String password) {}

  public record Overview(
      Instant measuredAt,
      long members,
      long problems,
      Map<String, Long> judgeQueue,
      Map<String, Long> generationJobs,
      Map<String, Long> aiTasks) {}
}
