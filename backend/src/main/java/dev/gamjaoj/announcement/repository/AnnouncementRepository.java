package dev.gamjaoj.announcement.repository;

import dev.gamjaoj.announcement.dto.AnnouncementDtos.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AnnouncementRepository {
  private final JdbcClient jdbc;

  public AnnouncementRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private Entry map(java.sql.ResultSet r, int n) throws java.sql.SQLException {
    return new Entry(
        r.getString("id"),
        r.getString("kind"),
        r.getString("title"),
        r.getString("summary"),
        r.getString("body"),
        r.getBoolean("pinned"),
        r.getBoolean("published"),
        r.getObject("published_at", OffsetDateTime.class),
        r.getInt("revision"),
        r.getString("updated_by"),
        r.getObject("updated_at", OffsetDateTime.class));
  }

  public List<Entry> list(boolean publicOnly) {
    return jdbc.sql(
            "SELECT * FROM announcement "
                + (publicOnly ? "WHERE published=true " : "")
                + "ORDER BY pinned DESC,COALESCE(published_at,created_at) DESC,id DESC LIMIT 200")
        .query(this::map)
        .list();
  }

  public Optional<Entry> find(String id) {
    return jdbc.sql("SELECT * FROM announcement WHERE id=? FOR UPDATE")
        .param(id)
        .query(this::map)
        .optional();
  }

  public void lockActor(String actor) {
    jdbc.sql("SELECT id FROM app_user WHERE username=? FOR UPDATE")
        .param(actor)
        .query(java.util.UUID.class)
        .single();
  }

  public int create(String id, String actor, Write w) {
    return jdbc.sql(
            "INSERT INTO"
                + " announcement(id,kind,title,summary,body,pinned,published,published_at,created_by,updated_by)"
                + " VALUES(?,?,?,?,?,?,?,CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END,?,?)")
        .param(id)
        .param(w.kind())
        .param(w.title().strip())
        .param(w.summary().strip())
        .param(w.body().strip())
        .param(w.pinned())
        .param(w.published())
        .param(w.published())
        .param(actor)
        .param(actor)
        .update();
  }

  public int update(String id, String actor, Write w) {
    return jdbc.sql(
            "UPDATE announcement SET"
                + " kind=?,title=?,summary=?,body=?,pinned=?,published=?,published_at=CASE WHEN ?"
                + " THEN COALESCE(published_at,CURRENT_TIMESTAMP) ELSE published_at"
                + " END,revision=revision+1,updated_by=?,updated_at=CURRENT_TIMESTAMP WHERE id=?"
                + " AND revision=?")
        .param(w.kind())
        .param(w.title().strip())
        .param(w.summary().strip())
        .param(w.body().strip())
        .param(w.pinned())
        .param(w.published())
        .param(w.published())
        .param(actor)
        .param(id)
        .param(w.revision())
        .update();
  }
}
