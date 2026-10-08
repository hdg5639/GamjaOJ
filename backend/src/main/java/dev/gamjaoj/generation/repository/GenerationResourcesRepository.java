package dev.gamjaoj.generation.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence operations for GenerationResources; transaction ownership remains in the service. */
@Repository
public class GenerationResourcesRepository {
  private final JdbcClient jdbc;

  public GenerationResourcesRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Boolean enabledRow(String tableName, UUID id) {
    return jdbc.sql("SELECT resource_validation FROM " + tableName + " WHERE id=?")
        .param(id)
        .query(Boolean.class)
        .single();
  }

  public <T> T ensureProblemVersion(String version, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT package_json,package_sha256 FROM problem_version WHERE id=? AND ready=false")
        .param(version)
        .query(mapper)
        .single();
  }

  public <T> Optional<T> ensureGenerationResourceCheck(
      String pipeline, UUID job, String fence, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT id,status,limits_json,error_code FROM generation_resource_check WHERE"
                + " pipeline=? AND job_id=? AND fence=?")
        .param(pipeline)
        .param(job)
        .param(fence)
        .query(mapper)
        .optional();
  }

  public int ensureGenerationResourceCheck2(String pipeline, UUID job, String fence) {
    return jdbc.sql(
            "UPDATE generation_resource_check SET status='SUPERSEDED' WHERE pipeline=? AND job_id=?"
                + " AND fence<>? AND status NOT IN ('PASSED','SUPERSEDED')")
        .param(pipeline)
        .param(job)
        .param(fence)
        .update();
  }

  public int ensureHybridGeneration(OffsetDateTime argument0, OffsetDateTime argument1, UUID job) {
    return jdbc.sql(
            "UPDATE hybrid_generation SET deadline_at=CASE WHEN deadline_at<? THEN ? ELSE"
                + " deadline_at END WHERE id=? AND status IN ('HELD','REVIEWING')")
        .param(argument0)
        .param(argument1)
        .param(job)
        .update();
  }

  public int ensureGenerationResourceCheck3(
      UUID argument0, String pipeline, UUID job, String version, String fence, String raw) {
    return jdbc.sql(
            "INSERT INTO"
                + " generation_resource_check(id,pipeline,job_id,problem_version,fence,input_json,status)"
                + " VALUES (?,?,?,?,?,?,'QUEUED')")
        .param(argument0)
        .param(pipeline)
        .param(job)
        .param(version)
        .param(fence)
        .param(raw)
        .update();
  }

  public <T> Optional<T> progressGenerationResourceCheck(
      String pipeline, UUID id, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT status,error_code FROM generation_resource_check WHERE pipeline=? AND job_id=?"
                + " ORDER BY created_at DESC LIMIT 1")
        .param(pipeline)
        .param(id)
        .query(mapper)
        .optional();
  }

  public Integer runningGenerationResourceCheck() {
    return jdbc.sql("SELECT count(*) FROM generation_resource_check WHERE status='GENERATING'")
        .query(Integer.class)
        .single();
  }

  public <T> Optional<T> claimGenerationResourceCheck(RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT c.id,c.input_json,c.error_code,c.retries FROM generation_resource_check c WHERE"
                + " c.status='QUEUED' AND EXISTS (SELECT 1 FROM problem_version p WHERE"
                + " p.id=c.problem_version AND p.ready=false) AND NOT EXISTS (SELECT 1 FROM"
                + " diagnostic_practice_plan p WHERE (p.generation_id=c.job_id OR"
                + " p.hybrid_generation_id=c.job_id) AND (p.training_session_id IS NOT NULL OR"
                + " EXISTS (SELECT 1 FROM learning_curriculum_end e WHERE"
                + " e.evaluation_id=p.evaluation_id AND e.user_id=p.user_id) OR EXISTS (SELECT 1"
                + " FROM diagnostic_practice_plan n WHERE n.previous_plan_id=p.id))) AND"
                + " (c.pipeline<>'RULE' OR EXISTS (SELECT 1 FROM hybrid_generation g WHERE"
                + " g.id=c.job_id AND g.status IN ('HELD','REVIEWING') AND"
                + " g.deadline_at>CURRENT_TIMESTAMP)) ORDER BY c.created_at LIMIT 1 FOR UPDATE")
        .query(mapper)
        .optional();
  }

  public String claimProblemVersion(String argument0) {
    return jdbc.sql("SELECT package_sha256 FROM problem_version WHERE id=?")
        .param(argument0)
        .query(String.class)
        .single();
  }

  public int claimGenerationResourceCheck2(UUID id) {
    return jdbc.sql("UPDATE generation_resource_check SET status='SUPERSEDED' WHERE id=?")
        .param(id)
        .update();
  }

  public int claimGenerationResourceCheck3(UUID token, OffsetDateTime argument1, UUID id) {
    return jdbc.sql(
            "UPDATE generation_resource_check SET status='GENERATING',token=?,lease_until=? WHERE"
                + " id=?")
        .param(token)
        .param(argument1)
        .param(id)
        .update();
  }

  public Optional<String> claimGenerationResourceAttempt(UUID id) {
    return jdbc.sql(
            "SELECT completion_json FROM generation_resource_attempt WHERE check_id=? ORDER BY"
                + " attempt DESC LIMIT 1")
        .param(id)
        .query(String.class)
        .optional();
  }

  public Integer containsGenerationResourceCheck(UUID id) {
    return jdbc.sql("SELECT count(*) FROM generation_resource_check WHERE id=?")
        .param(id)
        .query(Integer.class)
        .single();
  }

  public <T> T completeGenerationResourceCheck(UUID id, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT token,status,input_json,completion_json FROM generation_resource_check WHERE"
                + " id=? FOR UPDATE")
        .param(id)
        .query(mapper)
        .single();
  }

  public int completeGenerationResourceCheck2(String audit, UUID id) {
    return jdbc.sql("UPDATE generation_resource_check SET completion_json=? WHERE id=?")
        .param(audit)
        .param(id)
        .update();
  }

  public Integer completeGenerationResourceCheck3(UUID id) {
    return jdbc.sql(
            "SELECT count(*) FROM generation_resource_check WHERE id=? AND"
                + " lease_until>CURRENT_TIMESTAMP")
        .param(id)
        .query(Integer.class)
        .single();
  }

  public int completeGenerationResourceCheck4(String audit, UUID id) {
    return jdbc.sql("UPDATE generation_resource_check SET completion_json=? WHERE id=?")
        .param(audit)
        .param(id)
        .update();
  }

  public int completeGenerationResourceCheck5(String argument0, UUID id) {
    return jdbc.sql(
            "UPDATE generation_resource_check SET artifacts_json=?,status='MEASURING' WHERE id=?")
        .param(argument0)
        .param(id)
        .update();
  }

  public <T> T submitGenerationResourceCheck(UUID id, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT input_json,artifacts_json,problem_version FROM generation_resource_check WHERE"
                + " id=?")
        .param(id)
        .query(mapper)
        .single();
  }

  public int submitSubmission(
      UUID submission,
      String argument1,
      String source,
      String argument3,
      UUID submissionArgument4,
      String argument5,
      String argument6,
      String language,
      String argument8,
      String argument9,
      String raw,
      String argument11,
      String argument12) {
    return jdbc.sql(
            "INSERT INTO"
                + " submission(id,user_id,problem_version,source_code,source_sha256,idempotency_key,runtime_image,runner_policy,language,execution_profile_json,run_input,run_package,run_package_sha256)"
                + " SELECT ?,p.owner_id,?,?,?,?,?,?,?,?,?,?,? FROM problem_version p WHERE p.id=?")
        .param(submission)
        .param(argument1)
        .param(source)
        .param(argument3)
        .param(submissionArgument4)
        .param(argument5)
        .param(argument6)
        .param(language)
        .param(argument8)
        .param(argument9)
        .param(raw)
        .param(argument11)
        .param(argument12)
        .update();
  }

  public <T> T submitGenerationResourceCheck2(UUID id, RowMapper<T> mapper) {
    return jdbc.sql("SELECT pipeline,job_id FROM generation_resource_check WHERE id=?")
        .param(id)
        .query(mapper)
        .single();
  }

  public int submitSubmission2(String markerColumn, UUID originId, UUID submission) {
    return jdbc.sql("UPDATE submission SET " + markerColumn + "=? WHERE id=?")
        .param(originId)
        .param(submission)
        .update();
  }

  public int submitJudgeJob(UUID submission) {
    return jdbc.sql(
            "INSERT INTO judge_job(submission_id,priority,execution_mode) VALUES (?,1,'EXCLUSIVE')")
        .param(submission)
        .update();
  }

  public int submitGenerationResourceExecution(UUID id, String role, UUID submission) {
    return jdbc.sql(
            "INSERT INTO generation_resource_execution(check_id,role,submission_id) VALUES (?,?,?)")
        .param(id)
        .param(role)
        .param(submission)
        .update();
  }

  public int advanceGenerationResourceCheck() {
    return jdbc.sql(
            "UPDATE generation_resource_check SET"
                + " status='NEEDS_REVIEW',error_code='GENERATION_INTERRUPTED' WHERE"
                + " status='GENERATING' AND lease_until<CURRENT_TIMESTAMP")
        .update();
  }

  public List<UUID> advanceGenerationResourceCheck2() {
    return jdbc.sql(
            "SELECT id FROM generation_resource_check WHERE status IN ('MEASURING','REPLAYING')")
        .query(UUID.class)
        .list();
  }

  public <T> Stream<T> advanceGenerationResourceExecution(UUID id, RowMapper<T> mapper) {
    return jdbc
        .sql(
            "SELECT s.id,s.runtime_image,s.runner_policy,s.execution_profile_json FROM"
                + " generation_resource_execution e JOIN submission s ON s.id=e.submission_id WHERE"
                + " e.check_id=?")
        .param(id)
        .query(mapper)
        .list()
        .stream();
  }

  public int advanceJudgeAttempt(String report, UUID submission) {
    return jdbc.sql(
            "UPDATE judge_attempt SET"
                + " status='SUPERSEDED',result_json=?,finished_at=CURRENT_TIMESTAMP WHERE"
                + " submission_id=? AND status='RUNNING'")
        .param(report)
        .param(submission)
        .update();
  }

  public int advanceJudgeJob(String report, String argument1, UUID submission) {
    return jdbc.sql(
            "UPDATE judge_job SET"
                + " status='FINISHED',verdict='IE',result_json=?,result_sha256=?,finished_at=CURRENT_TIMESTAMP"
                + " WHERE submission_id=? AND status<>'FINISHED'")
        .param(report)
        .param(argument1)
        .param(submission)
        .update();
  }

  public Integer advanceGenerationResourceExecution2(UUID id) {
    return jdbc.sql(
            "SELECT count(*) FROM generation_resource_execution e JOIN judge_job j ON"
                + " j.submission_id=e.submission_id WHERE e.check_id=? AND j.status<>'FINISHED'")
        .param(id)
        .query(Integer.class)
        .single();
  }

  public <T> List<T> advanceGenerationResourceExecution3(UUID id, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT e.role,j.status,j.verdict,j.result_json FROM generation_resource_execution e"
                + " JOIN judge_job j ON j.submission_id=e.submission_id WHERE e.check_id=? ORDER BY"
                + " e.role")
        .param(id)
        .query(mapper)
        .list();
  }

  public String advanceGenerationResourceCheck3(UUID id) {
    return jdbc.sql("SELECT status FROM generation_resource_check WHERE id=?")
        .param(id)
        .query(String.class)
        .single();
  }

  public int advanceGenerationResourceCheck4(String raw, UUID id) {
    return jdbc.sql(
            "UPDATE generation_resource_check SET limits_json=?,status='REPLAYING' WHERE id=?")
        .param(raw)
        .param(id)
        .update();
  }

  public String advanceGenerationResourceCheck5(UUID id) {
    return jdbc.sql("SELECT input_json FROM generation_resource_check WHERE id=?")
        .param(id)
        .query(String.class)
        .single();
  }

  public String advanceGenerationResourceCheck6(UUID id) {
    return jdbc.sql("SELECT artifacts_json FROM generation_resource_check WHERE id=?")
        .param(id)
        .query(String.class)
        .single();
  }

  public String advanceGenerationResourceCheck7(UUID id) {
    return jdbc.sql("SELECT artifacts_json FROM generation_resource_check WHERE id=?")
        .param(id)
        .query(String.class)
        .single();
  }

  public int advanceGenerationResourceCheck8(String argument0, UUID id) {
    return jdbc.sql("UPDATE generation_resource_check SET status='PASSED',report_json=? WHERE id=?")
        .param(argument0)
        .param(id)
        .update();
  }

  public <T> T failGenerationResourceCheck(UUID id, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT retries,completion_json,artifacts_json,token FROM generation_resource_check"
                + " WHERE id=? FOR UPDATE")
        .param(id)
        .query(mapper)
        .single();
  }

  public <T> List<T> failGenerationResourceExecution(UUID id, RowMapper<T> mapper) {
    return jdbc.sql("SELECT role,submission_id FROM generation_resource_execution WHERE check_id=?")
        .param(id)
        .query(mapper)
        .list();
  }

  public Integer failGenerationResourceAttempt(UUID id, int attempts) {
    return jdbc.sql(
            "SELECT count(*) FROM generation_resource_attempt WHERE check_id=? AND attempt=?")
        .param(id)
        .param(attempts)
        .query(Integer.class)
        .single();
  }

  public int failGenerationResourceAttempt2(
      UUID id, int attempts, String argument2, String argument3, String argument4) {
    return jdbc.sql(
            "INSERT INTO"
                + " generation_resource_attempt(check_id,attempt,completion_json,artifacts_json,report_json)"
                + " VALUES (?,?,?,?,?)")
        .param(id)
        .param(attempts)
        .param(argument2)
        .param(argument3)
        .param(argument4)
        .update();
  }

  public int failGenerationResourceCheck2(String argument0, UUID id) {
    return jdbc.sql("UPDATE generation_resource_check SET status='FAILED',error_code=? WHERE id=?")
        .param(argument0)
        .param(id)
        .update();
  }

  public <T> List<T> failGenerationResourceExecution2(UUID id, RowMapper<T> mapper) {
    return jdbc.sql("SELECT role,submission_id FROM generation_resource_execution WHERE check_id=?")
        .param(id)
        .query(mapper)
        .list();
  }

  public int failGenerationResourceAttempt3(
      UUID id, int attempts, String argument2, String argument3, String argument4) {
    return jdbc.sql(
            "INSERT INTO"
                + " generation_resource_attempt(check_id,attempt,completion_json,artifacts_json,report_json)"
                + " VALUES (?,?,?,?,?)")
        .param(id)
        .param(attempts)
        .param(argument2)
        .param(argument3)
        .param(argument4)
        .update();
  }

  public Integer failGenerationRecoveryReceipt(UUID id, UUID argument1) {
    return jdbc.sql("SELECT count(*) FROM generation_recovery_receipt WHERE job_id=? AND token=?")
        .param(id)
        .param(argument1)
        .query(Integer.class)
        .single();
  }

  public int failGenerationRecoveryReceipt2(UUID id, UUID argument1, String argument2) {
    return jdbc.sql(
            "INSERT INTO generation_recovery_receipt(job_id,token,completion_json) VALUES (?,?,?)")
        .param(id)
        .param(argument1)
        .param(argument2)
        .update();
  }

  public int failGenerationResourceExecution3(UUID id) {
    return jdbc.sql("DELETE FROM generation_resource_execution WHERE check_id=?")
        .param(id)
        .update();
  }

  public int failGenerationResourceCheck3(String error, UUID id) {
    return jdbc.sql(
            "UPDATE generation_resource_check SET"
                + " retries=retries+1,status='MEASURING',limits_json=NULL,error_code=? WHERE id=?")
        .param(error)
        .param(id)
        .update();
  }

  public String failGenerationResourceCheck4(UUID id) {
    return jdbc.sql("SELECT input_json FROM generation_resource_check WHERE id=?")
        .param(id)
        .query(String.class)
        .single();
  }

  public int failGenerationResourceCheck5(String error, UUID id) {
    return jdbc.sql(
            "UPDATE generation_resource_check SET"
                + " retries=retries+1,status='QUEUED',completion_json=NULL,artifacts_json=NULL,limits_json=NULL,token=NULL,error_code=?"
                + " WHERE id=?")
        .param(error)
        .param(id)
        .update();
  }

  public int failGenerationResourceCheck6(String argument0, UUID id) {
    return jdbc.sql(
            "UPDATE generation_resource_check SET status='NEEDS_REVIEW',error_code=? WHERE id=?")
        .param(argument0)
        .param(id)
        .update();
  }
}
