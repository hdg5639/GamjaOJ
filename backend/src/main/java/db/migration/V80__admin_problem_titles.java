package db.migration;

import dev.gamjaoj.shared.support.JudgeJson;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Index display titles without changing immutable packages, hashes or grading contracts. */
public class V80__admin_problem_titles extends BaseJavaMigration {
  @Override
  public void migrate(Context context) throws Exception {
    var connection = context.getConnection();
    try (var select =
            connection.prepareStatement(
                "SELECT id,package_json FROM problem_version WHERE catalog_title IS NULL");
        var rows = select.executeQuery();
        var update =
            connection.prepareStatement(
                "UPDATE problem_version SET catalog_title=? WHERE id=? AND catalog_title IS"
                    + " NULL")) {
      while (rows.next()) {
        String id = rows.getString(1), title = id;
        try {
          String parsed = JudgeJson.JSON.readTree(rows.getString(2)).path("title").asText();
          if (!parsed.isBlank()) title = parsed;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
          /* Only a display fallback; never repair a judge package here. */
        }
        update.setString(
            1,
            new String(
                title.codePoints().limit(120).toArray(),
                0,
                (int) Math.min(title.codePointCount(0, title.length()), 120)));
        update.setString(2, id);
        update.addBatch();
      }
      update.executeBatch();
    }
    try (var statement = connection.createStatement()) {
      statement.execute("CREATE INDEX problem_admin_title ON problem_version(catalog_title)");
    }
  }
}
