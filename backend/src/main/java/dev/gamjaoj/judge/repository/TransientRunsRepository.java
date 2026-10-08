package dev.gamjaoj.judge.repository;

import java.time.OffsetDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence operations for TransientRuns; transaction ownership remains in the service. */
@Repository
public class TransientRunsRepository {
  private final JdbcClient jdbc;

  public TransientRunsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public int cleanSubmission(OffsetDateTime argument0) {
    return jdbc.sql(
            "DELETE FROM submission WHERE run_input IS NOT NULL AND generation_job_id IS NULL AND"
                + " spec_draft_id IS NULL AND id IN (SELECT submission_id FROM judge_job WHERE"
                + " status='FINISHED' AND finished_at<?) AND id NOT IN (SELECT submission_id FROM"
                + " generation_execution) AND id NOT IN (SELECT submission_id FROM"
                + " generation_spec_execution)")
        .param(argument0)
        .update();
  }
}
