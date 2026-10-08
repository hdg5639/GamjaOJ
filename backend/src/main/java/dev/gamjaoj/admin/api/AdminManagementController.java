package dev.gamjaoj.admin.api;

import dev.gamjaoj.admin.dto.AdminDtos.*;
import dev.gamjaoj.admin.service.AdminManagement;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class AdminManagementController {
  private final AdminManagement management;

  public AdminManagementController(AdminManagement management) {
    this.management = management;
  }

  @GetMapping("/lists/{kind}")
  public Page list(
      @PathVariable String kind,
      @RequestParam(defaultValue = "") String query,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "25") int size) {
    return management.list(kind, query, page, size);
  }

  @PostMapping("/courses")
  public void createCourse(Principal actor, @Valid @RequestBody Course body) {
    management.course(actor.getName(), body, true);
  }

  @PutMapping("/courses")
  public void course(Principal actor, @Valid @RequestBody Course body) {
    management.course(actor.getName(), body, false);
  }

  @PostMapping("/plans/{id}/end")
  public void endPlan(Principal actor, @PathVariable UUID id, @Valid @RequestBody Command body) {
    management.endPlan(actor.getName(), id, body.reason());
  }

  @PutMapping("/members/{username}/access")
  public void access(
      Principal actor, @PathVariable String username, @Valid @RequestBody Access body) {
    management.access(actor.getName(), username, body);
  }

  @PostMapping("/members/{username}/revoke")
  public void revoke(
      Principal actor, @PathVariable String username, @Valid @RequestBody Command body) {
    management.revoke(actor.getName(), username, body.reason());
  }

  @GetMapping("/settings")
  public Map<String, Object> setting() {
    return management.setting();
  }

  @GetMapping("/budget")
  public Object budget() {
    return management.budget();
  }

  @PutMapping("/settings")
  public Map<String, Object> setting(Principal actor, @Valid @RequestBody Setting body) {
    return management.setting(actor.getName(), body);
  }

  @PutMapping("/problems/{id}")
  public void problem(Principal actor, @PathVariable String id, @Valid @RequestBody Problem body) {
    management.problem(actor.getName(), id, body);
  }

  @PutMapping("/problems/{id}/limits")
  public void limits(Principal actor, @PathVariable String id, @Valid @RequestBody Limits body) {
    management.limits(actor.getName(), id, body);
  }

  @PostMapping("/problems/{id}/hold")
  public void hold(Principal actor, @PathVariable String id, @Valid @RequestBody Command body) {
    management.hold(actor.getName(), id, body.reason());
  }

  @GetMapping("/availability")
  public Map<String, Object> availability() {
    return management.availability();
  }

  @PutMapping("/availability/{kind}/{id}")
  public void availability(
      Principal actor,
      @PathVariable String kind,
      @PathVariable String id,
      @Valid @RequestBody Availability body) {
    management.availability(actor.getName(), kind, id, body);
  }

  @PostMapping("/training/{id}/end")
  public void end(Principal actor, @PathVariable UUID id, @Valid @RequestBody Command body) {
    management.endTraining(actor.getName(), id, body.reason());
  }

  @PostMapping("/jobs/{kind}/{id}/{action}")
  public void job(
      Principal actor,
      @PathVariable String kind,
      @PathVariable UUID id,
      @PathVariable String action,
      @Valid @RequestBody Command body) {
    management.job(actor.getName(), kind, id, action, body.reason());
  }
}
