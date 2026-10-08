package dev.gamjaoj.admin.api;

import dev.gamjaoj.admin.config.ControlSettings;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ControlEntryController {
  private final ControlSettings settings;

  public ControlEntryController(ControlSettings settings) {
    this.settings = settings;
  }

  @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
  public ResponseEntity<Resource> entry(HttpServletRequest request) {
    Resource page =
        new ClassPathResource(
            settings.matches(request) ? "static/controloj.html" : "static/index.html");
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .header("Vary", "Host")
        .contentType(MediaType.TEXT_HTML)
        .body(page);
  }
}
