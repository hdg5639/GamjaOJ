package dev.gamjaoj.repository.generation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence operations for GenerationDraftRecovery; transaction ownership remains in the service.
 */
@Repository
public class GenerationDraftRecoveryRepository {
  private final JdbcClient jdbc;

  public GenerationDraftRecoveryRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<UUID> advanceGenerationSpecDraft() {
    return jdbc.sql(
            "SELECT id FROM generation_spec_draft WHERE auto_recovery=true AND status IN"
                + " ('FAILED','BUILD_FAILED','REVIEW_FAILED','REVIEW_REJECTED','FINAL_FAILED','FINAL_REJECTED')"
                + " ORDER BY updated_at LIMIT 30 FOR UPDATE")
        .query(UUID.class)
        .list();
  }

  public Integer recoverDiagnosticPracticePlan(UUID id) {
    return jdbc.sql(
            "SELECT count(*) FROM diagnostic_practice_plan p WHERE p.generation_id=? AND"
                + " (p.training_session_id IS NOT NULL OR EXISTS (SELECT 1 FROM"
                + " learning_curriculum_end e WHERE e.evaluation_id=p.evaluation_id AND"
                + " e.user_id=p.user_id) OR EXISTS (SELECT 1 FROM diagnostic_practice_plan n WHERE"
                + " n.previous_plan_id=p.id))")
        .param(id)
        .query(Integer.class)
        .single();
  }

  public UUID recoverGenerationSpecDraft(UUID id) {
    return jdbc.sql("SELECT owner_id FROM generation_spec_draft WHERE id=?")
        .param(id)
        .query(UUID.class)
        .single();
  }

  public Integer recoverGenerationSpecDraft2(UUID owner, UUID id) {
    return jdbc.sql(
            "SELECT count(*) FROM generation_spec_draft WHERE owner_id=? AND id<>? AND status IN"
                + " ('QUEUED','GENERATING','BUILD_QUEUED','BUILD_GENERATING','CHECKING','REVIEW_QUEUED','REVIEW_GENERATING','REVIEW_CHECKING','FINAL_QUEUED','FINAL_GENERATING','FINAL_CHECKING')")
        .param(owner)
        .param(id)
        .query(Integer.class)
        .single();
  }

  public Integer recoverGenerationJob(UUID owner) {
    return jdbc.sql(
            "SELECT count(*) FROM generation_job WHERE owner_id=? AND status IN"
                + " ('QUEUED','GENERATING','AWAITING_REVIEW','VALIDATING')")
        .param(owner)
        .query(Integer.class)
        .single();
  }

  public <T> T recoverGenerationSpecDraft3(UUID id, RowMapper<T> mapper) {
    return jdbc.sql("SELECT * FROM generation_spec_draft WHERE id=?")
        .param(id)
        .query(mapper)
        .single();
  }

  public int recoverGenerationSpecDraft4(UUID id) {
    return jdbc.sql("UPDATE generation_spec_draft SET auto_recovery=false WHERE id=?")
        .param(id)
        .update();
  }

  public Integer recoverGenerationRecoveryAttempt(UUID id) {
    return jdbc.sql(
            "SELECT count(*) FROM generation_recovery_attempt WHERE pipeline='DIRECT' AND job_id=?")
        .param(id)
        .query(Integer.class)
        .single();
  }

  public Integer recoverGenerationRecoveryAttempt2(UUID id, String argument1) {
    return jdbc.sql(
            "SELECT count(*) FROM generation_recovery_attempt WHERE pipeline='DIRECT' AND job_id=?"
                + " AND scope=?")
        .param(id)
        .param(argument1)
        .query(Integer.class)
        .single();
  }

  public int recoverGenerationSpecDraft5(UUID id) {
    return jdbc.sql(
            "UPDATE generation_spec_draft SET auto_recovery=false,recovery_feedback='재시도 한도에 도달했어요."
                + " 저장된 실패와 검증 기록을 확인해 주세요.' WHERE id=?")
        .param(id)
        .update();
  }

  public <T> List<T> recoverGenerationSpecExecution(UUID id, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT role,submission_id,expected_verdict FROM generation_spec_execution WHERE"
                + " draft_id=? ORDER BY role")
        .param(id)
        .query(mapper)
        .list();
  }

  public int recoverGenerationRecoveryAttempt3(
      UUID id, int argument1, String argument2, String error, String argument4) {
    return jdbc.sql(
            "INSERT INTO"
                + " generation_recovery_attempt(pipeline,job_id,attempt,scope,error_code,snapshot_json)"
                + " VALUES ('DIRECT',?,?,?,?,?)")
        .param(id)
        .param(argument1)
        .param(argument2)
        .param(error)
        .param(argument4)
        .update();
  }

  public Integer recoverGenerationRecoveryReceipt(UUID id, UUID argument1) {
    return jdbc.sql("SELECT count(*) FROM generation_recovery_receipt WHERE job_id=? AND token=?")
        .param(id)
        .param(argument1)
        .query(Integer.class)
        .single();
  }

  public int recoverGenerationRecoveryReceipt2(UUID id, UUID argument1, String argument2) {
    return jdbc.sql(
            "INSERT INTO generation_recovery_receipt(job_id,token,completion_json) VALUES (?,?,?)")
        .param(id)
        .param(argument1)
        .param(argument2)
        .update();
  }

  public int recoverGenerationSpecExecution2(UUID id) {
    return jdbc.sql(
            "DELETE FROM generation_spec_execution WHERE draft_id=? AND role LIKE 'final-%'")
        .param(id)
        .update();
  }

  public int recoverGenerationSpecExecution3(UUID id) {
    return jdbc.sql(
            "DELETE FROM generation_spec_execution WHERE draft_id=? AND (role LIKE 'review-%' OR"
                + " role LIKE 'final-%')")
        .param(id)
        .update();
  }

  public int recoverGenerationSpecExecution4(UUID id) {
    return jdbc.sql("DELETE FROM generation_spec_execution WHERE draft_id=?").param(id).update();
  }

  public int recoverGenerationSpecDraft6(
      List<String> clearedColumns,
      String state,
      String argument2,
      String feedback,
      OffsetDateTime argument4,
      UUID id) {
    return jdbc.sql(
            "UPDATE generation_spec_draft SET "
                + String.join(",", clearedColumns.stream().map(k -> k + "=NULL").toList())
                + ",final_stage=0,status=?,recovery_scope=?,recovery_feedback=?,retry_after=?,error_code=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP"
                + " WHERE id=?")
        .param(state)
        .param(argument2)
        .param(feedback)
        .param(argument4)
        .param(id)
        .update();
  }

  public Integer progressGenerationRecoveryAttempt(String pipeline, UUID id) {
    return jdbc.sql(
            "SELECT count(*) FROM generation_recovery_attempt WHERE pipeline=? AND job_id=?")
        .param(pipeline)
        .param(id)
        .query(Integer.class)
        .single();
  }

  public String progressGenerationRecoveryAttempt2(String pipeline, UUID id) {
    return jdbc.sql(
            "SELECT scope FROM generation_recovery_attempt WHERE pipeline=? AND job_id=? ORDER BY"
                + " attempt DESC LIMIT 1")
        .param(pipeline)
        .param(id)
        .query(String.class)
        .single();
  }

  public Optional<String> receiptGenerationRecoveryReceipt(UUID id, UUID token) {
    return jdbc.sql(
            "SELECT completion_json FROM generation_recovery_receipt WHERE job_id=? AND token=?")
        .param(id)
        .param(token)
        .query(String.class)
        .optional();
  }
}
