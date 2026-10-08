package dev.gamjaoj.problem.repository;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence operations for ProblemCatalog; transaction ownership remains in the service. */
@Repository
public class ProblemCatalogRepository {
  private final JdbcClient jdbc;

  public ProblemCatalogRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public int saveProblemVersion(
      Boolean argument0,
      String argument1,
      String argument2,
      String argument3,
      String version,
      UUID owner,
      Boolean argument6) {
    return jdbc.sql(
            "UPDATE problem_version SET"
                + " shared=?,catalog_category=?,catalog_tags=?,catalog_difficulty=? WHERE id=? AND"
                + " owner_id=? AND ready=true AND diagnostic_only=false AND (review_hold=false OR"
                + " ?=false)")
        .param(argument0)
        .param(argument1)
        .param(argument2)
        .param(argument3)
        .param(version)
        .param(owner)
        .param(argument6)
        .update();
  }

  public int saveProblemThinkingProfile(String version) {
    return jdbc.sql("DELETE FROM problem_thinking_profile WHERE problem_version=?")
        .param(version)
        .update();
  }

  public int saveProblemThinkingProfile2(String version) {
    return jdbc.sql("DELETE FROM problem_thinking_profile WHERE problem_version=?")
        .param(version)
        .update();
  }

  public int saveProblemThinkingProfile3(
      Integer argument0,
      Integer argument1,
      Integer argument2,
      Integer argument3,
      String argument4,
      String version) {
    return jdbc.sql(
            "INSERT INTO"
                + " problem_thinking_profile(problem_version,package_sha256,layer,insight,implementation,edge_cases,rationale,source)"
                + " SELECT id,package_sha256,?,?,?,?,?,'AUTHOR_ESTIMATE' FROM problem_version WHERE"
                + " id=?")
        .param(argument0)
        .param(argument1)
        .param(argument2)
        .param(argument3)
        .param(argument4)
        .param(version)
        .update();
  }
}
