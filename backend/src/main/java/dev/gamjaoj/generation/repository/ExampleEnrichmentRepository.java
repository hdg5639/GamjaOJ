package dev.gamjaoj.generation.repository;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence operations for ExampleEnrichment; transaction ownership remains in the service. */
@Repository
public class ExampleEnrichmentRepository {
  private final JdbcClient jdbc;

  public ExampleEnrichmentRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<String> advanceProblemVersion() {
    return jdbc.sql(
            """
SELECT p.id FROM problem_version p JOIN hybrid_generation g ON g.published_version_id=p.id
WHERE p.ready=true AND p.examples_status IS NULL AND g.status='PUBLISHED' ORDER BY g.updated_at LIMIT 3""")
        .query(String.class)
        .list();
  }

  public List<String> advanceProblemVersion2() {
    return jdbc.sql(
            "SELECT id FROM problem_version WHERE examples_status='CHECKING' ORDER BY id LIMIT 10")
        .query(String.class)
        .list();
  }

  public Stream<String> latestHybridBranch(UUID generation, int revision, String role) {
    return jdbc
        .sql(
            "SELECT a.payload_json FROM hybrid_branch b JOIN hybrid_artifact a ON a.branch_id=b.id"
                + " WHERE b.generation_id=? AND b.revision=? AND b.role=? AND b.status='SUCCEEDED'"
                + " ORDER BY b.attempt DESC")
        .param(generation)
        .param(revision)
        .param(role)
        .query(String.class)
        .list()
        .stream();
  }

  public <T> T startHybridGeneration(String version, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT g.id,g.revision,g.owner_id FROM hybrid_generation g WHERE"
                + " g.published_version_id=?")
        .param(version)
        .query(mapper)
        .single();
  }

  public String startProblemVersion(String version) {
    return jdbc.sql("SELECT runtime_image FROM problem_version WHERE id=?")
        .param(version)
        .query(String.class)
        .single();
  }

  public int startProblemVersion2(String argument0, String version) {
    return jdbc.sql(
            "UPDATE problem_version SET examples_json=?,examples_status='CHECKING' WHERE id=?")
        .param(argument0)
        .param(version)
        .update();
  }

  public int queueSubmission(
      UUID id,
      UUID owner,
      String version,
      String source,
      String argument4,
      UUID idArgument5,
      String runtime,
      String argument7,
      String argument8,
      String payload,
      String argument10) {
    return jdbc.sql(
            "INSERT INTO"
                + " submission(id,user_id,problem_version,source_code,source_sha256,idempotency_key,runtime_image,runner_policy,run_input,run_package,run_package_sha256,example_check)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,true)")
        .param(id)
        .param(owner)
        .param(version)
        .param(source)
        .param(argument4)
        .param(idArgument5)
        .param(runtime)
        .param(argument7)
        .param(argument8)
        .param(payload)
        .param(argument10)
        .update();
  }

  public int queueJudgeJob(UUID id) {
    return jdbc.sql(
            "INSERT INTO judge_job(submission_id,priority,execution_mode) VALUES"
                + " (?,1,'FUNCTIONAL')")
        .param(id)
        .update();
  }

  public int queueExampleCheck(String version, int position, String role, UUID id) {
    return jdbc.sql(
            "INSERT INTO example_check(problem_version,position,role,submission_id) VALUES"
                + " (?,?,?,?)")
        .param(version)
        .param(position)
        .param(role)
        .param(id)
        .update();
  }

  public <T> List<T> finishExampleCheck(String version, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT c.position,c.role,j.status,j.verdict FROM example_check c JOIN judge_job j ON"
                + " j.submission_id=c.submission_id WHERE c.problem_version=?")
        .param(version)
        .query(mapper)
        .list();
  }

  public String finishProblemVersion(String version) {
    return jdbc.sql("SELECT examples_json FROM problem_version WHERE id=?")
        .param(version)
        .query(String.class)
        .single();
  }

  public int finishProblemVersion2(Object argument0, Object argument1, String version) {
    return jdbc.sql("UPDATE problem_version SET examples_json=?,examples_status=? WHERE id=?")
        .param(argument0)
        .param(argument1)
        .param(version)
        .update();
  }

  public int statusProblemVersion(String status, String version) {
    return jdbc.sql("UPDATE problem_version SET examples_status=? WHERE id=?")
        .param(status)
        .param(version)
        .update();
  }
}
