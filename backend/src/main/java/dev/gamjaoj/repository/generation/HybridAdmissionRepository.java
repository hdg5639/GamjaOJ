package dev.gamjaoj.repository.generation;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence operations for HybridAdmission; transaction ownership remains in the service. */
@Repository
public class HybridAdmissionRepository {
  private final JdbcClient jdbc;

  public HybridAdmissionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<UUID> viewerAppUser(String user) {
    return jdbc.sql("SELECT id FROM app_user WHERE username=?")
        .param(user)
        .query(UUID.class)
        .optional();
  }

  public Integer createAiBudgetLock() {
    return jdbc.sql("SELECT id FROM ai_budget_lock WHERE id=1 FOR UPDATE")
        .query(Integer.class)
        .single();
  }

  public Integer createHybridPublicRequest(UUID id) {
    return jdbc.sql("SELECT count(*) FROM hybrid_public_request WHERE generation_id=?")
        .param(id)
        .query(Integer.class)
        .single();
  }

  public Integer createHybridGeneration(UUID id) {
    return jdbc.sql("SELECT count(*) FROM hybrid_generation WHERE id=?")
        .param(id)
        .query(Integer.class)
        .single();
  }

  public Integer createGenerationJob(UUID owner) {
    return jdbc.sql(
            "SELECT count(*) FROM generation_job WHERE owner_id=? AND status IN"
                + " ('QUEUED','GENERATING','AWAITING_REVIEW','VALIDATING')")
        .param(owner)
        .query(Integer.class)
        .single();
  }

  public int createHybridGeneration2(UUID id) {
    return jdbc.sql("UPDATE hybrid_generation SET resource_validation=true WHERE id=?")
        .param(id)
        .update();
  }

  public int createHybridPublicRequest2(
      UUID id, String argument1, String argument2, String argument3, String versionId) {
    return jdbc.sql(
            "INSERT INTO"
                + " hybrid_public_request(generation_id,profile_id,profile_hash,contract_sha256,handoff_mode,rule_version_id)"
                + " VALUES (?,?,?,?,'SERVER_FIXED_CONTRACT_V1',?)")
        .param(id)
        .param(argument1)
        .param(argument2)
        .param(argument3)
        .param(versionId)
        .update();
  }

  public Optional<String> createHybridRuleOnboarding(String versionId, UUID owner) {
    return jdbc.sql(
            "SELECT request_json FROM hybrid_rule_onboarding WHERE version_id=? AND owner_id=?")
        .param(versionId)
        .param(owner)
        .query(String.class)
        .optional();
  }

  public int createHybridPublicRequest3(String requirementsJson, String argument1, UUID id) {
    return jdbc.sql(
            "UPDATE hybrid_public_request SET requirements_json=?,requirements_sha256=? WHERE"
                + " generation_id=?")
        .param(requirementsJson)
        .param(argument1)
        .param(id)
        .update();
  }

  public int createHybridPublicRequest4(UUID argument0, UUID id) {
    return jdbc.sql(
            "UPDATE hybrid_public_request SET reference_artifact_id=? WHERE generation_id=?")
        .param(argument0)
        .param(id)
        .update();
  }

  public Stream<UUID> listHybridGeneration(UUID owner) {
    return jdbc
        .sql(
            "SELECT g.id FROM hybrid_generation g JOIN hybrid_public_request r ON"
                + " r.generation_id=g.id WHERE g.owner_id=? ORDER BY g.created_at DESC,g.id LIMIT"
                + " 30")
        .param(owner)
        .query(UUID.class)
        .list()
        .stream();
  }
}
