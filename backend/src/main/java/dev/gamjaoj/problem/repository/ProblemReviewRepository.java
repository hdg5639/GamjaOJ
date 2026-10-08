package dev.gamjaoj.problem.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence operations for ProblemReview; transaction ownership remains in the service. */
@Repository
public class ProblemReviewRepository {
  private final JdbcClient jdbc;

  public ProblemReviewRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Integer holdAiBudgetLock() {
    return jdbc.sql("SELECT id FROM ai_budget_lock WHERE id=1 FOR UPDATE")
        .query(Integer.class)
        .single();
  }

  public <T> Optional<T> holdProblemVersion(String version, UUID owner, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT review_hold,review_reason FROM problem_version p WHERE p.id=? AND p.owner_id=?"
                + " AND p.ready=true AND (EXISTS (SELECT 1 FROM generation_spec_draft d WHERE"
                + " d.owner_id=p.owner_id AND d.status='PUBLISHED' AND"
                + " p.id=CONCAT('experimental-check-',CAST(d.id AS VARCHAR(36)))) OR EXISTS (SELECT"
                + " 1 FROM generation_job g WHERE g.owner_id=p.owner_id AND g.status='READY' AND"
                + " p.id=CONCAT(CONCAT(CONCAT('generated-',CAST(g.id AS"
                + " VARCHAR(36))),'-r'),CAST(g.revision AS VARCHAR(10))))) FOR UPDATE")
        .param(version)
        .param(owner)
        .query(mapper)
        .optional();
  }

  public int holdProblemVersion2(String argument0, String version) {
    return jdbc.sql(
            "UPDATE problem_version SET"
                + " review_hold=true,review_reason=?,review_held_at=CURRENT_TIMESTAMP WHERE id=?")
        .param(argument0)
        .param(version)
        .update();
  }

  public Optional<UUID> holdGenerationJob(UUID owner, String version) {
    return jdbc.sql(
            "SELECT id FROM generation_job WHERE owner_id=? AND"
                + " ?=CONCAT(CONCAT(CONCAT('generated-',CAST(id AS"
                + " VARCHAR(36))),'-r'),CAST(revision AS VARCHAR(10)))")
        .param(owner)
        .param(version)
        .query(UUID.class)
        .optional();
  }
}
