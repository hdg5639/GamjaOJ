package dev.gamjaoj.repository.learning;

import dev.gamjaoj.repository.problem.ProblemSql;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence operations for MyActivity; transaction ownership remains in the service. */
@Repository
public class MyActivityRepository {
  private final JdbcClient jdbc;

  public MyActivityRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private static final String FILTER =
      "s.user_id=? AND s.run_input IS NULL AND s.generation_job_id IS NULL AND s.spec_draft_id IS"
          + " NULL AND s.hybrid_branch_id IS NULL";

  public <T> T summarySubmission(UUID owner, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT count(*) submitted,count(DISTINCT s.problem_version) attempted,count(DISTINCT"
                + " CASE WHEN j.verdict='AC' AND p.review_hold=false THEN s.problem_version END)"
                + " solved FROM submission s JOIN judge_job j ON j.submission_id=s.id JOIN"
                + " problem_version p ON p.id=s.problem_version WHERE "
                + FILTER)
        .param(owner)
        .query(mapper)
        .single();
  }

  public <T> List<T> growthProblemVersion(UUID owner, UUID ownerArgument1, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT p.id,CASE WHEN p.catalog_category IS NOT NULL THEN p.catalog_category WHEN p.id"
                + " IN ('sum-v1','total-v1') THEN '수열' WHEN p.id='valid-parentheses-v1' THEN '문자열'"
                + " ELSE '미분류' END category,t.layer,t.source FROM problem_version p "
                + ProblemSql.JOIN
                + " WHERE p.ready=true AND p.review_hold=false AND p.diagnostic_only=false AND"
                + " (p.owner_id IS NULL OR p.owner_id=? OR p.shared=true) AND EXISTS (SELECT 1 FROM"
                + " submission s JOIN judge_job j ON j.submission_id=s.id WHERE"
                + " s.problem_version=p.id AND "
                + FILTER
                + " AND s.diagnostic_item_id IS NULL AND s.example_check=false AND"
                + " j.status='FINISHED' AND j.verdict='AC')")
        .param(owner)
        .param(ownerArgument1)
        .query(mapper)
        .list();
  }

  public Long problemsSubmission(UUID owner) {
    return jdbc.sql("SELECT count(DISTINCT s.problem_version) FROM submission s WHERE " + FILTER)
        .param(owner)
        .query(Long.class)
        .single();
  }

  public <T> List<T> problemsSubmission2(UUID owner, int argument1, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT p.id,p.package_json,p.review_hold,p.diagnostic_only,count(*) attempts,sum(CASE"
                + " WHEN j.verdict='AC' THEN 1 ELSE 0 END) accepted,max(s.created_at)"
                + " latest,p.catalog_category,max(r.confidence) confidence,max(r.note)"
                + " reflection_note FROM submission s JOIN judge_job j ON j.submission_id=s.id JOIN"
                + " problem_version p ON p.id=s.problem_version LEFT JOIN problem_reflection r ON"
                + " r.user_id=s.user_id AND r.problem_version=p.id WHERE "
                + FILTER
                + " GROUP BY p.id,p.package_json,p.review_hold,p.diagnostic_only,p.catalog_category"
                + " ORDER BY latest DESC,p.id DESC LIMIT 20 OFFSET ?")
        .param(owner)
        .param(argument1)
        .query(mapper)
        .list();
  }
}
