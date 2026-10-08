package dev.gamjaoj.diagnostic.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence operations for Diagnostics; transaction ownership remains in the service. */
@Repository
public class DiagnosticsRepository {
  private final JdbcClient jdbc;

  public DiagnosticsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Stream<String> banksDiagnosticBank() {
    return jdbc
        .sql(
            "SELECT bank.id FROM diagnostic_bank bank WHERE bank.reviewed=true AND NOT EXISTS"
                + " (SELECT 1 FROM diagnostic_bank_item i JOIN diagnostic_reassessment_pair r ON"
                + " r.target_version=i.problem_version WHERE i.bank_id=bank.id AND r.reviewed=true)"
                + " ORDER BY bank.id")
        .query(String.class)
        .list()
        .stream();
  }

  public List<String> banksDiagnosticBankItem(String id) {
    return jdbc.sql(
            "SELECT category FROM diagnostic_bank_item WHERE bank_id=? GROUP BY category ORDER BY"
                + " category")
        .param(id)
        .query(String.class)
        .list();
  }

  public Integer banksDiagnosticBankItem2(String id) {
    return jdbc.sql("SELECT count(*) FROM diagnostic_bank_item WHERE bank_id=?")
        .param(id)
        .query(Integer.class)
        .single();
  }

  public Integer banksDiagnosticBankItem3(String id) {
    return jdbc.sql(
            "SELECT count(*) FROM diagnostic_bank_item i JOIN problem_version p ON"
                + " p.id=i.problem_version WHERE i.bank_id=? AND (p.ready=false OR"
                + " p.review_hold=true OR p.diagnostic_only=false OR p.owner_id IS NOT NULL)")
        .param(id)
        .query(Integer.class)
        .single();
  }

  public Optional<UUID> ownerAppUser(String name) {
    return jdbc.sql("SELECT id FROM app_user WHERE username=? FOR UPDATE")
        .param(name)
        .query(UUID.class)
        .optional();
  }

  public Integer startInternalDiagnosticSession(UUID id, UUID user) {
    return jdbc.sql("SELECT count(*) FROM diagnostic_session WHERE id=? AND user_id=?")
        .param(id)
        .param(user)
        .query(Integer.class)
        .single();
  }

  public <T> T startInternalDiagnosticSession2(UUID id, RowMapper<T> mapper) {
    return jdbc.sql("SELECT requested_categories_json FROM diagnostic_session WHERE id=?")
        .param(id)
        .query(mapper)
        .single();
  }

  public Integer startInternalDiagnosticSession3(UUID id, UUID user) {
    return jdbc.sql("SELECT count(*) FROM diagnostic_session WHERE id=? OR open_owner=?")
        .param(id)
        .param(user)
        .query(Integer.class)
        .single();
  }

  public Integer startInternalDiagnosticBank(String bank) {
    return jdbc.sql("SELECT count(*) FROM diagnostic_bank WHERE id=? AND reviewed=true")
        .param(bank)
        .query(Integer.class)
        .single();
  }

  public int startInternalDiagnosticSession4(
      UUID id,
      UUID user,
      String bank,
      UUID userArgument3,
      String requested,
      UUID source,
      String correspondence) {
    return jdbc.sql(
            "INSERT INTO"
                + " diagnostic_session(id,user_id,bank_id,status,open_owner,requested_categories_json,source_session_id,correspondence_json)"
                + " VALUES (?,?,?,'ACTIVE',?,?,?,?)")
        .param(id)
        .param(user)
        .param(bank)
        .param(userArgument3)
        .param(requested)
        .param(source)
        .param(correspondence)
        .update();
  }

  public Integer startInternalDiagnosticExposure(UUID user, String content) {
    return jdbc.sql("SELECT count(*) FROM diagnostic_exposure WHERE user_id=? AND content_sha256=?")
        .param(user)
        .param(content)
        .query(Integer.class)
        .single();
  }

  public int startInternalDiagnosticExposure2(UUID user, String content) {
    return jdbc.sql("INSERT INTO diagnostic_exposure(user_id,content_sha256) VALUES (?,?)")
        .param(user)
        .param(content)
        .update();
  }

  public int startInternalDiagnosticItem(
      UUID argument0,
      UUID id,
      int argument2,
      String argument3,
      String argument4,
      String argument5,
      String argument6,
      String argument7,
      String argument8,
      String argument9,
      String argument10,
      String argument11) {
    return jdbc.sql(
            "INSERT INTO"
                + " diagnostic_item(id,session_id,position,category,difficulty,problem_version,package_json,package_sha256,runtime_image,runner_policy,rubric_json,time_limits_json)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")
        .param(argument0)
        .param(id)
        .param(argument2)
        .param(argument3)
        .param(argument4)
        .param(argument5)
        .param(argument6)
        .param(argument7)
        .param(argument8)
        .param(argument9)
        .param(argument10)
        .param(argument11)
        .update();
  }

