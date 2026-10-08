package dev.gamjaoj.admin.api;

import dev.gamjaoj.admin.dto.ControlDtos;
import dev.gamjaoj.admin.service.ControlOverview;
import java.security.Principal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class ControlController {
  private final ControlOverview overview;

  public ControlController(ControlOverview overview) {
    this.overview = overview;
  }

  @GetMapping("/me")
  public ControlDtos.Identity me(Principal user) {
    return new ControlDtos.Identity(user.getName());
  }

  @GetMapping("/overview")
  public ControlDtos.Overview overview() {
    return overview.overview();
  }
}
