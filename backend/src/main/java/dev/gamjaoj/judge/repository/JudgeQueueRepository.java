package dev.gamjaoj.judge.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence operations for JudgeQueue; transaction ownership remains in the service. */
@Repository
public class JudgeQueueRepository {
  private final JdbcClient jdbc;

  public JudgeQueueRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private static final String RESOURCE_ALLOWED =
      "(b.role='VALIDATION' AND b.status='CHECKED' AND g.status='REVIEWING' AND EXISTS (SELECT 1"
          + " FROM generation_resource_execution re JOIN generation_resource_check rc ON"
          + " rc.id=re.check_id WHERE re.submission_id=s.id AND rc.pipeline='RULE' AND"
          + " rc.job_id=g.id AND rc.status IN ('MEASURING','REPLAYING')))";
  private static final String JOB_COLUMNS =
      "submission_id,status,attempt,token,worker_id,lease_until,verdict,result_json,result_sha256,execution_mode";

  public Optional<dev.gamjaoj.judge.domain.JudgeJob> jobJudgeJob(UUID id) {
    return jdbc.sql("SELECT " + JOB_COLUMNS + " FROM judge_job WHERE submission_id=? FOR UPDATE")
        .param(id)
        .query(dev.gamjaoj.judge.domain.JudgeJob.class)
        .optional();
  }

  public Integer claimJudgeQueueLock() {
    return jdbc.sql("SELECT id FROM judge_queue_lock WHERE id=1 FOR UPDATE")
        .query(Integer.class)
        .single();
  }

  public List<UUID> claimJudgeJob(OffsetDateTime now) {
    return jdbc.sql(
            "SELECT submission_id FROM judge_job WHERE status='RUNNING' AND lease_until<=? AND"
                + " attempt>=3 FOR UPDATE")
        .param(now)
        .query(UUID.class)
        .list();
  }

  public int claimJudgeJob2(String report, String argument1, OffsetDateTime now, UUID id) {
    return jdbc.sql(
            "UPDATE judge_job SET"
                + " status='FINISHED',verdict='IE',result_json=?,result_sha256=?,finished_at=?"
                + " WHERE submission_id=?")
        .param(report)
        .param(argument1)
        .param(now)
        .param(id)
        .update();
  }

  public int claimJudgeAttempt(OffsetDateTime now, UUID id) {
    return jdbc.sql(
            "UPDATE judge_attempt SET status='EXPIRED',finished_at=? WHERE submission_id=? AND"
                + " status='RUNNING'")
        .param(now)
        .param(id)
        .update();
  }

  public List<UUID> claimJudgeJob3(OffsetDateTime now, OffsetDateTime nowArgument1) {
    return jdbc.sql(
            """
SELECT j.submission_id FROM judge_job j JOIN submission s ON s.id=j.submission_id
JOIN hybrid_branch b ON b.id=s.hybrid_branch_id JOIN hybrid_generation g ON g.id=b.generation_id
WHERE (j.status='QUEUED' OR (j.status='RUNNING' AND j.lease_until<=?))
  AND (g.deadline_at<=? OR g.status IN ('FAILED','CANCELLED','DEADLINE_EXCEEDED','PUBLISHED')
       OR (b.status IN ('FAILED','CANCELLED','SUPERSEDED','CHECKED','SUCCEEDED') AND NOT %s) OR b.revision<>g.revision)"""
                .formatted(RESOURCE_ALLOWED))
        .param(now)
        .param(nowArgument1)
        .query(UUID.class)
        .list();
  }

  public int claimJudgeJob4(String report, String argument1, OffsetDateTime now, UUID id) {
    return jdbc.sql(
            "UPDATE judge_job SET"
                + " status='FINISHED',verdict='IE',result_json=?,result_sha256=?,finished_at=?"
                + " WHERE submission_id=?")
        .param(report)
        .param(argument1)
        .param(now)
        .param(id)
        .update();
  }

