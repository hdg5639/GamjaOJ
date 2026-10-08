package dev.gamjaoj.repository.generation;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence operations for HybridRuleFollowup; transaction ownership remains in the service. */
@Repository
public class HybridRuleFollowupRepository {
  private final JdbcClient jdbc;

  public HybridRuleFollowupRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public <T> List<T> advanceHybridRuleOnboarding(RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT o.id,o.version_id,o.request_json,u.username FROM hybrid_rule_onboarding o JOIN"
                + " app_user u ON u.id=o.owner_id WHERE o.status='ACTIVE' AND o.version_id IS NOT"
                + " NULL AND o.followup_generation_id IS NULL AND o.followup_error IS NULL ORDER BY"
                + " o.updated_at")
        .query(mapper)
        .list();
  }

  public int advanceHybridRuleOnboarding2(UUID generation, UUID id) {
    return jdbc.sql("UPDATE hybrid_rule_onboarding SET followup_generation_id=? WHERE id=?")
        .param(generation)
        .param(id)
        .update();
  }

  public int advanceHybridRuleOnboarding3(Object argument0, UUID id) {
    return jdbc.sql("UPDATE hybrid_rule_onboarding SET followup_error=? WHERE id=?")
        .param(argument0)
        .param(id)
        .update();
  }
}
