package dev.gamjaoj.repository.export;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence operations for NotionTableRegistry; transaction ownership remains in the service. */
@Repository
public class NotionTableRegistryRepository {
  private final JdbcClient jdbc;

  public NotionTableRegistryRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public UUID resolveAppUser(UUID user) {
    return jdbc.sql("SELECT id FROM app_user WHERE id=? FOR UPDATE")
        .param(user)
        .query(UUID.class)
        .single();
  }

  public Integer resolveNotionExportTable(UUID user, String parent) {
    return jdbc.sql("SELECT count(*) FROM notion_export_table WHERE user_id=? AND parent_id=?")
        .param(user)
        .param(parent)
        .query(Integer.class)
        .single();
  }

  public int resolveNotionExportTable2(UUID user, String parent) {
    return jdbc.sql("INSERT INTO notion_export_table(user_id,parent_id) VALUES (?,?)")
        .param(user)
        .param(parent)
        .update();
  }

  public int resolveNotionExportTable3(
      UUID lease, OffsetDateTime argument1, UUID user, String parent, OffsetDateTime argument4) {
    return jdbc.sql(
            "UPDATE notion_export_table SET lease_token=?,lease_until=? WHERE user_id=? AND"
                + " parent_id=? AND (lease_token IS NULL OR lease_until<?)")
        .param(lease)
        .param(argument1)
        .param(user)
        .param(parent)
        .param(argument4)
        .update();
  }

  public String resolveNotionExportTable4(UUID user, String parent) {
    return jdbc.sql("SELECT remote_json FROM notion_export_table WHERE user_id=? AND parent_id=?")
        .param(user)
        .param(parent)
        .query(String.class)
        .single();
  }

  public int resolveNotionExportTable5(
      OffsetDateTime argument0, UUID user, String parent, UUID lease, OffsetDateTime argument4) {
    return jdbc.sql(
            "UPDATE notion_export_table SET lease_until=? WHERE user_id=? AND parent_id=? AND"
                + " lease_token=? AND lease_until>?")
        .param(argument0)
        .param(user)
        .param(parent)
        .param(lease)
        .param(argument4)
        .update();
  }

  public int resolveNotionExportTable6(String argument0, UUID user, String parent, UUID lease) {
    return jdbc.sql(
            "UPDATE notion_export_table SET remote_json=? WHERE user_id=? AND parent_id=? AND"
                + " lease_token=?")
        .param(argument0)
        .param(user)
        .param(parent)
        .param(lease)
        .update();
  }

  public int resolveNotionExportTable7(UUID user, String parent, UUID lease) {
    return jdbc.sql(
            "UPDATE notion_export_table SET lease_token=NULL,lease_until=NULL WHERE user_id=? AND"
                + " parent_id=? AND lease_token=?")
        .param(user)
        .param(parent)
        .param(lease)
        .update();
  }
}
