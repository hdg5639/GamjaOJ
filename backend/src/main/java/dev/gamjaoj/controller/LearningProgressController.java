package dev.gamjaoj.controller;

import dev.gamjaoj.dto.LearningProgressDtos;
import dev.gamjaoj.service.learning.LearningProgress;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
public class LearningProgressController {
  private final LearningProgress progress;

  public LearningProgressController(LearningProgress progress) {
    this.progress = progress;
  }

  @GetMapping("/api/my/learning")
  public LearningProgressDtos.Dashboard learning(Principal user) {
    return progress.learning(user.getName());
  }

  @GetMapping("/api/my/reflections")
  public LearningProgressDtos.Reflection reflection(
      Principal user, @RequestParam String problemVersion) {
    return progress.reflection(user.getName(), problemVersion);
  }

  @PutMapping("/api/my/reflections")
  public LearningProgressDtos.Reflection reflect(
      Principal user, @Valid @RequestBody LearningProgressDtos.ReflectionRequest request) {
    return progress.reflect(user.getName(), request);
  }
}
