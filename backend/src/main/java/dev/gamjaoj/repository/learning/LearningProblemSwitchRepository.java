package dev.gamjaoj.repository.learning;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence operations for LearningProblemSwitch; transaction ownership remains in the service.
 */
@Repository
public class LearningProblemSwitchRepository {
  private final JdbcClient jdbc;

  public LearningProblemSwitchRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public <T> Optional<T> switchProblemLearningProblemSwitch(UUID key, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT user_id,request_json,result_plan_id FROM learning_problem_switch WHERE id=?")
        .param(key)
        .query(mapper)
        .optional();
  }

  public Optional<UUID> switchProblemTrainingSession(UUID owner) {
    return jdbc.sql("SELECT id FROM training_session WHERE user_id=? AND status='ACTIVE'")
        .param(owner)
        .query(UUID.class)
        .optional();
  }

  public int switchProblemLearningProblemSwitch2(
      UUID key, UUID owner, String canonical, UUID argument3) {
    return jdbc.sql(
            "INSERT INTO learning_problem_switch(id,user_id,request_json,result_plan_id) VALUES"
                + " (?,?,?,?)")
        .param(key)
        .param(owner)
        .param(canonical)
        .param(argument3)
        .update();
  }
}
