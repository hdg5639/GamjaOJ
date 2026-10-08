package dev.gamjaoj.problem.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence operations for ProblemIllustrations; transaction ownership remains in the service.
 */
@Repository
public class ProblemIllustrationsRepository {
  private final JdbcClient jdbc;

  public ProblemIllustrationsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public <T> Optional<T> accessProblemVersion(String version, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT owner_id,ready,review_hold,diagnostic_only,package_json,package_sha256,shared"
                + " FROM problem_version WHERE id=?")
        .param(version)
        .query(mapper)
        .optional();
  }

  public Integer accessDiagnosticItem(UUID owner, String version, String argument2) {
    return jdbc.sql(
            "SELECT count(*) FROM diagnostic_item i JOIN diagnostic_session s ON s.id=i.session_id"
                + " WHERE s.user_id=? AND i.problem_version=? AND i.package_sha256=? AND"
                + " (i.status<>'OPEN' OR i.position=(SELECT min(x.position) FROM diagnostic_item x"
                + " WHERE x.session_id=i.session_id AND x.status='OPEN'))")
        .param(owner)
        .param(version)
        .param(argument2)
        .query(Integer.class)
        .single();
  }

  public <T> List<T> presentationProblemIllustration(
      String version, String argument1, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT id,alt,caption,width,height FROM problem_illustration WHERE problem_version=?"
                + " AND package_sha256=? ORDER BY created_at,id")
        .param(version)
        .param(argument1)
        .query(mapper)
        .list();
  }

  public <T> Optional<T> imageProblemIllustration(UUID id, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT problem_version,package_sha256,image_png FROM problem_illustration WHERE id=?")
        .param(id)
        .query(mapper)
        .optional();
  }

  public <T> Optional<T> uploadProblemIllustration(UUID key, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT user_id,problem_version,image_sha256,alt,caption FROM problem_illustration"
                + " WHERE id=?")
        .param(key)
        .query(mapper)
        .optional();
  }

  public Integer uploadProblemIllustration2(String version) {
    return jdbc.sql("SELECT count(*) FROM problem_illustration WHERE problem_version=?")
        .param(version)
        .query(Integer.class)
        .single();
  }

  public Long uploadProblemIllustration3(UUID owner) {
    return jdbc.sql(
            "SELECT COALESCE(SUM(OCTET_LENGTH(image_png)),0) FROM problem_illustration WHERE"
                + " user_id=?")
        .param(owner)
        .query(Long.class)
        .single();
  }

  public int uploadProblemIllustration4(
      UUID key,
      String version,
      UUID owner,
      String argument3,
      String hash,
      String argument5,
      String argument6,
      byte[] argument7,
      int argument8,
      int argument9) {
    return jdbc.sql(
            "INSERT INTO"
                + " problem_illustration(id,problem_version,user_id,package_sha256,image_sha256,alt,caption,image_png,width,height)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?)")
        .param(key)
        .param(version)
        .param(owner)
        .param(argument3)
        .param(hash)
        .param(argument5)
        .param(argument6)
        .param(argument7)
        .param(argument8)
        .param(argument9)
        .update();
  }

  public <T> Optional<T> deleteProblemIllustration(UUID id, RowMapper<T> mapper) {
    return jdbc.sql("SELECT problem_version,user_id FROM problem_illustration WHERE id=?")
        .param(id)
        .query(mapper)
        .optional();
  }

  public int deleteProblemIllustration2(UUID id) {
    return jdbc.sql("DELETE FROM problem_illustration WHERE id=?").param(id).update();
  }
}
