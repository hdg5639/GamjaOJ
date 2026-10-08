package dev.gamjaoj.support.repository;

import dev.gamjaoj.support.dto.SupportDtos.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SupportRepository {
  private final JdbcClient jdbc;

  public SupportRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private static final String SELECT =
      "SELECT s.*,u.username FROM support_request s JOIN app_user u ON u.id=s.owner_id ";

  private Entry map(java.sql.ResultSet r, int n) throws java.sql.SQLException {
    return new Entry(
        r.getObject("id", UUID.class),
        r.getString("username"),
        r.getString("kind"),
        r.getString("title"),
        r.getString("body"),
        r.getString("status"),
        r.getString("reply"),
        r.getInt("revision"),
        r.getObject("created_at", OffsetDateTime.class),
        r.getObject("updated_at", OffsetDateTime.class));
  }

  public UUID lockUser(String actor) {
    return jdbc.sql("SELECT id FROM app_user WHERE username=? FOR UPDATE")
        .param(actor)
        .query(UUID.class)
        .single();
  }

  public Optional<Entry> find(UUID id) {
    return jdbc.sql(SELECT + "WHERE s.id=?").param(id).query(this::map).optional();
  }

  public List<Entry> list(String owner, String status, int page) {
    return jdbc.sql(
            SELECT
                + "WHERE (?='' OR u.username=?) AND (?='' OR s.status=?) ORDER BY s.created_at"
                + " DESC,s.id DESC LIMIT 21 OFFSET ?")
        .param(owner)
        .param(owner)
        .param(status)
        .param(status)
        .param(page * 20)
        .query(this::map)
        .list();
  }

  public int recent(UUID owner, OffsetDateTime since) {
    return jdbc.sql("SELECT count(*) FROM support_request WHERE owner_id=? AND created_at>?")
        .param(owner)
        .param(since)
        .query(Integer.class)
        .single();
  }

  public void create(UUID id, UUID owner, Create w) {
    jdbc.sql("INSERT INTO support_request(id,owner_id,kind,title,body) VALUES(?,?,?,?,?)")
        .param(id)
        .param(owner)
        .param(w.kind())
        .param(w.title().strip())
        .param(w.body().strip())
        .update();
  }

  public int update(UUID id, Update w) {
    return jdbc.sql(
            "UPDATE support_request SET"
                + " status=?,reply=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=?"
                + " AND revision=?")
        .param(w.status())
        .param(w.reply().strip())
        .param(id)
        .param(w.revision())
        .update();
  }
}
