package dev.gamjaoj.export.repository;

import dev.gamjaoj.shared.repository.ProblemSql;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence operations for SolutionExports; transaction ownership remains in the service. */
@Repository
public class SolutionExportsRepository {
  private final JdbcClient jdbc;

  public SolutionExportsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<UUID> ownerAppUser(String username) {
    return jdbc.sql("SELECT id FROM app_user WHERE username=?")
        .param(username)
        .query(UUID.class)
        .optional();
  }

  public <T> Optional<T> connectionExportConnection(
      UUID user, String provider, RowMapper<T> mapper) {
    return jdbc.sql("SELECT * FROM export_connection WHERE user_id=? AND provider=?")
        .param(user)
        .param(provider)
        .query(mapper)
        .optional();
  }

  public UUID startAppUser(UUID user) {
    return jdbc.sql("SELECT id FROM app_user WHERE id=? FOR UPDATE")
        .param(user)
        .query(UUID.class)
        .single();
  }

  public int startExportOauth(UUID user, String provider) {
    return jdbc.sql("DELETE FROM export_oauth WHERE user_id=? AND provider=?")
        .param(user)
        .param(provider)
        .update();
  }

  public int startExportOauth2(
      UUID user, String provider, String argument2, String argument3, OffsetDateTime argument4) {
    return jdbc.sql(
            "INSERT INTO export_oauth(user_id,provider,state_sha256,verifier,expires_at) VALUES"
                + " (?,?,?,?,?)")
        .param(user)
        .param(provider)
        .param(argument2)
        .param(argument3)
        .param(argument4)
        .update();
  }

  public Optional<String> callbackExportOauth(
      UUID user, String provider, String hash, OffsetDateTime argument3) {
    return jdbc.sql(
            "SELECT verifier FROM export_oauth WHERE user_id=? AND provider=? AND state_sha256=?"
                + " AND claimed=false AND expires_at>? FOR UPDATE")
        .param(user)
        .param(provider)
        .param(hash)
        .param(argument3)
        .query(String.class)
        .optional();
  }

  public int callbackExportOauth2(UUID user, String provider, String hash) {
    return jdbc.sql(
            "UPDATE export_oauth SET claimed=true WHERE user_id=? AND provider=? AND"
                + " state_sha256=?")
        .param(user)
        .param(provider)
        .param(hash)
        .update();
  }

  public Optional<UUID> callbackExportOauth3(
      UUID user, String provider, String hash, OffsetDateTime argument3) {
    return jdbc.sql(
            "SELECT user_id FROM export_oauth WHERE user_id=? AND provider=? AND state_sha256=? AND"
                + " claimed=true AND expires_at>? FOR UPDATE")
        .param(user)
        .param(provider)
        .param(hash)
        .param(argument3)
        .query(UUID.class)
        .optional();
  }

  public Optional<String> callbackExportConnection(UUID user, String provider) {
    return jdbc.sql(
            "SELECT credentials FROM export_connection WHERE user_id=? AND provider=? FOR UPDATE")
        .param(user)
        .param(provider)
        .query(String.class)
        .optional();
  }

  public int callbackExportConnection2(
      UUID argument0, String argument1, String argument2, UUID user, String provider) {
    return jdbc.sql(
            "UPDATE export_connection SET"
                + " id=?,credentials=?,account_label=?,status='CONNECTED',token_version=token_version+1,refreshing_until=NULL"
                + " WHERE user_id=? AND provider=?")
        .param(argument0)
        .param(argument1)
        .param(argument2)
        .param(user)
        .param(provider)
        .update();
  }

  public int callbackSolutionExport(OffsetDateTime argument0, UUID user, String provider) {
    return jdbc.sql(
            "UPDATE solution_export SET status='QUEUED',lease_token=NULL,lease_until=NULL,next_at=?"
                + " WHERE user_id=? AND provider=? AND status='RUNNING'")
        .param(argument0)
        .param(user)
        .param(provider)
        .update();
  }

  public int callbackExportConnection3(UUID user, String provider) {
    return jdbc.sql("DELETE FROM export_connection WHERE user_id=? AND provider=?")
        .param(user)
        .param(provider)
        .update();
  }

  public int callbackExportConnection4(
      UUID argument0, UUID user, String provider, String argument3, String argument4) {
    return jdbc.sql(
            "INSERT INTO export_connection(id,user_id,provider,credentials,account_label) VALUES"
                + " (?,?,?,?,?)")
        .param(argument0)
        .param(user)
        .param(provider)
        .param(argument3)
        .param(argument4)
        .update();
  }

