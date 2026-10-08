package dev.gamjaoj.repository.generation;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence operations for GenerationActivity; transaction ownership remains in the service. */
@Repository
public class GenerationActivityRepository {
  private final JdbcClient jdbc;

  public GenerationActivityRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Integer activeHybridGeneration(UUID owner) {
    return jdbc.sql(
            "SELECT count(*) FROM hybrid_generation WHERE owner_id=? AND EXISTS (SELECT 1 FROM"
                + " hybrid_api_reservation r WHERE r.generation_id=hybrid_generation.id) AND"
                + " deadline_at>CURRENT_TIMESTAMP AND (status IN"
                + " ('QUEUED','DESIGNING','BUILDING','VALIDATING','REVIEWING') OR (status='HELD'"
                + " AND error_code IN"
                + " ('VALIDATION_ADAPTER_NOT_CONNECTED','CONTENT_REVIEW_REQUIRED')))")
        .param(owner)
        .query(Integer.class)
        .single();
  }
}
