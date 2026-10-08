package dev.gamjaoj.problem.api;

import dev.gamjaoj.judge.service.Submissions;
import dev.gamjaoj.problem.dto.ProblemCatalogDtos;
import dev.gamjaoj.problem.service.ProblemCatalog;
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