  public int callbackExportOauth4(UUID user, String provider, String hash) {
    return jdbc.sql("DELETE FROM export_oauth WHERE user_id=? AND provider=? AND state_sha256=?")
        .param(user)
        .param(provider)
        .param(hash)
        .update();
  }

  public int accessExportConnection(
      OffsetDateTime argument0, UUID argument1, int argument2, OffsetDateTime argument3) {
    return jdbc.sql(
            "UPDATE export_connection SET refreshing_until=? WHERE id=? AND token_version=? AND"
                + " (refreshing_until IS NULL OR refreshing_until<?)")
        .param(argument0)
        .param(argument1)
        .param(argument2)
        .param(argument3)
        .update();
  }

  public int accessExportConnection2(String argument0, UUID argument1, int argument2) {
    return jdbc.sql(
            "UPDATE export_connection SET"
                + " credentials=?,token_version=token_version+1,refreshing_until=NULL WHERE id=?"
                + " AND token_version=?")
        .param(argument0)
        .param(argument1)
        .param(argument2)
        .update();
  }

  public int accessExportConnection3(UUID argument0, int argument1) {
    return jdbc.sql(
            "UPDATE export_connection SET"
                + " status='RECONNECT_REQUIRED',auto_enabled=false,refreshing_until=NULL WHERE id=?"
                + " AND token_version=?")
        .param(argument0)
        .param(argument1)
        .update();
  }

  public int saveExportConnection(String argument0, boolean auto, UUID argument2) {
    return jdbc.sql(
            "UPDATE export_connection SET target_json=?,auto_enabled=? WHERE id=? AND"
                + " status='CONNECTED'")
        .param(argument0)
        .param(auto)
        .param(argument2)
        .update();
  }

  public int saveSolutionExport(UUID argument0, String provider, String argument2) {
    return jdbc.sql(
            "UPDATE solution_export SET"
                + " status='CANCELLED',lease_token=NULL,lease_until=NULL,error_code='TARGET_CHANGED'"
                + " WHERE user_id=? AND provider=? AND target_sha256<>? AND status IN"
                + " ('QUEUED','RETRY','RUNNING','FAILED')")
        .param(argument0)
        .param(provider)
        .param(argument2)
        .update();
  }

  public int automaticExportConnection(boolean enabled, UUID user, String provider) {
    return jdbc.sql(
            "UPDATE export_connection SET auto_enabled=? WHERE user_id=? AND provider=? AND"
                + " status='CONNECTED' AND target_json IS NOT NULL")
        .param(enabled)
        .param(user)
        .param(provider)
        .update();
  }

  public UUID disconnectAppUser(UUID user) {
    return jdbc.sql("SELECT id FROM app_user WHERE id=? FOR UPDATE")
        .param(user)
        .query(UUID.class)
        .single();
  }

  public int disconnectExportOauth(UUID user, String provider) {
    return jdbc.sql("DELETE FROM export_oauth WHERE user_id=? AND provider=?")
        .param(user)
        .param(provider)
        .update();
  }

  public int disconnectExportConnection(UUID user, String provider) {
    return jdbc.sql("DELETE FROM export_connection WHERE user_id=? AND provider=?")
        .param(user)
        .param(provider)
        .update();
  }

  public UUID acceptedSubmission(UUID argument0) {
    return jdbc.sql("SELECT user_id FROM submission WHERE id=?")
        .param(argument0)
        .query(UUID.class)
        .single();
  }

  public List<String> acceptedExportConnection(UUID user) {
    return jdbc.sql(
            "SELECT provider FROM export_connection WHERE user_id=? AND auto_enabled=true AND"
                + " status='CONNECTED' AND target_json IS NOT NULL")
        .param(user)
        .query(String.class)
        .list();
  }

