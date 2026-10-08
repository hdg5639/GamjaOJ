package dev.gamjaoj.repository.problem;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence operations for ThinkingAssessmentPublication; transaction ownership remains in the
 * service.
 */
@Repository
public class ThinkingAssessmentPublicationRepository {
  private final JdbcClient jdbc;

  public ThinkingAssessmentPublicationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public int publishProblemThinkingProfile(
      int argument0,
      int argument1,
      int argument2,
      int argument3,
      String argument4,
      String kind,
      String version) {
    return jdbc.sql(
            "INSERT INTO"
                + " problem_thinking_profile(problem_version,package_sha256,layer,insight,implementation,edge_cases,rationale,source,assessment_kind)"
                + " SELECT id,package_sha256,?,?,?,?,?,'CURATED_ESTIMATE',? FROM problem_version"
                + " WHERE id=? AND ready=true")
        .param(argument0)
        .param(argument1)
        .param(argument2)
        .param(argument3)
        .param(argument4)
        .param(kind)
        .param(version)
        .update();
  }
}
