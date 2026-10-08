package dev.gamjaoj.repository.problem;

/** Shared SQL projection for persisted thinking profiles. */
public final class ProblemSql {
  private ProblemSql() {}

  public static final String COLUMNS =
      "t.layer AS thinking_layer,t.insight AS thinking_insight,t.implementation AS"
          + " thinking_implementation,t.edge_cases AS thinking_edge_cases,t.rationale AS"
          + " thinking_rationale,CASE WHEN t.assessment_kind='MODEL' THEN 'MODEL_ESTIMATE' ELSE"
          + " t.source END AS thinking_source";
  public static final String JOIN =
      " LEFT JOIN problem_thinking_profile t ON t.problem_version=p.id AND"
          + " t.package_sha256=p.package_sha256 ";
}
