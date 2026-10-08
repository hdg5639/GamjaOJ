package dev.gamjaoj.repository.diagnostic;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence operations for DiagnosticEvaluations; transaction ownership remains in the service.
 */
@Repository
public class DiagnosticEvaluationsRepository {
  private final JdbcClient jdbc;

  public DiagnosticEvaluationsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Integer requestDiagnosticSession(UUID session) {
    return jdbc.sql("SELECT exposure_revision FROM diagnostic_session WHERE id=?")
        .param(session)
        .query(Integer.class)
        .single();
  }

  public String requestDiagnosticSession2(UUID session) {
    return jdbc.sql("SELECT correspondence_json FROM diagnostic_session WHERE id=?")
        .param(session)
        .query(String.class)
        .single();
  }

  public <T> T requestDiagnosticItem(UUID argument0, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT package_sha256,package_json,rubric_json,runtime_image,runner_policy FROM"
                + " diagnostic_item WHERE id=?")
        .param(argument0)
        .query(mapper)
        .single();
  }

  public <T> List<T> requestSubmission(UUID argument0, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT"
                + " s.id,s.source_code,s.source_sha256,j.verdict,j.result_sha256,s.language,s.execution_profile_json,s.runtime_image,s.runner_policy"
                + " FROM submission s JOIN judge_job j ON j.submission_id=s.id WHERE"
                + " s.diagnostic_item_id=? AND s.run_input IS NULL AND j.status='FINISHED' AND"
                + " j.verdict<>'IE' ORDER BY s.created_at,s.id")
        .param(argument0)
        .query(mapper)
        .list();
  }

  public Optional<UUID> requestDiagnosticEvaluation(UUID session, String hash) {
    return jdbc.sql("SELECT id FROM diagnostic_evaluation WHERE session_id=? AND evidence_sha256=?")
        .param(session)
        .param(hash)
        .query(UUID.class)
        .optional();
  }

  public int requestDiagnosticEvaluation2(
      UUID id, UUID session, String hash, String json, String argument4, UUID task, int revision) {
    return jdbc.sql(
            "INSERT INTO"
                + " diagnostic_evaluation(id,session_id,evidence_sha256,evidence_json,facts_json,ai_task_id,exposure_revision)"
                + " VALUES (?,?,?,?,?,?,?)")
        .param(id)
        .param(session)
        .param(hash)
        .param(json)
        .param(argument4)
        .param(task)
        .param(revision)
        .update();
  }

  public Stream<UUID> listDiagnosticEvaluation(UUID session) {
    return jdbc
        .sql(
            "SELECT id FROM diagnostic_evaluation WHERE session_id=? ORDER BY created_at DESC,id"
                + " DESC")
        .param(session)
        .query(UUID.class)
        .list()
        .stream();
  }

  public <T> Optional<T> correctDiagnosticCorrection(
      UUID evaluation, UUID key, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT observation_index,note FROM diagnostic_correction WHERE evaluation_id=? AND"
                + " request_key=?")
        .param(evaluation)
        .param(key)
        .query(mapper)
        .optional();
  }

  public int correctDiagnosticCorrection2(
      UUID argument0, UUID evaluation, UUID key, int observation, String argument4, String note) {
    return jdbc.sql(
            "INSERT INTO"
                + " diagnostic_correction(id,evaluation_id,request_key,observation_index,interpretation_sha256,note)"
                + " VALUES (?,?,?,?,?,?)")
        .param(argument0)
        .param(evaluation)
        .param(key)
        .param(observation)
        .param(argument4)
        .param(note)
        .update();
  }

  public <T> List<T> correctionsDiagnosticCorrection(UUID evaluation, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT id,observation_index,note,created_at FROM diagnostic_correction WHERE"
                + " evaluation_id=? ORDER BY created_at,id")
        .param(evaluation)
        .query(mapper)
        .list();
  }

  public <T> Optional<T> findDiagnosticEvaluation(UUID id, UUID owner, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT e.*,a.status,a.result_json,a.error_code FROM diagnostic_evaluation e JOIN"
                + " diagnostic_session d ON d.id=e.session_id LEFT JOIN ai_task a ON"
                + " a.id=e.ai_task_id WHERE e.id=? AND d.user_id=?")
        .param(id)
        .param(owner)
        .query(mapper)
        .optional();
  }

  public Integer findDiagnosticSession(UUID owner) {
    return jdbc.sql(
            "SELECT count(*) FROM diagnostic_session WHERE user_id=? AND status<>'COMPLETED'")
        .param(owner)
        .query(Integer.class)
        .single();
  }

  public <T> T findDiagnosticSession2(Object argument0, Class<T> type) {
    return jdbc.sql("SELECT exposure_revision FROM diagnostic_session WHERE id=?")
        .param(argument0)
        .query(type)
        .single();
  }

  public Integer findDiagnosticItem(UUID argument0) {
    return jdbc.sql(
            "SELECT count(*) FROM diagnostic_item i JOIN problem_version p ON"
                + " p.id=i.problem_version WHERE i.session_id=? AND p.review_hold=true")
        .param(argument0)
        .query(Integer.class)
        .single();
  }
}
