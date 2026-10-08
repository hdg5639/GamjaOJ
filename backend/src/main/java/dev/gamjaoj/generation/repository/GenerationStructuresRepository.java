package dev.gamjaoj.generation.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence operations for GenerationStructures; transaction ownership remains in the service.
 */
@Repository
public class GenerationStructuresRepository {
  private final JdbcClient jdbc;

  public GenerationStructuresRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public <T> T contractProblemVersion(String argument0, RowMapper<T> mapper) {
    return jdbc.sql("SELECT runtime_image,runner_policy FROM problem_version WHERE id=?")
        .param(argument0)
        .query(mapper)
        .single();
  }

  public <T> List<T> selectGenerationJob(
      UUID owner, String argument1, String contract, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT"
                + " id,artifacts_json,oracle_json,artifacts_sha256,validation_json,structure_tags_json"
                + " FROM generation_job WHERE owner_id=? AND template_id=? AND structure_contract=?"
                + " AND status='READY' ORDER BY updated_at DESC,id")
        .param(owner)
        .param(argument1)
        .param(contract)
        .query(mapper)
        .list();
  }

  public int selectGenerationJob2(String contract, String argument1, Object argument2, UUID job) {
    return jdbc.sql(
            "UPDATE generation_job SET"
                + " structure_contract=?,structure_tags_json=?,structure_reuse_json=? WHERE id=?")
        .param(contract)
        .param(argument1)
        .param(argument2)
        .param(job)
        .update();
  }

  public Optional<String> snapshotGenerationJob(UUID job) {
    return jdbc.sql("SELECT structure_reuse_json FROM generation_job WHERE id=?")
        .param(job)
        .query(String.class)
        .optional();
  }

  public Optional<String> summaryGenerationJob(UUID job) {
    return jdbc.sql("SELECT structure_tags_json FROM generation_job WHERE id=?")
        .param(job)
        .query(String.class)
        .optional();
  }

  public String summaryGenerationJob2(UUID job) {
    return jdbc.sql("SELECT focus FROM generation_job WHERE id=?")
        .param(job)
        .query(String.class)
        .single();
  }
}
