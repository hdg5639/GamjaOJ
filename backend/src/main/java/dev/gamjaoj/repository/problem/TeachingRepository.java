package dev.gamjaoj.repository.problem;

import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence operations for Teaching; transaction ownership remains in the service. */
@Repository
public class TeachingRepository {
  private final JdbcClient jdbc;

  public TeachingRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public <T> Optional<T> teachingProblemVersion(
      String version, String argument1, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT teaching_json FROM problem_version WHERE id=? AND ready=true AND"
                + " diagnostic_only=false AND review_hold=false AND (owner_id IS NULL OR"
                + " shared=true OR owner_id=(SELECT id FROM app_user WHERE username=?))")
        .param(version)
        .param(argument1)
        .query(mapper)
        .optional();
  }
}
