package dev.gamjaoj.controller;

import dev.gamjaoj.dto.ProblemCatalogDtos;
import dev.gamjaoj.service.judge.Submissions;
import dev.gamjaoj.service.problem.ProblemCatalog;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
public class ProblemCatalogController {
  private final ProblemCatalog catalog;

  public ProblemCatalogController(ProblemCatalog catalog) {
    this.catalog = catalog;
  }

  @PutMapping("/api/problems/{version}/catalog-settings")
  public Submissions.Problem save(
      Principal user,
      @PathVariable String version,
      @Valid @RequestBody ProblemCatalogDtos.Settings request) {
    return catalog.save(user.getName(), version, request);
  }
}
