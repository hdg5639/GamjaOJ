package dev.gamjaoj.controller;

import static dev.gamjaoj.dto.CodeDraftDtos.*;

import dev.gamjaoj.exception.AccountException;
import dev.gamjaoj.service.account.CodeDrafts;
import java.security.Principal;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/drafts")
public class CodeDraftController {

  private final CodeDrafts drafts;

  public CodeDraftController(CodeDrafts drafts) {
    this.drafts = drafts;
  }

  /** 200 with the draft, or 200 with {} when none is stored (clients treat both uniformly). */
  public @GetMapping ResponseEntity<?> get(
      Principal user, @RequestParam String scope, @RequestParam String language) {
    return drafts
        .get(user.getName(), scope, language)
        .<ResponseEntity<?>>map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.ok(Map.of()));
  }

  public @PutMapping CodeDrafts.Draft save(Principal user, @RequestBody Save body) {
    if (body == null) throw new AccountException(400, "저장할 초안이 없어요.");
    return drafts.save(user.getName(), body.scope(), body.language(), body.source());
  }
}