  public int claimJudgeAttempt2(OffsetDateTime now, UUID id) {
    return jdbc.sql(
            "UPDATE judge_attempt SET status='EXPIRED',finished_at=? WHERE submission_id=? AND"
                + " status='RUNNING'")
        .param(now)
        .param(id)
        .update();
  }

  public Optional<UUID> claimJudgeJob5(UUID worker, OffsetDateTime now) {
    return jdbc.sql(
            "SELECT submission_id FROM judge_job WHERE status='RUNNING' AND worker_id=? AND"
                + " lease_until>? AND EXISTS (SELECT 1 FROM submission s WHERE"
                + " s.id=judge_job.submission_id AND (s.hybrid_branch_id IS NULL OR EXISTS (SELECT"
                + " 1 FROM hybrid_branch b JOIN hybrid_generation g ON g.id=b.generation_id WHERE"
                + " b.id=s.hybrid_branch_id AND b.revision=g.revision AND"
                + " g.deadline_at>CURRENT_TIMESTAMP AND ((b.status='RUNNING' AND"
                + " g.status='VALIDATING') OR (b.status='EARLY' AND g.status='BUILDING') OR"
                + " (b.status='BLOCKED' AND g.status='HELD' AND"
                + " g.error_code='VALIDATION_ADAPTER_NOT_CONNECTED') OR (b.status='RUNNING' AND"
                + " g.status='QUALIFYING') OR "
                + RESOURCE_ALLOWED
                + ")))) ORDER BY created_at LIMIT 1")
        .param(worker)
        .param(now)
        .query(UUID.class)
        .optional();
  }

  public Optional<UUID> claimJudgeJob6(OffsetDateTime now) {
    return jdbc.sql(
            "SELECT submission_id FROM judge_job WHERE (status='QUEUED' OR (status='RUNNING' AND"
                + " lease_until<=? AND attempt<3)) AND EXISTS (SELECT 1 FROM submission s WHERE"
                + " s.id=judge_job.submission_id AND (s.hybrid_branch_id IS NULL OR EXISTS (SELECT"
                + " 1 FROM hybrid_branch b JOIN hybrid_generation g ON g.id=b.generation_id WHERE"
                + " b.id=s.hybrid_branch_id AND b.revision=g.revision AND"
                + " g.deadline_at>CURRENT_TIMESTAMP AND ((b.status='RUNNING' AND"
                + " g.status='VALIDATING') OR (b.status='EARLY' AND g.status='BUILDING') OR"
                + " (b.status='BLOCKED' AND g.status='HELD' AND"
                + " g.error_code='VALIDATION_ADAPTER_NOT_CONNECTED') OR (b.status='RUNNING' AND"
                + " g.status='QUALIFYING') OR "
                + RESOURCE_ALLOWED
                + ")))) ORDER BY priority,created_at,submission_id LIMIT 1 FOR UPDATE SKIP LOCKED")
        .param(now)
        .query(UUID.class)
        .optional();
  }

  public List<String> claimJudgeJob7(OffsetDateTime now) {
    return jdbc.sql("SELECT execution_mode FROM judge_job WHERE status='RUNNING' AND lease_until>?")
        .param(now)
        .query(String.class)
        .list();
  }

  public int claimJudgeAttempt3(OffsetDateTime now, UUID argument1, int argument2) {
    return jdbc.sql(
            "UPDATE judge_attempt SET status='SUPERSEDED',finished_at=? WHERE submission_id=? AND"
                + " attempt=?")
        .param(now)
        .param(argument1)
        .param(argument2)
        .update();
  }

  public int claimJudgeJob8(
      int argument0, UUID token, UUID worker, OffsetDateTime argument3, UUID argument4) {
    return jdbc.sql(
            "UPDATE judge_job SET status='RUNNING',attempt=?,token=?,worker_id=?,lease_until=?"
                + " WHERE submission_id=?")
        .param(argument0)
        .param(token)
        .param(worker)
        .param(argument3)
        .param(argument4)
        .update();
  }

