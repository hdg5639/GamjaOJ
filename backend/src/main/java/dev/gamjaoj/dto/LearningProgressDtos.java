package dev.gamjaoj.dto;

import dev.gamjaoj.domain.ThinkingProfile;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;

public final class LearningProgressDtos {
  private LearningProgressDtos() {}

  public record Day(LocalDate date, int solved) {}

  public record Category(String category, int attempted, int solved, int available) {}

  public record Suggestion(
      String version,
      String title,
      String category,
      String difficulty,
      String reason,
      String confidence,
      ThinkingProfile.Profile thinking) {}

  public record Dashboard(
      LocalDate start,
      LocalDate end,
      String timezone,
      List<Day> days,
      int activeDays,
      int currentStreak,
      int longestStreak,
      int practicedProblems,
      String dominantCategory,
      List<Category> categories,
      List<Suggestion> explore,
      List<Suggestion> revisit) {}

  public record Reflection(
      String problemVersion,
      UUID submissionId,
      UUID latestAcceptedSubmissionId,
      String confidence,
      String note,
      OffsetDateTime updatedAt) {}

  public record ReflectionRequest(
      @NotNull UUID submissionId,
      @Pattern(regexp = "SOLID|SHAKY|REVISIT") String confidence,
      @NotNull @Size(max = 500) String note) {}
}
