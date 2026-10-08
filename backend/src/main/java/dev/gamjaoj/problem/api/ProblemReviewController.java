package dev.gamjaoj.problem.api;

import static dev.gamjaoj.problem.dto.ProblemReviewDtos.*;

import dev.gamjaoj.problem.dto.ProblemReviewDtos;
import dev.gamjaoj.problem.service.ProblemReview;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
public class ProblemReviewController {
  private final ProblemReview review;

  public ProblemReviewController(ProblemReview review) {
    this.review = review;
  }

  public @PostMapping("/api/problems/{version}/review-hold") ProblemReviewDtos.State hold(
      Principal user, @PathVariable String version, @Valid @RequestBody Request request) {
    return review.hold(user.getName(), version, request.reason());
  }
}
