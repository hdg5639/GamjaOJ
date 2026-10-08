package dev.gamjaoj.learning.api;

import static dev.gamjaoj.learning.dto.TrainingCourseDtos.*;

import dev.gamjaoj.learning.service.TrainingCourses;
import dev.gamjaoj.learning.service.TrainingSessions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/training-courses")
public class TrainingCourseController {
  private final TrainingCourses courses;

  public TrainingCourseController(TrainingCourses courses) {
    this.courses = courses;
  }

  public @GetMapping List<TrainingCourses.View> catalog(Principal user) {
    return courses.catalog(user.getName());
  }

  public @GetMapping("/enrollments") List<TrainingCourses.View> enrolled(Principal user) {
    return courses.enrolled(user.getName());
  }

  public @PostMapping("/enrollments") TrainingCourses.View enroll(
      Principal user,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody Enroll request) {
    return courses.enroll(user.getName(), key, request.courseId(), request.revision());
  }

  public @PostMapping("/enrollments/{id}/start") TrainingSessions.View start(
      Principal user,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody Start request) {
    return courses.start(
        user.getName(), key, id, request.position(), request.activeSessionId(), request.note());
  }
}
