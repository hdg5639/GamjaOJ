package dev.gamjaoj.announcement.dto;

import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;

public final class AnnouncementDtos {
  private AnnouncementDtos() {}

  public record Write(
      @Pattern(regexp = "공지|새 기능|업데이트") @NotNull String kind,
      @NotBlank @Size(max = 120) String title,
      @NotBlank @Size(max = 400) String summary,
      @NotBlank @Size(max = 20000) String body,
      boolean pinned,
      boolean published,
      @Min(0) int revision) {}

  public record Entry(
      String id,
      String kind,
      String title,
      String summary,
      String body,
      boolean pinned,
      boolean published,
      OffsetDateTime publishedAt,
      int revision,
      String updatedBy,
      OffsetDateTime updatedAt) {}

  public record PublicEntry(
      String id,
      String kind,
      String title,
      String summary,
      boolean pinned,
      String date,
      List<String> paragraphs) {}
}
