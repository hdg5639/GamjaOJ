package dev.gamjaoj.generation.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence operations for HybridPublication; transaction ownership remains in the service. */
@Repository
public class HybridPublicationRepository {
  private final JdbcClient jdbc;

  public HybridPublicationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<String> requireReferenceQualifiedHybridBranch(UUID id, int revision) {
    return jdbc.sql(
            "SELECT completion_json FROM hybrid_branch WHERE generation_id=? AND revision=? AND"
                + " role='CORE' AND status='SUCCEEDED'")
        .param(id)
        .param(revision)
        .query(String.class)
        .optional();
  }

  public Optional<UUID> requireReferenceQualifiedHybridPublicRequest(UUID id) {
    return jdbc.sql("SELECT reference_artifact_id FROM hybrid_public_request WHERE generation_id=?")
        .param(id)
        .query(UUID.class)
        .optional();
  }

  public <T> T inputProblemVersion(String version, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT"
                + " package_json,package_sha256,teaching_json,runtime_image,runner_policy,owner_id,ready,review_hold,shared"
                + " FROM problem_version WHERE id=? FOR UPDATE")
        .param(version)
        .query(mapper)
        .single();
  }

  public <T> T inputHybridBranch(UUID argument0, RowMapper<T> mapper) {
    return jdbc.sql("SELECT input_sha256,completion_json FROM hybrid_branch WHERE id=?")
        .param(argument0)
        .query(mapper)
        .single();
  }

  public <T> List<T> inputHybridExecutionCheck(UUID argument0, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT"
                + " e.role,s.runtime_image,s.runner_policy,s.language,s.execution_profile_json,j.execution_mode,j.result_json"
                + " FROM hybrid_execution_check e JOIN submission s ON s.id=e.submission_id JOIN"
                + " judge_job j ON j.submission_id=s.id WHERE e.branch_id=? ORDER BY e.role")
        .param(argument0)
        .query(mapper)
        .list();
  }

  public String inputHybridValidationProfile(UUID argument0) {
    return jdbc.sql("SELECT scheduling FROM hybrid_validation_profile WHERE branch_id=?")
        .param(argument0)
        .query(String.class)
        .single();
  }

  public String inputHybridValidationProfile2(UUID argument0) {
    return jdbc.sql("SELECT policy FROM hybrid_validation_profile WHERE branch_id=?")
        .param(argument0)
        .query(String.class)
        .single();
  }

  public <T> Optional<T> inputHybridPublicRequest(UUID argument0, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT requirements_json,requirements_sha256 FROM hybrid_public_request WHERE"
                + " generation_id=?")
        .param(argument0)
        .query(mapper)
        .optional();
  }

  public String inputHybridGeneration(UUID argument0) {
    return jdbc.sql("SELECT request_json FROM hybrid_generation WHERE id=?")
        .param(argument0)
        .query(String.class)
        .single();
  }

  public Integer advanceAiBudgetLock() {
    return jdbc.sql("SELECT id FROM ai_budget_lock WHERE id=1 FOR UPDATE")
        .query(Integer.class)
        .single();
  }

  public List<UUID> advanceHybridGeneration() {
    return jdbc.sql(
            "SELECT id FROM hybrid_generation WHERE (status='HELD' AND"
                + " error_code='CONTENT_REVIEW_REQUIRED') OR status='REVIEWING'")
        .query(UUID.class)
        .list();
  }

  public <T> Optional<T> advanceHybridGeneration2(UUID id, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT g.owner_id,g.revision,g.share_on_publish,g.contract_sha256,g.public_sha256,b.id"
                + " FROM hybrid_generation g JOIN hybrid_branch b ON b.generation_id=g.id AND"
                + " b.revision=g.revision AND b.role='VALIDATION' AND b.status='CHECKED' WHERE"
                + " g.id=? AND g.deadline_at>CURRENT_TIMESTAMP FOR UPDATE")
        .param(id)
        .query(mapper)
        .optional();
  }

  public <T> Optional<T> advanceHybridBranch(UUID id, int argument1, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT id,status,input_json,input_sha256,output_sha256,attempt FROM hybrid_branch"
                + " WHERE generation_id=? AND revision=? AND role='CONTENT_REVIEW' ORDER BY attempt"
                + " DESC LIMIT 1")
        .param(id)
        .param(argument1)
        .query(mapper)
        .optional();
  }

  public Integer advanceHybridApiReservation(UUID id, int argument1) {
    return jdbc.sql(
            "SELECT COALESCE(MAX(r.retry),-1) FROM hybrid_api_reservation r JOIN ai_attempt a ON"
                + " a.id=r.attempt_id WHERE r.generation_id=? AND r.revision=? AND"
                + " r.role='CONTENT_REVIEW' AND a.status='HYBRID_RESERVED' AND r.branch_id IS NULL")
        .param(id)
        .param(argument1)
        .query(Integer.class)
        .single();
  }

  public Integer advanceHybridApiReservation2(UUID id, int argument1, int nextAttempt) {
    return jdbc.sql(
            "SELECT count(*) FROM hybrid_api_reservation r JOIN ai_attempt a ON a.id=r.attempt_id"
                + " WHERE r.generation_id=? AND r.revision=? AND r.role='CONTENT_REVIEW' AND"
                + " r.retry=? AND r.branch_id IS NULL AND a.status='HYBRID_RESERVED'")
        .param(id)
        .param(argument1)
        .param(nextAttempt)
        .query(Integer.class)
        .single();
  }

  public Integer advanceHybridApiReservation3(UUID id, int argument1) {
    return jdbc.sql(
            "SELECT count(*) FROM hybrid_api_reservation r JOIN ai_attempt a ON a.id=r.attempt_id"
                + " WHERE r.generation_id=? AND r.revision=? AND r.role='CONTENT_REVIEW' AND"
                + " a.status='HYBRID_RESERVED'")
        .param(id)
        .param(argument1)
        .query(Integer.class)
        .single();
  }

  public int advanceHybridBranch2(
      UUID argument0,
      UUID id,
      int argument2,
      int nextAttempt,
      String raw,
      String hash,
      String argument6,
      String argument7) {
    return jdbc.sql(
            "INSERT INTO"
                + " hybrid_branch(id,generation_id,revision,role,attempt,status,input_json,input_sha256,contract_sha256,public_sha256,created_at)"
                + " VALUES (?,?,?,'CONTENT_REVIEW',?,'QUEUED',?,?,?,?,CURRENT_TIMESTAMP)")
        .param(argument0)
        .param(id)
        .param(argument2)
        .param(nextAttempt)
        .param(raw)
        .param(hash)
        .param(argument6)
        .param(argument7)
        .update();
  }

  public int advanceHybridGeneration3(UUID id) {
    return jdbc.sql(
            "UPDATE hybrid_generation SET"
                + " status='REVIEWING',error_code=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?")
        .param(id)
        .update();
  }

  public Integer advanceHybridApiReservation4(UUID argument0, UUID id, int argument2) {
    return jdbc.sql(
            "SELECT count(*) FROM hybrid_api_reservation r JOIN ai_attempt a ON a.id=r.attempt_id"
                + " WHERE r.branch_id=? AND r.generation_id=? AND r.revision=? AND"
                + " r.role='CONTENT_REVIEW' AND r.receipt_json IS NOT NULL AND"
                + " a.status='HYBRID_COMPLETED'")
        .param(argument0)
        .param(id)
        .param(argument2)
        .query(Integer.class)
        .single();
  }

  public <T> T advanceHybridArtifact(UUID argument0, RowMapper<T> mapper) {
    return jdbc.sql("SELECT payload_json,payload_sha256 FROM hybrid_artifact WHERE branch_id=?")
        .param(argument0)
        .query(mapper)
        .single();
  }

  public OffsetDateTime advanceHybridGeneration4(UUID id) {
    return jdbc.sql("SELECT deadline_at FROM hybrid_generation WHERE id=?")
        .param(id)
        .query(OffsetDateTime.class)
        .single();
  }

  public String advanceHybridArtifact2(UUID id, int argument1) {
    return jdbc.sql(
            "SELECT a.payload_json FROM hybrid_artifact a JOIN hybrid_branch b ON b.id=a.branch_id"
                + " WHERE b.generation_id=? AND b.revision=? AND b.role='CORE' AND"
                + " b.status='SUCCEEDED' ORDER BY b.attempt DESC LIMIT 1")
        .param(id)
        .param(argument1)
        .query(String.class)
        .single();
  }

  public int advanceProblemVersion(boolean argument0, String limits, String version) {
    return jdbc.sql(
            "UPDATE problem_version SET ready=true,shared=?,time_limits_json=? WHERE id=? AND"
                + " ready=false")
        .param(argument0)
        .param(limits)
        .param(version)
        .update();
  }

  public Optional<String> advanceHybridPublicRequest(UUID id) {
    return jdbc.sql("SELECT rule_version_id FROM hybrid_public_request WHERE generation_id=?")
        .param(id)
        .query(String.class)
        .optional();
  }

  public String advanceHybridValidationProfile(UUID argument0) {
    return jdbc.sql("SELECT policy FROM hybrid_validation_profile WHERE branch_id=?")
        .param(argument0)
        .query(String.class)
        .single();
  }

  public int advanceProblemVersion2(String argument0, String argument1, String version) {
    return jdbc.sql("UPDATE problem_version SET catalog_category=?,catalog_tags=? WHERE id=?")
        .param(argument0)
        .param(argument1)
        .param(version)
        .update();
  }

  public Optional<String> advanceHybridRuleOnboarding(String argument0) {
    return jdbc.sql("SELECT request_json FROM hybrid_rule_onboarding WHERE version_id=?")
        .param(argument0)
        .query(String.class)
        .optional();
  }

  public int advanceProblemVersion3(String d, String version) {
    return jdbc.sql("UPDATE problem_version SET catalog_difficulty=? WHERE id=?")
        .param(d)
        .param(version)
        .update();
  }

  public int advanceHybridGeneration5(String version, UUID id) {
    return jdbc.sql(
            "UPDATE hybrid_generation SET"
                + " status='PUBLISHED',error_code=NULL,published_version_id=?,updated_at=CURRENT_TIMESTAMP"
                + " WHERE id=?")
        .param(version)
        .param(id)
        .update();
  }

  public int advanceHybridGeneration6(Object argument0, UUID id) {
    return jdbc.sql(
            "UPDATE hybrid_generation SET status='HELD',error_code=?,updated_at=CURRENT_TIMESTAMP"
                + " WHERE id=?")
        .param(argument0)
        .param(id)
        .update();
  }
}
