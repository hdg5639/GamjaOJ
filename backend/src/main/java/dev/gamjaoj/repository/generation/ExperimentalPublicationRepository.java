package dev.gamjaoj.repository.generation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence operations for ExperimentalPublication; transaction ownership remains in the service.
 */
@Repository
public class ExperimentalPublicationRepository {
  private final JdbcClient jdbc;

  public ExperimentalPublicationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<String> fieldGenerationSpecDraft(String columnName, UUID id) {
    return jdbc.sql("SELECT " + columnName + " FROM generation_spec_draft WHERE id=?")
        .param(id)
        .query(String.class)
        .optional();
  }

  public int startGenerationSpecDraft(String argument0, UUID id) {
    return jdbc.sql(
            "UPDATE generation_spec_draft SET status='FINAL_REJECTED',final_report_json=? WHERE"
                + " id=?")
        .param(argument0)
        .param(id)
        .update();
  }

  public int startGenerationSpecDraft2(UUID id) {
    return jdbc.sql(
            "UPDATE generation_spec_draft SET status='FINAL_CHECKING',final_stage=1 WHERE id=?")
        .param(id)
        .update();
  }

  public int failGenerationSpecDraft(String reason, UUID id) {
    return jdbc.sql(
            "UPDATE generation_spec_draft SET"
                + " status='FINAL_FAILED',error_code=?,updated_at=CURRENT_TIMESTAMP WHERE id=?")
        .param(reason)
        .param(id)
        .update();
  }

  public List<UUID> advanceGenerationSpecDraft() {
    return jdbc.sql("SELECT id FROM generation_spec_draft WHERE status='FINAL_CHECKING'")
        .query(UUID.class)
        .list();
  }

  public <T> List<T> advanceGenerationSpecExecution(UUID id, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT e.role,e.expected_verdict,j.status,j.verdict,j.result_json FROM"
                + " generation_spec_execution e JOIN judge_job j ON j.submission_id=e.submission_id"
                + " WHERE e.draft_id=? AND e.role LIKE 'final-%' ORDER BY e.role")
        .param(id)
        .query(mapper)
        .list();
  }

  public int advanceGenerationSpecDraft2(String argument0, UUID id) {
    return jdbc.sql("UPDATE generation_spec_draft SET final_inputs_json=?,final_stage=2 WHERE id=?")
        .param(argument0)
        .param(id)
        .update();
  }

  public int advanceProblemVersion(String saved, String argument1, String argument2) {
    return jdbc.sql(
            "UPDATE problem_version SET package_json=?,package_sha256=? WHERE id=? AND ready=false")
        .param(saved)
        .param(argument1)
        .param(argument2)
        .update();
  }

  public int advanceGenerationSpecDraft3(UUID id) {
    return jdbc.sql("UPDATE generation_spec_draft SET final_stage=3 WHERE id=?").param(id).update();
  }

  public <T> T advanceProblemVersion2(String argument0, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT package_json,package_sha256 FROM problem_version WHERE id=? AND ready=false")
        .param(argument0)
        .query(mapper)
        .single();
  }

  public String advanceGenerationSpecExecution2(UUID id, String role) {
    return jdbc.sql(
            "SELECT s.run_package FROM generation_spec_execution e JOIN submission s ON"
                + " s.id=e.submission_id WHERE e.draft_id=? AND e.role=?")
        .param(id)
        .param(role)
        .query(String.class)
        .single();
  }

  public int advanceProblemVersion3(
      String limits, String argument1, String argument2, UUID id, String argument4) {
    return jdbc.sql(
            "UPDATE problem_version SET"
                + " ready=true,time_limits_json=?,teaching_json=?,catalog_category=?,shared=(SELECT"
                + " share_on_publish FROM generation_spec_draft WHERE id=?) WHERE id=? AND"
                + " ready=false")
        .param(limits)
        .param(argument1)
        .param(argument2)
        .param(id)
        .param(argument4)
        .update();
  }

  public int advanceGenerationSpecDraft4(String argument0, UUID id) {
    return jdbc.sql(
            "UPDATE generation_spec_draft SET"
                + " status='PUBLISHED',final_report_json=?,updated_at=CURRENT_TIMESTAMP WHERE id=?")
        .param(argument0)
        .param(id)
        .update();
  }
}
