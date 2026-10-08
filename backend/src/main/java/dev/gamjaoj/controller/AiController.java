package dev.gamjaoj.controller;

import static dev.gamjaoj.dto.AiDtos.*;

import dev.gamjaoj.config.AiSettings;
import dev.gamjaoj.service.ai.AiTasks;
import dev.gamjaoj.service.judge.Submissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
public class AiController {
  private final AiTasks tasks;
  private final AiSettings settings;
  private final Submissions submissions;

  public AiController(AiTasks tasks, AiSettings settings, Submissions submissions) {
    this.tasks = tasks;
    this.settings = settings;
    this.submissions = submissions;
  }

  public @PostMapping("/tasks") AiTasks.View request(
      Principal user, @Valid @RequestBody Request request) {
    return tasks.request(
        user.getName(),
        request.submissionId(),
        request.kind(),
        request.question(),
        request.strong());
  }

  public @GetMapping("/tasks") List<AiTasks.View> list(
      Principal user, @RequestParam UUID submissionId) {
    return tasks.list(user.getName(), submissionId);
  }

  public @GetMapping("/tasks/{id}") AiTasks.View detail(Principal user, @PathVariable UUID id) {
    return tasks.detail(user.getName(), id);
  }

  public @PostMapping("/tasks/{id}/retry") AiTasks.View retry(
      Principal user, @PathVariable UUID id) {
    return tasks.retry(user.getName(), id);
  }

  public @GetMapping("/budget") AiTasks.Budget budget(Principal user) {
    return tasks.budget(user.getName());
  }

  public @GetMapping("/status") Map<String, Object> status(Principal user) {
    return Map.of(
        "enabled",
        settings.enabled() && !settings.key().isBlank(),
        "operator",
        settings.operator(user.getName()));
  }
}
