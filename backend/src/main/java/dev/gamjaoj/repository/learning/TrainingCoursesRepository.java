package dev.gamjaoj.repository.learning;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence operations for TrainingCourses; transaction ownership remains in the service. */
@Repository
public class TrainingCoursesRepository {
  private final JdbcClient jdbc;

  public TrainingCoursesRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public <T> List<T> enrolledTrainingCourseEnrollment(UUID owner, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT id,course_json FROM training_course_enrollment WHERE user_id=? ORDER BY"
                + " created_at DESC,id")
        .param(owner)
        .query(mapper)
        .list();
  }

  public <T> List<T> linksTrainingCourseSession(UUID enrollment, UUID owner, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT c.position,t.id,t.status FROM training_course_session c JOIN training_session t"
                + " ON t.id=c.session_id WHERE c.enrollment_id=? AND t.user_id=? ORDER BY CASE WHEN"
                + " t.status='ACTIVE' THEN 0 ELSE 1 END,t.started_at DESC,t.id DESC")
        .param(enrollment)
        .param(owner)
        .query(mapper)
        .list();
  }

  public Optional<String> ownedTrainingCourseEnrollment(UUID enrollment, UUID owner) {
    return jdbc.sql("SELECT course_json FROM training_course_enrollment WHERE id=? AND user_id=?")
        .param(enrollment)
        .param(owner)
        .query(String.class)
        .optional();
  }

  public <T> Optional<T> priorTrainingCourseRequest(UUID key, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT user_id,request_json,enrollment_id,session_id FROM training_course_request"
                + " WHERE id=?")
        .param(key)
        .query(mapper)
        .optional();
  }

  public int saveTrainingCourseRequest(
      UUID key, UUID owner, String request, UUID enrollment, UUID session) {
    return jdbc.sql(
            "INSERT INTO training_course_request(id,user_id,request_json,enrollment_id,session_id)"
                + " VALUES (?,?,?,?,?)")
        .param(key)
        .param(owner)
        .param(request)
        .param(enrollment)
        .param(session)
        .update();
  }

  public Optional<UUID> enrollTrainingCourseEnrollment(UUID owner, String courseId, int revision) {
    return jdbc.sql(
            "SELECT id FROM training_course_enrollment WHERE user_id=? AND course_id=? AND"
                + " revision=?")
        .param(owner)
        .param(courseId)
        .param(revision)
        .query(UUID.class)
        .optional();
  }

  public int enrollTrainingCourseEnrollment2(
      UUID enrollment, UUID owner, String courseId, int revision, String argument4) {
    return jdbc.sql(
            "INSERT INTO training_course_enrollment(id,user_id,course_id,revision,course_json)"
                + " VALUES (?,?,?,?,?)")
        .param(enrollment)
        .param(owner)
        .param(courseId)
        .param(revision)
        .param(argument4)
        .update();
  }

  public Optional<UUID> startTrainingSession(UUID owner) {
    return jdbc.sql("SELECT id FROM training_session WHERE user_id=? AND status='ACTIVE'")
        .param(owner)
        .query(UUID.class)
        .optional();
  }

  public Integer startTrainingSession2(UUID key) {
    return jdbc.sql("SELECT count(*) FROM training_session WHERE id=?")
        .param(key)
        .query(Integer.class)
        .single();
  }

  public int startTrainingCourseSession(UUID result, UUID enrollment, int position) {
    return jdbc.sql(
            "INSERT INTO training_course_session(session_id,enrollment_id,position) VALUES (?,?,?)")
        .param(result)
        .param(enrollment)
        .param(position)
        .update();
  }
}
