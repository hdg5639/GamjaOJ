package dev.gamjaoj.generation.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence operations for ExperimentalReview; transaction ownership remains in the service. */
@Repository
public class ExperimentalReviewRepository {
  private final JdbcClient jdbc;

  public ExperimentalReviewRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public <T> T snapshotGenerationSpecDraft(UUID id, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT"
                + " spec_json,spec_sha256,build_artifacts_json,build_oracle_json,build_sha256,build_report_json,review_payload_json,review_payload_sha256"
                + " FROM generation_spec_draft WHERE id=?")
        .param(id)
        .query(mapper)
        .single();
  }

  public int startGenerationSpecDraft(String argument0, Object argument1, UUID id) {
    return jdbc.sql("UPDATE generation_spec_draft SET review_report_json=?,status=? WHERE id=?")
        .param(argument0)
        .param(argument1)
        .param(id)
        .update();
  }

  public int failGenerationSpecDraft(String error, UUID id) {
    return jdbc.sql(
            "UPDATE generation_spec_draft SET"
                + " status='REVIEW_FAILED',error_code=?,updated_at=CURRENT_TIMESTAMP WHERE id=?")
        .param(error)
        .param(id)
        .update();
  }

  public List<UUID> advanceGenerationSpecDraft() {
    return jdbc.sql("SELECT id FROM generation_spec_draft WHERE status='REVIEW_CHECKING'")
        .query(UUID.class)
        .list();
  }

  public <T> List<T> advanceGenerationSpecExecution(UUID id, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT e.role,e.expected_verdict,j.status,j.verdict,j.result_json FROM"
                + " generation_spec_execution e JOIN judge_job j ON j.submission_id=e.submission_id"
                + " WHERE e.draft_id=? AND e.role LIKE 'review-%' ORDER BY e.role")
        .param(id)
        .query(mapper)
        .list();
  }

  public int advanceGenerationSpecDraft2(String argument0, UUID id) {
    return jdbc.sql("UPDATE generation_spec_draft SET review_report_json=? WHERE id=?")
        .param(argument0)
        .param(id)
        .update();
  }

  public int advanceGenerationSpecDraft3(UUID id) {
    return jdbc.sql(
            "UPDATE generation_spec_draft SET status='REVIEW_CHECKED',updated_at=CURRENT_TIMESTAMP"
                + " WHERE id=?")
        .param(id)
        .update();
  }
}
