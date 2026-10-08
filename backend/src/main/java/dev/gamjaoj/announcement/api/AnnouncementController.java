package dev.gamjaoj.announcement.api;

import dev.gamjaoj.announcement.dto.AnnouncementDtos.*;
import dev.gamjaoj.announcement.service.Announcements;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class AnnouncementController {
  private final Announcements announcements;

  public AnnouncementController(Announcements announcements) {
    this.announcements = announcements;
  }

  @GetMapping("/api/announcements")
  public List<PublicEntry> published() {
    return announcements.published();
  }

  @GetMapping("/api/admin/announcements")
  public List<Entry> list() {
    return announcements.list();
  }

  @PostMapping("/api/admin/announcements")
  public Entry create(
      Principal actor, @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody Write w) {
    return announcements.create(actor.getName(), key, w);
  }

  @PutMapping("/api/admin/announcements/{id}")
  public Entry update(Principal actor, @PathVariable String id, @Valid @RequestBody Write w) {
    return announcements.update(actor.getName(), id, w);
  }
}
