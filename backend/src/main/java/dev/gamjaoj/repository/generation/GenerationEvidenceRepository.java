package dev.gamjaoj.repository.generation;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence operations for GenerationEvidence; transaction ownership remains in the service. */
@Repository
public class GenerationEvidenceRepository {
  private final JdbcClient jdbc;

  public GenerationEvidenceRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public <T> List<T> captureGenerationExecution(UUID job, int revision, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT"
                + " e.role,e.expected_verdict,s.source_sha256,s.run_package,s.run_package_sha256,s.runtime_image,s.runner_policy,j.execution_mode,j.result_json,j.result_sha256,s.source_code,a.execution_environment_json"
                + " FROM generation_execution e JOIN submission s ON s.id=e.submission_id JOIN"
                + " judge_job j ON j.submission_id=s.id LEFT JOIN judge_attempt a ON"
                + " a.submission_id=j.submission_id AND a.attempt=j.attempt WHERE e.job_id=? AND"
                + " e.revision=? ORDER BY e.role")
        .param(job)
        .param(revision)
        .query(mapper)
        .list();
  }
}
