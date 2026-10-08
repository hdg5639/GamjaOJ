package dev.gamjaoj.learning.dto;

import jakarta.validation.constraints.*;
import java.util.*;

public final class TrainingCourseDtos {
  private TrainingCourseDtos() {}

  public record Enroll(@NotBlank @Size(max = 64) String courseId, @Min(1) int revision) {}

  public record Start(
      @Min(0) int position, UUID activeSessionId, @NotNull @Size(max = 2000) String note) {}
}
