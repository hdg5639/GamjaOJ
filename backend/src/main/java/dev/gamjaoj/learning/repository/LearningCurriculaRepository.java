package dev.gamjaoj.learning.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence operations for LearningCurricula; transaction ownership remains in the service. */
@Repository
public class LearningCurriculaRepository {
  private final JdbcClient jdbc;

  public LearningCurriculaRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Integer endDiagnosticPracticePlan(UUID owner, UUID evaluationId) {
    return jdbc.sql(
            "SELECT count(*) FROM diagnostic_practice_plan WHERE user_id=? AND evaluation_id=?")
        .param(owner)
        .param(evaluationId)
        .query(Integer.class)
        .single();
  }

  public List<UUID> endTrainingSession(UUID owner, UUID evaluationId) {
    return jdbc.sql(
            "SELECT t.id FROM training_session t JOIN diagnostic_practice_plan p ON"
                + " p.training_session_id=t.id WHERE p.user_id=? AND p.evaluation_id=? AND"
                + " t.status='ACTIVE'")
        .param(owner)
        .param(evaluationId)
        .query(UUID.class)
        .list();
  }

  public int endLearningCurriculumEnd(UUID evaluationId, UUID owner, String note) {
    return jdbc.sql(
            "INSERT INTO learning_curriculum_end(evaluation_id,user_id,note) VALUES (?,?,?)")
        .param(evaluationId)
        .param(owner)
        .param(note)
        .update();
  }

  public <T> Optional<T> endedLearningCurriculumEnd(
      UUID evaluationId, UUID owner, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT ended_at,note FROM learning_curriculum_end WHERE evaluation_id=? AND user_id=?")
        .param(evaluationId)
        .param(owner)
        .query(mapper)
        .optional();
  }

  public <T> Optional<T> createLearningCurriculumRequest(UUID key, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT user_id,evaluation_id,result_json FROM learning_curriculum_request WHERE id=?")
        .param(key)
        .query(mapper)
        .optional();
  }

  public Integer createDiagnosticSession(UUID owner) {
    return jdbc.sql(
            "SELECT count(*) FROM diagnostic_session WHERE user_id=? AND status<>'COMPLETED'")
        .param(owner)
        .query(Integer.class)
        .single();
  }

  public int createLearningCurriculumRequest2(
      UUID key, UUID owner, UUID evaluationId, String argument3) {
    return jdbc.sql(
            "INSERT INTO learning_curriculum_request(id,user_id,evaluation_id,result_json) VALUES"
                + " (?,?,?,?)")
        .param(key)
        .param(owner)
        .param(evaluationId)
        .param(argument3)
        .update();
  }

  public List<UUID> overviewDiagnosticPracticePlan(UUID owner) {
    return jdbc.sql(
            "SELECT evaluation_id FROM diagnostic_practice_plan WHERE user_id=? GROUP BY"
                + " evaluation_id ORDER BY MAX(created_at) DESC,evaluation_id")
        .param(owner)
        .query(UUID.class)
        .list();
  }

  public <T> T overviewDiagnosticSession(UUID argument0, RowMapper<T> mapper) {
    return jdbc.sql("SELECT d.bank_id,d.created_at FROM diagnostic_session d WHERE d.id=?")
        .param(argument0)
        .query(mapper)
        .single();
  }

  public <T> T overviewSubmission(UUID argument0, UUID owner, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT (SELECT count(*) FROM submission WHERE training_session_id=t.id AND run_input"
                + " IS NULL) AS submissions,(SELECT count(*) FROM submission s JOIN judge_job j ON"
                + " j.submission_id=s.id WHERE s.training_session_id=t.id AND s.run_input IS NULL"
                + " AND j.verdict='AC') AS accepted,(SELECT count(*) FROM submission s JOIN"
                + " judge_job j ON j.submission_id=s.id WHERE s.training_session_id=t.id AND"
                + " j.status<>'FINISHED') AS pending FROM training_session t WHERE t.id=? AND"
                + " t.user_id=?")
        .param(argument0)
        .param(owner)
        .query(mapper)
        .single();
  }

  public <T> Stream<T> overviewSubmission2(UUID argument0, UUID owner, RowMapper<T> mapper) {
    return jdbc
        .sql(
            "SELECT j.verdict FROM submission s JOIN judge_job j ON j.submission_id=s.id WHERE"
                + " s.training_session_id=? AND s.user_id=? AND s.run_input IS NULL ORDER BY"
                + " s.created_at DESC,s.id DESC LIMIT 1")
        .param(argument0)
        .param(owner)
        .query(mapper)
        .list()
        .stream();
  }
}
