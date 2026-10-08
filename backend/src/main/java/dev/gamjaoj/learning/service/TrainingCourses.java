package dev.gamjaoj.learning.service;

import dev.gamjaoj.judge.service.Submissions;
import dev.gamjaoj.learning.dto.TrainingSessionDtos;
import dev.gamjaoj.learning.repository.TrainingCoursesRepository;
import dev.gamjaoj.problem.domain.ThinkingProfile;
import dev.gamjaoj.shared.exception.AccountException;
import dev.gamjaoj.shared.support.JudgeJson;
import java.io.IOException;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Curated public problem paths. Enrollment never invents a diagnostic result or calls a model. */
@Service
public class TrainingCourses {
  public record Stage(String title, String goal, List<String> versions) {}

  public record Course(
      String id,
      int revision,
      String title,
      String kind,
      String summary,
      String prerequisite,
      String notice,
      List<Stage> stages) {}

  public record Step(
      int position,
      String stage,
      String goal,
      String version,
      String title,
      String category,
      ThinkingProfile.Profile thinking,
      boolean available,
      boolean solved,
      UUID sessionId,
      String sessionStatus) {}

  public record View(
      UUID enrollmentId, Course course, List<Step> steps, int solved, int available) {}

  private record Saved(UUID owner, String request, UUID enrollment, UUID session) {}

  private final TrainingCoursesRepository repository;
  private final Submissions submissions;
  private final TrainingSessions training;
  private final List<Course> definitions;

  public TrainingCourses(
      TrainingCoursesRepository repository, Submissions submissions, TrainingSessions training) {
    this.repository = repository;
    this.submissions = submissions;
    this.training = training;
    try (var stream = new ClassPathResource("training-courses-v1.json").getInputStream()) {
      definitions = List.of(JudgeJson.JSON.readValue(stream, Course[].class));
    } catch (IOException e) {
      throw new IllegalStateException("Cannot load curated training courses", e);
    }
    var ids = new HashSet<String>();
    for (var c : definitions) {
      var versions = c.stages().stream().flatMap(s -> s.versions().stream()).toList();
      if (!ids.add(c.id())
          || c.revision() < 1
          || versions.isEmpty()
          || new HashSet<>(versions).size() != versions.size()
          || c.stages().stream().anyMatch(s -> s.versions().isEmpty() || s.goal().length() > 120))
        throw new IllegalStateException("Invalid training course: " + c.id());
    }
  }

  public List<View> catalog(String username) {
    var problems = problems(username);
    return definitions.stream().map(c -> view(null, c, problems, Map.of())).toList();
  }

  public List<View> enrolled(String username) {
    UUID owner = submissions.owner(username, false);
    var saved =
        repository.enrolledTrainingCourseEnrollment(
            owner,
            (r, n) ->
                new Object[] {r.getObject("id", UUID.class), read(r.getString("course_json"))});
    if (saved.isEmpty()) return List.of();
    var problems = problems(username);
    return saved.stream()
        .map(row -> view((UUID) row[0], (Course) row[1], problems, links((UUID) row[0], owner)))
        .toList();
  }

  private Map<String, Submissions.Problem> problems(String username) {
    var result = new HashMap<String, Submissions.Problem>();
    // Only explicit public catalog fields are returned; hidden inputs and reference code stay
    // private.
    for (var p : submissions.problems(username))
      if (p.shared() && !p.problemHeld()) result.put(p.version(), p);
    return result;
  }

