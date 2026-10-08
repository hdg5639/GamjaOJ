package dev.gamjaoj.generation.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence operations for HybridRuleRegistry; transaction ownership remains in the service. */
@Repository
public class HybridRuleRegistryRepository {
  private final JdbcClient jdbc;

  public HybridRuleRegistryRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public <T> Optional<T> rowHybridRuleVersion(String id, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT"
                + " v.id,v.family_id,v.engine,v.validation_policy,v.profile_sha256,v.contract_sha256,v.status,v.catalog_json,v.package_json,f.visibility,f.owner_id,f.shared"
                + " FROM hybrid_rule_version v JOIN hybrid_rule_family f ON f.id=v.family_id WHERE"
                + " v.id=?")
        .param(id)
        .query(mapper)
        .optional();
  }

  public List<String> syncHybridRuleVersion(String argument0) {
    return jdbc.sql("SELECT id FROM hybrid_rule_version WHERE engine=? AND status='ACTIVE'")
        .param(argument0)
        .query(String.class)
        .list();
  }

  public int syncHybridRuleVersion2(OffsetDateTime argument0, String id) {
    return jdbc.sql(
            "UPDATE hybrid_rule_version SET"
                + " status='QUARANTINED',status_reason='EXECUTION_IDENTITY_MISMATCH',updated_at=?"
                + " WHERE id=?")
        .param(argument0)
        .param(id)
        .update();
  }

  public int syncHybridRuleVersion3(
      String argument0, String argument1, OffsetDateTime argument2, String argument3) {
    return jdbc.sql(
            "UPDATE hybrid_rule_version SET profile_sha256=?,contract_sha256=?,updated_at=? WHERE"
                + " id=? AND profile_sha256 IS NULL AND contract_sha256 IS NULL")
        .param(argument0)
        .param(argument1)
        .param(argument2)
        .param(argument3)
        .update();
  }

  public int syncHybridRuleVersion4(String argument0, String argument1) {
    return jdbc.sql("UPDATE hybrid_rule_version SET catalog_json=? WHERE id=?")
        .param(argument0)
        .param(argument1)
        .update();
  }

  public int syncHybridRuleVersion5(OffsetDateTime argument0, String argument1) {
    return jdbc.sql(
            "UPDATE hybrid_rule_version SET"
                + " status='QUARANTINED',status_reason='EXECUTION_IDENTITY_MISMATCH',updated_at=?"
                + " WHERE id=?")
        .param(argument0)
        .param(argument1)
        .update();
  }

  public List<UUID> syncHybridGeneration() {
    return jdbc.sql(
            "SELECT g.id FROM hybrid_generation g JOIN hybrid_public_request r ON"
                + " r.generation_id=g.id WHERE g.status='PUBLISHED' AND r.reference_artifact_id IS"
                + " NULL ORDER BY g.updated_at")
        .query(UUID.class)
        .list();
  }

  public Stream<String> selectableHybridRuleVersion() {
    return jdbc
        .sql(
            "SELECT id FROM hybrid_rule_version WHERE status='ACTIVE' ORDER BY"
                + " sort_order,created_at,id")
        .query(String.class)
        .list()
        .stream();
  }

  public Stream<String> ownedHybridRuleVersion(UUID owner) {
    return jdbc
        .sql(
            "SELECT v.id FROM hybrid_rule_version v JOIN hybrid_rule_family f ON f.id=v.family_id"
                + " WHERE f.owner_id=? ORDER BY v.created_at DESC,v.id")
        .param(owner)
        .query(String.class)
        .list()
        .stream();
  }

  public int shareHybridRuleFamily(boolean shared, String argument1, UUID owner) {
    return jdbc.sql("UPDATE hybrid_rule_family SET shared=? WHERE id=? AND owner_id=?")
        .param(shared)
        .param(argument1)
        .param(owner)
        .update();
  }

  public Integer activateHybridRuleVersion(String contract) {
    return jdbc.sql(
            "SELECT count(*) FROM hybrid_rule_version WHERE contract_sha256=? AND status='ACTIVE'")
        .param(contract)
        .query(Integer.class)
        .single();
  }

  public int activateHybridRuleFamily(String family, UUID owner) {
    return jdbc.sql(
            "INSERT INTO hybrid_rule_family(id,visibility,owner_id,shared) VALUES"
                + " (?,'MEMBER',?,false)")
        .param(family)
        .param(owner)
        .update();
  }

  public int activateHybridRuleVersion2(
      String versionId,
      String family,
      String argument2,
      String argument3,
      String argument4,
      String contract,
      String argument6,
      String argument7,
      OffsetDateTime now,
      OffsetDateTime nowArgument9) {
    return jdbc.sql(
            "INSERT INTO"
                + " hybrid_rule_version(id,family_id,engine,validation_policy,profile_sha256,contract_sha256,status,catalog_json,sort_order,package_json,created_at,updated_at)"
                + " VALUES (?,?,?,?,?,?,'ACTIVE',?,1000,?,?,?)")
        .param(versionId)
        .param(family)
        .param(argument2)
        .param(argument3)
        .param(argument4)
        .param(contract)
        .param(argument6)
        .param(argument7)
        .param(now)
        .param(nowArgument9)
        .update();
  }

  public int activateHybridRuleArtifact(
      UUID argument0,
      String versionId,
      String raw,
      String argument3,
      UUID onboarding,
      OffsetDateTime now) {
    return jdbc.sql(
            "INSERT INTO"
                + " hybrid_rule_artifact(id,rule_version_id,kind,payload_json,payload_sha256,source_onboarding_id,status,qualified_at)"
                + " VALUES (?,?,'REFERENCE',?,?,?,'QUALIFIED',?)")
        .param(argument0)
        .param(versionId)
        .param(raw)
        .param(argument3)
        .param(onboarding)
        .param(now)
        .update();
  }

  public <T> List<T> qualifiedReferenceHybridRuleArtifact(String versionId, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT a.id,a.payload_json,a.payload_sha256 FROM hybrid_rule_artifact a LEFT JOIN"
                + " problem_version p ON p.id=a.source_version_id WHERE a.rule_version_id=? AND"
                + " a.kind='REFERENCE' AND a.status='QUALIFIED' AND (a.source_onboarding_id IS NOT"
                + " NULL OR (p.ready=true AND p.review_hold=false)) ORDER BY a.qualified_at"
                + " DESC,a.id")
        .param(versionId)
        .query(mapper)
        .list();
  }

  public Integer stillQualifiedHybridRuleArtifact(UUID artifact) {
    return jdbc.sql(
            "SELECT count(*) FROM hybrid_rule_artifact a LEFT JOIN problem_version p ON"
                + " p.id=a.source_version_id JOIN hybrid_rule_version v ON v.id=a.rule_version_id"
                + " WHERE a.id=? AND a.status='QUALIFIED' AND v.status='ACTIVE' AND"
                + " (a.source_onboarding_id IS NOT NULL OR (p.ready=true AND p.review_hold=false))")
        .param(artifact)
        .query(Integer.class)
        .single();
  }

  public <T> Optional<T> qualifyHybridPublicRequest(UUID generation, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT"
                + " r.rule_version_id,r.profile_hash,r.contract_sha256,r.reference_artifact_id,g.published_version_id,g.revision"
                + " FROM hybrid_public_request r JOIN hybrid_generation g ON g.id=r.generation_id"
                + " WHERE g.id=? AND g.status='PUBLISHED'")
        .param(generation)
        .query(mapper)
        .optional();
  }

  public <T> Optional<T> qualifyHybridBranch(
      UUID generation, Integer argument1, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT b.id,b.completion_json,a.payload_json,a.payload_sha256,b.output_sha256 FROM"
                + " hybrid_branch b JOIN hybrid_artifact a ON a.branch_id=b.id WHERE"
                + " b.generation_id=? AND b.revision=? AND b.role='CORE' AND b.status='SUCCEEDED'")
        .param(generation)
        .param(argument1)
        .query(mapper)
        .optional();
  }

  public Integer qualifyHybridRuleArtifact(Object argument0, String hash) {
    return jdbc.sql(
            "SELECT count(*) FROM hybrid_rule_artifact WHERE rule_version_id=? AND kind='REFERENCE'"
                + " AND payload_sha256=?")
        .param(argument0)
        .param(hash)
        .query(Integer.class)
        .single();
  }

  public int qualifyHybridRuleArtifact2(
      UUID argument0,
      Object argument1,
      String raw,
      String hash,
      UUID generation,
      Object argument5,
      Object argument6,
      OffsetDateTime argument7) {
    return jdbc.sql(
            "INSERT INTO"
                + " hybrid_rule_artifact(id,rule_version_id,kind,payload_json,payload_sha256,source_generation_id,source_branch_id,source_version_id,status,qualified_at)"
                + " VALUES (?,?,'REFERENCE',?,?,?,?,?,'QUALIFIED',?)")
        .param(argument0)
        .param(argument1)
        .param(raw)
        .param(hash)
        .param(generation)
        .param(argument5)
        .param(argument6)
        .param(argument7)
        .update();
  }
}
