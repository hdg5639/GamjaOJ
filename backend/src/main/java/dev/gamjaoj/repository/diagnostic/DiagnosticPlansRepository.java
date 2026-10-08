package dev.gamjaoj.repository.diagnostic;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence operations for DiagnosticPlans; transaction ownership remains in the service. */
@Repository
public class DiagnosticPlansRepository {
  private final JdbcClient jdbc;

  public DiagnosticPlansRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Integer basicFenceDiagnosticSession(UUID owner) {
    return jdbc.sql(
            "SELECT count(*) FROM diagnostic_session WHERE user_id=? AND status<>'COMPLETED'")
        .param(owner)
        .query(Integer.class)
        .single();
  }

  public <T> Optional<T> confirmDiagnosticPracticePlan(UUID id, UUID owner, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT evaluation_id,observation_index,review_sha256,goal,source_kind FROM"
                + " diagnostic_practice_plan WHERE id=? AND user_id=?")
        .param(id)
        .param(owner)
        .query(mapper)
        .optional();
  }

  public Integer confirmDiagnosticPracticePlan2(UUID id) {
    return jdbc.sql("SELECT count(*) FROM diagnostic_practice_plan WHERE id=?")
        .param(id)
        .query(Integer.class)
        .single();
  }

  public int confirmDiagnosticPracticePlan3(
      UUID id,
      UUID owner,
      UUID evaluation,
      int index,
      String hash,
      String argument5,
      String goal,
      String kind) {
    return jdbc.sql(
            "INSERT INTO"
                + " diagnostic_practice_plan(id,user_id,evaluation_id,observation_index,review_sha256,review_json,goal,source_kind)"
                + " VALUES (?,?,?,?,?,?,?,?)")
        .param(id)
        .param(owner)
        .param(evaluation)
        .param(index)
        .param(hash)
        .param(argument5)
        .param(goal)
        .param(kind)
        .update();
  }

  public int confirmDiagnosticPracticePlan4(UUID owner, UUID evaluation, UUID id) {
    return jdbc.sql(
            "UPDATE diagnostic_practice_plan SET sort_order=(SELECT COALESCE(MAX(sort_order),0)+1"
                + " FROM diagnostic_practice_plan WHERE user_id=? AND evaluation_id=?) WHERE id=?")
        .param(owner)
        .param(evaluation)
        .param(id)
        .update();
  }

  public Stream<UUID> listDiagnosticPracticePlan(UUID owner, UUID evaluation) {
    return jdbc
        .sql(
            "SELECT id FROM diagnostic_practice_plan WHERE user_id=? AND evaluation_id=? ORDER BY"
                + " sort_order,created_at,id")
        .param(owner)
        .param(evaluation)
        .query(UUID.class)
        .list()
        .stream();
  }

  public int reorderDiagnosticPracticePlan(int argument0, UUID argument1) {
    return jdbc.sql("UPDATE diagnostic_practice_plan SET sort_order=? WHERE id=?")
        .param(argument0)
        .param(argument1)
        .update();
  }

  public List<UUID> trainedScopeDiagnosticPracticePlan(UUID owner, UUID source) {
    return jdbc.sql(
            "SELECT p.id FROM diagnostic_practice_plan p JOIN diagnostic_evaluation e ON"
                + " e.id=p.evaluation_id WHERE p.user_id=? AND e.session_id=? AND"
                + " p.reviewed_submission_id IS NOT NULL")
        .param(owner)
        .param(source)
        .query(UUID.class)
        .list();
  }

  public <T> T trainedScopeDiagnosticPracticePlan2(UUID id, RowMapper<T> mapper) {
    return jdbc.sql("SELECT review_json,review_sha256 FROM diagnostic_practice_plan WHERE id=?")
        .param(id)
        .query(mapper)
        .single();
  }

  public Optional<String> trainedScopeSubmission(UUID submitted, UUID source) {
    return jdbc.sql(
            "SELECT i.category FROM submission s JOIN diagnostic_item i ON"
                + " i.id=s.diagnostic_item_id WHERE s.id=? AND i.session_id=?")
        .param(submitted)
        .param(source)
        .query(String.class)
        .optional();
  }

  public int startDiagnosticPracticePlan(UUID id, UUID idArgument1) {
    return jdbc.sql("UPDATE diagnostic_practice_plan SET training_session_id=? WHERE id=?")
        .param(id)
        .param(idArgument1)
        .update();
  }

  public int generateDiagnosticPracticePlan(UUID id, UUID idArgument1) {
    return jdbc.sql("UPDATE diagnostic_practice_plan SET hybrid_generation_id=? WHERE id=?")
        .param(id)
        .param(idArgument1)
        .update();
  }

  public int generateDiagnosticPracticePlan2(UUID id, UUID idArgument1) {
    return jdbc.sql("UPDATE diagnostic_practice_plan SET generation_id=? WHERE id=?")
        .param(id)
        .param(idArgument1)
        .update();
  }

  public Integer reflectSubmission(UUID argument0) {
    return jdbc.sql(
            "SELECT count(*) FROM submission s JOIN judge_job j ON j.submission_id=s.id WHERE"
                + " s.training_session_id=? AND j.status<>'FINISHED'")
        .param(argument0)
        .query(Integer.class)
        .single();
  }

  public <T> Optional<T> reflectSubmission2(UUID argument0, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT s.id,j.verdict FROM submission s JOIN judge_job j ON j.submission_id=s.id WHERE"
                + " s.training_session_id=? AND s.run_input IS NULL ORDER BY s.created_at DESC,s.id"
                + " DESC LIMIT 1")
        .param(argument0)
        .query(mapper)
        .optional();
  }

  public int reflectDiagnosticPracticePlan(Object argument0, boolean helped, UUID id) {
    return jdbc.sql(
            "UPDATE diagnostic_practice_plan SET"
                + " reviewed_submission_id=?,used_help=?,reflected_at=CURRENT_TIMESTAMP WHERE id=?")
        .param(argument0)
        .param(helped)
        .param(id)
        .update();
  }

  public Optional<UUID> nextRoundDiagnosticPracticePlan(UUID id, UUID owner) {
    return jdbc.sql(
            "SELECT id FROM diagnostic_practice_plan WHERE previous_plan_id=? AND user_id=?")
        .param(id)
        .param(owner)
        .query(UUID.class)
        .optional();
  }

  public Integer nextRoundSubmission(UUID argument0) {
    return jdbc.sql(
            "SELECT count(*) FROM submission s JOIN judge_job j ON j.submission_id=s.id WHERE"
                + " s.training_session_id=? AND j.status<>'FINISHED'")
        .param(argument0)
        .query(Integer.class)
        .single();
  }

  public int nextRoundDiagnosticPracticePlan2(UUID id, int argument1, UUID next) {
    return jdbc.sql(
            "UPDATE diagnostic_practice_plan SET previous_plan_id=?,round_number=? WHERE id=?")
        .param(id)
        .param(argument1)
        .param(next)
        .update();
  }

  public int nextRoundLearningProblemPreparation(UUID next, UUID id) {
    return jdbc.sql(
            "INSERT INTO learning_problem_preparation(plan_id) SELECT ? WHERE EXISTS (SELECT 1 FROM"
                + " learning_problem_preparation WHERE plan_id=?)")
        .param(next)
        .param(id)
        .update();
  }

  public Integer requireOpenLearningCurriculumEnd(UUID owner, UUID evaluationId) {
    return jdbc.sql(
            "SELECT count(*) FROM learning_curriculum_end WHERE user_id=? AND evaluation_id=?")
        .param(owner)
        .param(evaluationId)
        .query(Integer.class)
        .single();
  }

  public <T> Optional<T> viewDiagnosticPracticePlan(UUID id, UUID owner, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT p.*,t.status AS training_status,t.problem_version,tp.review_hold AS"
                + " target_held,COALESCE(g.status,h.status) AS"
                + " generation_status,h.published_version_id AS rule_version_published FROM"
                + " diagnostic_practice_plan p LEFT JOIN training_session t ON"
                + " t.id=p.training_session_id LEFT JOIN problem_version tp ON"
                + " tp.id=t.problem_version LEFT JOIN generation_spec_draft g ON"
                + " g.id=p.generation_id LEFT JOIN hybrid_generation h ON"
                + " h.id=p.hybrid_generation_id WHERE p.id=? AND p.user_id=?")
        .param(id)
        .param(owner)
        .query(mapper)
        .optional();
  }

  public <T> T viewDiagnosticSession(Object owner, Class<T> type) {
    return jdbc.sql(
            "SELECT count(*) FROM diagnostic_session WHERE user_id=? AND status<>'COMPLETED'")
        .param(owner)
        .query(type)
        .single();
  }

  public Integer viewProblemVersion(String generated, UUID owner) {
    return jdbc.sql(
            "SELECT count(*) FROM problem_version WHERE id=? AND ready=true AND review_hold=false"
                + " AND diagnostic_only=false AND owner_id=?")
        .param(generated)
        .param(owner)
        .query(Integer.class)
        .single();
  }
}