  public int claimJudgeAttempt4(
      UUID argument0, int argument1, UUID token, UUID worker, String argument4) {
    return jdbc.sql(
            "INSERT INTO judge_attempt"
                + " (submission_id,attempt,token,worker_id,status,execution_environment_json)"
                + " VALUES (?,?,?,?,'RUNNING',?)")
        .param(argument0)
        .param(argument1)
        .param(token)
        .param(worker)
        .param(argument4)
        .update();
  }

  public Optional<String> assignmentJudgeAttempt(UUID argument0, int argument1) {
    return jdbc.sql(
            "SELECT execution_environment_json FROM judge_attempt WHERE submission_id=? AND"
                + " attempt=?")
        .param(argument0)
        .param(argument1)
        .query(String.class)
        .optional();
  }

  public <T> T assignmentSubmission(UUID argument0, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT"
                + " s.source_code,s.source_sha256,COALESCE(s.callable_package,s.run_package,d.package_json,p.package_json)"
                + " AS package_json,COALESCE(s.callable_package_sha256,s.run_package_sha256,d.package_sha256,p.package_sha256)"
                + " AS package_sha256,s.runtime_image,s.runner_policy,s.language,s.execution_profile_json,(s.run_input"
                + " IS NULL AND s.generation_job_id IS NULL AND s.spec_draft_id IS NULL AND"
                + " s.hybrid_branch_id IS NULL) AS judge_all FROM submission s JOIN problem_version"
                + " p ON p.id=s.problem_version LEFT JOIN diagnostic_item d ON"
                + " d.id=s.diagnostic_item_id WHERE s.id=?")
        .param(argument0)
        .query(mapper)
        .single();
  }

  public int heartbeatJudgeJob(OffsetDateTime argument0, UUID id, UUID token, OffsetDateTime now) {
    return jdbc.sql(
            "UPDATE judge_job SET lease_until=? WHERE submission_id=? AND token=? AND"
                + " status='RUNNING' AND lease_until>?")
        .param(argument0)
        .param(id)
        .param(token)
        .param(now)
        .update();
  }

  public Integer completeSubmission(UUID id) {
    return jdbc.sql(
            "SELECT count(*) FROM submission WHERE id=? AND generation_job_id IS NULL AND"
                + " spec_draft_id IS NULL AND hybrid_branch_id IS NULL")
        .param(id)
        .query(Integer.class)
        .single();
  }

  public int completeJudgeAttempt(String json, OffsetDateTime now, UUID id, UUID token) {
    return jdbc.sql(
            "UPDATE judge_attempt SET status='COMPLETED',result_json=?,finished_at=? WHERE"
                + " submission_id=? AND token=?")
        .param(json)
        .param(now)
        .param(id)
        .param(token)
        .update();
  }

  public int completeJudgeJob(UUID id) {
    return jdbc.sql(
            "UPDATE judge_job SET"
                + " status='QUEUED',execution_mode='EXCLUSIVE',token=NULL,worker_id=NULL,lease_until=NULL"
                + " WHERE submission_id=?")
        .param(id)
        .update();
  }

  public int completeJudgeJob2(
      String argument0, String json, String hash, OffsetDateTime now, UUID id) {
    return jdbc.sql(
            "UPDATE judge_job SET"
                + " status='FINISHED',verdict=?,result_json=?,result_sha256=?,finished_at=? WHERE"
                + " submission_id=?")
        .param(argument0)
        .param(json)
        .param(hash)
        .param(now)
        .param(id)
        .update();
  }

  public int completeJudgeAttempt2(String json, OffsetDateTime now, UUID id, UUID token) {
    return jdbc.sql(
            "UPDATE judge_attempt SET status='COMPLETED',result_json=?,finished_at=? WHERE"
                + " submission_id=? AND token=?")
        .param(json)
        .param(now)
        .param(id)
        .param(token)
        .update();
  }

  public Integer completeSubmission2(UUID id) {
    return jdbc.sql("SELECT count(*) FROM submission WHERE id=? AND hybrid_branch_id IS NOT NULL")
        .param(id)
        .query(Integer.class)
        .single();
  }
}
