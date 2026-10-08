package dev.gamjaoj.controller;

import dev.gamjaoj.service.problem.ProblemIllustrations;
import java.io.IOException;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class ProblemIllustrationController {
  public @ExceptionHandler(MaxUploadSizeExceededException.class) ResponseEntity<?> tooLarge() {
    return ResponseEntity.status(413).body(Map.of("message", "PNG·JPEG 그림을 3 MiB 이내로 올려 주세요."));
  }

  private final ProblemIllustrations images;

  public ProblemIllustrationController(ProblemIllustrations images) {
    this.images = images;
  }

  public @GetMapping("/api/problems/{version}/illustrations") ProblemIllustrations.Presentation
      presentation(Principal user, @PathVariable String version) {
    return images.presentation(user.getName(), version);
  }

  public @PostMapping(
      value = "/api/problems/{version}/illustrations",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE) ProblemIllustrations.Presentation upload(
      Principal user,
      @PathVariable String version,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestParam MultipartFile file,
      @RequestParam String alt,
      @RequestParam(defaultValue = "") String caption)
      throws IOException {
    return images.upload(user.getName(), version, key, file.getBytes(), alt, caption);
  }

  public @DeleteMapping("/api/problems/{version}/illustrations/{id}") ProblemIllustrations
          .Presentation
      delete(Principal user, @PathVariable String version, @PathVariable UUID id) {
    return images.delete(user.getName(), version, id);
  }

  public @GetMapping("/api/problem-images/{id}") ResponseEntity<byte[]> image(
      Principal user, @PathVariable UUID id) {
    return ResponseEntity.ok()
        .contentType(MediaType.IMAGE_PNG)
        .cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options", "nosniff")
        .header("Content-Disposition", "inline; filename=illustration.png")
        .body(images.image(user.getName(), id));
  }
}
