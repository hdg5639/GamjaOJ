package dev.gamjaoj.generation.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence operations for VerificationLedger; transaction ownership remains in the service. */
@Repository
public class VerificationLedgerRepository {
  private final JdbcClient jdbc;

  public VerificationLedgerRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<dev.gamjaoj.generation.domain.VerificationEntry> entryGenerationEvidence(
      UUID job, int revision) {
    return jdbc.sql(
            "SELECT id,job_id,revision,snapshot_json,snapshot_sha256 FROM generation_evidence WHERE"
                + " job_id=? AND revision=?")
        .param(job)
        .param(revision)
        .query(dev.gamjaoj.generation.domain.VerificationEntry.class)
        .optional();
  }

  public Integer activeGenerationEvidenceRevocation(UUID argument0) {
    return jdbc.sql("SELECT count(*) FROM generation_evidence_revocation WHERE evidence_id=?")
        .param(argument0)
        .query(Integer.class)
        .single();
  }

  public <T> T freezeGenerationJob(String argument0, UUID job, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT"
                + " g.artifacts_json,g.oracle_json,g.structure_contract,p.package_json,p.runtime_image,p.runner_policy"
                + " FROM generation_job g JOIN problem_version p ON p.id=? WHERE g.id=?")
        .param(argument0)
        .param(job)
        .query(mapper)
        .single();
  }

  public Optional<UUID> freezeGenerationDependency(UUID job) {
    return jdbc.sql("SELECT source_evidence_id FROM generation_dependency WHERE job_id=?")
        .param(job)
        .query(UUID.class)
        .optional();
  }

  public int freezeGenerationEvidence(
      UUID id, UUID job, int revision, String json, String argument4) {
    return jdbc.sql(
            "INSERT INTO generation_evidence(id,job_id,revision,snapshot_json,snapshot_sha256)"
                + " VALUES (?,?,?,?,?)")
        .param(id)
        .param(job)
        .param(revision)
        .param(json)
        .param(argument4)
        .update();
  }

  public Optional<Integer> bindGenerationJob(UUID source, UUID target) {
    return jdbc.sql(
            "SELECT revision FROM generation_job WHERE id=? AND owner_id=(SELECT owner_id FROM"
                + " generation_job WHERE id=?) AND status='READY'")
        .param(source)
        .param(target)
        .query(Integer.class)
        .optional();
  }

  public int bindGenerationDependency(
      UUID target, UUID source, Integer argument2, UUID argument3, String argument4) {
    return jdbc.sql(
            "INSERT INTO"
                + " generation_dependency(job_id,source_job_id,source_revision,source_evidence_id,source_evidence_sha256)"
                + " VALUES (?,?,?,?,?)")
        .param(target)
        .param(source)
        .param(argument2)
        .param(argument3)
        .param(argument4)
        .update();
  }

  public Integer availableProblemVersion(String argument0) {
    return jdbc.sql(
            "SELECT count(*) FROM problem_version WHERE id=? AND ready=true AND review_hold=false")
        .param(argument0)
        .query(Integer.class)
        .single();
  }

  public <T> Optional<T> validGenerationDependency(UUID current, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT source_job_id,source_revision,source_evidence_id,source_evidence_sha256 FROM"
                + " generation_dependency WHERE job_id=?")
        .param(current)
        .query(mapper)
        .optional();
  }

  public Integer validGenerationJob(UUID current) {
    return jdbc.sql(
            "SELECT count(*) FROM generation_job WHERE id=? AND structure_reuse_json IS NULL")
        .param(current)
        .query(Integer.class)
        .single();
  }

  public Integer validGenerationJob2(UUID current, UUID parent) {
    return jdbc.sql(
            "SELECT count(*) FROM generation_job a JOIN generation_job b ON a.owner_id=b.owner_id"
                + " WHERE a.id=? AND b.id=?")
        .param(current)
        .param(parent)
        .query(Integer.class)
        .single();
  }

  public int blockGenerationJob(UUID job) {
    return jdbc.sql(
            "UPDATE generation_job SET"
                + " status='NEEDS_REVIEW',error_code='STRUCTURE_EVIDENCE_REVOKED',updated_at=CURRENT_TIMESTAMP"
                + " WHERE id=?")
        .param(job)
        .update();
  }

  public <T> List<T> revokeTreeGenerationJob(UUID owner, RowMapper<T> mapper) {
    return jdbc.sql("SELECT id,structure_reuse_json FROM generation_job WHERE owner_id=?")
        .param(owner)
        .query(mapper)
        .list();
  }

  public <T> List<T> revokeTreeGenerationDependency(UUID owner, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT d.job_id,d.source_job_id FROM generation_dependency d JOIN generation_job g ON"
                + " g.id=d.job_id WHERE g.owner_id=?")
        .param(owner)
        .query(mapper)
        .list();
  }

  public List<UUID> revokeTreeGenerationEvidence(UUID id) {
    return jdbc.sql("SELECT id FROM generation_evidence WHERE job_id=?")
        .param(id)
        .query(UUID.class)
        .list();
  }

  public Integer revokeTreeGenerationEvidenceRevocation(UUID evidence) {
    return jdbc.sql("SELECT count(*) FROM generation_evidence_revocation WHERE evidence_id=?")
        .param(evidence)
        .query(Integer.class)
        .single();
  }

  public int revokeTreeGenerationEvidenceRevocation2(UUID evidence, UUID root, String propagated) {
    return jdbc.sql(
            "INSERT INTO generation_evidence_revocation(evidence_id,root_job_id,reason) VALUES"
                + " (?,?,?)")
        .param(evidence)
        .param(root)
        .param(propagated)
        .update();
  }

  public int revokeTreeProblemVersion(String propagated, UUID owner, UUID id) {
    return jdbc.sql(
            "UPDATE problem_version SET"
                + " review_hold=true,review_reason=?,review_held_at=CURRENT_TIMESTAMP WHERE"
                + " owner_id=? AND review_hold=false AND id IN (SELECT"
                + " CONCAT(CONCAT(CONCAT('generated-',CAST(id AS VARCHAR(36))),'-r'),CAST(revision"
                + " AS VARCHAR(10))) FROM generation_job WHERE id=?)")
        .param(propagated)
        .param(owner)
        .param(id)
        .update();
  }

  public int revokeTreeGenerationJob2(UUID id, UUID owner) {
    return jdbc.sql(
            "UPDATE generation_job SET status=CASE WHEN status IN"
                + " ('QUEUED','AWAITING_REVIEW','VALIDATING') THEN 'NEEDS_REVIEW' ELSE status"
                + " END,error_code='STRUCTURE_EVIDENCE_REVOKED',updated_at=CURRENT_TIMESTAMP WHERE"
                + " id=? AND owner_id=?")
        .param(id)
        .param(owner)
        .update();
  }
}
