package dev.gamjaoj.repository.account;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence operations for Accounts; transaction ownership remains in the service. */
@Repository
public class AccountsRepository {
  private final JdbcClient jdbc;

  public AccountsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public <T> Optional<T> loadUserByUsernameAppUser(String username, RowMapper<T> mapper) {
    return jdbc.sql("SELECT username, password_hash FROM app_user WHERE username = :username")
        .param("username", username)
        .query(mapper)
        .optional();
  }

  public int registerAppUser(UUID id, String username, String passwordHash, String nickname) {
    return jdbc.sql(
            "INSERT INTO app_user (id, username, password_hash, nickname) VALUES (:id, :username,"
                + " :hash, :nickname)")
        .param("id", id)
        .param("username", username)
        .param("hash", passwordHash)
        .param("nickname", nickname)
        .update();
  }

  public <T> Optional<T> profileAppUser(String username, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT id, username, nickname, training_goal FROM app_user WHERE username = :username")
        .param("username", username)
        .query(mapper)
        .optional();
  }

  public int updateAppUser(String nickname, String trainingGoal, String authenticatedUsername) {
    return jdbc.sql(
            "UPDATE app_user SET nickname = :nickname, training_goal = :goal WHERE username ="
                + " :username")
        .param("nickname", nickname)
        .param("goal", trainingGoal)
        .param("username", authenticatedUsername)
        .update();
  }
}
