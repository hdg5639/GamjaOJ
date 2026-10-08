package dev.gamjaoj.controller;

import dev.gamjaoj.service.account.ContentDeletion;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

/** A member removes a problem or rule they made. */
@RestController
public class ContentDeletionController {
  private final ContentDeletion deletion;

  public ContentDeletionController(ContentDeletion deletion) {
    this.deletion = deletion;
  }

  public @DeleteMapping("/api/problems/{version}") ContentDeletion.Result problem(
      Principal user, @PathVariable String version) {
    return deletion.problem(user.getName(), version);
  }

  public @DeleteMapping("/api/rules/{id}") ContentDeletion.Result rule(
      Principal user, @PathVariable String id) {
    return deletion.rule(user.getName(), id);
  }
}