  public <T> Optional<T> enqueueSubmission(UUID submission, UUID user, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT s.*,"
                + ProblemSql.COLUMNS
                + ",j.finished_at,j.result_json AS"
                + " judge_result_json,p.package_json,p.catalog_category,p.catalog_tags,p.catalog_difficulty,g.template_id,g.focus,d.spec_json,u.username"
                + " FROM submission s JOIN judge_job j ON j.submission_id=s.id JOIN problem_version"
                + " p ON p.id=s.problem_version "
                + ProblemSql.JOIN
                + " JOIN app_user u ON u.id=s.user_id LEFT JOIN generation_job g ON"
                + " p.id=CONCAT(CONCAT(CONCAT('generated-',CAST(g.id AS"
                + " VARCHAR(36))),'-r'),CAST(g.revision AS VARCHAR(10))) LEFT JOIN"
                + " generation_spec_draft d ON p.id=CONCAT('experimental-check-',CAST(d.id AS"
                + " VARCHAR(36))) WHERE s.id=? AND s.user_id=? AND j.status='FINISHED' AND"
                + " j.verdict='AC' AND s.run_input IS NULL AND s.diagnostic_item_id IS NULL AND"
                + " s.hybrid_branch_id IS NULL AND s.generation_job_id IS NULL AND s.spec_draft_id"
                + " IS NULL AND s.example_check=false AND p.diagnostic_only=false AND"
                + " p.review_hold=false")
        .param(submission)
        .param(user)
        .query(mapper)
        .optional();
  }

  public Optional<UUID> enqueueExportConnection(UUID user, String provider) {
    return jdbc.sql(
            "SELECT id FROM export_connection WHERE user_id=? AND provider=? AND status='CONNECTED'"
                + " AND target_json IS NOT NULL FOR UPDATE")
        .param(user)
        .param(provider)
        .query(UUID.class)
        .optional();
  }

  public <T> Optional<T> enqueueSolutionExport(
      UUID user,
      String provider,
      String hash,
      String argument3,
      String argument4,
      RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT id,submission_id,submitted_at,status FROM solution_export WHERE user_id=? AND"
                + " provider=? AND target_sha256=? AND problem_version=? AND language=? FOR UPDATE")
        .param(user)
        .param(provider)
        .param(hash)
        .param(argument3)
        .param(argument4)
        .query(mapper)
        .optional();
  }

  public int enqueueSolutionExport2(OffsetDateTime argument0, String target, UUID id) {
    return jdbc.sql(
            "UPDATE solution_export SET"
                + " status='QUEUED',attempts=0,error_code=NULL,next_at=?,target_json=? WHERE id=?")
        .param(argument0)
        .param(target)
        .param(id)
        .update();
  }

  public int enqueueSolutionExport3(
      UUID submission,
      OffsetDateTime created,
      String argument2,
      String target,
      OffsetDateTime argument4,
      OffsetDateTime argument5,
      UUID id) {
    return jdbc.sql(
            "UPDATE solution_export SET"
                + " submission_id=?,submitted_at=?,payload_json=?,target_json=?,revision=revision+1,status=CASE"
                + " WHEN status='RUNNING' THEN status ELSE 'QUEUED'"
                + " END,attempts=0,next_at=?,error_code=NULL,updated_at=? WHERE id=?")
        .param(submission)
        .param(created)
        .param(argument2)
        .param(target)
        .param(argument4)
        .param(argument5)
        .param(id)
        .update();
  }

  public int enqueueSolutionExport4(
      UUID id,
      UUID user,
      String provider,
      String hash,
      String target,
      String argument5,
      String argument6,
      UUID submission,
      OffsetDateTime created,
      String argument9) {
    return jdbc.sql(
            "INSERT INTO"
                + " solution_export(id,user_id,provider,target_sha256,target_json,problem_version,language,submission_id,submitted_at,payload_json)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?)")
        .param(id)
        .param(user)
        .param(provider)
        .param(hash)
        .param(target)
        .param(argument5)
        .param(argument6)
        .param(submission)
        .param(created)
        .param(argument9)
        .update();
  }

  public <T> List<T> findDeliveries(UUID owner, UUID submission, RowMapper<T> mapper) {
    var query =
        jdbc.sql(
                "SELECT * FROM solution_export WHERE user_id=?"
                    + (submission == null ? "" : " AND submission_id=?")
                    + " ORDER BY updated_at DESC LIMIT 50")
            .param(owner);
    if (submission != null) query.param(submission);
    return query.query(mapper).list();
  }

  public int retrySolutionExport(OffsetDateTime argument0, UUID id, UUID user) {
    return jdbc.sql(
            "UPDATE solution_export SET status='QUEUED',attempts=0,next_at=?,error_code=NULL WHERE"
                + " id=? AND user_id=? AND status IN ('FAILED','RETRY') AND EXISTS (SELECT 1 FROM"
                + " export_connection c WHERE c.user_id=solution_export.user_id AND"
                + " c.provider=solution_export.provider AND c.status='CONNECTED')")
        .param(argument0)
        .param(id)
        .param(user)
        .update();
  }

  public int claimSolutionExport(OffsetDateTime argument0, OffsetDateTime argument1) {
    return jdbc.sql(
            "UPDATE solution_export SET status=CASE WHEN attempts>=6 THEN 'FAILED' ELSE 'RETRY'"
                + " END,error_code='DELIVERY_INTERRUPTED',lease_token=NULL,lease_until=NULL,next_at=?"
                + " WHERE status='RUNNING' AND lease_until<?")
        .param(argument0)
        .param(argument1)
        .update();
  }

  public List<UUID> claimSolutionExport2(OffsetDateTime argument0) {
    return jdbc.sql(
            "SELECT id FROM solution_export WHERE status IN ('QUEUED','RETRY') AND next_at<=? ORDER"
                + " BY next_at,id LIMIT 1 FOR UPDATE")
        .param(argument0)
        .query(UUID.class)
        .list();
  }

  public int claimSolutionExport3(
      UUID lease, OffsetDateTime argument1, OffsetDateTime argument2, UUID id) {
    return jdbc.sql(
            "UPDATE solution_export SET"
                + " status='RUNNING',attempts=attempts+1,lease_token=?,lease_until=?,updated_at=?"
                + " WHERE id=?")
        .param(lease)
        .param(argument1)
        .param(argument2)
        .param(id)
        .update();
  }

  public <T> T claimSolutionExport4(UUID id, RowMapper<T> mapper) {
    return jdbc.sql("SELECT * FROM solution_export WHERE id=?").param(id).query(mapper).single();
  }

  public int fenceSolutionExport(
      OffsetDateTime argument0, UUID argument1, UUID argument2, OffsetDateTime argument3) {
    return jdbc.sql(
            "UPDATE solution_export SET lease_until=? WHERE id=? AND lease_token=? AND"
                + " status='RUNNING' AND lease_until>? AND EXISTS (SELECT 1 FROM export_connection"
                + " c WHERE c.user_id=solution_export.user_id AND"
                + " c.provider=solution_export.provider AND c.status='CONNECTED')")
        .param(argument0)
        .param(argument1)
        .param(argument2)
        .param(argument3)
        .update();
  }

  public int checkpointSolutionExport(String argument0, UUID argument1, UUID argument2) {
    return jdbc.sql(
            "UPDATE solution_export SET remote_json=? WHERE id=? AND lease_token=? AND"
                + " status='RUNNING'")
        .param(argument0)
        .param(argument1)
        .param(argument2)
        .update();
  }

  public Optional<UUID> finishExportConnection(UUID argument0, String argument1) {
    return jdbc.sql("SELECT id FROM export_connection WHERE user_id=? AND provider=? FOR UPDATE")
        .param(argument0)
        .param(argument1)
        .query(UUID.class)
        .optional();
  }

  public Optional<UUID> finishSolutionExport(UUID argument0, UUID argument1) {
    return jdbc.sql(
            "SELECT id FROM solution_export WHERE id=? AND lease_token=? AND status='RUNNING' FOR"
                + " UPDATE")
        .param(argument0)
        .param(argument1)
        .query(UUID.class)
        .optional();
  }

  public int finishExportConnection2(UUID argument0, String argument1) {
    return jdbc.sql(
            "UPDATE export_connection SET status='RECONNECT_REQUIRED',auto_enabled=false WHERE"
                + " user_id=? AND provider=?")
        .param(argument0)
        .param(argument1)
        .update();
  }

  public int finishSolutionExport2(
      int argument0,
      String status,
      String url,
      Object argument3,
      OffsetDateTime argument4,
      OffsetDateTime argument5,
      UUID argument6,
      UUID argument7) {
    return jdbc.sql(
            "UPDATE solution_export SET status=CASE WHEN revision<>? THEN 'QUEUED' ELSE ?"
                + " END,external_url=COALESCE(?,external_url),error_code=?,next_at=?,lease_token=NULL,lease_until=NULL,updated_at=?"
                + " WHERE id=? AND lease_token=? AND status='RUNNING'")
        .param(argument0)
        .param(status)
        .param(url)
        .param(argument3)
        .param(argument4)
        .param(argument5)
        .param(argument6)
        .param(argument7)
        .update();
  }
}
