package dev.gamjaoj.admin.repository;

import dev.gamjaoj.admin.dto.AdminDtos.*;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AdminManagementRepository {
  private final JdbcClient jdbc;

  public AdminManagementRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private Map<String, Object> map(ResultSet r, int n) throws SQLException {
    var out = new LinkedHashMap<String, Object>();
    var meta = r.getMetaData();
    for (int i = 1; i <= meta.getColumnCount(); i++)
      out.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT), r.getObject(i));
    return out;
  }

  private Page page(
      String select, String from, String where, String order, String query, int page, int size) {
    String like = "%" + query.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    long total =
        jdbc.sql("SELECT COUNT(*) " + from + " WHERE " + where)
            .param(like)
            .query(Long.class)
            .single();
    var items =
        jdbc.sql(
                select
                    + " "
                    + from
                    + " WHERE "
                    + where
                    + " ORDER BY "
                    + order
                    + " LIMIT ? OFFSET ?")
            .param(like)
            .param(size)
            .param((long) page * size)
            .query(this::map)
            .list();
    return new Page(items, total, page, size);
  }

  public Page members(String q, int p, int s) {
    return page(
        "SELECT"
            + " u.id,u.username,u.nickname,u.blocked,u.admin_role,u.admin_revision,u.created_at,(SELECT"
            + " COUNT(*) FROM SPRING_SESSION ss WHERE ss.PRINCIPAL_NAME=u.username AND"
            + " ss.EXPIRY_TIME>"
            + System.currentTimeMillis()
            + ") AS sessions",
        "FROM app_user u",
        "LOWER(CONCAT(CONCAT(u.username,' '),u.nickname)) LIKE LOWER(?) ESCAPE '!'",
        "u.created_at DESC,u.id",
        q,
        p,
        s);
  }

  public Optional<Map<String, Object>> member(String username) {
    return jdbc.sql(
            "SELECT username,blocked,admin_role,admin_revision FROM app_user WHERE username=? FOR"
                + " UPDATE")
        .param(username)
        .query(this::map)
        .optional();
  }

  public int access(String username, Access w) {
    return jdbc.sql(
            "UPDATE app_user SET"
                + " blocked=?,admin_role=?,access_epoch=access_epoch+1,admin_revision=admin_revision+1"
                + " WHERE username=? AND admin_revision=?")
        .param(w.blocked())
        .param(w.role())
        .param(username)
        .param(w.revision())
        .update();
  }

  public void revoke(String username) {
    jdbc.sql("UPDATE app_user SET access_epoch=access_epoch+1 WHERE username=?")
        .param(username)
        .update();
    jdbc.sql("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME=?").param(username).update();
  }

  public Page audit(String q, int p, int s) {
    return page(
        "SELECT actor,action,target,reason,before_json,after_json,created_at",
        "FROM admin_audit",
        "LOWER(CONCAT(CONCAT(actor,' '),CONCAT(action,CONCAT(' ',target)))) LIKE LOWER(?) ESCAPE"
            + " '!'",
        "created_at DESC,id",
        q,
        p,
        s);
  }

  public Map<String, Object> setting() {
    return jdbc.sql("SELECT maintenance,message,revision FROM admin_site_setting WHERE id=1")
        .query(this::map)
        .single();
  }

  public int setting(Setting w) {
    return jdbc.sql(
            "UPDATE admin_site_setting SET maintenance=?,message=?,revision=revision+1 WHERE id=1"
                + " AND revision=?")
        .param(w.maintenance())
        .param(w.message().strip())
        .param(w.revision())
        .update();
  }

  public Page problems(String q, int p, int s) {
    return page(
        "SELECT v.id,v.owner_id,u.username AS"
            + " owner,v.ready,v.shared,v.diagnostic_only,v.review_hold,v.review_reason,v.catalog_title,v.catalog_category,v.catalog_tags,v.catalog_difficulty,v.time_limits_json,v.admin_revision,v.package_json,t.layer,t.insight,t.implementation,t.edge_cases,t.rationale",
        "FROM problem_version v LEFT JOIN app_user u ON v.owner_id=u.id LEFT JOIN"
            + " problem_thinking_profile t ON t.problem_version=v.id AND"
            + " t.package_sha256=v.package_sha256",
        "LOWER(CONCAT(v.id,COALESCE(v.catalog_title,''))) LIKE LOWER(?) ESCAPE '!'",
        "v.id",
        q,
        p,
        s);
  }

  public Optional<Map<String, Object>> problem(String id) {
    return jdbc.sql(
            "SELECT"
                + " id,owner_id,shared,catalog_title,catalog_category,catalog_tags,review_hold,diagnostic_only,time_limits_json,admin_revision"
                + " FROM problem_version WHERE id=? FOR UPDATE")
        .param(id)
        .query(this::map)
        .optional();
  }

  public int problem(String id, Problem w, String category, String tags) {
    return jdbc.sql(
            "UPDATE problem_version SET"
                + " catalog_title=?,catalog_category=?,catalog_tags=?,shared=?,admin_revision=admin_revision+1"
                + " WHERE id=? AND admin_revision=?")
        .param(w.title().strip())
        .param(category)
        .param(tags)
        .param(w.shared())
        .param(id)
        .param(w.revision())
        .update();
  }

  public void thinking(String id, dev.gamjaoj.problem.domain.ThinkingProfile.Input t) {
    jdbc.sql("DELETE FROM problem_thinking_profile WHERE problem_version=?").param(id).update();
    jdbc.sql(
            "INSERT INTO"
                + " problem_thinking_profile(problem_version,package_sha256,layer,insight,implementation,edge_cases,rationale,source)"
                + " SELECT id,package_sha256,?,?,?,?,?,'CURATED_ESTIMATE' FROM problem_version"
                + " WHERE id=?")
        .param(t.layer())
        .param(t.insight())
        .param(t.implementation())
        .param(t.edgeCases())
        .param(t.rationale().strip())
        .param(id)
        .update();
  }

  public int limits(String id, int revision, String json) {
    return jdbc.sql(
            "UPDATE problem_version SET time_limits_json=?,admin_revision=admin_revision+1 WHERE"
                + " id=? AND admin_revision=? AND diagnostic_only=false")
        .param(json)
        .param(id)
        .param(revision)
        .update();
  }

  public List<Map<String, Object>> banks() {
    return jdbc.sql(
            "SELECT b.id,b.reviewed,b.admin_enabled,b.admin_revision,(SELECT COUNT(*) FROM"
                + " diagnostic_bank_item i WHERE i.bank_id=b.id) AS problems,(SELECT COUNT(*) FROM"
                + " diagnostic_session s WHERE s.bank_id=b.id AND s.status<>'COMPLETED') AS active"
                + " FROM diagnostic_bank b ORDER BY b.id")
        .query(this::map)
        .list();
  }

  public List<Map<String, Object>> courses() {
    return jdbc.sql("SELECT id,enabled,revision FROM admin_course_setting ORDER BY id")
        .query(this::map)
        .list();
  }

  public int availability(String kind, String id, Availability w) {
    if (kind.equals("bank"))
      return jdbc.sql(
              "UPDATE diagnostic_bank SET admin_enabled=?,admin_revision=admin_revision+1 WHERE"
                  + " id=? AND admin_revision=? AND (reviewed=true OR ?=false)")
          .param(w.enabled())
          .param(id)
          .param(w.revision())
          .param(w.enabled())
          .update();
    return jdbc.sql(
            "UPDATE admin_course_setting SET enabled=?,revision=revision+1 WHERE id=? AND"
                + " revision=?")
        .param(w.enabled())
        .param(id)
        .param(w.revision())
        .update();
  }

  public Optional<Map<String, Object>> course(String id) {
    return jdbc.sql(
            "SELECT id,enabled,revision,course_json FROM admin_course_setting WHERE id=? FOR"
                + " UPDATE")
        .param(id)
        .query(this::map)
        .optional();
  }

  public int course(String id, int revision, String json) {
    return jdbc.sql(
            "UPDATE admin_course_setting SET course_json=?,revision=revision+1 WHERE id=? AND"
                + " revision=?")
        .param(json)
        .param(id)
        .param(revision)
        .update();
  }

  public void createCourse(String id, String json) {
    jdbc.sql(
            "INSERT INTO admin_course_setting(id,enabled,revision,course_json) VALUES(?,false,1,?)")
        .param(id)
        .param(json)
        .update();
  }

  public int courseProblem(String id) {
    return jdbc.sql(
            "SELECT COUNT(*) FROM problem_version WHERE id=? AND ready=true AND review_hold=false"
                + " AND diagnostic_only=false AND (owner_id IS NULL OR shared=true)")
        .param(id)
        .query(Integer.class)
        .single();
  }

  public Page plans(String q, int p, int s) {
    return page(
        "SELECT e.id,u.username,e.created_at,ce.ended_at,(SELECT COUNT(*) FROM"
            + " diagnostic_practice_plan p WHERE p.evaluation_id=e.id) AS steps",
        "FROM diagnostic_evaluation e JOIN diagnostic_session ds ON e.session_id=ds.id JOIN"
            + " app_user u ON ds.user_id=u.id LEFT JOIN learning_curriculum_end ce ON"
            + " ce.evaluation_id=e.id",
        "LOWER(u.username) LIKE LOWER(?) ESCAPE '!' AND EXISTS(SELECT 1 FROM"
            + " diagnostic_practice_plan p WHERE p.evaluation_id=e.id)",
        "e.created_at DESC,e.id",
        q,
        p,
        s);
  }

  public Optional<String> planOwner(UUID id) {
    return jdbc.sql(
            "SELECT u.username FROM diagnostic_evaluation e JOIN diagnostic_session d ON"
                + " e.session_id=d.id JOIN app_user u ON d.user_id=u.id WHERE e.id=? AND"
                + " EXISTS(SELECT 1 FROM diagnostic_practice_plan p WHERE p.evaluation_id=e.id)")
        .param(id)
        .query(String.class)
        .optional();
  }

  public Page training(String q, int p, int s) {
    return page(
        "SELECT t.id,u.username,t.problem_version,t.goal,t.status,t.started_at,t.ended_at",
        "FROM training_session t JOIN app_user u ON t.user_id=u.id",
        "LOWER(u.username) LIKE LOWER(?) ESCAPE '!'",
        "t.started_at DESC,t.id",
        q,
        p,
        s);
  }

  public Optional<String> trainingOwner(UUID id) {
    return jdbc.sql(
            "SELECT u.username FROM training_session t JOIN app_user u ON t.user_id=u.id WHERE"
                + " t.id=?")
        .param(id)
        .query(String.class)
        .optional();
  }

  private String jobs() {
    return "(SELECT j.submission_id AS id,u.username,'JUDGE' AS"
               + " kind,j.status,s.created_at,j.verdict AS error_code,false AS retryable FROM"
               + " judge_job j JOIN submission s ON j.submission_id=s.id JOIN app_user u ON"
               + " s.user_id=u.id UNION ALL SELECT"
               + " j.id,u.username,'GENERATION',j.status,j.created_at,j.error_code,false FROM"
               + " generation_job j JOIN app_user u ON j.owner_id=u.id UNION ALL SELECT"
               + " j.id,u.username,'HYBRID',j.status,j.created_at,j.error_code,false FROM"
               + " hybrid_generation j JOIN app_user u ON j.owner_id=u.id UNION ALL SELECT"
               + " j.id,u.username,'RULE',j.status,j.created_at,j.error_code,false FROM"
               + " hybrid_rule_onboarding j JOIN app_user u ON j.owner_id=u.id UNION ALL SELECT"
               + " j.id,u.username,'AI',j.status,j.created_at,j.error_code,(j.kind<>'DIAGNOSTIC')"
               + " FROM ai_task j JOIN app_user u ON j.user_id=u.id UNION ALL SELECT"
               + " j.id,u.username,'EXPORT',j.status,j.updated_at,j.error_code,false FROM"
               + " solution_export j JOIN app_user u ON j.user_id=u.id) jobs";
  }

  public Page jobs(String q, int p, int s) {
    return page(
        "SELECT id,username,kind,status,created_at,error_code,retryable",
        "FROM " + jobs(),
        "LOWER(CONCAT(username,CONCAT(' ',CONCAT(kind,CONCAT(' ',status))))) LIKE LOWER(?) ESCAPE"
            + " '!'",
        "created_at DESC,id",
        q,
        p,
        s);
  }

  public Optional<Map<String, Object>> job(String kind, UUID id) {
    return jdbc.sql("SELECT id,username,kind,status FROM " + jobs() + " WHERE kind=? AND id=?")
        .param(kind)
        .param(id)
        .query(this::map)
        .optional();
  }
}
