package dev.gamjaoj.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class ProblemReviewDtos {
  private ProblemReviewDtos() {}

  public record Request(@NotBlank @Size(max = 500) String reason) {}

  public record State(boolean held, String reason) {}
}
