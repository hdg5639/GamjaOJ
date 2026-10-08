package dev.gamjaoj.problem.api;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamjaoj.problem.service.Teaching;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
public class TeachingController {
  private final Teaching teaching;

  public TeachingController(Teaching teaching) {
    this.teaching = teaching;
  }

  @GetMapping("/api/problems/{version}/teaching")
  public JsonNode teaching(Principal user, @PathVariable String version) {
    return teaching.teaching(user.getName(), version);
  }
}