  public List<String> allocateExamDiagnosticBank(String argument0) {
    return jdbc.sql("SELECT id FROM diagnostic_bank WHERE reviewed=true AND id LIKE ? ORDER BY id")
        .param(argument0)
        .query(String.class)
        .list();
  }

  public Integer allocateExamDiagnosticExposure(UUID user, String argument1) {
    return jdbc.sql("SELECT count(*) FROM diagnostic_exposure WHERE user_id=? AND content_sha256=?")
        .param(user)
        .param(argument1)
        .query(Integer.class)
        .single();
  }

  public Long allocateExamDiagnosticSession(UUID user, String candidate) {
    return jdbc.sql("SELECT count(*) FROM diagnostic_session WHERE user_id=? AND bank_id=?")
        .param(user)
        .param(candidate)
        .query(Long.class)
        .single();
  }

  public <T> Stream<T> bankItemsDiagnosticBankItem(String bank, RowMapper<T> mapper) {
    return jdbc
        .sql(
            "SELECT"
                + " b.*,p.package_json,p.package_sha256,p.runtime_image,p.runner_policy,p.time_limits_json,p.ready,p.review_hold,p.diagnostic_only,p.owner_id"
                + " FROM diagnostic_bank_item b JOIN problem_version p ON p.id=b.problem_version"
                + " WHERE bank_id=? ORDER BY position")
        .param(bank)
        .query(mapper)
        .list()
        .stream();
  }

  public List<String> reassessmentOptionsDiagnosticBank() {
    return jdbc.sql("SELECT id FROM diagnostic_bank WHERE reviewed=true ORDER BY id")
        .query(String.class)
        .list();
  }

  public Integer reportExposureAiBudgetLock() {
    return jdbc.sql("SELECT id FROM ai_budget_lock WHERE id=1 FOR UPDATE")
        .query(Integer.class)
        .single();
  }

  public int reportExposureDiagnosticItem(UUID item) {
    return jdbc.sql(
            "UPDATE diagnostic_item SET"
                + " externally_seen=true,exposure_reported_at=CURRENT_TIMESTAMP,status=CASE WHEN"
                + " status='OPEN' THEN 'SKIPPED' ELSE status END WHERE id=?")
        .param(item)
        .update();
  }

  public int reportExposureDiagnosticSession(UUID session) {
    return jdbc.sql(
            "UPDATE diagnostic_session SET exposure_revision=exposure_revision+1 WHERE id=?")
        .param(session)
        .update();
  }

  public Stream<String> validateReassessmentDiagnosticItem(UUID user) {
    return jdbc
        .sql(
            "SELECT i.package_json FROM diagnostic_item i JOIN diagnostic_session s ON"
                + " s.id=i.session_id WHERE s.user_id=?")
        .param(user)
        .query(String.class)
        .list()
        .stream();
  }

  public List<String> validateReassessmentDiagnosticExposure(UUID user) {
    return jdbc.sql("SELECT content_sha256 FROM diagnostic_exposure WHERE user_id=?")
        .param(user)
        .query(String.class)
        .list();
  }

  public Integer validateReassessmentProblemVersion(String argument0) {
    return jdbc.sql(
            "SELECT count(*) FROM problem_version WHERE id=? AND review_hold=false AND ready=true")
        .param(argument0)
        .query(Integer.class)
        .single();
  }

  public String validateReassessmentDiagnosticItem2(UUID argument0) {
    return jdbc.sql("SELECT package_sha256 FROM diagnostic_item WHERE id=?")
        .param(argument0)
        .query(String.class)
        .single();
  }

  public Integer validateReassessmentDiagnosticReassessmentPair(
      String argument0, String argument1, String sourceHash, String argument3) {
    return jdbc.sql(
            "SELECT count(*) FROM diagnostic_reassessment_pair WHERE source_version=? AND"
                + " target_version=? AND source_sha256=? AND target_sha256=? AND reviewed=true")
        .param(argument0)
        .param(argument1)
        .param(sourceHash)
        .param(argument3)
        .query(Integer.class)
        .single();
  }

  public Stream<UUID> historyDiagnosticSession(UUID user) {
    return jdbc
        .sql(
            "SELECT id FROM diagnostic_session WHERE user_id=? ORDER BY created_at DESC,id DESC"
                + " LIMIT 20")
        .param(user)
        .query(UUID.class)
        .list()
        .stream();
  }

  public int stateDiagnosticSession(String target, UUID id) {
    return jdbc.sql("UPDATE diagnostic_session SET status=? WHERE id=?")
        .param(target)
        .param(id)
        .update();
  }

  public int finishDiagnosticItem(UUID session) {
    return jdbc.sql(
            "UPDATE diagnostic_item SET status='SKIPPED',skip_reason='SESSION_ENDED' WHERE"
                + " session_id=? AND status='OPEN'")
        .param(session)
        .update();
  }

