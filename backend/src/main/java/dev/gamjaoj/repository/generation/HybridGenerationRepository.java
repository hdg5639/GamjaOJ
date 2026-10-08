package dev.gamjaoj.repository.generation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence operations for HybridGeneration; transaction ownership remains in the service. */
@Repository
public class HybridGenerationRepository {
  private final JdbcClient jdbc;

  public HybridGenerationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public <T> Optional<T> jobHybridGeneration(boolean lock, UUID id, RowMapper<T> mapper) {
    return jdbc.sql("SELECT * FROM hybrid_generation WHERE id=?" + (lock ? " FOR UPDATE" : ""))
        .param(id)
        .query(mapper)
        .optional();
  }

  public <T> List<T> branchesHybridBranch(UUID id, int revision, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT * FROM hybrid_branch WHERE generation_id=? AND revision=? ORDER BY"
                + " role,attempt")
        .param(id)
        .param(revision)
        .query(mapper)
        .list();
  }

  public <T> Optional<T> branchHybridBranch(UUID branchId, RowMapper<T> mapper) {
    return jdbc.sql("SELECT generation_id,revision FROM hybrid_branch WHERE id=?")
        .param(branchId)
        .query(mapper)
        .optional();
  }

  public Integer budgetLockAiBudgetLock() {
    return jdbc.sql("SELECT id FROM ai_budget_lock WHERE id=1 FOR UPDATE")
        .query(Integer.class)
        .single();
  }

  public int statusHybridGeneration(String value, String error, OffsetDateTime argument2, UUID id) {
    return jdbc.sql("UPDATE hybrid_generation SET status=?,error_code=?,updated_at=? WHERE id=?")
        .param(value)
        .param(error)
        .param(argument2)
        .param(id)
        .update();
  }

  public int statusAiAttempt(UUID id) {
    return jdbc.sql(
            "UPDATE ai_attempt SET"
                + " status='HYBRID_RELEASED',actual_usd=0,finished_at=CURRENT_TIMESTAMP WHERE"
                + " status='HYBRID_RESERVED' AND id IN (SELECT attempt_id FROM"
                + " hybrid_api_reservation WHERE generation_id=?)")
        .param(id)
        .update();
  }

  public int cancelPendingHybridBranch(
      OffsetDateTime argument0, UUID id, int revision, String argument3) {
    return jdbc.sql(
            "UPDATE hybrid_branch SET"
                + " status='CANCELLED',error_code='SUPERSEDED_OR_STOPPED',finished_at=? WHERE"
                + " generation_id=? AND revision=? AND role=? AND status IN"
                + " ('QUEUED','RUNNING','BLOCKED','EARLY')")
        .param(argument0)
        .param(id)
        .param(revision)
        .param(argument3)
        .update();
  }

  public Integer enqueueHybridApiReservation(UUID argument0, int argument1, String argument2) {
    return jdbc.sql(
            "SELECT COALESCE(MAX(r.retry),-1) FROM hybrid_api_reservation r JOIN ai_attempt a ON"
                + " a.id=r.attempt_id WHERE r.generation_id=? AND r.revision=? AND r.role=? AND"
                + " a.status='HYBRID_RESERVED' AND r.branch_id IS NULL")
        .param(argument0)
        .param(argument1)
        .param(argument2)
        .query(Integer.class)
        .single();
  }

  public int enqueueHybridBranch(
      UUID id,
      UUID argument1,
      int argument2,
      String argument3,
      int attempt,
      String state,
      String payload,
      String argument7,
      String argument8,
      Object argument9,
      OffsetDateTime argument10) {
    return jdbc.sql(
            "INSERT INTO"
                + " hybrid_branch(id,generation_id,revision,role,attempt,status,input_json,input_sha256,contract_sha256,public_sha256,created_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?)")
        .param(id)
        .param(argument1)
        .param(argument2)
        .param(argument3)
        .param(attempt)
        .param(state)
        .param(payload)
        .param(argument7)
        .param(argument8)
        .param(argument9)
        .param(argument10)
        .update();
  }

  public Integer startAiBudgetLock() {
    return jdbc.sql("SELECT id FROM ai_budget_lock WHERE id=1 FOR UPDATE")
        .query(Integer.class)
        .single();
  }

  public Integer startHybridGeneration(UUID id) {
    return jdbc.sql("SELECT count(*) FROM hybrid_generation WHERE id=?")
        .param(id)
        .query(Integer.class)
        .single();
  }

  public String startHybridGeneration2(UUID id) {
    return jdbc.sql("SELECT request_sha256 FROM hybrid_generation WHERE id=?")
        .param(id)
        .query(String.class)
        .single();
  }

  public int startHybridGeneration3(
      UUID id,
      UUID owner,
      String argument2,
      String input,
      String argument4,
      boolean shared,
      OffsetDateTime accepted,
      OffsetDateTime argument7,
      OffsetDateTime acceptedArgument8) {
    return jdbc.sql(
            "INSERT INTO"
                + " hybrid_generation(id,owner_id,pipeline_version,request_json,request_sha256,status,share_on_publish,created_at,deadline_at,updated_at)"
                + " VALUES (?,?,?,?,?,'QUEUED',?,?,?,?)")
        .param(id)
        .param(owner)
        .param(argument2)
        .param(input)
        .param(argument4)
        .param(shared)
        .param(accepted)
        .param(argument7)
        .param(acceptedArgument8)
        .update();
  }

  public Optional<String> viewHybridGeneration(UUID id) {
    return jdbc.sql("SELECT published_version_id FROM hybrid_generation WHERE id=?")
        .param(id)
        .query(String.class)
        .optional();
  }

  public Integer viewProblemVersion(UUID id) {
    return jdbc.sql(
            "SELECT count(*) FROM problem_version WHERE id=(SELECT published_version_id FROM"
                + " hybrid_generation WHERE id=?) AND review_hold=true")
        .param(id)
        .query(Integer.class)
        .single();
  }

  public Optional<String> viewHybridPublicRequest(UUID id) {
    return jdbc.sql("SELECT profile_id FROM hybrid_public_request WHERE generation_id=?")
        .param(id)
        .query(String.class)
        .optional();
  }

  public Optional<String> viewHybridBranch(UUID id) {
    return jdbc.sql(
            "SELECT completion_json FROM hybrid_branch WHERE generation_id=? AND role='CORE' AND"
                + " status='SUCCEEDED' ORDER BY attempt DESC LIMIT 1")
        .param(id)
        .query(String.class)
        .optional();
  }

  public int claimHybridBranch(UUID token, OffsetDateTime argument1, UUID argument2) {
    return jdbc.sql(
            "UPDATE hybrid_branch SET status='RUNNING',attempt_token=?,started_at=? WHERE id=?")
        .param(token)
        .param(argument1)
        .param(argument2)
        .update();
  }

  public int completeHybridBranch(
      String completion, boolean late, OffsetDateTime argument2, UUID argument3) {
    return jdbc.sql(
            "UPDATE hybrid_branch SET completion_json=?,late_result=?,finished_at=? WHERE id=?")
        .param(completion)
        .param(late)
        .param(argument2)
        .param(argument3)
        .update();
  }

  public int completeHybridArtifact(
      UUID argument0, String argument1, String raw, String hash, OffsetDateTime argument4) {
    return jdbc.sql(
            "INSERT INTO"
                + " hybrid_artifact(branch_id,schema_version,prompt_version,payload_json,payload_sha256,created_at)"
                + " VALUES (?,'1',?,?,?,?)")
        .param(argument0)
        .param(argument1)
        .param(raw)
        .param(hash)
        .param(argument4)
        .update();
  }

  public int completeHybridBranch2(String hash, UUID argument1) {
    return jdbc.sql("UPDATE hybrid_branch SET status='SUCCEEDED',output_sha256=? WHERE id=?")
        .param(hash)
        .param(argument1)
        .update();
  }

  public int completeHybridGeneration(String hash, OffsetDateTime argument1, UUID argument2) {
    return jdbc.sql(
            "UPDATE hybrid_generation SET contract_sha256=?,status='BUILDING',updated_at=? WHERE"
                + " id=?")
        .param(hash)
        .param(argument1)
        .param(argument2)
        .update();
  }

  public Optional<String> completeHybridPublicRequest(UUID argument0) {
    return jdbc.sql("SELECT profile_id FROM hybrid_public_request WHERE generation_id=?")
        .param(argument0)
        .query(String.class)
        .optional();
  }

  public <T> Optional<T> completeHybridPublicRequest2(UUID argument0, RowMapper<T> mapper) {
    return jdbc.sql("SELECT requirements_json FROM hybrid_public_request WHERE generation_id=?")
        .param(argument0)
        .query(mapper)
        .optional();
  }

  public int completeHybridGeneration2(
      String publicHash, OffsetDateTime argument1, UUID argument2) {
    return jdbc.sql("UPDATE hybrid_generation SET public_sha256=?,updated_at=? WHERE id=?")
        .param(publicHash)
        .param(argument1)
        .param(argument2)
        .update();
  }

  public int completeHybridBranch3(String argument0, UUID argument1) {
    return jdbc.sql("UPDATE hybrid_branch SET status='FAILED',error_code=? WHERE id=?")
        .param(argument0)
        .param(argument1)
        .update();
  }

  public int rerouteHybridBranch(
      String completion, boolean late, OffsetDateTime argument2, UUID argument3) {
    return jdbc.sql(
            "UPDATE hybrid_branch SET completion_json=?,late_result=?,finished_at=? WHERE id=?")
        .param(completion)
        .param(late)
        .param(argument2)
        .param(argument3)
        .update();
  }

  public int rerouteHybridBranch2(UUID argument0) {
    return jdbc.sql(
            "UPDATE hybrid_branch SET status='FAILED',error_code='CODEX_QUOTA_EXHAUSTED' WHERE"
                + " id=?")
        .param(argument0)
        .update();
  }

  public <T> T artifactHybridArtifact(UUID argument0, RowMapper<T> mapper) {
    return jdbc.sql("SELECT payload_json,payload_sha256 FROM hybrid_artifact WHERE branch_id=?")
        .param(argument0)
        .query(mapper)
        .single();
  }

  public int joinHybridBranch(String raw, String argument1, String argument2, UUID argument3) {
    return jdbc.sql(
            "UPDATE hybrid_branch SET status='BLOCKED',input_json=?,input_sha256=?,public_sha256=?"
                + " WHERE id=? AND status='EARLY'")
        .param(raw)
        .param(argument1)
        .param(argument2)
        .param(argument3)
        .update();
  }

  public Integer repairHybridApiReservation(UUID id) {
    return jdbc.sql("SELECT count(*) FROM hybrid_api_reservation WHERE generation_id=?")
        .param(id)
        .query(Integer.class)
        .single();
  }

  public int repairHybridGeneration(OffsetDateTime argument0, UUID id) {
    return jdbc.sql(
            "UPDATE hybrid_generation SET"
                + " repair_rounds=1,status='BUILDING',error_code=NULL,updated_at=? WHERE id=?")
        .param(argument0)
        .param(id)
        .update();
  }

  public int repairHybridBranch(UUID argument0) {
    return jdbc.sql("UPDATE hybrid_branch SET status='SUPERSEDED' WHERE id=?")
        .param(argument0)
        .update();
  }

  public int repairHybridGeneration2(UUID id) {
    return jdbc.sql("UPDATE hybrid_generation SET public_sha256=NULL WHERE id=?")
        .param(id)
        .update();
  }

  public int recoverStageHybridBranch(UUID argument0) {
    return jdbc.sql("UPDATE hybrid_branch SET status='SUPERSEDED' WHERE id=?")
        .param(argument0)
        .update();
  }

  public int recoverStageHybridGeneration(OffsetDateTime argument0, OffsetDateTime now, UUID id) {
    return jdbc.sql(
            "UPDATE hybrid_generation SET"
                + " status='BUILDING',error_code=NULL,deadline_at=?,updated_at=? WHERE id=?")
        .param(argument0)
        .param(now)
        .param(id)
        .update();
  }

  public int recoverStageHybridGeneration2(UUID id) {
    return jdbc.sql("UPDATE hybrid_generation SET public_sha256=NULL WHERE id=?")
        .param(id)
        .update();
  }

  public Integer reviseContractHybridApiReservation(UUID id) {
    return jdbc.sql("SELECT count(*) FROM hybrid_api_reservation WHERE generation_id=?")
        .param(id)
        .query(Integer.class)
        .single();
  }

  public int reviseContractHybridGeneration(OffsetDateTime argument0, UUID id) {
    return jdbc.sql(
            "UPDATE hybrid_generation SET"
                + " revision=revision+1,repair_rounds=1,status='QUEUED',contract_sha256=NULL,public_sha256=NULL,error_code=NULL,updated_at=?"
                + " WHERE id=?")
        .param(argument0)
        .param(id)
        .update();
  }

  public List<UUID> expirePendingHybridGeneration(OffsetDateTime argument0) {
    return jdbc.sql(
            "SELECT id FROM hybrid_generation WHERE deadline_at<=? AND (status IN"
                + " ('QUEUED','DESIGNING','BUILDING','VALIDATING','REVIEWING') OR (status='HELD'"
                + " AND error_code IN"
                + " ('VALIDATION_ADAPTER_NOT_CONNECTED','CONTENT_REVIEW_REQUIRED')))")
        .param(argument0)
        .query(UUID.class)
        .list();
  }
}
