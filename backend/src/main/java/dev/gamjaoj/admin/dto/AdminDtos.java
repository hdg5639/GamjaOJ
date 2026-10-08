package dev.gamjaoj.admin.dto;

import jakarta.validation.constraints.*;
import java.util.*;

public final class AdminDtos {
  private AdminDtos() {}

  public record Stage(
      @NotBlank @Size(max = 120) String title,
      @NotBlank @Size(max = 120) String goal,
      @NotEmpty @Size(max = 60) List<@NotBlank @Size(max = 80) String> versions) {}

  public record Course(
      @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{2,79}") String id,
      @NotBlank @Size(max = 120) String title,
      @NotBlank @Size(max = 40) String kind,
      @NotBlank @Size(max = 500) String summary,
      @NotNull @Size(max = 500) String prerequisite,
      @NotNull @Size(max = 500) String notice,
      @NotEmpty @Size(max = 12) List<@jakarta.validation.Valid Stage> stages,
      @Min(0) int revision,
      @NotBlank @Size(min = 4, max = 500) String reason) {}

  public record Page(List<Map<String, Object>> items, long total, int page, int size) {}

  public record Access(
      @NotNull @Pattern(regexp = "MEMBER|ADMIN") String role,
      boolean blocked,
      @Min(0) int revision,
      @NotBlank @Size(min = 4, max = 500) String reason) {}

  public record Command(@NotBlank @Size(min = 4, max = 500) String reason) {}

  public record Setting(
      boolean maintenance,
      @NotNull @Size(max = 300) String message,
      @Min(0) int revision,
      @NotBlank @Size(min = 4, max = 500) String reason) {}

  public record Availability(
      boolean enabled, @Min(0) int revision, @NotBlank @Size(min = 4, max = 500) String reason) {}

  public record Resources(
      @DecimalMin("0.1") @DecimalMax("180") double wallSeconds,
      @DecimalMin("0.1") @DecimalMax("180") double cpuSeconds,
      @Min(32) @Max(4096) int memoryMb) {}

  public record Limits(
      @NotNull Map<String, @jakarta.validation.Valid Resources> languages,
      @Min(0) int revision,
      @NotBlank @Size(min = 4, max = 500) String reason) {}

  public record Problem(
      @NotBlank @Size(max = 120) String title,
      @NotBlank @Size(max = 80) String category,
      @NotNull @Size(max = 6) List<@NotBlank @Size(max = 40) String> tags,
      boolean shared,
      @jakarta.validation.Valid dev.gamjaoj.problem.domain.ThinkingProfile.Input thinking,
      @Min(0) int revision,
      @NotBlank @Size(min = 4, max = 500) String reason) {}
}
