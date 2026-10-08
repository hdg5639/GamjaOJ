package dev.gamjaoj.admin.repository;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AdminSecurityRepository {
  public record Access(boolean blocked, String role, int epoch) {}

  private final JdbcClient jdbc;

  public AdminSecurityRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Access> access(String username) {
    return jdbc.sql("SELECT blocked,admin_role,access_epoch FROM app_user WHERE username=:name")
        .param("name", username)
        .query((r, n) -> new Access(r.getBoolean(1), r.getString(2), r.getInt(3)))
        .optional();
  }

  public record Verification(int failures, long lockedUntil) {}

  public Verification verification(String username) {
    return jdbc.sql(
            "SELECT admin_verify_failures,admin_verify_locked_until FROM app_user WHERE username=?"
                + " FOR UPDATE")
        .param(username)
        .query((r, n) -> new Verification(r.getInt(1), r.getLong(2)))
        .single();
  }

  public void verification(String username, int failures, long until) {
    jdbc.sql(
            "UPDATE app_user SET admin_verify_failures=?,admin_verify_locked_until=? WHERE"
                + " username=?")
        .param(failures)
        .param(until)
        .param(username)
        .update();
  }

  public Optional<String> maintenanceMessage() {
    return jdbc.sql("SELECT message FROM admin_site_setting WHERE id=1 AND maintenance=true")
        .query(String.class)
        .optional();
  }
}
