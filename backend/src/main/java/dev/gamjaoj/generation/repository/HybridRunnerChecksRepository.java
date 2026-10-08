package dev.gamjaoj.generation.repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence operations for HybridRunnerChecks; transaction ownership remains in the service. */
@Repository
public class HybridRunnerChecksRepository {
  private final JdbcClient jdbc;

  public HybridRunnerChecksRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Integer retryReaderHybridApiReservation(UUID argument0) {
    return jdbc.sql("SELECT count(*) FROM hybrid_api_reservation WHERE generation_id=?")
        .param(argument0)
        .query(Integer.class)
        .single();
  }

  public <T> List<T> retryReaderHybridBranch(UUID argument0, int argument1, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT id,attempt,status,input_json,input_sha256,contract_sha256,public_sha256 FROM"
                + " hybrid_branch WHERE generation_id=? AND revision=? AND role='READER' ORDER BY"
                + " attempt")
        .param(argument0)
        .param(argument1)
        .query(mapper)
        .list();
  }

  public int retryReaderHybridBranch2(String error, OffsetDateTime now, UUID argument2) {
    return jdbc.sql(
            "UPDATE hybrid_branch SET status='SUPERSEDED',error_code=?,finished_at=? WHERE id=?")
        .param(error)
        .param(now)
        .param(argument2)
        .update();
  }

  public int retryReaderHybridBranch3(Object argument0) {
    return jdbc.sql(
            "UPDATE hybrid_branch SET status='SUPERSEDED' WHERE id=? AND status='SUCCEEDED'")
        .param(argument0)
        .update();
  }

  public int retryReaderHybridBranch4(
      UUID argument0,
      UUID argument1,
      int argument2,
      int argument3,
      Object argument4,
      Object argument5,
      Object argument6,
      Object argument7,
      OffsetDateTime now) {
    return jdbc.sql(
            "INSERT INTO"
                + " hybrid_branch(id,generation_id,revision,role,attempt,status,input_json,input_sha256,contract_sha256,public_sha256,created_at)"
                + " VALUES (?,?,?,'READER',?,'QUEUED',?,?,?,?,?)")
        .param(argument0)
        .param(argument1)
        .param(argument2)
        .param(argument3)
        .param(argument4)
        .param(argument5)
        .param(argument6)
        .param(argument7)
        .param(now)
        .update();
  }

  public int retryReaderAiAttempt(
      UUID attempt, String argument1, BigDecimal amount, String argument3) {
    return jdbc.sql(
            "INSERT INTO ai_attempt(id,month_key,status,reserved_usd,settings_json) VALUES"
                + " (?,?,'HYBRID_RESERVED',?,?)")
        .param(attempt)
        .param(argument1)
        .param(amount)
        .param(argument3)
        .update();
  }

  public int retryReaderHybridApiReservation2(
      UUID attempt, UUID argument1, int argument2, int argument3) {
    return jdbc.sql(
            "INSERT INTO hybrid_api_reservation(attempt_id,generation_id,revision,role,retry)"
                + " VALUES (?,?,?,'READER',?)")
        .param(attempt)
        .param(argument1)
        .param(argument2)
        .param(argument3)
        .update();
  }

  public int retryReaderHybridGeneration(
      OffsetDateTime argument0, OffsetDateTime argument1, OffsetDateTime now, UUID argument3) {
    return jdbc.sql(
            "UPDATE hybrid_generation SET status='BUILDING',error_code=NULL,deadline_at=CASE WHEN"
                + " deadline_at<? THEN ? ELSE deadline_at END,updated_at=? WHERE id=?")
        .param(argument0)
        .param(argument1)
        .param(now)
        .param(argument3)
        .update();
  }

  public <T> Optional<T> checkedPackageHybridBranch(UUID branch, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT b.generation_id,b.revision,b.input_json,b.input_sha256 FROM hybrid_branch b"
                + " JOIN hybrid_generation g ON g.id=b.generation_id WHERE b.id=? AND"
                + " b.role='VALIDATION' AND b.status='CHECKED' AND b.revision=g.revision")
        .param(branch)
        .query(mapper)
        .optional();
  }

  public <T> T checkedPackageHybridValidationProfile(UUID branch, RowMapper<T> mapper) {
    return jdbc.sql("SELECT policy,profile_hash FROM hybrid_validation_profile WHERE branch_id=?")
        .param(branch)
        .query(mapper)
        .single();
  }

  public Integer lockAiBudgetLock() {
    return jdbc.sql("SELECT id FROM ai_budget_lock WHERE id=1 FOR UPDATE")
        .query(Integer.class)
        .single();
  }

  public <T> List<T> artifactsHybridBranch(UUID argument0, int argument1, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT"
                + " b.role,b.output_sha256,b.contract_sha256,b.public_sha256,a.payload_json,a.payload_sha256"
                + " FROM hybrid_branch b JOIN hybrid_artifact a ON a.branch_id=b.id WHERE"
                + " b.generation_id=? AND b.revision=? AND b.status='SUCCEEDED' AND"
                + " b.role<>'VALIDATION' ORDER BY b.attempt")
        .param(argument0)
        .param(argument1)
        .query(mapper)
        .list();
  }

  public String qualifiedSecondsHybridValidationProfile(UUID argument0) {
    return jdbc.sql("SELECT policy FROM hybrid_validation_profile WHERE branch_id=?")
        .param(argument0)
        .query(String.class)
        .single();
  }

  public int queuePlanSubmission(
      UUID id,
      String argument1,
      String source,
      String sourceHash,
      UUID idArgument4,
      Object argument5,
      String argument6,
      String payload,
      String hash,
      UUID argument9,
      String argument10,
      UUID argument11) {
    return jdbc.sql(
            "INSERT INTO"
                + " submission(id,user_id,problem_version,source_code,source_sha256,idempotency_key,runtime_image,runner_policy,run_input,run_package,run_package_sha256,hybrid_branch_id)"
                + " SELECT ?,g.owner_id,?,?,?,?,p.runtime_image,?,?,?,?,? FROM hybrid_generation g"
                + " JOIN problem_version p ON p.id=? WHERE g.id=?")
        .param(id)
        .param(argument1)
        .param(source)
        .param(sourceHash)
        .param(idArgument4)
        .param(argument5)
        .param(argument6)
        .param(payload)
        .param(hash)
        .param(argument9)
        .param(argument10)
        .param(argument11)
        .update();
  }

  public int queuePlanSubmission2(String argument0, UUID id) {
    return jdbc.sql("UPDATE submission SET execution_profile_json=? WHERE id=?")
        .param(argument0)
        .param(id)
        .update();
  }

  public int queuePlanJudgeJob(UUID id, String argument1) {
    return jdbc.sql("INSERT INTO judge_job(submission_id,priority,execution_mode) VALUES (?,1,?)")
        .param(id)
        .param(argument1)
        .update();
  }

  public int queuePlanHybridExecutionCheck(
      UUID argument0, String role, UUID id, String sourceHash, String hash) {
    return jdbc.sql(
            "INSERT INTO"
                + " hybrid_execution_check(branch_id,role,submission_id,source_sha256,package_sha256)"
                + " VALUES (?,?,?,?,?)")
        .param(argument0)
        .param(role)
        .param(id)
        .param(sourceHash)
        .param(hash)
        .update();
  }

  public String modeHybridValidationProfile(UUID argument0) {
    return jdbc.sql("SELECT scheduling FROM hybrid_validation_profile WHERE branch_id=?")
        .param(argument0)
        .query(String.class)
        .single();
  }

  public String finishHybridBranch(UUID argument0) {
    return jdbc.sql("SELECT completion_json FROM hybrid_branch WHERE id=?")
        .param(argument0)
        .query(String.class)
        .single();
  }

  public int finishHybridBranch2(Object argument0, String error, Object argument2, UUID argument3) {
    return jdbc.sql(
            "UPDATE hybrid_branch SET"
                + " status=?,error_code=?,completion_json=?,finished_at=CURRENT_TIMESTAMP WHERE"
                + " id=? AND status IN ('BLOCKED','RUNNING')")
        .param(argument0)
        .param(error)
        .param(argument2)
        .param(argument3)
        .update();
  }

  public int finishHybridGeneration(String error, UUID argument1) {
    return jdbc.sql(
            "UPDATE hybrid_generation SET status='HELD',error_code=?,updated_at=CURRENT_TIMESTAMP"
                + " WHERE id=?")
        .param(error)
        .param(argument1)
        .update();
  }

  public <T> List<T> startEarlyHybridGeneration(RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT g.id,g.revision,g.contract_sha256,r.profile_id FROM hybrid_generation g JOIN"
                + " hybrid_public_request r ON r.generation_id=g.id WHERE g.status='BUILDING' AND"
                + " g.deadline_at>CURRENT_TIMESTAMP AND g.contract_sha256 IS NOT NULL AND EXISTS"
                + " (SELECT 1 FROM hybrid_branch c WHERE c.generation_id=g.id AND"
                + " c.revision=g.revision AND c.role='CORE' AND c.status='SUCCEEDED') AND NOT"
                + " EXISTS (SELECT 1 FROM hybrid_branch v WHERE v.generation_id=g.id AND"
                + " v.revision=g.revision AND v.role='VALIDATION') ORDER BY g.created_at")
        .query(mapper)
        .list();
  }

  public UUID startEarlyHybridGeneration2(UUID generation) {
    return jdbc.sql("SELECT id FROM hybrid_generation WHERE id=? FOR UPDATE")
        .param(generation)
        .query(UUID.class)
        .single();
  }

  public <T> List<T> startEarlyHybridBranch(UUID generation, int revision, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT role,output_sha256 FROM hybrid_branch WHERE generation_id=? AND revision=? AND"
                + " role IN ('CONTRACT','CORE') AND status='SUCCEEDED'")
        .param(generation)
        .param(revision)
        .query(mapper)
        .list();
  }

  public Integer startEarlyHybridBranch2(UUID generation, int revision) {
    return jdbc.sql(
            "SELECT count(*) FROM hybrid_branch WHERE generation_id=? AND revision=? AND"
                + " role='VALIDATION'")
        .param(generation)
        .param(revision)
        .query(Integer.class)
        .single();
  }

  public int startEarlyHybridBranch3(
      UUID branch, UUID generation, int revision, String raw, String argument4, Object argument5) {
    return jdbc.sql(
            "INSERT INTO"
                + " hybrid_branch(id,generation_id,revision,role,attempt,status,input_json,input_sha256,contract_sha256,created_at,started_at)"
                + " VALUES"
                + " (?,?,?,'VALIDATION',0,'EARLY',?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")
        .param(branch)
        .param(generation)
        .param(revision)
        .param(raw)
        .param(argument4)
        .param(argument5)
        .update();
  }

  public <T> T startEarlyHybridPublicRequest(UUID generation, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT profile_hash,contract_sha256 FROM hybrid_public_request WHERE generation_id=?")
        .param(generation)
        .query(mapper)
        .single();
  }

  public int startEarlyHybridValidationProfile(
      UUID branch, String argument1, String argument2, String PIPELINE) {
    return jdbc.sql(
            "INSERT INTO hybrid_validation_profile(branch_id,policy,profile_hash,scheduling) VALUES"
                + " (?,?,?,?)")
        .param(branch)
        .param(argument1)
        .param(argument2)
        .param(PIPELINE)
        .update();
  }

  public int placeholderProblemVersion(
      String argument0, String empty, String argument2, UUID argument3) {
    return jdbc.sql(
            "INSERT INTO"
                + " problem_version(id,package_json,package_sha256,runtime_image,runner_policy,ready,owner_id)"
                + " SELECT ?,?,?,p.runtime_image,p.runner_policy,false,g.owner_id FROM"
                + " problem_version p JOIN hybrid_generation g ON g.id=? WHERE p.id='total-v1'")
        .param(argument0)
        .param(empty)
        .param(argument2)
        .param(argument3)
        .update();
  }

  public <T> List<T> advanceHybridBranch(RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT b.id,b.generation_id,b.revision,b.input_json,b.input_sha256,b.status FROM"
                + " hybrid_branch b JOIN hybrid_generation g ON g.id=b.generation_id WHERE"
                + " b.role='VALIDATION' AND b.revision=g.revision AND ((b.status='BLOCKED' AND"
                + " g.status='HELD' AND g.error_code='VALIDATION_ADAPTER_NOT_CONNECTED') OR"
                + " (b.status='RUNNING' AND g.status='VALIDATING') OR (b.status='EARLY' AND"
                + " g.status='BUILDING')) AND g.deadline_at>CURRENT_TIMESTAMP ORDER BY"
                + " b.created_at")
        .query(mapper)
        .list();
  }

  public UUID advanceHybridGeneration(UUID argument0) {
    return jdbc.sql("SELECT id FROM hybrid_generation WHERE id=? FOR UPDATE")
        .param(argument0)
        .query(UUID.class)
        .single();
  }

  public String advanceHybridValidationProfile(UUID argument0) {
    return jdbc.sql("SELECT policy FROM hybrid_validation_profile WHERE branch_id=?")
        .param(argument0)
        .query(String.class)
        .single();
  }

  public Integer advanceHybridValidationProfile2(UUID argument0) {
    return jdbc.sql("SELECT count(*) FROM hybrid_validation_profile WHERE branch_id=?")
        .param(argument0)
        .query(Integer.class)
        .single();
  }

  public Optional<String> advanceHybridPublicRequest(UUID argument0) {
    return jdbc.sql("SELECT profile_id FROM hybrid_public_request WHERE generation_id=?")
        .param(argument0)
        .query(String.class)
        .optional();
  }

  public Optional<String> advanceHybridValidationProfile3(UUID argument0) {
    return jdbc.sql("SELECT policy FROM hybrid_validation_profile WHERE branch_id=?")
        .param(argument0)
        .query(String.class)
        .optional();
  }

  public UUID advanceHybridGeneration2(UUID argument0) {
    return jdbc.sql("SELECT id FROM hybrid_generation WHERE id=? FOR UPDATE")
        .param(argument0)
        .query(UUID.class)
        .single();
  }

  public <T> Optional<T> advanceHybridPublicRequest2(UUID argument0, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT profile_hash,contract_sha256 FROM hybrid_public_request WHERE generation_id=?")
        .param(argument0)
        .query(mapper)
        .optional();
  }

  public String advanceHybridValidationProfile4(UUID argument0) {
    return jdbc.sql("SELECT profile_hash FROM hybrid_validation_profile WHERE branch_id=?")
        .param(argument0)
        .query(String.class)
        .single();
  }

  public String advanceHybridValidationProfile5(UUID argument0) {
    return jdbc.sql("SELECT profile_hash FROM hybrid_validation_profile WHERE branch_id=?")
        .param(argument0)
        .query(String.class)
        .single();
  }

  public int advanceHybridBranch2(UUID argument0) {
    return jdbc.sql("UPDATE hybrid_branch SET status='RUNNING' WHERE id=?")
        .param(argument0)
        .update();
  }

  public int advanceHybridGeneration3(UUID argument0) {
    return jdbc.sql(
            "UPDATE hybrid_generation SET"
                + " status='VALIDATING',error_code=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?")
        .param(argument0)
        .update();
  }

  public int advanceHybridValidationProfile6(
      UUID argument0, String policy, Object argument2, String scheduling) {
    return jdbc.sql(
            "INSERT INTO hybrid_validation_profile(branch_id,policy,profile_hash,scheduling) VALUES"
                + " (?,?,?,?)")
        .param(argument0)
        .param(policy)
        .param(argument2)
        .param(scheduling)
        .update();
  }

  public int advanceHybridBranch3(UUID argument0) {
    return jdbc.sql(
            "UPDATE hybrid_branch SET status='RUNNING',started_at=CURRENT_TIMESTAMP WHERE id=?")
        .param(argument0)
        .update();
  }

  public int advanceHybridGeneration4(UUID argument0) {
    return jdbc.sql(
            "UPDATE hybrid_generation SET"
                + " status='VALIDATING',error_code=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?")
        .param(argument0)
        .update();
  }

  public <T> List<T> advanceResultsHybridExecutionCheck(UUID argument0, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT"
                + " e.role,e.source_sha256,e.package_sha256,s.source_code,s.source_sha256,s.run_package,s.run_package_sha256,j.status,j.verdict,j.result_json,j.result_sha256,j.execution_mode"
                + " FROM hybrid_execution_check e JOIN submission s ON s.id=e.submission_id JOIN"
                + " judge_job j ON j.submission_id=s.id WHERE e.branch_id=? ORDER BY e.role")
        .param(argument0)
        .query(mapper)
        .list();
  }

  public String advanceResultsHybridValidationProfile(UUID argument0) {
    return jdbc.sql("SELECT scheduling FROM hybrid_validation_profile WHERE branch_id=?")
        .param(argument0)
        .query(String.class)
        .single();
  }

  public int advancePackageHybridPackageEvidence(
      UUID argument0, long generatorSeed, long randomSeed) {
    return jdbc.sql(
            "INSERT INTO hybrid_package_evidence(branch_id,generator_seed,random_seed) VALUES"
                + " (?,?,?)")
        .param(argument0)
        .param(generatorSeed)
        .param(randomSeed)
        .update();
  }

  public <T> T advancePackageHybridPackageEvidence2(UUID argument0, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT"
                + " generator_seed,random_seed,candidates_json,candidates_sha256,package_json,package_sha256"
                + " FROM hybrid_package_evidence WHERE branch_id=?")
        .param(argument0)
        .query(mapper)
        .single();
  }

  public int advancePackageHybridPackageEvidence3(
      String candidateJson, String argument1, UUID argument2) {
    return jdbc.sql(
            "UPDATE hybrid_package_evidence SET candidates_json=?,candidates_sha256=? WHERE"
                + " branch_id=? AND candidates_json IS NULL")
        .param(candidateJson)
        .param(argument1)
        .param(argument2)
        .update();
  }

  public int advancePackageHybridPackageEvidence4(String payload, String hash, UUID argument2) {
    return jdbc.sql(
            "UPDATE hybrid_package_evidence SET package_json=?,package_sha256=? WHERE branch_id=?"
                + " AND package_json IS NULL")
        .param(payload)
        .param(hash)
        .param(argument2)
        .update();
  }

  public int advancePackageProblemVersion(
      String payload, String hash, String teachingJson, String argument3) {
    return jdbc.sql(
            "UPDATE problem_version SET package_json=?,package_sha256=?,teaching_json=? WHERE id=?"
                + " AND ready=false")
        .param(payload)
        .param(hash)
        .param(teachingJson)
        .param(argument3)
        .update();
  }

  public <T> T advancePackageProblemVersion2(String argument0, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT package_json,package_sha256,teaching_json,ready FROM problem_version WHERE"
                + " id=?")
        .param(argument0)
        .query(mapper)
        .single();
  }

  public int advancePipelineHybridPackageEvidence(UUID argument0, long argument1, long argument2) {
    return jdbc.sql(
            "INSERT INTO hybrid_package_evidence(branch_id,generator_seed,random_seed) VALUES"
                + " (?,?,?)")
        .param(argument0)
        .param(argument1)
        .param(argument2)
        .update();
  }

  public String advancePipelineHybridPackageEvidence2(UUID argument0) {
    return jdbc.sql("SELECT generator_seed FROM hybrid_package_evidence WHERE branch_id=?")
        .param(argument0)
        .query(String.class)
        .single();
  }

  public <T> T advancePipelineHybridPackageEvidence3(UUID argument0, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT"
                + " generator_seed,random_seed,candidates_json,candidates_sha256,package_json,package_sha256"
                + " FROM hybrid_package_evidence WHERE branch_id=?")
        .param(argument0)
        .query(mapper)
        .single();
  }

  public int advancePipelineHybridPackageEvidence4(
      String candidateJson, String argument1, UUID argument2) {
    return jdbc.sql(
            "UPDATE hybrid_package_evidence SET candidates_json=?,candidates_sha256=? WHERE"
                + " branch_id=? AND candidates_json IS NULL")
        .param(candidateJson)
        .param(argument1)
        .param(argument2)
        .update();
  }

  public int advancePipelineHybridPackageEvidence5(String payload, String hash, UUID argument2) {
    return jdbc.sql(
            "UPDATE hybrid_package_evidence SET package_json=?,package_sha256=? WHERE branch_id=?"
                + " AND package_json IS NULL")
        .param(payload)
        .param(hash)
        .param(argument2)
        .update();
  }

  public int advancePipelineProblemVersion(
      String payload, String hash, String teachingJson, String argument3) {
    return jdbc.sql(
            "UPDATE problem_version SET package_json=?,package_sha256=?,teaching_json=? WHERE id=?"
                + " AND ready=false")
        .param(payload)
        .param(hash)
        .param(teachingJson)
        .param(argument3)
        .update();
  }

  public <T> T advancePipelineProblemVersion2(String argument0, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT package_json,package_sha256,teaching_json,ready FROM problem_version WHERE"
                + " id=?")
        .param(argument0)
        .query(mapper)
        .single();
  }
}
