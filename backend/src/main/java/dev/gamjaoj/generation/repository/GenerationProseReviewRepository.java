package dev.gamjaoj.generation.repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence operations for GenerationProseReview; transaction ownership remains in the service.
 */
@Repository
public class GenerationProseReviewRepository {
  private final JdbcClient jdbc;

  public GenerationProseReviewRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public int startGenerationProseReview(
      UUID argument0, UUID argument1, int argument2, String argument3, String argument4) {
    return jdbc.sql(
            "INSERT INTO"
                + " generation_prose_review(id,job_id,revision,artifact_hash,input_json,status)"
                + " VALUES (?,?,?,?,?,'QUEUED')")
        .param(argument0)
        .param(argument1)
        .param(argument2)
        .param(argument3)
        .param(argument4)
        .update();
  }

  public Optional<String> progressGenerationProseReview(UUID job, int revision) {
    return jdbc.sql("SELECT status FROM generation_prose_review WHERE job_id=? AND revision=?")
        .param(job)
        .param(revision)
        .query(String.class)
        .optional();
  }

  public Integer containsGenerationProseReview(UUID id) {
    return jdbc.sql("SELECT count(*) FROM generation_prose_review WHERE id=?")
        .param(id)
        .query(Integer.class)
        .single();
  }

  public Integer passedGenerationProseReview(UUID job, int revision, String hash) {
    return jdbc.sql(
            "SELECT count(*) FROM generation_prose_review WHERE job_id=? AND revision=? AND"
                + " artifact_hash=? AND status='PASSED'")
        .param(job)
        .param(revision)
        .param(hash)
        .query(Integer.class)
        .single();
  }

  public int claimGenerationJob() {
    return jdbc.sql(
            "UPDATE generation_job SET status='NEEDS_REVIEW',error_code='GENERATION_INTERRUPTED'"
                + " WHERE status='AWAITING_REVIEW' AND EXISTS (SELECT 1 FROM"
                + " generation_prose_review r WHERE r.job_id=generation_job.id AND"
                + " r.revision=generation_job.revision AND r.status='GENERATING' AND"
                + " r.lease_until<CURRENT_TIMESTAMP)")
        .update();
  }

  public int claimGenerationProseReview() {
    return jdbc.sql(
            "UPDATE generation_prose_review SET status='NEEDS_REVIEW' WHERE status='GENERATING' AND"
                + " lease_until<CURRENT_TIMESTAMP")
        .update();
  }

  public Integer claimGenerationProseReview2() {
    return jdbc.sql("SELECT count(*) FROM generation_prose_review WHERE status='GENERATING'")
        .query(Integer.class)
        .single();
  }

  public <T> Optional<T> claimGenerationProseReview3(RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT r.id,r.input_json,r.revision FROM generation_prose_review r JOIN generation_job"
                + " j ON j.id=r.job_id WHERE r.status='QUEUED' AND j.status='AWAITING_REVIEW' AND"
                + " j.revision=r.revision ORDER BY r.created_at LIMIT 1 FOR UPDATE")
        .query(mapper)
        .optional();
  }

  public int claimGenerationProseReview4(UUID token, OffsetDateTime argument1, UUID id) {
    return jdbc.sql(
            "UPDATE generation_prose_review SET status='GENERATING',token=?,lease_until=? WHERE"
                + " id=?")
        .param(token)
        .param(argument1)
        .param(id)
        .update();
  }

  public <T> T completeGenerationProseReview(UUID id, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT job_id,revision,artifact_hash,token,status,completion_json FROM"
                + " generation_prose_review WHERE id=? FOR UPDATE")
        .param(id)
        .query(mapper)
        .single();
  }

  public Integer completeGenerationProseReview2(UUID id) {
    return jdbc.sql(
            "SELECT count(*) FROM generation_prose_review WHERE id=? AND"
                + " lease_until>CURRENT_TIMESTAMP")
        .param(id)
        .query(Integer.class)
        .single();
  }

  public int completeGenerationProseReview3(String audit, Object argument1, UUID id) {
    return jdbc.sql("UPDATE generation_prose_review SET completion_json=?,status=? WHERE id=?")
        .param(audit)
        .param(argument1)
        .param(id)
        .update();
  }

  public Integer completeGenerationJob(UUID job, int revision, String argument2) {
    return jdbc.sql(
            "SELECT count(*) FROM generation_job WHERE id=? AND revision=? AND artifacts_sha256=?"
                + " AND status='AWAITING_REVIEW'")
        .param(job)
        .param(revision)
        .param(argument2)
        .query(Integer.class)
        .single();
  }
}
