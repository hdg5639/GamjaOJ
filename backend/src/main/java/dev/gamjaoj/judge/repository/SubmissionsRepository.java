package dev.gamjaoj.judge.repository;

import dev.gamjaoj.shared.repository.ProblemSql;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence operations for Submissions; transaction ownership remains in the service. */
@Repository
public class SubmissionsRepository {
  private final JdbcClient jdbc;

  public SubmissionsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private static String viewQuery(boolean includeSource) {
    return "SELECT s.id,s.problem_version,s.source_sha256,"
        + (includeSource ? "s.source_code" : "NULL AS source_code")
        + ",s.created_at,s.run_input,s.training_session_id,s.runner_policy,s.diagnostic_item_id,s.language,s.execution_profile_json,p.review_hold,j.status,j.verdict,j.result_json,j.finished_at,"
        + (includeSource
            ? "CASE WHEN s.run_input IS NULL AND j.status='FINISHED' THEN"
                + " COALESCE(s.callable_package,d.package_json,p.package_json) END"
            : "NULL")
        + " AS plan_json FROM submission s JOIN problem_version p ON p.id=s.problem_version JOIN"
        + " judge_job j ON s.id=j.submission_id LEFT JOIN diagnostic_item d ON"
        + " d.id=s.diagnostic_item_id";
  }

  public Optional<UUID> ownerAppUser(boolean lock, String username) {
    return jdbc.sql("SELECT id FROM app_user WHERE username = ?" + (lock ? " FOR UPDATE" : ""))
        .param(username)
        .query(UUID.class)
        .optional();
  }

  public Integer problemsExecutionGrant(UUID owner) {
    return jdbc.sql("SELECT count(*) FROM execution_grant WHERE user_id = ?")
        .param(owner)
        .query(Integer.class)
        .single();
  }

  public <T> List<T> findVisibleProblems(
      UUID owner, Set<String> cachedHashes, RowMapper<T> mapper) {
    String packageColumn =
        cachedHashes.isEmpty()
            ? "p.package_json"
            : "CASE WHEN p.package_sha256 IN ("
                + String.join(",", java.util.Collections.nCopies(cachedHashes.size(), "?"))
                + ") THEN NULL ELSE p.package_json END";
    var query =
        jdbc.sql(
            "SELECT"
                + " p.id,p.package_sha256,p.owner_id,p.shared,p.review_hold,p.review_reason,p.catalog_category,p.catalog_tags,p.catalog_difficulty,p.time_limits_json,p.examples_json,"
                + packageColumn
                + " AS package_json,"
                + ProblemSql.COLUMNS
                + ",g.template_id,g.focus,d.spec_json,COALESCE(progress.submissions,0) AS"
                + " my_submissions,COALESCE(progress.accepted,0) AS"
                + " my_accepted,COALESCE(progress.pending,0) AS my_pending FROM problem_version p "
                + ProblemSql.JOIN
                + " LEFT JOIN (SELECT s.problem_version,COUNT(*) AS submissions,SUM(CASE WHEN"
                + " j.status='FINISHED' AND j.verdict='AC' THEN 1 ELSE 0 END) AS accepted,SUM(CASE"
                + " WHEN j.status<>'FINISHED' THEN 1 ELSE 0 END) AS pending FROM submission s JOIN"
                + " judge_job j ON j.submission_id=s.id WHERE s.user_id=? AND s.run_input IS NULL"
                + " AND s.generation_job_id IS NULL AND s.spec_draft_id IS NULL AND"
                + " s.hybrid_branch_id IS NULL AND s.diagnostic_item_id IS NULL GROUP BY"
                + " s.problem_version) progress ON progress.problem_version=p.id LEFT JOIN"
                + " generation_job g ON p.id=CONCAT(CONCAT(CONCAT('generated-',CAST(g.id AS"
                + " VARCHAR(36))),'-r'),CAST(g.revision AS VARCHAR(10))) LEFT JOIN"
                + " generation_spec_draft d ON p.id=CONCAT('experimental-check-',CAST(d.id AS"
                + " VARCHAR(36))) WHERE p.ready=true AND p.diagnostic_only=false AND (p.owner_id IS"
                + " NULL OR p.owner_id=? OR (p.shared=true AND p.review_hold=false)) ORDER BY"
                + " p.id");
    for (String hash : cachedHashes) query.param(hash);
    return query.param(owner).param(owner).query(mapper).list();
  }

  public Optional<UUID> saveSubmission(UUID user, UUID key) {
    return jdbc.sql("SELECT id FROM submission WHERE user_id = ? AND idempotency_key = ?")
        .param(user)
        .param(key)
        .query(UUID.class)
        .optional();
  }

  public Integer saveExecutionGrant(UUID user, String hash) {
    return jdbc.sql("SELECT count(*) FROM execution_grant WHERE user_id = ? AND source_sha256 = ?")
        .param(user)
        .param(hash)
        .query(Integer.class)
        .single();
  }

