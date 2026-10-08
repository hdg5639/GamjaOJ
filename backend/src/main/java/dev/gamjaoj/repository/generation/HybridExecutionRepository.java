package dev.gamjaoj.repository.generation;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence operations for HybridExecution; transaction ownership remains in the service. */
@Repository
public class HybridExecutionRepository {
  private final JdbcClient jdbc;

  public HybridExecutionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private static final String activeWhere =
      "EXISTS (SELECT 1 FROM hybrid_api_reservation r WHERE r.generation_id=g.id) AND (g.status IN"
          + " ('QUEUED','DESIGNING','BUILDING','VALIDATING','REVIEWING') OR (g.status='HELD' AND"
          + " g.error_code IN ('VALIDATION_ADAPTER_NOT_CONNECTED','CONTENT_REVIEW_REQUIRED') AND"
          + " g.deadline_at>CURRENT_TIMESTAMP))";

  public Integer lockAiBudgetLock() {
    return jdbc.sql("SELECT id FROM ai_budget_lock WHERE id=1 FOR UPDATE")
        .query(Integer.class)
        .single();
  }

  public Integer admitHybridApiReservation(UUID id) {
    return jdbc.sql("SELECT count(*) FROM hybrid_api_reservation WHERE generation_id=?")
        .param(id)
        .query(Integer.class)
        .single();
  }

  public Integer admitHybridGeneration(UUID id) {
    return jdbc.sql("SELECT count(*) FROM hybrid_generation WHERE id=?")
        .param(id)
        .query(Integer.class)
        .single();
  }

  public Optional<UUID> admitAppUser(String user) {
    return jdbc.sql("SELECT id FROM app_user WHERE username=?")
        .param(user)
        .query(UUID.class)
        .optional();
  }

  public Integer admitHybridGeneration2(UUID owner) {
    return jdbc.sql(
            "SELECT count(*) FROM hybrid_generation g WHERE g.owner_id=? AND " + activeWhere)
        .param(owner)
        .query(Integer.class)
        .single();
  }

  public Integer admitHybridGeneration3() {
    return jdbc.sql("SELECT count(*) FROM hybrid_generation g WHERE " + activeWhere)
        .query(Integer.class)
        .single();
  }

  public int admitHybridExecutionPolicy(UUID id, String argument1, String argument2) {
    return jdbc.sql(
            "INSERT INTO hybrid_execution_policy(generation_id,codex_model,codex_effort) VALUES"
                + " (?,?,?)")
        .param(id)
        .param(argument1)
        .param(argument2)
        .update();
  }

  public int admitAiAttempt(
      UUID attempt, String argument1, BigDecimal argument2, String argument3) {
    return jdbc.sql(
            "INSERT INTO ai_attempt(id,month_key,status,reserved_usd,settings_json) VALUES"
                + " (?,?,'HYBRID_RESERVED',?,?)")
        .param(attempt)
        .param(argument1)
        .param(argument2)
        .param(argument3)
        .update();
  }

  public int admitHybridApiReservation2(UUID attempt, UUID id, String argument2) {
    return jdbc.sql(
            "INSERT INTO hybrid_api_reservation(attempt_id,generation_id,revision,role) VALUES"
                + " (?,?,0,?)")
        .param(attempt)
        .param(id)
        .param(argument2)
        .update();
  }

  public Integer codexBlockedHybridCodexQuota(OffsetDateTime argument0) {
    return jdbc.sql("SELECT count(*) FROM hybrid_codex_quota WHERE id=1 AND blocked_until>?")
        .param(argument0)
        .query(Integer.class)
        .single();
  }

  public int markCodexQuotaHybridCodexQuota() {
    return jdbc.sql("DELETE FROM hybrid_codex_quota WHERE id=1").update();
  }

  public int markCodexQuotaHybridCodexQuota2(OffsetDateTime now, OffsetDateTime argument1) {
    return jdbc.sql("INSERT INTO hybrid_codex_quota(id,observed_at,blocked_until) VALUES (1,?,?)")
        .param(now)
        .param(argument1)
        .update();
  }

  public int reserveAuthorAiAttempt(
      UUID attempt, String argument1, BigDecimal amount, String argument3) {
    return jdbc.sql(
            "INSERT INTO ai_attempt(id,month_key,status,reserved_usd,settings_json) VALUES"
                + " (?,?,'HYBRID_RESERVED',?,?)")
        .param(attempt)
        .param(argument1)
        .param(amount)
        .param(argument3)
        .update();
  }

