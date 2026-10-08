package dev.gamjaoj.repository.ai;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence operations for AiTasks; transaction ownership remains in the service. */
@Repository
public class AiTasksRepository {
  private final JdbcClient jdbc;

  public AiTasksRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Integer lockAiBudgetLock() {
    return jdbc.sql("SELECT id FROM ai_budget_lock WHERE id=1 FOR UPDATE")
        .query(Integer.class)
        .single();
  }

  public <T> Optional<T> enqueueSubmission(UUID submission, UUID owner, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT"
                + " s.source_code,s.language,s.execution_profile_json,s.runtime_image,s.runner_policy,p.package_json,p.package_sha256,p.review_hold,p.diagnostic_only,j.result_sha256,j.status,j.verdict"
                + " FROM submission s JOIN problem_version p ON p.id=s.problem_version JOIN"
                + " judge_job j ON j.submission_id=s.id WHERE s.id=? AND s.user_id=? AND"
                + " s.run_input IS NULL")
        .param(submission)
        .param(owner)
        .query(mapper)
        .optional();
  }

  public Optional<UUID> enqueueAiTask(UUID owner, String cache) {
    return jdbc.sql("SELECT id FROM ai_task WHERE user_id=? AND cache_key=?")
        .param(owner)
        .param(cache)
        .query(UUID.class)
        .optional();
  }

  public Integer enqueueAiTask2(UUID owner) {
    return jdbc.sql(
            "SELECT count(*) FROM ai_task WHERE user_id=? AND status IN"
                + " ('QUEUED','RUNNING','HELD_DISABLED','HELD_BUDGET') AND NOT EXISTS (SELECT 1"
                + " FROM submission s JOIN problem_version p ON p.id=s.problem_version WHERE"
                + " s.id=ai_task.submission_id AND p.review_hold=true)")
        .param(owner)
        .query(Integer.class)
        .single();
  }

  public int enqueueAiTask3(
      UUID id,
      UUID owner,
      UUID submission,
      String kind,
      String cache,
      String configuration,
      String input,
      Object argument7) {
    return jdbc.sql(
            "INSERT INTO ai_task"
                + " (id,user_id,submission_id,kind,cache_key,settings_json,input_json,status)"
                + " VALUES (?,?,?,?,?,?,?,?)")
        .param(id)
        .param(owner)
        .param(submission)
        .param(kind)
        .param(cache)
        .param(configuration)
        .param(input)
        .param(argument7)
        .update();
  }

  public Optional<UUID> themeAiTask(UUID owner, String cache) {
    return jdbc.sql("SELECT id FROM ai_task WHERE user_id=? AND cache_key=?")
        .param(owner)
        .param(cache)
        .query(UUID.class)
        .optional();
  }

  public int themeAiTask2(
      UUID id, UUID owner, String cache, String argument3, String argument4, Object argument5) {
    return jdbc.sql(
            "INSERT INTO ai_task (id,user_id,kind,cache_key,settings_json,input_json,status) VALUES"
                + " (?,?,'THEME',?,?,?,?)")
        .param(id)
        .param(owner)
        .param(cache)
        .param(argument3)
        .param(argument4)
        .param(argument5)
        .update();
  }

  public Optional<UUID> diagnosticAiTask(UUID owner, String cache) {
    return jdbc.sql("SELECT id FROM ai_task WHERE user_id=? AND cache_key=?")
        .param(owner)
        .param(cache)
        .query(UUID.class)
        .optional();
  }

  public Integer diagnosticAiTask2(UUID owner) {
    return jdbc.sql(
            "SELECT count(*) FROM ai_task WHERE user_id=? AND status IN"
                + " ('QUEUED','RUNNING','HELD_DISABLED','HELD_BUDGET')")
        .param(owner)
        .query(Integer.class)
        .single();
  }

  public int diagnosticAiTask3(
      UUID id,
      UUID owner,
      UUID session,
      String cache,
      String configuration,
      String payload,
      Object argument6) {
    return jdbc.sql(
            "INSERT INTO"
                + " ai_task(id,user_id,diagnostic_session_id,kind,cache_key,settings_json,input_json,status)"
                + " VALUES (?,?,?,'DIAGNOSTIC',?,?,?,?)")
        .param(id)
        .param(owner)
        .param(session)
        .param(cache)
        .param(configuration)
        .param(payload)
        .param(argument6)
        .update();
  }

  public Stream<UUID> listAiTask(UUID owner, UUID submission) {
    return jdbc
        .sql("SELECT id FROM ai_task WHERE user_id=? AND submission_id=? ORDER BY created_at DESC")
        .param(owner)
        .param(submission)
        .query(UUID.class)
        .list()
        .stream();
  }

  public <T> Optional<T> findAiTask(UUID id, UUID owner, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT a.*,COALESCE(p.review_hold,false) AS problem_held FROM ai_task a LEFT JOIN"
                + " submission s ON s.id=a.submission_id LEFT JOIN problem_version p ON"
                + " p.id=s.problem_version WHERE a.id=? AND a.user_id=?")
        .param(id)
        .param(owner)
        .query(mapper)
        .optional();
  }

  public Integer retryAiAttempt(UUID id) {
    return jdbc.sql("SELECT count(*) FROM ai_attempt WHERE task_id=?")
        .param(id)
        .query(Integer.class)
        .single();
  }

  public int retryAiTask(Object argument0, UUID id) {
    return jdbc.sql(
            "UPDATE ai_task SET status=?,error_code=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?")
        .param(argument0)
        .param(id)
        .update();
  }

  public BigDecimal budgetAiAttempt(String argument0) {
    return jdbc.sql("SELECT COALESCE(SUM(actual_usd),0) FROM ai_attempt WHERE month_key=?")
        .param(argument0)
        .query(BigDecimal.class)
        .single();
  }

  public BigDecimal budgetAiAttempt2() {
    return jdbc.sql("SELECT COALESCE(SUM(reserved_usd),0) FROM ai_attempt WHERE actual_usd IS NULL")
        .query(BigDecimal.class)
        .single();
  }

  public <T> List<T> claimAiAttempt(OffsetDateTime argument0, RowMapper<T> mapper) {
    return jdbc.sql("SELECT id,task_id FROM ai_attempt WHERE status='RUNNING' AND started_at<?")
        .param(argument0)
        .query(mapper)
        .list();
  }

  public int claimAiAttempt2(UUID argument0) {
    return jdbc.sql(
            "UPDATE ai_attempt SET status='UNKNOWN',error_code='INTERRUPTED_USAGE_UNKNOWN' WHERE"
                + " id=?")
        .param(argument0)
        .update();
  }

  public int claimAiTask(UUID argument0) {
    return jdbc.sql(
            "UPDATE ai_task SET status='UNKNOWN',error_code='INTERRUPTED_USAGE_UNKNOWN' WHERE id=?"
                + " AND status='RUNNING'")
        .param(argument0)
        .update();
  }

  public Integer claimAiAttempt3() {
    return jdbc.sql("SELECT count(*) FROM ai_attempt WHERE status IN ('RUNNING','HYBRID_RUNNING')")
        .query(Integer.class)
        .single();
  }

  public <T> Optional<T> claimAiTask2(RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT id,settings_json,input_json FROM ai_task WHERE status IN"
                + " ('QUEUED','HELD_DISABLED','HELD_BUDGET') AND NOT EXISTS (SELECT 1 FROM"
                + " submission s JOIN problem_version p ON p.id=s.problem_version WHERE"
                + " s.id=ai_task.submission_id AND p.review_hold=true) AND NOT EXISTS (SELECT 1"
                + " FROM generation_job g WHERE g.theme_task_id=ai_task.id AND"
                + " g.error_code='STRUCTURE_EVIDENCE_REVOKED') AND NOT EXISTS (SELECT 1 FROM"
                + " diagnostic_evaluation e JOIN diagnostic_session d ON d.id=e.session_id WHERE"
                + " e.ai_task_id=ai_task.id AND e.exposure_revision<>d.exposure_revision) AND"
                + " (diagnostic_session_id IS NULL OR (NOT EXISTS (SELECT 1 FROM diagnostic_session"
                + " d WHERE d.user_id=ai_task.user_id AND d.status<>'COMPLETED') AND NOT EXISTS"
                + " (SELECT 1 FROM diagnostic_item i JOIN problem_version p ON"
                + " p.id=i.problem_version WHERE i.session_id=ai_task.diagnostic_session_id AND"
                + " p.review_hold=true))) ORDER BY created_at,id LIMIT 1 FOR UPDATE")
        .query(mapper)
        .optional();
  }

  public int claimAiTask3(UUID task) {
    return jdbc.sql(
            "UPDATE ai_task SET status='HELD_BUDGET',updated_at=CURRENT_TIMESTAMP WHERE id=?")
        .param(task)
        .update();
  }

  public int claimAiAttempt4(
      UUID attempt, UUID task, String argument2, BigDecimal reserve, String argument4) {
    return jdbc.sql(
            "INSERT INTO ai_attempt (id,task_id,month_key,status,reserved_usd,settings_json) VALUES"
                + " (?,?,?,'RUNNING',?,?)")
        .param(attempt)
        .param(task)
        .param(argument2)
        .param(reserve)
        .param(argument4)
        .update();
  }

  public int claimAiTask4(UUID task) {
    return jdbc.sql(
            "UPDATE ai_task SET status='RUNNING',error_code=NULL,updated_at=CURRENT_TIMESTAMP WHERE"
                + " id=?")
        .param(task)
        .update();
  }

  public Integer noticeAiBudgetNotice(String argument0) {
    return jdbc.sql("SELECT count(*) FROM ai_budget_notice WHERE month_key=?")
        .param(argument0)
        .query(Integer.class)
        .single();
  }

  public int noticeAiBudgetNotice2(String argument0) {
    return jdbc.sql("INSERT INTO ai_budget_notice (month_key) VALUES (?)")
        .param(argument0)
        .update();
  }

  public String finishAiAttempt(UUID argument0) {
    return jdbc.sql("SELECT status FROM ai_attempt WHERE id=?")
        .param(argument0)
        .query(String.class)
        .single();
  }

  public int finishAiAttempt2(
      String status,
      BigDecimal actual,
      Object argument2,
      Object argument3,
      Object argument4,
      Object argument5,
      String error,
      UUID argument7) {
    return jdbc.sql(
            "UPDATE ai_attempt SET"
                + " status=?,actual_usd=?,usage_json=?,request_id=?,response_id=?,provider_model=?,error_code=?,finished_at=CURRENT_TIMESTAMP"
                + " WHERE id=?")
        .param(status)
        .param(actual)
        .param(argument2)
        .param(argument3)
        .param(argument4)
        .param(argument5)
        .param(error)
        .param(argument7)
        .update();
  }

  public int finishAiTask(String status, Object argument1, String error, UUID argument3) {
    return jdbc.sql(
            "UPDATE ai_task SET status=?,result_json=?,error_code=?,updated_at=CURRENT_TIMESTAMP"
                + " WHERE id=?")
        .param(status)
        .param(argument1)
        .param(error)
        .param(argument3)
        .update();
  }

  public <T> List<T> enqueueEndedSessionsTrainingSession(RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT id,user_id FROM training_session WHERE status='ENDED' AND"
                + " analysis_checked=false ORDER BY ended_at LIMIT 20")
        .query(mapper)
        .list();
  }

  public Optional<UUID> enqueueEndedSessionsAppUser(UUID argument0) {
    return jdbc.sql("SELECT id FROM app_user WHERE id=? FOR UPDATE")
        .param(argument0)
        .query(UUID.class)
        .optional();
  }

  public Integer enqueueEndedSessionsSubmission(UUID argument0) {
    return jdbc.sql(
            "SELECT count(*) FROM submission s JOIN judge_job j ON j.submission_id=s.id WHERE"
                + " s.training_session_id=? AND j.status<>'FINISHED'")
        .param(argument0)
        .query(Integer.class)
        .single();
  }

  public Optional<UUID> enqueueEndedSessionsSubmission2(UUID argument0) {
    return jdbc.sql(
            "SELECT s.id FROM submission s JOIN judge_job j ON j.submission_id=s.id WHERE"
                + " s.training_session_id=? AND EXISTS (SELECT 1 FROM problem_version p WHERE"
                + " p.id=s.problem_version AND p.review_hold=false) AND s.run_input IS NULL AND"
                + " j.status='FINISHED' AND j.verdict<>'IE' ORDER BY s.created_at DESC,s.id DESC"
                + " LIMIT 1")
        .param(argument0)
        .query(UUID.class)
        .optional();
  }

  public int enqueueEndedSessionsTrainingSession2(UUID task, UUID argument1) {
    return jdbc.sql(
            "UPDATE training_session SET analysis_checked=true,analysis_task_id=? WHERE id=?")
        .param(task)
        .param(argument1)
        .update();
  }
}
