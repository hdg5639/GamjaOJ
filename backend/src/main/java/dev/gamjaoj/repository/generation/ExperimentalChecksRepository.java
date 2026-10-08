package dev.gamjaoj.repository.generation;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence operations for ExperimentalChecks; transaction ownership remains in the service. */
@Repository
public class ExperimentalChecksRepository {
  private final JdbcClient jdbc;

  public ExperimentalChecksRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public int executeSubmission(
      UUID submission,
      String argument1,
      String source,
      String argument3,
      UUID submissionArgument4,
      Object argument5,
      String argument6,
      String json,
      String argument8,
      String argument9,
      UUID id) {
    return jdbc.sql(
            "INSERT INTO submission"
                + " (id,user_id,problem_version,source_code,source_sha256,idempotency_key,runtime_image,runner_policy,run_input,run_package,run_package_sha256,spec_draft_id)"
                + " SELECT ?,d.owner_id,?,?,?,?,p.runtime_image,?,?,?,?,d.id FROM"
                + " generation_spec_draft d JOIN problem_version p ON p.id=? WHERE d.id=?")
        .param(submission)
        .param(argument1)
        .param(source)
        .param(argument3)
        .param(submissionArgument4)
        .param(argument5)
        .param(argument6)
        .param(json)
        .param(argument8)
        .param(argument9)
        .param(id)
        .update();
  }

  public int executeJudgeJob(UUID submission, String argument1) {
    return jdbc.sql("INSERT INTO judge_job (submission_id,priority,execution_mode) VALUES (?,1,?)")
        .param(submission)
        .param(argument1)
        .update();
  }

  public int executeGenerationSpecExecution(
      UUID id, String role, UUID submission, String expected) {
    return jdbc.sql(
            "INSERT INTO generation_spec_execution (draft_id,role,submission_id,expected_verdict)"
                + " VALUES (?,?,?,?)")
        .param(id)
        .param(role)
        .param(submission)
        .param(expected)
        .update();
  }

  public Integer startProblemVersion(String argument0) {
    return jdbc.sql("SELECT count(*) FROM problem_version WHERE id=?")
        .param(argument0)
        .query(Integer.class)
        .single();
  }

  public int startProblemVersion2(String argument0, String json, String argument2, UUID id) {
    return jdbc.sql(
            "INSERT INTO problem_version"
                + " (id,package_json,package_sha256,runtime_image,runner_policy,ready,owner_id)"
                + " SELECT ?,?,?,p.runtime_image,p.runner_policy,false,d.owner_id FROM"
                + " problem_version p JOIN generation_spec_draft d ON d.id=? WHERE p.id='total-v1'")
        .param(argument0)
        .param(json)
        .param(argument2)
        .param(id)
        .update();
  }

  public int startProblemVersion3(String json, String argument1, String argument2) {
    return jdbc.sql(
            "UPDATE problem_version SET package_json=?,package_sha256=? WHERE id=? AND ready=false")
        .param(json)
        .param(argument1)
        .param(argument2)
        .update();
  }

  public int failGenerationSpecDraft(String error, UUID id) {
    return jdbc.sql(
            "UPDATE generation_spec_draft SET"
                + " status='BUILD_FAILED',error_code=?,updated_at=CURRENT_TIMESTAMP WHERE id=?")
        .param(error)
        .param(id)
        .update();
  }

  public List<UUID> advanceGenerationSpecDraft() {
    return jdbc.sql("SELECT id FROM generation_spec_draft WHERE status='CHECKING'")
        .query(UUID.class)
        .list();
  }

  public <T> List<T> advanceGenerationSpecExecution(UUID id, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT e.role,e.expected_verdict,j.status,j.verdict,j.result_json FROM"
                + " generation_spec_execution e JOIN judge_job j ON j.submission_id=e.submission_id"
                + " WHERE e.draft_id=? ORDER BY e.role")
        .param(id)
        .query(mapper)
        .list();
  }

  public <T> T advanceGenerationSpecDraft2(UUID id, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT spec_json,spec_sha256,build_artifacts_json,build_oracle_json,build_sha256 FROM"
                + " generation_spec_draft WHERE id=?")
        .param(id)
        .query(mapper)
        .single();
  }

  public int advanceGenerationSpecDraft3(String argument0, UUID id) {
    return jdbc.sql("UPDATE generation_spec_draft SET build_inputs_json=? WHERE id=?")
        .param(argument0)
        .param(id)
        .update();
  }

  public int advanceGenerationSpecDraft4(String argument0, UUID id) {
    return jdbc.sql(
            "UPDATE generation_spec_draft SET"
                + " status='CHECKED',build_report_json=?,updated_at=CURRENT_TIMESTAMP WHERE id=?")
        .param(argument0)
        .param(id)
        .update();
  }
}