  public int skipDiagnosticItem(Object argument0, UUID item) {
    return jdbc.sql("UPDATE diagnostic_item SET status='SKIPPED',skip_reason=? WHERE id=?")
        .param(argument0)
        .param(item)
        .update();
  }

  public Optional<UUID> admitDiagnosticSession(UUID item, UUID user) {
    return jdbc.sql(
            "SELECT d.id FROM diagnostic_session d JOIN diagnostic_item i ON i.session_id=d.id"
                + " WHERE i.id=? AND d.user_id=?")
        .param(item)
        .param(user)
        .query(UUID.class)
        .optional();
  }

  public <T> T admitDiagnosticItem(UUID item, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT package_json,package_sha256,runtime_image,runner_policy,time_limits_json FROM"
                + " diagnostic_item WHERE id=?")
        .param(item)
        .query(mapper)
        .single();
  }

  public <T> Optional<T> viewDiagnosticSession(UUID id, UUID user, RowMapper<T> mapper) {
    return jdbc.sql("SELECT bank_id,status FROM diagnostic_session WHERE id=? AND user_id=?")
        .param(id)
        .param(user)
        .query(mapper)
        .optional();
  }

  public List<UUID> viewJudgeJob(UUID id) {
    return jdbc.sql(
            "SELECT j.submission_id FROM judge_job j JOIN submission s ON s.id=j.submission_id"
                + " WHERE s.diagnostic_item_id IN (SELECT id FROM diagnostic_item WHERE"
                + " session_id=?) ORDER BY j.submission_id FOR UPDATE")
        .param(id)
        .query(UUID.class)
        .list();
  }

  public int viewDiagnosticItem(UUID id) {
    return jdbc.sql(
            "UPDATE diagnostic_item SET status='PASSED' WHERE session_id=? AND status='OPEN' AND"
                + " EXISTS (SELECT 1 FROM submission s JOIN judge_job j ON j.submission_id=s.id"
                + " WHERE s.diagnostic_item_id=diagnostic_item.id AND s.run_input IS NULL AND"
                + " j.status='FINISHED' AND j.verdict='AC')")
        .param(id)
        .update();
  }

  public int viewDiagnosticItem2(UUID id) {
    return jdbc.sql(
            "UPDATE diagnostic_item SET status='EXHAUSTED' WHERE session_id=? AND status='OPEN' AND"
                + " (SELECT count(*) FROM submission s JOIN judge_job j ON j.submission_id=s.id"
                + " WHERE s.diagnostic_item_id=diagnostic_item.id AND s.run_input IS NULL AND"
                + " j.status='FINISHED' AND j.verdict<>'IE')>=5")
        .param(id)
        .update();
  }

  public <T> List<T> viewSubmission(UUID id, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT i.*, (SELECT count(*) FROM submission s JOIN judge_job j ON"
                + " j.submission_id=s.id WHERE s.diagnostic_item_id=i.id AND s.run_input IS NULL"
                + " AND j.status='FINISHED' AND j.verdict<>'IE') AS attempts, (SELECT count(*) FROM"
                + " submission s JOIN judge_job j ON j.submission_id=s.id WHERE"
                + " s.diagnostic_item_id=i.id AND s.run_input IS NULL AND j.status<>'FINISHED') AS"
                + " pending FROM diagnostic_item i WHERE session_id=? ORDER BY position")
        .param(id)
        .query(mapper)
        .list();
  }

  public int viewDiagnosticSession2(UUID id) {
    return jdbc.sql("UPDATE diagnostic_session SET status='COMPLETED',open_owner=NULL WHERE id=?")
        .param(id)
        .update();
  }

  public String viewDiagnosticItem3(UUID argument0) {
    return jdbc.sql("SELECT package_json FROM diagnostic_item WHERE id=?")
        .param(argument0)
        .query(String.class)
        .single();
  }

  public <T> Optional<T> viewDiagnosticItem4(UUID argument0, RowMapper<T> mapper) {
    return jdbc.sql("SELECT time_limits_json FROM diagnostic_item WHERE id=?")
        .param(argument0)
        .query(mapper)
        .optional();
  }

  public <T> T viewDiagnosticSession3(UUID id, RowMapper<T> mapper) {
    return jdbc.sql("SELECT source_session_id FROM diagnostic_session WHERE id=?")
        .param(id)
        .query(mapper)
        .single();
  }

  public OffsetDateTime viewDiagnosticSession4(UUID id) {
    return jdbc.sql("SELECT created_at FROM diagnostic_session WHERE id=?")
        .param(id)
        .query(OffsetDateTime.class)
        .single();
  }

  public Integer viewDiagnosticSession5(UUID user, String argument1, OffsetDateTime created) {
    return jdbc.sql(
            "SELECT count(*) FROM diagnostic_session WHERE user_id=? AND bank_id=? AND"
                + " created_at<?")
        .param(user)
        .param(argument1)
        .param(created)
        .query(Integer.class)
        .single();
  }
}
