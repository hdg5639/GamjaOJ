package dev.gamjaoj;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/training-courses")
public class TrainingCourseController {
    private final TrainingCourses courses;
    public TrainingCourseController(TrainingCourses courses){this.courses=courses;}
    public record Enroll(@NotBlank @Size(max=64) String courseId,@Min(1) int revision) {}
    public record Start(@Min(0) int position,UUID activeSessionId,@NotNull @Size(max=2000) String note) {}
    @GetMapping List<TrainingCourses.View> catalog(Principal user){return courses.catalog(user.getName());}
    @GetMapping("/enrollments") List<TrainingCourses.View> enrolled(Principal user){return courses.enrolled(user.getName());}
    @PostMapping("/enrollments") TrainingCourses.View enroll(Principal user,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Enroll request){return courses.enroll(user.getName(),key,request.courseId(),request.revision());}
    @PostMapping("/enrollments/{id}/start") TrainingSessions.View start(Principal user,@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Start request){return courses.start(user.getName(),key,id,request.position(),request.activeSessionId(),request.note());}
}
