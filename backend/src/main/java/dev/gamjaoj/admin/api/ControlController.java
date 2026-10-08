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
  private final dev.gamjaoj.admin.service.AdminSessions sessions;
  private final dev.gamjaoj.admin.config.ControlSettings settings;

  public ControlController(
      ControlOverview overview,
      dev.gamjaoj.admin.service.AdminSessions sessions,
      dev.gamjaoj.admin.config.ControlSettings settings) {
    this.sessions = sessions;
    this.settings = settings;
    this.overview = overview;
  }

  @GetMapping("/me")
  public ControlDtos.Identity me(Principal user, jakarta.servlet.http.HttpServletRequest request) {
    boolean verified = sessions.verified(request, user.getName());
    return new ControlDtos.Identity(
        user.getName(),
        verified,
        verified ? sessions.until(request) : 0,
        settings.bootstrap(user.getName()));
  }

  @org.springframework.web.bind.annotation.PostMapping("/session/verify")
  public ControlDtos.Identity verify(
      Principal user,
      jakarta.servlet.http.HttpServletRequest request,
      @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody
          ControlDtos.Verify body) {
    sessions.verify(request, user.getName(), body.password());
    return me(user, request);
  }

  @GetMapping("/overview")
  public ControlDtos.Overview overview() {
    return overview.overview();
  }
}
