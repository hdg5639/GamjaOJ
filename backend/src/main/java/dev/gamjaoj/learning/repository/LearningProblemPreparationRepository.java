package dev.gamjaoj.learning.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence operations for LearningProblemPreparation; transaction ownership remains in the
 * service.
 */
@Repository
public class LearningProblemPreparationRepository {
  private final JdbcClient jdbc;

  public LearningProblemPreparationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public <T> Optional<T> stateLearningProblemPreparation(UUID id, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT status,message,problem_version FROM learning_problem_preparation WHERE"
                + " plan_id=?")
        .param(id)
        .query(mapper)
        .optional();
  }

  public int enrollLearningProblemPreparation(UUID id) {
    return jdbc.sql("INSERT INTO learning_problem_preparation(plan_id) VALUES (?)")
        .param(id)
        .update();
  }

  public int prepareLearningProblemPreparation(UUID id) {
    return jdbc.sql(
            "UPDATE learning_problem_preparation SET updated_at=CURRENT_TIMESTAMP WHERE plan_id=?")
        .param(id)
        .update();
  }

  public List<String> prepareDiagnosticPracticePlan(
      UUID owner, UUID argument1, UUID ownerArgument2, UUID argument3, UUID id) {
    return jdbc.sql(
            "SELECT t.problem_version FROM diagnostic_practice_plan p JOIN training_session t ON"
                + " t.id=p.training_session_id WHERE p.user_id=? AND p.evaluation_id=? UNION SELECT"
                + " w.problem_version FROM learning_problem_preparation w JOIN"
                + " diagnostic_practice_plan p ON p.id=w.plan_id WHERE p.user_id=? AND"
                + " p.evaluation_id=? AND w.plan_id<>? AND w.problem_version IS NOT NULL")
        .param(owner)
        .param(argument1)
        .param(ownerArgument2)
        .param(argument3)
        .param(id)
        .query(String.class)
        .list();
  }

  public List<String> prepareDiagnosticPracticePlan2(
      UUID owner, UUID argument1, UUID ownerArgument2, UUID argument3, UUID id) {
    return jdbc.sql(
            "SELECT t.problem_version FROM diagnostic_practice_plan p JOIN training_session t ON"
                + " t.id=p.training_session_id WHERE p.user_id=? AND p.evaluation_id=? UNION SELECT"
                + " w.problem_version FROM learning_problem_preparation w JOIN"
                + " diagnostic_practice_plan p ON p.id=w.plan_id WHERE p.user_id=? AND"
                + " p.evaluation_id=? AND w.plan_id<>? AND w.problem_version IS NOT NULL")
        .param(owner)
        .param(argument1)
        .param(ownerArgument2)
        .param(argument3)
        .param(id)
        .query(String.class)
        .list();
  }

  public Integer busyGenerationJob(UUID owner) {
    return jdbc.sql(
            "SELECT count(*) FROM generation_job WHERE owner_id=? AND status IN"
                + " ('QUEUED','GENERATING','AWAITING_REVIEW','VALIDATING')")
        .param(owner)
        .query(Integer.class)
        .single();
  }

  public int saveLearningProblemPreparation(
      String status, String message, String version, UUID id) {
    return jdbc.sql(
            "UPDATE learning_problem_preparation SET"
                + " status=?,message=?,problem_version=?,updated_at=CURRENT_TIMESTAMP WHERE"
                + " plan_id=?")
        .param(status)
        .param(message)
        .param(version)
        .param(id)
        .update();
  }

  public <T> List<T> pendingLearningProblemPreparation(RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT u.username,w.plan_id FROM learning_problem_preparation w JOIN"
                + " diagnostic_practice_plan p ON p.id=w.plan_id JOIN app_user u ON u.id=p.user_id"
                + " WHERE ((w.status IN ('WAITING','GENERATING') OR (w.status='FAILED' AND EXISTS"
                + " (SELECT 1 FROM generation_spec_draft d WHERE d.id=p.generation_id AND"
                + " d.auto_recovery=true))) OR (w.status='MAPPED' AND (w.problem_version IS NULL OR"
                + " w.updated_at<CURRENT_TIMESTAMP-INTERVAL '30' SECOND))) AND"
                + " p.training_session_id IS NULL AND NOT EXISTS (SELECT 1 FROM"
                + " learning_curriculum_end e WHERE e.evaluation_id=p.evaluation_id AND"
                + " e.user_id=p.user_id) AND NOT EXISTS (SELECT 1 FROM diagnostic_practice_plan n"
                + " WHERE n.previous_plan_id=p.id) ORDER BY w.updated_at,w.plan_id LIMIT 50")
        .query(mapper)
        .list();
  }
}
