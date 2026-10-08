package dev.gamjaoj.admin.dto;

import java.time.Instant;
import java.util.Map;

public final class ControlDtos {
  private ControlDtos() {}

  public record Identity(String username) {}

  public record Overview(
      Instant measuredAt,
      long members,
      long problems,
      Map<String, Long> judgeQueue,
      Map<String, Long> generationJobs,
      Map<String, Long> aiTasks) {}
}
