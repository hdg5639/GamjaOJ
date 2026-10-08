package dev.gamjaoj.admin.repository;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Read-only operating projections; feature mutations stay with their owning services. */
@Repository
public class ControlRepository {
  private final JdbcClient jdbc;

  public ControlRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public long members() {
    return jdbc.sql("SELECT count(*) FROM app_user").query(Long.class).single();
  }

  public long problems() {
    return jdbc.sql(
            "SELECT count(*) FROM problem_version WHERE ready=true AND (owner_id IS NULL OR"
                + " shared=true)")
        .query(Long.class)
        .single();
  }

  public Map<String, Long> judgeQueue() {
    return counts("SELECT status,count(*) AS total FROM judge_job GROUP BY status");
  }

  public Map<String, Long> generationJobs() {
    return counts("SELECT status,count(*) AS total FROM generation_job GROUP BY status");
  }

  public Map<String, Long> aiTasks() {
    return counts("SELECT status,count(*) AS total FROM ai_task GROUP BY status");
  }

  private Map<String, Long> counts(String sql) {
    Map<String, Long> result = new LinkedHashMap<>();
    jdbc.sql(sql)
        .query((row, index) -> Map.entry(row.getString("status"), row.getLong("total")))
        .list()
        .forEach(e -> result.put(e.getKey(), e.getValue()));
    return result;
  }
}
