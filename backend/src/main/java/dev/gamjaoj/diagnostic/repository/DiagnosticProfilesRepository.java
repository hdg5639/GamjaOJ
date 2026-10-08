package dev.gamjaoj.diagnostic.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence operations for DiagnosticProfiles; transaction ownership remains in the service. */
@Repository
public class DiagnosticProfilesRepository {
  private final JdbcClient jdbc;

  public DiagnosticProfilesRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<String> categorySubmission(UUID argument0, UUID session) {
    return jdbc.sql(
            "SELECT i.category FROM submission s JOIN diagnostic_item i ON"
                + " i.id=s.diagnostic_item_id WHERE s.id=? AND i.session_id=?")
        .param(argument0)
        .param(session)
        .query(String.class)
        .optional();
  }

  public String profileDiagnosticSession(UUID session) {
    return jdbc.sql("SELECT bank_id FROM diagnostic_session WHERE id=?")
        .param(session)
        .query(String.class)
        .single();
  }

  public List<String> profileDiagnosticBankItem(String bank) {
    return jdbc.sql(
            "SELECT DISTINCT category FROM diagnostic_bank_item WHERE bank_id=? ORDER BY category")
        .param(bank)
        .query(String.class)
        .list();
  }
}
