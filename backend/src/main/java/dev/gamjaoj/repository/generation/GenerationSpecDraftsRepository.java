package dev.gamjaoj.repository.generation;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence operations for GenerationSpecDrafts; transaction ownership remains in the service.
 */
@Repository
public class GenerationSpecDraftsRepository {
  private final JdbcClient jdbc;

  public GenerationSpecDraftsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Integer lockAiBudgetLock() {
    return jdbc.sql("SELECT id FROM ai_budget_lock WHERE id=1 FOR UPDATE")
        .query(Integer.class)
        .single();
  }

  public Integer containsGenerationSpecDraft(UUID id) {
    return jdbc.sql("SELECT count(*) FROM generation_spec_draft WHERE id=?")
        .param(id)
        .query(Integer.class)
        .single();
  }

  public Integer activeGenerationSpecDraft(UUID owner) {
    return jdbc.sql(
            "SELECT count(*) FROM generation_spec_draft WHERE owner_id=? AND status IN"
                + " ('QUEUED','GENERATING','BUILD_QUEUED','BUILD_GENERATING','CHECKING','REVIEW_QUEUED','REVIEW_GENERATING','REVIEW_CHECKING','FINAL_QUEUED','FINAL_GENERATING','FINAL_CHECKING')")
        .param(owner)
        .query(Integer.class)
        .single();
  }

  public int expireGenerationSpecDraft() {
    return jdbc.sql(
            "UPDATE generation_spec_draft SET"
                + " status='NEEDS_REVIEW',error_code='GENERATION_INTERRUPTED',updated_at=CURRENT_TIMESTAMP"
                + " WHERE status IN"
                + " ('GENERATING','BUILD_GENERATING','REVIEW_GENERATING','FINAL_GENERATING') AND"
                + " lease_until<CURRENT_TIMESTAMP")
        .update();
  }

  public Integer runningGenerationSpecDraft() {
    return jdbc.sql(
            "SELECT count(*) FROM generation_spec_draft WHERE status IN"
                + " ('GENERATING','BUILD_GENERATING','REVIEW_GENERATING','FINAL_GENERATING')")
        .query(Integer.class)
        .single();
  }

  public Boolean createGenerationSpecDraft(UUID id) {
    return jdbc.sql("SELECT share_on_publish FROM generation_spec_draft WHERE id=?")
        .param(id)
        .query(Boolean.class)
        .single();
  }

  public Integer createGenerationJob(UUID id) {
    return jdbc.sql("SELECT count(*) FROM generation_job WHERE id=?")
        .param(id)
        .query(Integer.class)
        .single();
  }

  public Integer createGenerationJob2(UUID owner) {
    return jdbc.sql(
            "SELECT count(*) FROM generation_job WHERE owner_id=? AND status IN"
                + " ('QUEUED','GENERATING','AWAITING_REVIEW','VALIDATING')")
        .param(owner)
        .query(Integer.class)
        .single();
  }

  public int createGenerationSpecDraft2(
      UUID id, UUID owner, String request, String model, String effort) {
    return jdbc.sql(
            "INSERT INTO generation_spec_draft (id,owner_id,request_text,status,model,effort)"
                + " VALUES (?,?,?,'QUEUED',?,?)")
        .param(id)
        .param(owner)
        .param(request)
        .param(model)
        .param(effort)
        .update();
  }

  public int createGenerationSpecDraft3(boolean shared, UUID id) {
    return jdbc.sql(
            "UPDATE generation_spec_draft SET"
                + " resource_validation=true,auto_recovery=true,share_on_publish=? WHERE id=?")
        .param(shared)
        .param(id)
        .update();
  }

  public Stream<UUID> listGenerationSpecDraft(UUID owner) {
    return jdbc
        .sql(
            "SELECT id FROM generation_spec_draft WHERE owner_id=? ORDER BY created_at DESC,id"
                + " LIMIT 30")
        .param(owner)
        .query(UUID.class)
        .list()
        .stream();
  }

