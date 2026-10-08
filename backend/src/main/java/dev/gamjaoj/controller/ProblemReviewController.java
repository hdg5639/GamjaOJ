package dev.gamjaoj.controller;

import static dev.gamjaoj.dto.ProblemReviewDtos.*;

import dev.gamjaoj.dto.ProblemReviewDtos;
import dev.gamjaoj.service.problem.ProblemReview;
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
