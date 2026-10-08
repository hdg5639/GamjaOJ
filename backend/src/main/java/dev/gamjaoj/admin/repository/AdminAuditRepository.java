package dev.gamjaoj.admin.repository;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AdminAuditRepository {
  private final JdbcClient jdbc;

  public AdminAuditRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void record(
      String actor, String action, String target, String reason, String before, String after) {
    jdbc.sql(
            "INSERT INTO admin_audit(id,actor,action,target,reason,before_json,after_json)"
                + " VALUES(:id,:actor,:action,:target,:reason,:before,:after)")
        .param("id", UUID.randomUUID())
        .param("actor", actor)
        .param("action", action)
        .param("target", target)
        .param("reason", reason)
        .param("before", before)
        .param("after", after)
        .update();
  }
}
