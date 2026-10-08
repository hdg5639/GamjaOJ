package dev.gamjaoj.account.repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence operations for CodeDrafts; transaction ownership remains in the service. */
@Repository
public class CodeDraftsRepository {
  private final JdbcClient jdbc;

  public CodeDraftsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public <T> Optional<T> getCodeDraft(
      UUID user, String scope, String language, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT source,updated_at FROM code_draft WHERE user_id=? AND scope=? AND language=?")
        .param(user)
        .param(scope)
        .param(language)
        .query(mapper)
        .optional();
  }

  public int saveCodeDraft(
      String source, OffsetDateTime now, UUID user, String scope, String language) {
    return jdbc.sql(
            "UPDATE code_draft SET source=?,updated_at=? WHERE user_id=? AND scope=? AND"
                + " language=?")
        .param(source)
        .param(now)
        .param(user)
        .param(scope)
        .param(language)
        .update();
  }

  public int saveCodeDraft2(
      UUID user, String scope, String language, String source, OffsetDateTime now) {
    return jdbc.sql(
            "INSERT INTO code_draft(user_id,scope,language,source,updated_at) VALUES (?,?,?,?,?)")
        .param(user)
        .param(scope)
        .param(language)
        .param(source)
        .param(now)
        .update();
  }

  public Optional<OffsetDateTime> saveCodeDraft3(UUID user) {
    return jdbc.sql(
            "SELECT updated_at FROM code_draft WHERE user_id=? ORDER BY updated_at DESC LIMIT 1"
                + " OFFSET "
                + 300)
        .param(user)
        .query(OffsetDateTime.class)
        .optional();
  }

  public int saveCodeDraft4(UUID user, OffsetDateTime cutoff) {
    return jdbc.sql("DELETE FROM code_draft WHERE user_id=? AND updated_at<=?")
        .param(user)
        .param(cutoff)
        .update();
  }
}
