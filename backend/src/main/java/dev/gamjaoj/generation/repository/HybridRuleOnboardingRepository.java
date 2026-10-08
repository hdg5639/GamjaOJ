package dev.gamjaoj.generation.repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence operations for HybridRuleOnboarding; transaction ownership remains in the service.
 */
@Repository
public class HybridRuleOnboardingRepository {
  private final JdbcClient jdbc;

  public HybridRuleOnboardingRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Integer lockAiBudgetLock() {
    return jdbc.sql("SELECT id FROM ai_budget_lock WHERE id=1 FOR UPDATE")
        .query(Integer.class)
        .single();
  }

  public <T> Optional<T> createHybridRuleOnboarding(UUID id, RowMapper<T> mapper) {
    return jdbc.sql("SELECT owner_id,request_sha256 FROM hybrid_rule_onboarding WHERE id=?")
        .param(id)
        .query(mapper)
        .optional();
  }

  public Integer createHybridRuleOnboarding2(UUID owner) {
    return jdbc.sql(
            "SELECT count(*) FROM hybrid_rule_onboarding WHERE owner_id=? AND status IN"
                + " ('QUEUED','AUTHORING','AUTHORED','ORACLE','QUALIFYING')")
        .param(owner)
        .query(Integer.class)
        .single();
  }

  public Integer createHybridRuleOnboarding3() {
    return jdbc.sql(
            "SELECT count(*) FROM hybrid_rule_onboarding WHERE status IN"
                + " ('QUEUED','AUTHORING','AUTHORED','ORACLE','QUALIFYING')")
        .query(Integer.class)
        .single();
  }

  public int createHybridRuleOnboarding4(
      UUID id,
      UUID owner,
      String raw,
      String hash,
      BigDecimal argument4,
      OffsetDateTime created,
      OffsetDateTime argument6,
      OffsetDateTime createdArgument7) {
    return jdbc.sql(
            "INSERT INTO"
                + " hybrid_rule_onboarding(id,owner_id,request_json,request_sha256,status,budget_usd,created_at,deadline_at,updated_at)"
                + " VALUES (?,?,?,?,'QUEUED',?,?,?,?)")
        .param(id)
        .param(owner)
        .param(raw)
        .param(hash)
        .param(argument4)
        .param(created)
        .param(argument6)
        .param(createdArgument7)
        .update();
  }