  public Integer saveProblemVersion(String argument0, UUID user) {
    return jdbc.sql(
            "SELECT count(*) FROM problem_version WHERE id = ? AND ready = true AND"
                + " review_hold=false AND (owner_id IS NULL OR owner_id=? OR shared=true)")
        .param(argument0)
        .param(user)
        .query(Integer.class)
        .single();
  }

  public Integer saveSubmission2(UUID user) {
    return jdbc.sql(
            "SELECT count(*) FROM submission s JOIN judge_job j ON s.id=j.submission_id WHERE"
                + " s.user_id=? AND s.generation_job_id IS NULL AND s.spec_draft_id IS NULL AND"
                + " s.hybrid_branch_id IS NULL AND s.example_check=false AND j.status <>"
                + " 'FINISHED'")
        .param(user)
        .query(Integer.class)
        .single();
  }

  public String saveProblemVersion2(String argument0) {
    return jdbc.sql("SELECT package_json FROM problem_version WHERE id=?")
        .param(argument0)
        .query(String.class)
        .single();
  }

  public Boolean saveProblemVersion3(String argument0) {
    return jdbc.sql("SELECT diagnostic_only FROM problem_version WHERE id=?")
        .param(argument0)
        .query(Boolean.class)
        .single();
  }

  public <T> Optional<T> saveTrainingSession(UUID argument0, UUID user, RowMapper<T> mapper) {
    return jdbc.sql("SELECT problem_version,status FROM training_session WHERE id=? AND user_id=?")
        .param(argument0)
        .param(user)
        .query(mapper)
        .optional();
  }

  public <T> Optional<T> saveProblemVersion4(String argument0, RowMapper<T> mapper) {
    return jdbc.sql("SELECT time_limits_json FROM problem_version WHERE id=?")
        .param(argument0)
        .query(mapper)
        .optional();
  }

  public int saveSubmission3(
      UUID id,
      UUID user,
      String argument2,
      String argument3,
      String hash,
      UUID key,
      String argument6,
      String argument7,
      String language,
      String argument9) {
    return jdbc.sql(
            "INSERT INTO submission"
                + " (id,user_id,problem_version,source_code,source_sha256,idempotency_key,runtime_image,runner_policy,language,execution_profile_json)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?)")
        .param(id)
        .param(user)
        .param(argument2)
        .param(argument3)
        .param(hash)
        .param(key)
        .param(argument6)
        .param(argument7)
        .param(language)
        .param(argument9)
        .update();
  }

  public int saveSubmission4(UUID argument0, UUID id) {
    return jdbc.sql("UPDATE submission SET training_session_id=? WHERE id=?")
        .param(argument0)
        .param(id)
        .update();
  }

  public int saveSubmission5(UUID argument0, UUID id) {
    return jdbc.sql("UPDATE submission SET diagnostic_item_id=? WHERE id=?")
        .param(argument0)
        .param(id)
        .update();
  }

  public int saveSubmission6(String json, String argument1, UUID id) {
    return jdbc.sql("UPDATE submission SET callable_package=?,callable_package_sha256=? WHERE id=?")
        .param(json)
        .param(argument1)
        .param(id)
        .update();
  }

  public int saveSubmission7(String input, String json, String argument2, UUID id) {
    return jdbc.sql(
            "UPDATE submission SET run_input=?,run_package=?,run_package_sha256=? WHERE id=?")
        .param(input)
        .param(json)
        .param(argument2)
        .param(id)
        .update();
  }

  public int saveJudgeJob(UUID id, String executionMode) {
    return jdbc.sql("INSERT INTO judge_job (submission_id,execution_mode) VALUES (?,?)")
        .param(id)
        .param(executionMode)
        .update();
  }

  public String savedRunInputsSubmission(UUID id) {
    return jdbc.sql("SELECT run_package FROM submission WHERE id=?")
        .param(id)
        .query(String.class)
        .single();
  }

  public <T> List<T> findHistory(
      UUID user, String version, int size, int offset, RowMapper<T> mapper) {
    String filter = version == null ? "" : " AND s.problem_version=?";
    var query =
        jdbc.sql(
                viewQuery(false)
                    + " WHERE s.generation_job_id IS NULL AND s.spec_draft_id IS NULL AND"
                    + " s.hybrid_branch_id IS NULL AND s.user_id=? AND s.run_input IS NULL"
                    + filter
                    + " ORDER BY s.created_at DESC,s.id DESC LIMIT ? OFFSET ?")
            .param(user);
    if (version != null) query.param(version);
    return query.param(size).param(offset).query(mapper).list();
  }

  public <T> Optional<T> findRow(boolean includeSource, UUID id, UUID user, RowMapper<T> mapper) {
    return jdbc.sql(
            viewQuery(includeSource)
                + " WHERE s.id=? AND s.user_id=? AND s.spec_draft_id IS NULL AND s.hybrid_branch_id"
                + " IS NULL")
        .param(id)
        .param(user)
        .query(mapper)
        .optional();
  }
}
