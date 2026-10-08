package dev.gamjaoj;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.gamjaoj.learning.dto.TrainingSessionDtos;
import dev.gamjaoj.learning.service.TrainingCourses;
import dev.gamjaoj.learning.service.TrainingSessions;
import dev.gamjaoj.shared.exception.AccountException;
import dev.gamjaoj.shared.support.JudgeJson;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:training-courses;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "gamjaoj.invite-code=test",
      "gamjaoj.submissions-enabled=true",
      "AI_API_ENABLED=false",
      "AI_POLL_MS=3600000"
    })
@AutoConfigureMockMvc
class TrainingCoursesIntegrationTest {
  @Autowired JdbcClient jdbc;
  @Autowired TrainingCourses courses;
  @Autowired TrainingSessions training;
  @Autowired MockMvc mvc;
  UUID alice, bob;
  TrainingCourses.View first;

  @BeforeEach
  void setup() {
    jdbc.sql("DELETE FROM submission").update();
    jdbc.sql("UPDATE problem_version SET owner_id=NULL WHERE id LIKE 'iamywl-v1-%'").update();
    jdbc.sql("DELETE FROM app_user").update();
    alice = createUser("alice");
    bob = createUser("bob");
    var definitions = courses.catalog("alice");
    for (var version :
        definitions.stream()
            .flatMap(c -> c.steps().stream())
            .map(TrainingCourses.Step::version)
            .distinct()
            .toList()) {
      jdbc.sql("DELETE FROM problem_version WHERE id=?").param(version).update();
      String pkg =
          "{\"version\":\""
              + version
              + "\",\"title\":\"훈련 문제\",\"statement\":\"공개"
              + " 문제\",\"tests\":[{\"id\":\"sample\",\"input\":\"1\",\"output\":\"1\"}]}";
      jdbc.sql(
              "INSERT INTO"
                  + " problem_version(id,package_json,package_sha256,runtime_image,runner_policy,ready,catalog_category,catalog_difficulty,diagnostic_only,review_hold,shared)"
                  + " SELECT ?,?,?,runtime_image,runner_policy,true,'스택','EASY',false,false,true"
                  + " FROM problem_version WHERE id='sum-v1'")
          .param(version)
          .param(pkg)
          .param(JudgeJson.hash(pkg))
          .update();
    }
    first = courses.catalog("alice").getFirst();
  }

  UUID createUser(String name) {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO app_user(id,username,password_hash,nickname) VALUES (?,?,?,?)")
        .param(id)
        .param(name)
        .param("unused")
        .param(name)
        .update();
    return id;
  }

  TrainingCourses.View enroll() {
    return courses.enroll("alice", UUID.randomUUID(), first.course().id(), 1);
  }