  public <T> Optional<T> retryableAttemptHybridRuleCodexCall(UUID id, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT id,status,receipt_json,error_code FROM hybrid_rule_codex_call WHERE"
                + " onboarding_id=? ORDER BY created_at DESC LIMIT 1")
        .param(id)
        .query(mapper)
        .optional();
  }

  public <T> Optional<T> retryAuthorHybridRuleCodexCall(
      UUID failedAttempt, UUID id, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT retry_requested_at FROM hybrid_rule_codex_call WHERE id=? AND onboarding_id=?")
        .param(failedAttempt)
        .param(id)
        .query(mapper)
        .optional();
  }

  public Integer retryAuthorHybridRuleOnboarding() {
    return jdbc.sql(
            "SELECT count(*) FROM hybrid_rule_onboarding WHERE status IN"
                + " ('QUEUED','AUTHORING','AUTHORED','ORACLE','QUALIFYING')")
        .query(Integer.class)
        .single();
  }

  public Integer retryAuthorHybridRuleOnboarding2(UUID owner) {
    return jdbc.sql(
            "SELECT count(*) FROM hybrid_rule_onboarding WHERE owner_id=? AND status IN"
                + " ('QUEUED','AUTHORING','AUTHORED','ORACLE','QUALIFYING')")
        .param(owner)
        .query(Integer.class)
        .single();
  }

  public String retryAuthorHybridRuleOnboarding3(UUID id) {
    return jdbc.sql("SELECT request_json FROM hybrid_rule_onboarding WHERE id=?")
        .param(id)
        .query(String.class)
        .single();
  }

  public int retryAuthorHybridRuleCodexCall2(UUID failedAttempt) {
    return jdbc.sql(
            "UPDATE hybrid_rule_codex_call SET retry_requested_at=CURRENT_TIMESTAMP WHERE id=?")
        .param(failedAttempt)
        .update();
  }

  public int retryAuthorHybridRuleOnboarding4(
      OffsetDateTime argument0, OffsetDateTime argument1, UUID id) {
    return jdbc.sql(
            "UPDATE hybrid_rule_onboarding SET"
                + " status='QUEUED',error_code=NULL,deadline_at=?,updated_at=? WHERE id=?")
        .param(argument0)
        .param(argument1)
        .param(id)
        .update();
  }

  public Stream<UUID> listHybridRuleOnboarding(UUID owner) {
    return jdbc
        .sql(
            "SELECT id FROM hybrid_rule_onboarding WHERE owner_id=? ORDER BY created_at DESC,id"
                + " LIMIT 20")
        .param(owner)
        .query(UUID.class)
        .list()
        .stream();
  }

  public <T> Optional<T> viewHybridRuleOnboarding(UUID id, UUID owner, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT o.*,v.catalog_json,g.status AS followup_status,g.published_version_id FROM"
                + " hybrid_rule_onboarding o LEFT JOIN hybrid_rule_version v ON v.id=o.version_id"
                + " LEFT JOIN hybrid_generation g ON g.id=o.followup_generation_id WHERE o.id=? AND"
                + " o.owner_id=?")
        .param(id)
        .param(owner)
        .query(mapper)
        .optional();
  }

  public <T> List<T> viewHybridExecutionCheck(UUID argument0, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT e.role,j.status,j.verdict FROM hybrid_execution_check e JOIN hybrid_branch b ON"
                + " b.id=e.branch_id JOIN judge_job j ON j.submission_id=e.submission_id WHERE"
                + " b.generation_id=?")
        .param(argument0)
        .query(mapper)
        .list();
  }

  public Optional<String> viewHybridRuleCodexCall(UUID id, int argument1) {
    return jdbc.sql(
            "SELECT receipt_json FROM hybrid_rule_codex_call WHERE onboarding_id=? AND"
                + " repair_round=? AND stage='DESIGN' AND status='REJECTED' ORDER BY created_at"
                + " DESC LIMIT 1")
        .param(id)
        .param(argument1)
        .query(String.class)
        .optional();
  }

  public BigDecimal spentHybridRuleOnboardingCall(UUID id) {
    return jdbc.sql(
            "SELECT COALESCE(SUM(COALESCE(a.actual_usd,a.reserved_usd)),0) FROM"
                + " hybrid_rule_onboarding_call c JOIN ai_attempt a ON a.id=c.attempt_id WHERE"
                + " c.onboarding_id=?")
        .param(id)
        .query(BigDecimal.class)
        .single();
  }

  public int stopHybridRuleOnboarding(
      String status, String error, OffsetDateTime argument2, UUID id) {
    return jdbc.sql(
            "UPDATE hybrid_rule_onboarding SET status=?,error_code=?,updated_at=? WHERE id=? AND"
                + " status IN ('QUEUED','AUTHORING','AUTHORED','ORACLE','QUALIFYING')")
        .param(status)
        .param(error)
        .param(argument2)
        .param(id)
        .update();
  }

  public int stopHybridGeneration(
      Object argument0, String error, OffsetDateTime argument2, UUID id) {
    return jdbc.sql(
            "UPDATE hybrid_generation SET status=?,error_code=?,updated_at=? WHERE id=(SELECT"
                + " carrier_generation_id FROM hybrid_rule_onboarding WHERE id=?) AND"
                + " status='QUALIFYING'")
        .param(argument0)
        .param(error)
        .param(argument2)
        .param(id)
        .update();
  }

  public Integer claimCallAiAttempt() {
    return jdbc.sql(
            "SELECT count(*) FROM ai_attempt a JOIN hybrid_rule_onboarding_call c ON"
                + " c.attempt_id=a.id WHERE a.status='ONBOARD_RUNNING'")
        .query(Integer.class)
        .single();
  }

  public <T> Stream<T> claimCallHybridRuleOnboarding(
      OffsetDateTime argument0, RowMapper<T> mapper) {
    return jdbc
        .sql(
            "SELECT id,status,request_json,author_json,deadline_at FROM hybrid_rule_onboarding"
                + " WHERE status IN ('QUEUED','AUTHORED') AND deadline_at>? ORDER BY created_at")
        .param(argument0)
        .query(mapper)
        .list()
        .stream();
  }

  public Optional<String> claimCallHybridRuleOnboarding2(Object argument0) {
    return jdbc.sql("SELECT repair_json FROM hybrid_rule_onboarding WHERE id=?")
        .param(argument0)
        .query(String.class)
        .optional();
  }

  public BigDecimal claimCallHybridRuleOnboarding3(UUID id) {
    return jdbc.sql("SELECT budget_usd FROM hybrid_rule_onboarding WHERE id=?")
        .param(id)
        .query(BigDecimal.class)
        .single();
  }

  public int claimCallAiAttempt2(
      UUID attempt, String argument1, BigDecimal amount, String argument3) {
    return jdbc.sql(
            "INSERT INTO ai_attempt(id,month_key,status,reserved_usd,settings_json,started_at)"
                + " VALUES (?,?,'ONBOARD_RUNNING',?,?,CURRENT_TIMESTAMP)")
        .param(attempt)
        .param(argument1)
        .param(amount)
        .param(argument3)
        .update();
  }

  public int claimCallHybridRuleOnboardingCall(
      UUID attempt, UUID id, String role, UUID idArgument3) {
    return jdbc.sql(
            "INSERT INTO hybrid_rule_onboarding_call(attempt_id,onboarding_id,role,repair_round)"
                + " SELECT ?,?,?,repairs FROM hybrid_rule_onboarding WHERE id=?")
        .param(attempt)
        .param(id)
        .param(role)
        .param(idArgument3)
        .update();
  }

  public int claimCallHybridRuleOnboarding4(Object argument0, OffsetDateTime argument1, UUID id) {
    return jdbc.sql("UPDATE hybrid_rule_onboarding SET status=?,updated_at=? WHERE id=?")
        .param(argument0)
        .param(argument1)
        .param(id)
        .update();
  }

  public Integer claimCodexAuthorHybridRuleCodexCall() {
    return jdbc.sql("SELECT count(*) FROM hybrid_rule_codex_call WHERE status='RUNNING'")
        .query(Integer.class)
        .single();
  }

  public <T> List<T> claimCodexAuthorHybridRuleOnboarding(
      OffsetDateTime argument0, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT id,request_json,repair_json,repairs,deadline_at FROM hybrid_rule_onboarding"
                + " WHERE status='QUEUED' AND deadline_at>? ORDER BY created_at")
        .param(argument0)
        .query(mapper)
        .list();
  }

  public <T> Optional<T> claimCodexAuthorHybridRuleCodexCall2(
      UUID id, int round, String stage, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT stage_attempt,error_code,receipt_json FROM hybrid_rule_codex_call WHERE"
                + " onboarding_id=? AND repair_round=? AND stage=? ORDER BY stage_attempt DESC"
                + " LIMIT 1")
        .param(id)
        .param(round)
        .param(stage)
        .query(mapper)
        .optional();
  }

  public int claimCodexAuthorHybridRuleCodexCall3(
      UUID attempt,
      UUID id,
      int round,
      String stage,
      int stageAttempt,
      UUID token,
      String raw,
      String hash,
      String model,
      String effort,
      String argument10) {
    return jdbc.sql(
            "INSERT INTO"
                + " hybrid_rule_codex_call(id,onboarding_id,repair_round,stage,stage_attempt,token,input_json,input_sha256,model,effort,prompt_version,status,created_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,'RUNNING',CURRENT_TIMESTAMP)")
        .param(attempt)
        .param(id)
        .param(round)
        .param(stage)
        .param(stageAttempt)
        .param(token)
        .param(raw)
        .param(hash)
        .param(model)
        .param(effort)
        .param(argument10)
        .update();
  }

  public int claimCodexAuthorHybridRuleOnboarding2(OffsetDateTime argument0, UUID id) {
    return jdbc.sql("UPDATE hybrid_rule_onboarding SET status='AUTHORING',updated_at=? WHERE id=?")
        .param(argument0)
        .param(id)
        .update();
  }

  public <T> Optional<T> finishCodexAuthorHybridRuleCodexCall(UUID argument0, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT"
                + " onboarding_id,token,input_sha256,prompt_version,receipt_json,stage,repair_round,stage_attempt"
                + " FROM hybrid_rule_codex_call WHERE id=?")
        .param(argument0)
        .query(mapper)
        .optional();
  }

  public int finishCodexAuthorHybridRuleCodexCall2(
      Object argument0, String receipt, String error, UUID argument3) {
    return jdbc.sql(
            "UPDATE hybrid_rule_codex_call SET status=?,receipt_json=?,error_code=? WHERE id=?")
        .param(argument0)
        .param(receipt)
        .param(error)
        .param(argument3)
        .update();
  }

  public Integer finishCodexAuthorHybridRuleOnboarding(UUID id, int round) {
    return jdbc.sql(
            "SELECT count(*) FROM hybrid_rule_onboarding WHERE id=? AND status='AUTHORING' AND"
                + " repairs=?")
        .param(id)
        .param(round)
        .query(Integer.class)
        .single();
  }

  public String finishCodexAuthorHybridRuleOnboarding2(UUID id) {
    return jdbc.sql("SELECT request_json FROM hybrid_rule_onboarding WHERE id=?")
        .param(id)
        .query(String.class)
        .single();
  }

  public int finishCodexAuthorHybridRuleOnboarding3(OffsetDateTime argument0, UUID id) {
    return jdbc.sql("UPDATE hybrid_rule_onboarding SET status='QUEUED',updated_at=? WHERE id=?")
        .param(argument0)
        .param(id)
        .update();
  }

  public int finishCodexAuthorHybridRuleCodexCall3(String code, UUID argument1) {
    return jdbc.sql("UPDATE hybrid_rule_codex_call SET status='REJECTED',error_code=? WHERE id=?")
        .param(code)
        .param(argument1)
        .update();
  }

  public int finishCodexAuthorHybridRuleOnboarding4(OffsetDateTime argument0, UUID id) {
    return jdbc.sql("UPDATE hybrid_rule_onboarding SET status='QUEUED',updated_at=? WHERE id=?")
        .param(argument0)
        .param(id)
        .update();
  }

  public <T> T finishCallHybridRuleOnboardingCall(UUID attempt, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT c.onboarding_id,c.role,c.receipt_json,a.settings_json FROM"
                + " hybrid_rule_onboarding_call c JOIN ai_attempt a ON a.id=c.attempt_id WHERE"
                + " c.attempt_id=?")
        .param(attempt)
        .query(mapper)
        .single();
  }

  public int finishCallAiAttempt(
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

  public int finishCallHybridRuleOnboardingCall2(String argument0, UUID attempt) {
    return jdbc.sql("UPDATE hybrid_rule_onboarding_call SET receipt_json=? WHERE attempt_id=?")
        .param(argument0)
        .param(attempt)
        .update();
  }

  public String finishCallHybridRuleOnboarding(UUID id) {
    return jdbc.sql("SELECT status FROM hybrid_rule_onboarding WHERE id=?")
        .param(id)
        .query(String.class)
        .single();
  }

  public int acceptPayloadHybridRuleOnboarding(String candidate, String argument1, UUID id) {
    return jdbc.sql("UPDATE hybrid_rule_onboarding SET author_json=?,author_sha256=? WHERE id=?")
        .param(candidate)
        .param(argument1)
        .param(id)
        .update();
  }

  public String acceptPayloadHybridRuleOnboarding2(UUID id) {
    return jdbc.sql("SELECT request_json FROM hybrid_rule_onboarding WHERE id=?")
        .param(id)
        .query(String.class)
        .single();
  }

  public int acceptPayloadHybridRuleOnboarding3(
      String raw, String argument1, OffsetDateTime argument2, UUID id) {
    return jdbc.sql(
            "UPDATE hybrid_rule_onboarding SET"
                + " author_json=?,author_sha256=?,status='AUTHORED',updated_at=? WHERE id=?")
        .param(raw)
        .param(argument1)
        .param(argument2)
        .param(id)
        .update();
  }

  public int acceptPayloadHybridRuleOnboarding4(String observed, String argument1, UUID id) {
    return jdbc.sql("UPDATE hybrid_rule_onboarding SET oracle_json=?,oracle_sha256=? WHERE id=?")
        .param(observed)
        .param(argument1)
        .param(id)
        .update();
  }

  public int acceptPayloadHybridRuleOnboarding5(
      String raw, String argument1, OffsetDateTime argument2, UUID id) {
    return jdbc.sql(
            "UPDATE hybrid_rule_onboarding SET"
                + " oracle_json=?,oracle_sha256=?,status='QUALIFYING',updated_at=? WHERE id=?")
        .param(raw)
        .param(argument1)
        .param(argument2)
        .param(id)
        .update();
  }

  public int queueSubmission(
      UUID id,
      String argument1,
      String source,
      String sourceHash,
      UUID idArgument4,
      Object argument5,
      String argument6,
      String payload,
      String hash,
      UUID branch,
      String argument10,
      UUID generation) {
    return jdbc.sql(
            "INSERT INTO"
                + " submission(id,user_id,problem_version,source_code,source_sha256,idempotency_key,runtime_image,runner_policy,run_input,run_package,run_package_sha256,hybrid_branch_id)"
                + " SELECT ?,g.owner_id,?,?,?,?,p.runtime_image,?,?,?,?,? FROM hybrid_generation g"
                + " JOIN problem_version p ON p.id=? WHERE g.id=?")
        .param(id)
        .param(argument1)
        .param(source)
        .param(sourceHash)
        .param(idArgument4)
        .param(argument5)
        .param(argument6)
        .param(payload)
        .param(hash)
        .param(branch)
        .param(argument10)
        .param(generation)
        .update();
  }

  public String queueHybridRuleOnboarding(UUID generation) {
    return jdbc.sql("SELECT answers_json FROM hybrid_rule_onboarding WHERE carrier_generation_id=?")
        .param(generation)
        .query(String.class)
        .single();
  }

  public int queueSubmission2(String argument0, UUID id) {
    return jdbc.sql("UPDATE submission SET execution_profile_json=? WHERE id=?")
        .param(argument0)
        .param(id)
        .update();
  }

  public int queueJudgeJob(UUID id, Object argument1) {
    return jdbc.sql("INSERT INTO judge_job(submission_id,priority,execution_mode) VALUES (?,1,?)")
        .param(id)
        .param(argument1)
        .update();
  }

  public int queueHybridExecutionCheck(
      UUID branch, String role, UUID id, String sourceHash, String hash) {
    return jdbc.sql(
            "INSERT INTO"
                + " hybrid_execution_check(branch_id,role,submission_id,source_sha256,package_sha256)"
                + " VALUES (?,?,?,?,?)")
        .param(branch)
        .param(role)
        .param(id)
        .param(sourceHash)
        .param(hash)
        .update();
  }

  public <T> T startQualificationHybridRuleOnboarding(UUID id, RowMapper<T> mapper) {
    return jdbc.sql("SELECT owner_id,deadline_at FROM hybrid_rule_onboarding WHERE id=?")
        .param(id)
        .query(mapper)
        .single();
  }

  public int startQualificationHybridGeneration(
      UUID generation,
      Object argument1,
      String PIPELINE,
      String raw,
      String argument4,
      OffsetDateTime argument5,
      Object argument6,
      OffsetDateTime argument7) {
    return jdbc.sql(
            "INSERT INTO"
                + " hybrid_generation(id,owner_id,pipeline_version,request_json,request_sha256,status,created_at,deadline_at,updated_at)"
                + " VALUES (?,?,?,?,?,'QUALIFYING',?,?,?)")
        .param(generation)
        .param(argument1)
        .param(PIPELINE)
        .param(raw)
        .param(argument4)
        .param(argument5)
        .param(argument6)
        .param(argument7)
        .update();
  }

  public int startQualificationHybridBranch(
      UUID branch, UUID generation, String raw, String argument3) {
    return jdbc.sql(
            "INSERT INTO"
                + " hybrid_branch(id,generation_id,revision,role,attempt,status,input_json,input_sha256,created_at,started_at)"
                + " VALUES"
                + " (?,?,0,'VALIDATION',0,'RUNNING',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")
        .param(branch)
        .param(generation)
        .param(raw)
        .param(argument3)
        .update();
  }

  public int startQualificationProblemVersion(
      String argument0, String empty, String argument2, Object argument3) {
    return jdbc.sql(
            "INSERT INTO"
                + " problem_version(id,package_json,package_sha256,runtime_image,runner_policy,ready,owner_id)"
                + " SELECT ?,?,?,p.runtime_image,p.runner_policy,false,? FROM problem_version p"
                + " WHERE p.id='total-v1'")
        .param(argument0)
        .param(empty)
        .param(argument2)
        .param(argument3)
        .update();
  }

  public int startQualificationHybridRuleOnboarding2(
      UUID generation, String argument1, OffsetDateTime argument2, UUID id) {
    return jdbc.sql(
            "UPDATE hybrid_rule_onboarding SET carrier_generation_id=?,answers_json=?,updated_at=?"
                + " WHERE id=?")
        .param(generation)
        .param(argument1)
        .param(argument2)
        .param(id)
        .update();
  }

  public List<UUID> advanceHybridRuleOnboarding() {
    return jdbc.sql(
            "SELECT id FROM hybrid_rule_onboarding WHERE status='QUALIFYING' ORDER BY created_at")
        .query(UUID.class)
        .list();
  }

  public <T> Optional<T> advanceOneHybridRuleOnboarding(UUID id, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT"
                + " owner_id,author_json,author_sha256,oracle_json,oracle_sha256,carrier_generation_id,answers_json,request_json"
                + " FROM hybrid_rule_onboarding WHERE id=? AND status='QUALIFYING'")
        .param(id)
        .query(mapper)
        .optional();
  }

  public UUID advanceOneHybridBranch(UUID generation) {
    return jdbc.sql("SELECT id FROM hybrid_branch WHERE generation_id=? AND role='VALIDATION'")
        .param(generation)
        .query(UUID.class)
        .single();
  }

  public <T> List<T> advanceOneHybridExecutionCheck(UUID branch, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT e.role,e.source_sha256,j.status,j.verdict,j.result_json,j.result_sha256 FROM"
                + " hybrid_execution_check e JOIN judge_job j ON j.submission_id=e.submission_id"
                + " WHERE e.branch_id=?")
        .param(branch)
        .query(mapper)
        .list();
  }

  public int advanceOneHybridRuleOnboarding2(String argument0, UUID id) {
    return jdbc.sql("UPDATE hybrid_rule_onboarding SET answers_json=? WHERE id=?")
        .param(argument0)
        .param(id)
        .update();
  }

  public Optional<String> advanceOneHybridRuleOnboarding3(UUID id) {
    return jdbc.sql("SELECT answers_json FROM hybrid_rule_onboarding WHERE id=?")
        .param(id)
        .query(String.class)
        .optional();
  }

  public int advanceOneHybridRuleOnboarding4(String argument0, UUID id) {
    return jdbc.sql("UPDATE hybrid_rule_onboarding SET answers_json=? WHERE id=?")
        .param(argument0)
        .param(id)
        .update();
  }

  public <T> Optional<T> repairHybridRuleOnboarding(UUID id, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT repairs,author_json,carrier_generation_id,deadline_at,request_json FROM"
                + " hybrid_rule_onboarding WHERE id=? AND status IN"
                + " ('AUTHORING','ORACLE','QUALIFYING')")
        .param(id)
        .query(mapper)
        .optional();
  }

  public int repairHybridGeneration(String code, OffsetDateTime argument1, Object argument2) {
    return jdbc.sql(
            "UPDATE hybrid_generation SET status='HELD',error_code=?,updated_at=? WHERE id=? AND"
                + " status='QUALIFYING'")
        .param(code)
        .param(argument1)
        .param(argument2)
        .update();
  }

  public int repairHybridRuleOnboarding2(
      String argument0, OffsetDateTime deadline, OffsetDateTime argument2, UUID id) {
    return jdbc.sql(
            "UPDATE hybrid_rule_onboarding SET"
                + " status='QUEUED',repairs=repairs+1,repair_json=?,oracle_json=NULL,oracle_sha256=NULL,carrier_generation_id=NULL,answers_json=NULL,error_code=NULL,deadline_at=?,updated_at=?"
                + " WHERE id=?")
        .param(argument0)
        .param(deadline)
        .param(argument2)
        .param(id)
        .update();
  }

  public String activateHybridRuleOnboarding(UUID id) {
    return jdbc.sql("SELECT answers_json FROM hybrid_rule_onboarding WHERE id=?")
        .param(id)
        .query(String.class)
        .single();
  }

  public int activateHybridRuleOnboarding2(String versionId, String argument1, UUID id) {
    return jdbc.sql("UPDATE hybrid_rule_onboarding SET version_id=?,answers_json=? WHERE id=?")
        .param(versionId)
        .param(argument1)
        .param(id)
        .update();
  }

  public List<UUID> releaseInterruptedCallsHybridRuleOnboardingCall() {
    return jdbc.sql(
            "SELECT DISTINCT c.onboarding_id FROM hybrid_rule_onboarding_call c JOIN ai_attempt a"
                + " ON a.id=c.attempt_id WHERE a.status='ONBOARD_RUNNING'")
        .query(UUID.class)
        .list();
  }

  public int releaseInterruptedCallsHybridRuleCodexCall() {
    return jdbc.sql(
            "UPDATE hybrid_rule_codex_call SET status='UNKNOWN' WHERE status='RUNNING' AND"
                + " onboarding_id IN (SELECT id FROM hybrid_rule_onboarding WHERE"
                + " status<>'AUTHORING')")
        .update();
  }

  public int releaseInterruptedCallsAiAttempt() {
    return jdbc.sql(
            "UPDATE ai_attempt SET status='ONBOARD_UNKNOWN',error_code='INTERRUPTED_USAGE_UNKNOWN'"
                + " WHERE status='ONBOARD_RUNNING' AND id IN (SELECT attempt_id FROM"
                + " hybrid_rule_onboarding_call)")
        .update();
  }

  public List<UUID> recoverHybridRuleOnboarding(OffsetDateTime argument0) {
    return jdbc.sql(
            "SELECT id FROM hybrid_rule_onboarding WHERE status IN"
                + " ('QUEUED','AUTHORING','AUTHORED','ORACLE','QUALIFYING') AND deadline_at<=?")
        .param(argument0)
        .query(UUID.class)
        .list();
  }

  public int recoverHybridRuleCodexCall() {
    return jdbc.sql(
            "UPDATE hybrid_rule_codex_call SET status='UNKNOWN' WHERE status='RUNNING' AND"
                + " onboarding_id IN (SELECT id FROM hybrid_rule_onboarding WHERE"
                + " status<>'AUTHORING')")
        .update();
  }

  public int recoverAiAttempt() {
    return jdbc.sql(
            "UPDATE ai_attempt SET status='ONBOARD_UNKNOWN',error_code='INTERRUPTED_USAGE_UNKNOWN'"
                + " WHERE status='ONBOARD_RUNNING' AND id IN (SELECT c.attempt_id FROM"
                + " hybrid_rule_onboarding_call c JOIN hybrid_rule_onboarding o ON"
                + " o.id=c.onboarding_id WHERE o.status NOT IN ('AUTHORING','ORACLE'))")
        .update();
  }
}
