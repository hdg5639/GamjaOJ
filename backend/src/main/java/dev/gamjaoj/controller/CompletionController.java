package dev.gamjaoj.controller;

import static dev.gamjaoj.dto.CompletionDtos.*;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamjaoj.service.editor.Completions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/editor")
public class CompletionController {
  private final Completions completions;

  public CompletionController(Completions completions) {
    this.completions = completions;
  }

  @PostMapping("/completions")
  public JsonNode complete(Principal principal, @Valid @RequestBody Request request) {
    return completions.complete(principal.getName(), request);
  }
}
