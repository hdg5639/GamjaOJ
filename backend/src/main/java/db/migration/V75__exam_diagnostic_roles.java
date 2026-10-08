package db.migration;

import java.util.ArrayList;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Preserve legacy pairs while allowing CORE/APPLIED roles independently of difficulty. */
public class V75__exam_diagnostic_roles extends BaseJavaMigration {
  @Override
  public void migrate(Context context) throws Exception {
    var connection = context.getConnection();
    var names = new ArrayList<String>();
    try (var query =
        connection.prepareStatement(
            "SELECT t.constraint_name FROM information_schema.table_constraints t JOIN"
                + " information_schema.check_constraints c ON"
                + " t.constraint_catalog=c.constraint_catalog AND"
                + " t.constraint_schema=c.constraint_schema AND t.constraint_name=c.constraint_name"
                + " WHERE LOWER(t.table_name)='diagnostic_bank_item' AND LOWER(c.check_clause) LIKE"
                + " '%difficulty%' AND LOWER(c.check_clause) LIKE '%easy%' AND"
                + " t.constraint_schema=?")) {
      query.setString(1, connection.getSchema());
      try (var rows = query.executeQuery()) {
        while (rows.next()) names.add(rows.getString(1));
      }
    }
    try (var statement = connection.createStatement()) {
      for (String name : names)
        statement.execute(
            "ALTER TABLE diagnostic_bank_item DROP CONSTRAINT \""
                + name.replace("\"", "\"\"")
                + "\"");
      statement.execute(
          "ALTER TABLE diagnostic_bank_item ADD CONSTRAINT diagnostic_item_role_check CHECK"
              + " (difficulty IN ('EASY','MEDIUM','CORE','APPLIED'))");
    }
  }
}