  @Test
  void definitionsOnlyUseRealImportedVersionsAndDoNotLeakAnswers() throws Exception {
    var imported =
        JudgeJson.parse(Files.readString(Path.of("../generation/thinking-problemset-v1.json")));
    var versions = new HashSet<String>();
    for (var p : imported) versions.add(p.path("version").asText());
    var catalog = courses.catalog("alice");
    assertThat(catalog).hasSize(8);
    for (var c : catalog) {
      assertThat(c.available()).isEqualTo(c.steps().size());
      assertThat(c.steps())
          .extracting(TrainingCourses.Step::version)
          .doesNotHaveDuplicates()
          .allMatch(versions::contains);
      assertThat(c.course().stages()).hasSize(3);
    }
    mvc.perform(get("/api/training-courses").with(user("alice")))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("package_json"))))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("source_code"))));
    assertThat(jdbc.sql("SELECT count(*) FROM ai_task").query(Integer.class).single()).isZero();
  }

  @Test
  void enrollmentIsOwnedPersistentIdempotentAndKeepsItsSnapshot() throws Exception {
    UUID key = UUID.randomUUID();
    var saved = courses.enroll("alice", key, first.course().id(), 1);
    assertThat(courses.enroll("alice", key, first.course().id(), 1).enrollmentId())
        .isEqualTo(saved.enrollmentId());
    assertThat(enroll().enrollmentId()).isEqualTo(saved.enrollmentId());
    assertThat(courses.enrolled("alice").getFirst().course()).isEqualTo(saved.course());
    assertThat(courses.enrolled("bob")).isEmpty();
    assertThatThrownBy(() -> courses.enroll("bob", key, first.course().id(), 1))
        .isInstanceOf(AccountException.class);
    assertThatThrownBy(() -> courses.enroll("alice", key, "graph-mastery", 1))
        .isInstanceOf(AccountException.class);
    mvc.perform(
            post("/api/training-courses/enrollments")
                .with(user("alice"))
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content("{\"courseId\":\"first-steps\",\"revision\":1}"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/training-courses")).andExpect(status().isUnauthorized());
    assertThat(jdbc.sql("SELECT count(*) FROM diagnostic_evaluation").query(Integer.class).single())
        .isZero();
  }

  @Test
  void startsAndSwitchesUseActualTrainingAndReplayNeverRestartsEndedSession() {
    var saved = enroll();
    UUID key = UUID.randomUUID();
    var started = courses.start("alice", key, saved.enrollmentId(), 0, null, "");
    assertThat(started.problemVersion()).isEqualTo(saved.steps().getFirst().version());
    assertThat(training.history("alice")).hasSize(1);
    assertThat(courses.start("alice", key, saved.enrollmentId(), 0, null, "").id())
        .isEqualTo(started.id());
    UUID continueKey = UUID.randomUUID();
    assertThat(courses.start("alice", continueKey, saved.enrollmentId(), 0, started.id(), "").id())
        .isEqualTo(started.id());
    UUID switchKey = UUID.randomUUID();
    var switched =
        courses.start("alice", switchKey, saved.enrollmentId(), 1, started.id(), "다음 단계");
    assertThat(training.detail("alice", started.id()).session().note()).isEqualTo("다음 단계");
    assertThat(training.history("alice")).hasSize(2);
    assertThat(switched.status()).isEqualTo("ACTIVE");
    training.end("alice", switched.id(), "완료");
    assertThat(
            courses
                .start("alice", switchKey, saved.enrollmentId(), 1, started.id(), "다음 단계")
                .status())
        .isEqualTo("ENDED");
    assertThat(training.history("alice")).hasSize(2);
    assertThat(courses.enrolled("alice").getFirst().steps().get(1).sessionId())
        .isEqualTo(switched.id());
    assertThatThrownBy(
            () -> courses.start("bob", UUID.randomUUID(), saved.enrollmentId(), 0, null, ""))
        .isInstanceOf(AccountException.class);
  }

  @Test
  void staleOrHeldTargetsAndRequestKeyCollisionsLeaveCurrentTrainingUnchanged() {
    var saved = enroll();
    var current =
        training.start(
            "alice", UUID.randomUUID(), new TrainingSessionDtos.Start("sum-v1", "수동 목표"));
    assertThatThrownBy(
            () -> courses.start("alice", UUID.randomUUID(), saved.enrollmentId(), 0, null, ""))
        .isInstanceOf(AccountException.class);
    jdbc.sql("UPDATE problem_version SET review_hold=true WHERE id=?")
        .param(saved.steps().getFirst().version())
        .update();
    assertThatThrownBy(
            () ->
                courses.start(
                    "alice", UUID.randomUUID(), saved.enrollmentId(), 0, current.id(), "전환"))
        .isInstanceOf(AccountException.class);
    assertThatThrownBy(
            () -> courses.start("alice", current.id(), saved.enrollmentId(), 1, current.id(), "전환"))
        .isInstanceOf(AccountException.class);
    assertThat(training.detail("alice", current.id()).session().status()).isEqualTo("ACTIVE");
    assertThat(
            jdbc.sql("SELECT count(*) FROM training_course_session").query(Integer.class).single())
        .isZero();
  }

  @Test
  void heldDiagnosticUnreadyAndForeignPrivateProblemsAreUnavailable() {
    var steps = first.steps();
    jdbc.sql("UPDATE problem_version SET review_hold=true WHERE id=?")
        .param(steps.get(0).version())
        .update();
    jdbc.sql("UPDATE problem_version SET diagnostic_only=true WHERE id=?")
        .param(steps.get(1).version())
        .update();
    jdbc.sql("UPDATE problem_version SET ready=false WHERE id=?")
        .param(steps.get(2).version())
        .update();
    jdbc.sql("UPDATE problem_version SET owner_id=?,shared=false WHERE id=?")
        .param(bob)
        .param(steps.get(3).version())
        .update();
    assertThat(courses.catalog("alice").getFirst().available()).isEqualTo(steps.size() - 4);
    assertThatThrownBy(this::enroll).isInstanceOf(AccountException.class);
  }

  @Test
  void progressUsesOnlyOwnedFinishedFormalAcceptedPracticeAndDeduplicatesProblems() {
    var saved = enroll();
    String v = saved.steps().getFirst().version(), other = saved.steps().get(1).version();
    submit(alice, v, "AC", false);
    submit(alice, v, "AC", false);
    submit(bob, other, "AC", false);
    submit(alice, other, "AC", true);
    submit(alice, other, "WA", false);
    assertThat(courses.enrolled("alice").getFirst().solved()).isEqualTo(1);
    jdbc.sql("UPDATE problem_version SET review_hold=true WHERE id=?").param(v).update();
    assertThat(courses.enrolled("alice").getFirst().solved()).isZero();
  }

  void submit(UUID owner, String version, String verdict, boolean run) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO"
                + " submission(id,user_id,problem_version,source_code,source_sha256,idempotency_key,runtime_image,runner_policy,run_input,run_package,run_package_sha256)"
                + " SELECT ?,?,?,'class Main {}',?,?,runtime_image,runner_policy,?,?,? FROM"
                + " problem_version WHERE id=?")
        .param(id)
        .param(owner)
        .param(version)
        .param(JudgeJson.hash("class Main {}"))
        .param(UUID.randomUUID())
        .param(run ? "1" : null)
        .param(run ? "{}" : null)
        .param(run ? JudgeJson.hash("{}") : null)
        .param(version)
        .update();
    jdbc.sql(
            "INSERT INTO"
                + " judge_job(submission_id,status,verdict,result_json,result_sha256,finished_at)"
                + " VALUES (?,'FINISHED',?,'{}',?,CURRENT_TIMESTAMP)")
        .param(id)
        .param(verdict)
        .param(JudgeJson.hash("{}"))
        .update();
  }
}
