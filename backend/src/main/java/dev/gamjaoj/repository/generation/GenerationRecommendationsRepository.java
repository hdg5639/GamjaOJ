package dev.gamjaoj.repository.generation;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence operations for GenerationRecommendations; transaction ownership remains in the
 * service.
 */
@Repository
public class GenerationRecommendationsRepository {
  private final JdbcClient jdbc;

  public GenerationRecommendationsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<String> recommendGenerationJob(UUID owner) {
    return jdbc.sql(
            "SELECT template_id FROM generation_job WHERE owner_id=? AND status IN"
                + " ('READY','QUEUED','GENERATING','AWAITING_REVIEW','VALIDATING') ORDER BY"
                + " created_at DESC,id LIMIT 12")
        .param(owner)
        .query(String.class)
        .list();
  }
}
