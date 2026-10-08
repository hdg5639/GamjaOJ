package dev.gamjaoj.controller;

import dev.gamjaoj.domain.GrowthLevels;
import dev.gamjaoj.dto.MyActivityDtos;
import dev.gamjaoj.service.learning.MyActivity;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
public class MyActivityController {
  private final MyActivity activity;

  public MyActivityController(MyActivity activity) {
    this.activity = activity;
  }

  @GetMapping("/api/my/summary")
  public MyActivityDtos.Summary summary(Principal user) {
    return activity.summary(user.getName());
  }

  @GetMapping("/api/my/growth")
  public GrowthLevels.Growth growth(Principal user) {
    return activity.growth(user.getName());
  }

  @GetMapping("/api/my/problems")
  public MyActivityDtos.Page problems(Principal user, @RequestParam(defaultValue = "0") int page) {
    return activity.problems(user.getName(), page);
  }
}
