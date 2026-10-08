package dev.gamjaoj.repository.account;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence operations for AccountArchive; transaction ownership remains in the service. */
@Repository
public class AccountArchiveRepository {
  private final JdbcClient jdbc;

  public AccountArchiveRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<UUID> archiveAppUser(String argument0) {
    return jdbc.sql("SELECT id FROM app_user WHERE username=?")
        .param(argument0)
        .query(UUID.class)
        .optional();
  }

  public int archiveAppUser2(UUID id, String argument1, String argument2, String argument3) {
    return jdbc.sql("INSERT INTO app_user(id,username,password_hash,nickname) VALUES (?,?,?,?)")
        .param(id)
        .param(argument1)
        .param(argument2)
        .param(argument3)
        .update();
  }

  public <T> T moveToArchiveProblemVersion(String id, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT"
                + " p.id,p.catalog_category,p.catalog_tags,p.catalog_difficulty,g.template_id,g.focus,d.spec_json"
                + " FROM problem_version p LEFT JOIN generation_job g ON"
                + " p.id=CONCAT(CONCAT(CONCAT('generated-',CAST(g.id AS"
                + " VARCHAR(36))),'-r'),CAST(g.revision AS VARCHAR(10))) LEFT JOIN"
                + " generation_spec_draft d ON p.id=CONCAT('experimental-check-',CAST(d.id AS"
                + " VARCHAR(36))) WHERE p.id=?")
        .param(id)
        .query(mapper)
        .single();
  }

  public int moveToArchiveProblemVersion2(
      UUID archive, String argument1, String argument2, String id) {
    return jdbc.sql(
            "UPDATE problem_version SET owner_id=?,catalog_category=?,catalog_tags=? WHERE id=?")
        .param(archive)
        .param(argument1)
        .param(argument2)
        .param(id)
        .update();
  }
}
