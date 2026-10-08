package dev.gamjaoj.learning.repository;

import dev.gamjaoj.shared.repository.ProblemSql;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence operations for LearningProgress; transaction ownership remains in the service. */
@Repository
public class LearningProgressRepository {
  private final JdbcClient jdbc;

  public LearningProgressRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private static final String FORMAL =
      "s.user_id=? AND s.run_input IS NULL AND s.generation_job_id IS NULL AND s.spec_draft_id IS"
          + " NULL AND s.hybrid_branch_id IS NULL";

  public <T> List<T> dashboardSubmission(
      UUID owner, OffsetDateTime since, OffsetDateTime until, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT s.created_at,s.problem_version FROM submission s JOIN judge_job j ON"
                + " j.submission_id=s.id JOIN problem_version p ON p.id=s.problem_version WHERE "
                + FORMAL
                + " AND p.diagnostic_only=false AND p.review_hold=false AND j.status='FINISHED' AND"
                + " j.verdict='AC' AND s.created_at>=? AND s.created_at<?")
        .param(owner)
        .param(since)
        .param(until)
        .query(mapper)
        .list();
  }

  public <T> List<T> dashboardProblemVersion(UUID owner, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT p.id,p.package_json,p.catalog_category,p.catalog_difficulty,"
                + ProblemSql.COLUMNS
                + " FROM problem_version p "
                + ProblemSql.JOIN
                + " WHERE p.ready=true AND p.review_hold=false AND p.diagnostic_only=false AND"
                + " (p.owner_id IS NULL OR p.owner_id=? OR p.shared=true) ORDER BY p.id")
        .param(owner)
        .query(mapper)
        .list();
  }

  public <T> List<T> dashboardSubmission2(
      UUID owner, OffsetDateTime argument1, OffsetDateTime until, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT s.problem_version,p.catalog_category,j.verdict FROM submission s JOIN judge_job"
                + " j ON j.submission_id=s.id JOIN problem_version p ON p.id=s.problem_version"
                + " WHERE "
                + FORMAL
                + " AND p.diagnostic_only=false AND p.review_hold=false AND j.status='FINISHED' AND"
                + " j.verdict<>'IE' AND s.created_at>=? AND s.created_at<?")
        .param(owner)
        .param(argument1)
        .param(until)
        .query(mapper)
        .list();
  }

  public List<String> dashboardSubmission3(UUID owner) {
    return jdbc.sql(
            "SELECT DISTINCT s.problem_version FROM submission s JOIN judge_job j ON"
                + " j.submission_id=s.id WHERE "
                + FORMAL
                + " AND j.status='FINISHED' AND j.verdict='AC'")
        .param(owner)
        .query(String.class)
        .list();
  }

  public <T> List<T> dashboardProblemReflection(
      UUID owner, UUID ownerArgument1, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT p.id,p.package_json,p.catalog_category,p.catalog_difficulty,r.confidence,"
                + ProblemSql.COLUMNS
                + " FROM problem_reflection r JOIN problem_version p ON p.id=r.problem_version "
                + ProblemSql.JOIN
                + " WHERE r.user_id=? AND r.confidence IN ('SHAKY','REVISIT') AND p.ready=true AND"
                + " p.review_hold=false AND p.diagnostic_only=false AND (p.owner_id IS NULL OR"
                + " p.owner_id=? OR p.shared=true) ORDER BY CASE WHEN r.confidence='REVISIT' THEN 0"
                + " ELSE 1 END,r.updated_at,p.id LIMIT 3")
        .param(owner)
        .param(ownerArgument1)
        .query(mapper)
        .list();
  }

  public Optional<UUID> findSubmission(UUID owner, String version) {
    return jdbc.sql(
            "SELECT s.id FROM submission s JOIN judge_job j ON j.submission_id=s.id JOIN"
                + " problem_version p ON p.id=s.problem_version WHERE "
                + FORMAL
                + " AND s.problem_version=? AND p.diagnostic_only=false AND p.review_hold=false AND"
                + " j.status='FINISHED' AND j.verdict='AC' ORDER BY s.created_at DESC,s.id DESC"
                + " LIMIT 1")
        .param(owner)
        .param(version)
        .query(UUID.class)
        .optional();
  }

  public <T> Optional<T> findProblemReflection(UUID owner, String version, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT submission_id,confidence,note,updated_at FROM problem_reflection WHERE"
                + " user_id=? AND problem_version=?")
        .param(owner)
        .param(version)
        .query(mapper)
        .optional();
  }

  public Optional<String> reflectSubmission(UUID owner, UUID argument1) {
    return jdbc.sql(
            "SELECT s.problem_version FROM submission s JOIN judge_job j ON j.submission_id=s.id"
                + " JOIN problem_version p ON p.id=s.problem_version WHERE "
                + FORMAL
                + " AND s.id=? AND p.diagnostic_only=false AND p.review_hold=false AND"
                + " j.status='FINISHED' AND j.verdict='AC'")
        .param(owner)
        .param(argument1)
        .query(String.class)
        .optional();
  }

  public int reflectProblemReflection(UUID owner, String version) {
    return jdbc.sql("DELETE FROM problem_reflection WHERE user_id=? AND problem_version=?")
        .param(owner)
        .param(version)
        .update();
  }

  public int reflectProblemReflection2(
      UUID argument0, String argument1, String argument2, UUID owner, String version) {
    return jdbc.sql(
            "UPDATE problem_reflection SET"
                + " submission_id=?,confidence=?,note=?,updated_at=CURRENT_TIMESTAMP WHERE"
                + " user_id=? AND problem_version=?")
        .param(argument0)
        .param(argument1)
        .param(argument2)
        .param(owner)
        .param(version)
        .update();
  }

  public int reflectProblemReflection3(
      UUID owner, String version, UUID argument2, String argument3, String argument4) {
    return jdbc.sql(
            "INSERT INTO problem_reflection(user_id,problem_version,submission_id,confidence,note)"
                + " VALUES (?,?,?,?,?)")
        .param(owner)
        .param(version)
        .param(argument2)
        .param(argument3)
        .param(argument4)
        .update();
  }
}
