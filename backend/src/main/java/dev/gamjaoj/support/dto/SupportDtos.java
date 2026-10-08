package dev.gamjaoj.support.dto;

import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.*;

public final class SupportDtos {
  private SupportDtos() {}

  public record Create(
      @NotNull @Pattern(regexp = "오류 제보|이용 문의|개선 제안") String kind,
      @NotBlank @Size(max = 120) String title,
      @NotBlank @Size(max = 4000) String body) {}

  public record Update(
      @NotNull @Pattern(regexp = "OPEN|IN_PROGRESS|RESOLVED") String status,
      @NotNull @Size(max = 4000) String reply,
      @Min(0) int revision) {}

  public record Entry(
      UUID id,
      String username,
      String kind,
      String title,
      String body,
      String status,
      String reply,
      int revision,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  public record Page(List<Entry> items, int page, boolean hasNext) {}
}