  private Course read(String json) {
    try {
      return JudgeJson.JSON.readValue(json, Course.class);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private Map<Integer, Object[]> links(UUID enrollment, UUID owner) {
    var result = new HashMap<Integer, Object[]>();
    repository
        .linksTrainingCourseSession(
            enrollment,
            owner,
            (r, n) -> new Object[] {r.getInt(1), r.getObject(2, UUID.class), r.getString(3)})
        .forEach(row -> result.putIfAbsent((Integer) row[0], row));
    return result;
  }

  private View view(
      UUID id,
      Course course,
      Map<String, Submissions.Problem> problems,
      Map<Integer, Object[]> links) {
    var steps = new ArrayList<Step>();
    for (var stage : course.stages())
      for (var version : stage.versions()) {
        var p = problems.get(version);
        int position = steps.size();
        var link = links.get(position);
        steps.add(
            new Step(
                position,
                stage.title(),
                stage.goal(),
                version,
                p == null ? "현재 이용할 수 없는 문제" : p.title(),
                p == null ? "" : p.category(),
                p == null ? null : p.thinking(),
                p != null,
                p != null && "SOLVED".equals(p.solveStatus()),
                link == null ? null : (UUID) link[1],
                link == null ? null : (String) link[2]));
      }
    return new View(
        id,
        course,
        List.copyOf(steps),
        (int) steps.stream().filter(Step::solved).count(),
        (int) steps.stream().filter(Step::available).count());
  }

  private Course owned(UUID owner, UUID enrollment) {
    return read(
        repository
            .ownedTrainingCourseEnrollment(enrollment, owner)
            .orElseThrow(() -> new AccountException(404, "내 훈련 코스를 찾을 수 없어요.")));
  }

  private Saved prior(UUID key, UUID owner, String request) {
    var saved =
        repository
            .priorTrainingCourseRequest(
                key,
                (r, n) ->
                    new Saved(
                        r.getObject(1, UUID.class),
                        r.getString(2),
                        r.getObject(3, UUID.class),
                        r.getObject(4, UUID.class)))
            .orElse(null);
    if (saved != null && (!owner.equals(saved.owner()) || !request.equals(saved.request())))
      throw new AccountException(409, "같은 요청 키에 다른 훈련 내용이 들어왔어요.");
    return saved;
  }

  private void save(UUID key, UUID owner, String request, UUID enrollment, UUID session) {
    repository.saveTrainingCourseRequest(key, owner, request, enrollment, session);
  }

  @Transactional
  public View enroll(String username, UUID key, String courseId, int revision) {
    UUID owner = submissions.owner(username, true);
    String request =
        JudgeJson.canonical(
            JudgeJson.JSON
                .createObjectNode()
                .put("kind", "ENROLL")
                .put("courseId", courseId)
                .put("revision", revision));
    var previous = prior(key, owner, request);
    if (previous != null)
      return view(
          previous.enrollment(),
          owned(owner, previous.enrollment()),
          problems(username),
          links(previous.enrollment(), owner));
    var course =
        definitions.stream()
            .filter(c -> c.id().equals(courseId) && c.revision() == revision)
            .findFirst()
            .orElseThrow(() -> new AccountException(404, "현재 제공하는 훈련 코스를 선택해 주세요."));
    UUID enrollment =
        repository.enrollTrainingCourseEnrollment(owner, courseId, revision).orElse(null);
    if (enrollment == null) {
      var available = view(null, course, problems(username), Map.of());
      if (available.available() != available.steps().size())
        throw new AccountException(409, "코스 문제를 준비·검토 중이에요. 이용 가능한 코스를 선택해 주세요.");
      enrollment = UUID.randomUUID();
      repository.enrollTrainingCourseEnrollment2(
          enrollment, owner, courseId, revision, JudgeJson.JSON.valueToTree(course).toString());
    }
    save(key, owner, request, enrollment, null);
    return view(enrollment, owned(owner, enrollment), problems(username), links(enrollment, owner));
  }

  @Transactional
  public TrainingSessions.View start(
      String username, UUID key, UUID enrollment, int position, UUID expectedActive, String note) {
    UUID owner = submissions.owner(username, true);
    var body =
        JudgeJson.JSON
            .createObjectNode()
            .put("kind", "START")
            .put("enrollmentId", enrollment.toString())
            .put("position", position)
            .put("note", note);
    if (expectedActive == null) body.putNull("activeSessionId");
    else body.put("activeSessionId", expectedActive.toString());
    String request = JudgeJson.canonical(body);
    var previous = prior(key, owner, request);
    // Replay returns the original session even if it has since ended; it never starts another
    // round.
    if (previous != null) return training.detail(username, previous.session()).session();
    var course = owned(owner, enrollment);
    var steps = view(enrollment, course, problems(username), links(enrollment, owner)).steps();
    if (position < 0 || position >= steps.size())
      throw new AccountException(400, "코스의 문제를 선택해 주세요.");
    var step = steps.get(position);
    if (!step.available()) throw new AccountException(409, "선택한 문제는 준비·검토 중이에요.");
    UUID active = repository.startTrainingSession(owner).orElse(null);
    if (!Objects.equals(active, expectedActive))
      throw new AccountException(409, "진행 중인 훈련이 바뀌었어요. 최신 화면에서 다시 선택해 주세요.");
    UUID result;
    if (active != null && active.equals(step.sessionId())) result = active;
    else {
      // Validate before ending. Both operations share this transaction and the per-owner lock.
      if (repository.startTrainingSession2(key) > 0)
        throw new AccountException(409, "이미 사용한 훈련 요청 키예요. 새 요청으로 시작해 주세요.");
      if (submissions.problems(username).stream()
          .noneMatch(
              p ->
                  p.version().equals(step.version()) && p.submissionsEnabled() && !p.problemHeld()))
        throw new AccountException(409, "지금은 이 문제의 채점을 이용할 수 없어요.");
      if (active != null) training.end(username, active, note);
      result = key;
      training.start(username, result, new TrainingSessionDtos.Start(step.version(), step.goal()));
      repository.startTrainingCourseSession(result, enrollment, position);
    }
    save(key, owner, request, enrollment, result);
    return training.detail(username, result).session();
  }
}
