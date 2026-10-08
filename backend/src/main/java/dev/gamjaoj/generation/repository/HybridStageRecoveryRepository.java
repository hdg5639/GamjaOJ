package dev.gamjaoj.generation.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence operations for HybridStageRecovery; transaction ownership remains in the service. */
@Repository
public class HybridStageRecoveryRepository {
  private final JdbcClient jdbc;

  public HybridStageRecoveryRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<UUID> advanceHybridGeneration() {
    return jdbc.sql(
            "SELECT id FROM hybrid_generation WHERE resource_validation=true AND status='HELD' AND"
                + " error_code NOT IN"
                + " ('VALIDATION_ADAPTER_NOT_CONNECTED','CONTENT_REVIEW_REQUIRED') AND"
                + " deadline_at>CURRENT_TIMESTAMP ORDER BY updated_at LIMIT 20 FOR UPDATE")
        .query(UUID.class)
        .list();
  }

  public Integer advanceDiagnosticPracticePlan(UUID id) {
    return jdbc.sql(
            "SELECT count(*) FROM diagnostic_practice_plan p WHERE p.hybrid_generation_id=? AND"
                + " (p.training_session_id IS NOT NULL OR EXISTS (SELECT 1 FROM"
                + " learning_curriculum_end e WHERE e.evaluation_id=p.evaluation_id AND"
                + " e.user_id=p.user_id) OR EXISTS (SELECT 1 FROM diagnostic_practice_plan n WHERE"
                + " n.previous_plan_id=p.id))")
        .param(id)
        .query(Integer.class)
        .single();
  }

  public Integer advanceHybridApiReservation(UUID id) {
    return jdbc.sql(
            "SELECT count(*) FROM hybrid_api_reservation r JOIN ai_attempt a ON a.id=r.attempt_id"
                + " WHERE r.generation_id=? AND (a.status IN ('HYBRID_RUNNING','HYBRID_UNKNOWN') OR"
                + " (a.status IN ('HYBRID_COMPLETED','HYBRID_FAILED') AND a.actual_usd IS NULL))")
        .param(id)
        .query(Integer.class)
        .single();
  }

  public UUID advanceHybridGeneration2(UUID id) {
    return jdbc.sql("SELECT owner_id FROM hybrid_generation WHERE id=?")
        .param(id)
        .query(UUID.class)
        .single();
  }

  public Integer advanceGenerationSpecDraft(UUID owner) {
    return jdbc.sql(
            "SELECT count(*) FROM generation_spec_draft WHERE owner_id=? AND status IN"
                + " ('QUEUED','GENERATING','BUILD_QUEUED','BUILD_GENERATING','CHECKING','REVIEW_QUEUED','REVIEW_GENERATING','REVIEW_CHECKING','FINAL_QUEUED','FINAL_GENERATING','FINAL_CHECKING')")
        .param(owner)
        .query(Integer.class)
        .single();
  }

  public Integer advanceHybridGeneration3(UUID owner, UUID id) {
    return jdbc.sql(
            "SELECT count(*) FROM hybrid_generation WHERE owner_id=? AND id<>? AND status IN"
                + " ('QUEUED','DESIGNING','BUILDING','VALIDATING','REVIEWING')")
        .param(owner)
        .param(id)
        .query(Integer.class)
        .single();
  }

  public <T> Optional<T> advanceHybridBranch(UUID id, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT role,error_code,completion_json FROM hybrid_branch WHERE generation_id=? AND"
                + " status='FAILED' AND late_result=false ORDER BY finished_at DESC LIMIT 1")
        .param(id)
        .query(mapper)
        .optional();
  }

  public String advanceHybridGeneration4(UUID id) {
    return jdbc.sql("SELECT error_code FROM hybrid_generation WHERE id=?")
        .param(id)
        .query(String.class)
        .single();
  }

  public Integer advanceGenerationRecoveryAttempt(UUID id) {
    return jdbc.sql(
            "SELECT count(*) FROM generation_recovery_attempt WHERE pipeline='RULE' AND job_id=?")
        .param(id)
        .query(Integer.class)
        .single();
  }

  public Integer advanceHybridBranch2(UUID id, String argument1) {
    return jdbc.sql("SELECT count(*) FROM hybrid_branch WHERE generation_id=? AND role=?")
        .param(id)
        .param(argument1)
        .query(Integer.class)
        .single();
  }

  public int advanceHybridGeneration5(UUID id) {
    return jdbc.sql("UPDATE hybrid_generation SET error_code='STAGE_RETRY_LIMIT' WHERE id=?")
        .param(id)
        .update();
  }

  public Integer advanceHybridGeneration6(UUID id) {
    return jdbc.sql("SELECT revision FROM hybrid_generation WHERE id=?")
        .param(id)
        .query(Integer.class)
        .single();
  }

  public Integer advanceHybridApiReservation2(UUID id, int revision, String argument2) {
    return jdbc.sql(
            "SELECT COALESCE(MAX(retry),-1)+1 FROM hybrid_api_reservation WHERE generation_id=? AND"
                + " revision=? AND role=?")
        .param(id)
        .param(revision)
        .param(argument2)
        .query(Integer.class)
        .single();
  }

  public int advanceAiAttempt(
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

  public int advanceHybridApiReservation3(
      UUID attempt, UUID id, int revision, String argument3, int next) {
    return jdbc.sql(
            "INSERT INTO hybrid_api_reservation(attempt_id,generation_id,revision,role,retry)"
                + " VALUES (?,?,?,?,?)")
        .param(attempt)
        .param(id)
        .param(revision)
        .param(argument3)
        .param(next)
        .update();
  }

  public int advanceGenerationRecoveryAttempt2(
      UUID id, int argument1, String argument2, String argument3, String argument4) {
    return jdbc.sql(
            "INSERT INTO"
                + " generation_recovery_attempt(pipeline,job_id,attempt,scope,error_code,snapshot_json)"
                + " VALUES ('RULE',?,?,?,?,?)")
        .param(id)
        .param(argument1)
        .param(argument2)
        .param(argument3)
        .param(argument4)
        .update();
  }
}