  public <T> Optional<T> viewGenerationSpecDraft(UUID id, UUID argument1, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT d.*,p.review_hold,p.review_reason FROM generation_spec_draft d LEFT JOIN"
                + " problem_version p ON p.id=CONCAT('experimental-check-',CAST(d.id AS"
                + " VARCHAR(36))) WHERE d.id=? AND d.owner_id=?")
        .param(id)
        .param(argument1)
        .query(mapper)
        .optional();
  }

  public <T> Optional<T> claimGenerationSpecDraft(RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT id,request_text,model,effort,status,spec_json FROM generation_spec_draft WHERE"
                + " status IN ('QUEUED','BUILD_QUEUED','REVIEW_QUEUED','FINAL_QUEUED') AND"
                + " (retry_after IS NULL OR retry_after<=CURRENT_TIMESTAMP) ORDER BY created_at,id"
                + " LIMIT 1 FOR UPDATE")
        .query(mapper)
        .optional();
  }

  public int claimGenerationSpecDraft2(
      boolean finish,
      boolean review,
      boolean build,
      UUID token,
      OffsetDateTime argument4,
      UUID id) {
    return jdbc.sql(
            "UPDATE generation_spec_draft SET status='"
                + (finish
                    ? "FINAL_GENERATING"
                    : review ? "REVIEW_GENERATING" : build ? "BUILD_GENERATING" : "GENERATING")
                + "',"
                + (finish
                    ? "final_token"
                    : review ? "review_token" : build ? "build_token" : "token")
                + "=?,lease_until=?,updated_at=CURRENT_TIMESTAMP WHERE id=?")
        .param(token)
        .param(argument4)
        .param(id)
        .update();
  }

  public Stream<String> claimGenerationSpecExecution(UUID id) {
    return jdbc
        .sql(
            "SELECT j.result_json FROM generation_spec_execution e JOIN judge_job j ON"
                + " j.submission_id=e.submission_id WHERE e.draft_id=? AND e.role LIKE"
                + " 'reference-%' AND j.status='FINISHED'")
        .param(id)
        .query(String.class)
        .list()
        .stream();
  }

  public int claimGenerationSpecDraft3(UUID id) {
    return jdbc.sql(
            "UPDATE generation_spec_draft SET review_requirements=true,review_thinking=true WHERE"
                + " id=?")
        .param(id)
        .update();
  }

  public Optional<String> claimGenerationSpecDraft4(UUID id) {
    return jdbc.sql("SELECT recovery_scope FROM generation_spec_draft WHERE id=?")
        .param(id)
        .query(String.class)
        .optional();
  }

  public Optional<String> claimGenerationSpecDraft5(UUID id) {
    return jdbc.sql("SELECT recovery_feedback FROM generation_spec_draft WHERE id=?")
        .param(id)
        .query(String.class)
        .optional();
  }

  public <T> T claimGenerationSpecDraft6(UUID id, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT build_artifacts_json,build_oracle_json FROM generation_spec_draft WHERE id=?")
        .param(id)
        .query(mapper)
        .single();
  }

  public Boolean recoveryEnabledGenerationSpecDraft(UUID id) {
    return jdbc.sql("SELECT auto_recovery FROM generation_spec_draft WHERE id=?")
        .param(id)
        .query(Boolean.class)
        .single();
  }

  public Integer completeGenerationSpecDraft(UUID id, UUID token) {
    return jdbc.sql("SELECT count(*) FROM generation_spec_draft WHERE id=? AND final_token=?")
        .param(id)
        .param(token)
        .query(Integer.class)
        .single();
  }

  public Integer completeGenerationSpecDraft2(UUID id, UUID token) {
    return jdbc.sql("SELECT count(*) FROM generation_spec_draft WHERE id=? AND review_token=?")
        .param(id)
        .param(token)
        .query(Integer.class)
        .single();
  }

  public Integer completeGenerationSpecDraft3(UUID id, UUID token) {
    return jdbc.sql("SELECT count(*) FROM generation_spec_draft WHERE id=? AND build_token=?")
        .param(id)
        .param(token)
        .query(Integer.class)
        .single();
  }

  public <T> T completeGenerationSpecDraft4(UUID id, RowMapper<T> mapper) {
    return jdbc.sql("SELECT token,status,completion_json FROM generation_spec_draft WHERE id=?")
        .param(id)
        .query(mapper)
        .single();
  }

  public Integer completeGenerationSpecDraft5(UUID id) {
    return jdbc.sql(
            "SELECT count(*) FROM generation_spec_draft WHERE id=? AND"
                + " lease_until>CURRENT_TIMESTAMP")
        .param(id)
        .query(Integer.class)
        .single();
  }

  public Optional<String> completeGenerationSpecDraft6(UUID id) {
    return jdbc.sql("SELECT recovery_scope FROM generation_spec_draft WHERE id=?")
        .param(id)
        .query(String.class)
        .optional();
  }

  public int completeGenerationSpecDraft7(
      String state, String spec, Object argument2, String audit, String failure, UUID id) {
    return jdbc.sql(
            "UPDATE generation_spec_draft SET"
                + " status=?,spec_json=?,spec_sha256=?,completion_json=?,error_code=?,updated_at=CURRENT_TIMESTAMP"
                + " WHERE id=?")
        .param(state)
        .param(spec)
        .param(argument2)
        .param(audit)
        .param(failure)
        .param(id)
        .update();
  }

  public Integer buildGenerationJob(UUID owner) {
    return jdbc.sql(
            "SELECT count(*) FROM generation_job WHERE owner_id=? AND status IN"
                + " ('QUEUED','GENERATING','AWAITING_REVIEW','VALIDATING')")
        .param(owner)
        .query(Integer.class)
        .single();
  }

  public int buildGenerationSpecDraft(UUID id) {
    return jdbc.sql(
            "UPDATE generation_spec_draft SET"
                + " status='BUILD_QUEUED',error_code=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?")
        .param(id)
        .update();
  }

  public Optional<String> completeBuildGenerationSpecDraft(UUID id) {
    return jdbc.sql("SELECT build_completion_json FROM generation_spec_draft WHERE id=?")
        .param(id)
        .query(String.class)
        .optional();
  }

  public Integer completeBuildGenerationSpecDraft2(UUID id) {
    return jdbc.sql(
            "SELECT count(*) FROM generation_spec_draft WHERE id=? AND status='BUILD_GENERATING'"
                + " AND lease_until>CURRENT_TIMESTAMP")
        .param(id)
        .query(Integer.class)
        .single();
  }

  public Optional<String> completeBuildGenerationSpecDraft3(UUID id) {
    return jdbc.sql("SELECT recovery_scope FROM generation_spec_draft WHERE id=?")
        .param(id)
        .query(String.class)
        .optional();
  }

  public String completeBuildGenerationSpecDraft4(UUID id) {
    return jdbc.sql("SELECT build_artifacts_json FROM generation_spec_draft WHERE id=?")
        .param(id)
        .query(String.class)
        .single();
  }

  public String completeBuildGenerationSpecDraft5(UUID id) {
    return jdbc.sql("SELECT build_oracle_json FROM generation_spec_draft WHERE id=?")
        .param(id)
        .query(String.class)
        .single();
  }

  public int completeBuildGenerationSpecDraft6(
      String audit, Object argument1, String failure, UUID id) {
    return jdbc.sql(
            "UPDATE generation_spec_draft SET"
                + " build_completion_json=?,status=?,error_code=?,updated_at=CURRENT_TIMESTAMP"
                + " WHERE id=?")
        .param(audit)
        .param(argument1)
        .param(failure)
        .param(id)
        .update();
  }

  public int completeBuildGenerationSpecDraft7(
      String code, String independent, String argument2, UUID id) {
    return jdbc.sql(
            "UPDATE generation_spec_draft SET"
                + " build_artifacts_json=?,build_oracle_json=?,build_sha256=? WHERE id=?")
        .param(code)
        .param(independent)
        .param(argument2)
        .param(id)
        .update();
  }

  public int completeBuildGenerationSpecDraft8(
      String code, String independent, String argument2, UUID id) {
    return jdbc.sql(
            "UPDATE generation_spec_draft SET"
                + " build_artifacts_json=?,build_oracle_json=?,build_sha256=? WHERE id=?")
        .param(code)
        .param(independent)
        .param(argument2)
        .param(id)
        .update();
  }

  public String completeBuildGenerationSpecDraft9(UUID id) {
    return jdbc.sql("SELECT spec_json FROM generation_spec_draft WHERE id=?")
        .param(id)
        .query(String.class)
        .single();
  }

  public Integer reviewGenerationJob(UUID owner) {
    return jdbc.sql(
            "SELECT count(*) FROM generation_job WHERE owner_id=? AND status IN"
                + " ('QUEUED','GENERATING','AWAITING_REVIEW','VALIDATING')")
        .param(owner)
        .query(Integer.class)
        .single();
  }

  public int reviewGenerationSpecDraft(UUID id) {
    return jdbc.sql(
            "UPDATE generation_spec_draft SET"
                + " status='REVIEW_QUEUED',error_code=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?")
        .param(id)
        .update();
  }

  public Optional<String> completeReviewGenerationSpecDraft(UUID id) {
    return jdbc.sql("SELECT review_completion_json FROM generation_spec_draft WHERE id=?")
        .param(id)
        .query(String.class)
        .optional();
  }

  public Integer completeReviewGenerationSpecDraft2(UUID id) {
    return jdbc.sql(
            "SELECT count(*) FROM generation_spec_draft WHERE id=? AND status='REVIEW_GENERATING'"
                + " AND lease_until>CURRENT_TIMESTAMP")
        .param(id)
        .query(Integer.class)
        .single();
  }

  public Boolean completeReviewGenerationSpecDraft3(UUID id) {
    return jdbc.sql("SELECT review_requirements FROM generation_spec_draft WHERE id=?")
        .param(id)
        .query(Boolean.class)
        .single();
  }

  public Boolean completeReviewGenerationSpecDraft4(UUID id) {
    return jdbc.sql("SELECT review_thinking FROM generation_spec_draft WHERE id=?")
        .param(id)
        .query(Boolean.class)
        .single();
  }

  public int completeReviewGenerationSpecDraft5(String audit, String failure, UUID id) {
    return jdbc.sql(
            "UPDATE generation_spec_draft SET"
                + " review_completion_json=?,error_code=?,status='REVIEW_FAILED',updated_at=CURRENT_TIMESTAMP"
                + " WHERE id=?")
        .param(audit)
        .param(failure)
        .param(id)
        .update();
  }

  public int completeReviewGenerationSpecDraft6(String payload, String argument1, UUID id) {
    return jdbc.sql(
            "UPDATE generation_spec_draft SET review_payload_json=?,review_payload_sha256=? WHERE"
                + " id=?")
        .param(payload)
        .param(argument1)
        .param(id)
        .update();
  }

  public Integer publishGenerationJob(UUID owner) {
    return jdbc.sql(
            "SELECT count(*) FROM generation_job WHERE owner_id=? AND status IN"
                + " ('QUEUED','GENERATING','AWAITING_REVIEW','VALIDATING')")
        .param(owner)
        .query(Integer.class)
        .single();
  }

  public int publishGenerationSpecDraft(UUID id) {
    return jdbc.sql(
            "UPDATE generation_spec_draft SET"
                + " status='FINAL_QUEUED',error_code=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?")
        .param(id)
        .update();
  }

  public Optional<String> completeFinalGenerationSpecDraft(UUID id) {
    return jdbc.sql("SELECT final_completion_json FROM generation_spec_draft WHERE id=?")
        .param(id)
        .query(String.class)
        .optional();
  }

  public Integer completeFinalGenerationSpecDraft2(UUID id) {
    return jdbc.sql(
            "SELECT count(*) FROM generation_spec_draft WHERE id=? AND status='FINAL_GENERATING'"
                + " AND lease_until>CURRENT_TIMESTAMP")
        .param(id)
        .query(Integer.class)
        .single();
  }

  public int completeFinalGenerationSpecDraft3(String audit, String failure, UUID id) {
    return jdbc.sql(
            "UPDATE generation_spec_draft SET"
                + " final_completion_json=?,error_code=?,status='FINAL_FAILED',updated_at=CURRENT_TIMESTAMP"
                + " WHERE id=?")
        .param(audit)
        .param(failure)
        .param(id)
        .update();
  }

  public int completeFinalGenerationSpecDraft4(String payload, String argument1, UUID id) {
    return jdbc.sql(
            "UPDATE generation_spec_draft SET final_plan_json=?,final_plan_sha256=? WHERE id=?")
        .param(payload)
        .param(argument1)
        .param(id)
        .update();
  }

  public int completeProseGenerationSpecDraft(String audit, String failure, UUID id) {
    return jdbc.sql(
            "UPDATE generation_spec_draft SET"
                + " completion_json=?,status='REVIEW_REJECTED',error_code=? WHERE id=?")
        .param(audit)
        .param(failure)
        .param(id)
        .update();
  }

  public int completeProseGenerationSpecDraft2(UUID id) {
    return jdbc.sql(
            "UPDATE generation_spec_draft SET error_code='PROSE_ARTIFACT_FENCE_MISMATCH' WHERE"
                + " id=?")
        .param(id)
        .update();
  }

  public String completeProseGenerationSpecDraft3(UUID id) {
    return jdbc.sql("SELECT spec_json FROM generation_spec_draft WHERE id=?")
        .param(id)
        .query(String.class)
        .single();
  }

  public int completeProseGenerationSpecDraft4(UUID id) {
    return jdbc.sql("UPDATE generation_spec_draft SET error_code='INVALID_PROSE_REPAIR' WHERE id=?")
        .param(id)
        .update();
  }

  public String completeProseGenerationSpecDraft5(UUID id) {
    return jdbc.sql("SELECT build_report_json FROM generation_spec_draft WHERE id=?")
        .param(id)
        .query(String.class)
        .single();
  }

  public String completeProseProblemVersion(String argument0) {
    return jdbc.sql("SELECT package_json FROM problem_version WHERE id=? AND ready=false")
        .param(argument0)
        .query(String.class)
        .single();
  }

  public String completeProseProblemVersion2(String argument0) {
    return jdbc.sql("SELECT package_sha256 FROM problem_version WHERE id=?")
        .param(argument0)
        .query(String.class)
        .single();
  }

  public int completeProseGenerationSpecDraft6(UUID id) {
    return jdbc.sql(
            "UPDATE generation_spec_draft SET error_code='PROSE_PACKAGE_FENCE_MISMATCH' WHERE id=?")
        .param(id)
        .update();
  }

  public int completeProseProblemVersion3(String pack, String argument1, String argument2) {
    return jdbc.sql(
            "UPDATE problem_version SET package_json=?,package_sha256=? WHERE id=? AND ready=false")
        .param(pack)
        .param(argument1)
        .param(argument2)
        .update();
  }

  public int completeProseGenerationSpecDraft7(
      String spec, String hash, String argument2, UUID id) {
    return jdbc.sql(
            "UPDATE generation_spec_draft SET"
                + " spec_json=?,spec_sha256=?,build_report_json=?,status='REVIEW_QUEUED',recovery_scope=NULL,error_code=NULL,updated_at=CURRENT_TIMESTAMP"
                + " WHERE id=?")
        .param(spec)
        .param(hash)
        .param(argument2)
        .param(id)
        .update();
  }
}
