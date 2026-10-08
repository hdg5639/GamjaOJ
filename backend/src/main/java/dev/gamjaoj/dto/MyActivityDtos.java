package dev.gamjaoj.dto;

import java.time.OffsetDateTime;
import java.util.List;

public final class MyActivityDtos {
  private MyActivityDtos() {}

  public record Problem(
      String version,
      String title,
      long attempts,
      long accepted,
      OffsetDateTime lastSubmitted,
      boolean held,
      boolean diagnostic,
      String category,
      String confidence,
      String reflectionNote) {}

  public record Summary(long submitted, long attemptedProblems, long solvedProblems) {}

  public record Page(List<Problem> items, long total) {}
}
