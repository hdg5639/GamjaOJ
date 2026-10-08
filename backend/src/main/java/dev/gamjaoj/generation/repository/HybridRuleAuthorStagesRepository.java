package dev.gamjaoj.generation.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence operations for HybridRuleAuthorStages; transaction ownership remains in the service.
 */
@Repository
public class HybridRuleAuthorStagesRepository {
  private final JdbcClient jdbc;

  public HybridRuleAuthorStagesRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public <T> List<T> snapshotHybridRuleAuthorStage(UUID id, int round, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT stage,payload_json,payload_sha256 FROM hybrid_rule_author_stage WHERE"
                + " onboarding_id=? AND repair_round=?")
        .param(id)
        .param(round)
        .query(mapper)
        .list();
  }

  public int saveHybridRuleAuthorStage(
      UUID id, int round, String stage, UUID call, String raw, String argument5) {
    return jdbc.sql(
            "INSERT INTO"
                + " hybrid_rule_author_stage(onboarding_id,repair_round,stage,call_id,payload_json,payload_sha256,created_at)"
                + " VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP)")
        .param(id)
        .param(round)
        .param(stage)
        .param(call)
        .param(raw)
        .param(argument5)
        .update();
  }

  public int copyPrefixHybridRuleAuthorStage(int newRound, UUID id, int oldRound, String stage) {
    return jdbc.sql(
            "INSERT INTO"
                + " hybrid_rule_author_stage(onboarding_id,repair_round,stage,call_id,payload_json,payload_sha256,created_at)"
                + " SELECT"
                + " onboarding_id,?,stage,call_id,payload_json,payload_sha256,CURRENT_TIMESTAMP"
                + " FROM hybrid_rule_author_stage WHERE onboarding_id=? AND repair_round=? AND"
                + " stage=?")
        .param(newRound)
        .param(id)
        .param(oldRound)
        .param(stage)
        .update();
  }
}