  public int reserveAuthorHybridApiReservation(
      UUID attempt, UUID generation, int revision, String argument3) {
    return jdbc.sql(
            "INSERT INTO hybrid_api_reservation(attempt_id,generation_id,revision,role) VALUES"
                + " (?,?,?,?)")
        .param(attempt)
        .param(generation)
        .param(revision)
        .param(argument3)
        .update();
  }

  public Integer hasAuthorReservationHybridApiReservation(
      UUID generation, int revision, String argument2) {
    return jdbc.sql(
            "SELECT count(*) FROM hybrid_api_reservation WHERE generation_id=? AND revision=? AND"
                + " role=?")
        .param(generation)
        .param(revision)
        .param(argument2)
        .query(Integer.class)
        .single();
  }

  public <T> List<T> routeBlockedAuthorsHybridBranch(RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT b.generation_id,b.role,g.revision FROM hybrid_branch b JOIN hybrid_generation g"
                + " ON g.id=b.generation_id WHERE b.revision=g.revision AND b.status='QUEUED' AND"
                + " b.role IN ('CONTRACT','CORE') AND g.status IN ('QUEUED','DESIGNING','BUILDING')"
                + " AND EXISTS (SELECT 1 FROM hybrid_api_reservation r WHERE r.generation_id=g.id)")
        .query(mapper)
        .list();
  }

  public <T> List<T> claimCodexHybridBranch(RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT b.generation_id,b.role,g.deadline_at FROM hybrid_branch b JOIN"
                + " hybrid_generation g ON g.id=b.generation_id WHERE b.revision=g.revision AND"
                + " b.status='QUEUED' AND b.role IN ('CONTRACT','CORE') AND g.status IN"
                + " ('QUEUED','DESIGNING','BUILDING') AND EXISTS (SELECT 1 FROM"
                + " hybrid_api_reservation r WHERE r.generation_id=g.id) AND NOT EXISTS (SELECT 1"
                + " FROM hybrid_api_reservation f WHERE f.generation_id=g.id AND"
                + " f.revision=g.revision AND f.role=b.role) ORDER BY b.created_at LIMIT 20")
        .query(mapper)
        .list();
  }

  public <T> T claimCodexHybridExecutionPolicy(UUID argument0, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT codex_model,codex_effort FROM hybrid_execution_policy WHERE generation_id=?")
        .param(argument0)
        .query(mapper)
        .single();
  }

  public Integer completeCodexHybridBranch(UUID argument0) {
    return jdbc.sql(
            "SELECT count(*) FROM hybrid_branch b WHERE b.id=? AND EXISTS (SELECT 1 FROM"
                + " hybrid_api_reservation r WHERE r.generation_id=b.generation_id)")
        .param(argument0)
        .query(Integer.class)
        .single();
  }

  public Integer claimApiAiAttempt(boolean authorLane) {
    return jdbc.sql(
            "SELECT count(*) FROM ai_attempt a JOIN hybrid_api_reservation r ON r.attempt_id=a.id"
                + " WHERE a.status='HYBRID_RUNNING' AND r.role IN "
                + (authorLane
                    ? "('CONTRACT','CORE')"
                    : "('PRESENTATION','READER','CONTENT_REVIEW')"))
        .query(Integer.class)
        .single();
  }

  public Integer claimApiAiAttempt2() {
    return jdbc.sql("SELECT count(*) FROM ai_attempt a WHERE a.status='RUNNING'")
        .query(Integer.class)
        .single();
  }

  public Integer claimApiAiAttempt3() {
    return jdbc.sql(
            "SELECT count(*) FROM ai_attempt a WHERE a.status='HYBRID_RUNNING' AND NOT EXISTS"
                + " (SELECT 1 FROM hybrid_api_reservation r WHERE r.attempt_id=a.id AND r.role IN"
                + " ('CONTRACT','CORE'))")
        .query(Integer.class)
        .single();
  }

  public <T> List<T> claimApiHybridApiReservation(boolean authorLane, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT r.attempt_id,r.generation_id,r.role,a.settings_json FROM hybrid_api_reservation"
                + " r JOIN ai_attempt a ON a.id=r.attempt_id JOIN hybrid_generation g ON"
                + " g.id=r.generation_id WHERE a.status='HYBRID_RESERVED' AND r.revision=g.revision"
                + " AND r.role IN "
                + (authorLane
                    ? "('CONTRACT','CORE')"
                    : "('PRESENTATION','READER','CONTENT_REVIEW')")
                + " AND g.status IN ('QUEUED','BUILDING','DESIGNING','REVIEWING') ORDER BY"
                + " g.created_at,r.role")
        .query(mapper)
        .list();
  }

  public Optional<String> claimApiHybridPublicRequest(UUID argument0) {
    return jdbc.sql("SELECT profile_id FROM hybrid_public_request WHERE generation_id=?")
        .param(argument0)
        .query(String.class)
        .optional();
  }

  public int dispatchHybridApiReservation(UUID argument0, String argument1, UUID attempt) {
    return jdbc.sql(
            "UPDATE hybrid_api_reservation SET branch_id=?,assignment_json=? WHERE attempt_id=?")
        .param(argument0)
        .param(argument1)
        .param(attempt)
        .update();
  }

  public int dispatchAiAttempt(String argument0, UUID attempt) {
    return jdbc.sql(
            "UPDATE ai_attempt SET status='HYBRID_RUNNING',started_at=CURRENT_TIMESTAMP,month_key=?"
                + " WHERE id=?")
        .param(argument0)
        .param(attempt)
        .update();
  }

  public OffsetDateTime dispatchHybridGeneration(UUID generation) {
    return jdbc.sql("SELECT deadline_at FROM hybrid_generation WHERE id=?")
        .param(generation)
        .query(OffsetDateTime.class)
        .single();
  }

  public <T> T finishHybridApiReservation(UUID attempt, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT r.assignment_json,r.receipt_json,a.settings_json,a.status FROM"
                + " hybrid_api_reservation r JOIN ai_attempt a ON a.id=r.attempt_id WHERE"
                + " r.attempt_id=?")
        .param(attempt)
        .query(mapper)
        .single();
  }

  public int finishAiAttempt(
      Object argument0,
      BigDecimal cost,
      Object argument2,
      Object argument3,
      Object argument4,
      Object argument5,
      String error,
      UUID attempt) {
    return jdbc.sql(
            "UPDATE ai_attempt SET"
                + " status=?,actual_usd=?,usage_json=?,request_id=?,response_id=?,provider_model=?,error_code=?,finished_at=CURRENT_TIMESTAMP"
                + " WHERE id=?")
        .param(argument0)
        .param(cost)
        .param(argument2)
        .param(argument3)
        .param(argument4)
        .param(argument5)
        .param(error)
        .param(attempt)
        .update();
  }

  public int finishHybridApiReservation2(String saved, UUID attempt) {
    return jdbc.sql("UPDATE hybrid_api_reservation SET receipt_json=? WHERE attempt_id=?")
        .param(saved)
        .param(attempt)
        .update();
  }

  public int recoverAiAttempt() {
    return jdbc.sql(
            "UPDATE ai_attempt SET status='HYBRID_UNKNOWN',error_code='INTERRUPTED_USAGE_UNKNOWN'"
                + " WHERE status='HYBRID_RUNNING' AND id IN (SELECT r.attempt_id FROM"
                + " hybrid_api_reservation r JOIN hybrid_generation g ON g.id=r.generation_id WHERE"
                + " g.deadline_at<=CURRENT_TIMESTAMP)")
        .update();
  }

  public int releaseStoppedAiAttempt() {
    return jdbc.sql(
            "UPDATE ai_attempt SET"
                + " status='HYBRID_RELEASED',actual_usd=0,finished_at=CURRENT_TIMESTAMP WHERE"
                + " status='HYBRID_RESERVED' AND id IN (SELECT r.attempt_id FROM"
                + " hybrid_api_reservation r JOIN hybrid_generation g ON g.id=r.generation_id WHERE"
                + " (g.status IN ('FAILED','CANCELLED','DEADLINE_EXCEEDED','PUBLISHED') OR"
                + " (g.status='HELD' AND (g.error_code IS NULL OR g.error_code NOT IN"
                + " ('VALIDATION_ADAPTER_NOT_CONNECTED','CONTENT_REVIEW_REQUIRED')))) OR"
                + " g.revision<>r.revision)")
        .update();
  }
}
