package dev.gamjaoj.repository.generation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence operations for GenerationJobs; transaction ownership remains in the service. */
@Repository
public class GenerationJobsRepository {
  private final JdbcClient jdbc;

  public GenerationJobsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Integer lockAiBudgetLock() {
    return jdbc.sql("SELECT id FROM ai_budget_lock WHERE id=1 FOR UPDATE")
        .query(Integer.class)
        .single();
  }

  public Integer createGenerationJob(UUID key) {
    return jdbc.sql("SELECT count(*) FROM generation_job WHERE id=?")
        .param(key)
        .query(Integer.class)
        .single();
  }

  public <T> T createGenerationJob2(UUID key, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT focus,source_analysis_id,template_id,share_on_publish FROM generation_job WHERE"
                + " id=?")
        .param(key)
        .query(mapper)
        .single();
  }

  public Integer createGenerationJob3(UUID owner) {
    return jdbc.sql(
            "SELECT count(*) FROM generation_job WHERE owner_id=? AND status IN"
                + " ('QUEUED','GENERATING','AWAITING_REVIEW','VALIDATING')")
        .param(owner)
        .query(Integer.class)
        .single();
  }

  public int createGenerationJob4(
      UUID key, UUID owner, String template, String model, String effort, String focus) {
    return jdbc.sql(
            "INSERT INTO generation_job (id,owner_id,template_id,status,model,effort,focus) VALUES"
                + " (?,?,?,'QUEUED',?,?,?)")
        .param(key)
        .param(owner)
        .param(template)
        .param(model)
        .param(effort)
        .param(focus)
        .update();
  }

  public int createGenerationJob5(boolean shared, UUID key) {
    return jdbc.sql(
            "UPDATE generation_job SET resource_validation=true,share_on_publish=? WHERE id=?")
        .param(shared)
        .param(key)
        .update();
  }

  public int createGenerationJob6(UUID sourceAnalysis, String argument1, UUID key) {
    return jdbc.sql(
            "UPDATE generation_job SET source_analysis_id=?,learning_context_json=? WHERE id=?")
        .param(sourceAnalysis)
        .param(argument1)
        .param(key)
        .update();
  }

  public List<String> createGenerationJob7(UUID owner, UUID key) {
    return jdbc.sql(
            "SELECT artifacts_json FROM generation_job WHERE owner_id=? AND id<>? AND"
                + " artifacts_json IS NOT NULL ORDER BY created_at DESC LIMIT 12")
        .param(owner)
        .param(key)
        .query(String.class)
        .list();
  }

  public List<String> createGenerationJob8(UUID owner) {
    return jdbc.sql(
            "SELECT theme_domain FROM generation_job WHERE owner_id=? AND theme_domain IS NOT NULL"
                + " ORDER BY created_at DESC LIMIT 8")
        .param(owner)
        .query(String.class)
        .list();
  }

  public int createGenerationJob9(UUID theme, String domain, UUID key) {
    return jdbc.sql("UPDATE generation_job SET theme_task_id=?,theme_domain=? WHERE id=?")
        .param(theme)
        .param(domain)
        .param(key)
        .update();
  }

  public <T> List<T> learningOptionsAiTask(
      UUID owner, UUID ownerArgument1, Object argument2, String argument3, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT a.id,a.submission_id,a.result_json FROM ai_task a JOIN submission s ON"
                + " s.id=a.submission_id WHERE a.user_id=? AND s.user_id=? AND a.kind='ANALYSIS'"
                + " AND a.status='COMPLETED' AND EXISTS (SELECT 1 FROM problem_version p WHERE"
                + " p.id=s.problem_version AND p.review_hold=false) AND (s.problem_version=? OR"
                + " EXISTS (SELECT 1 FROM generation_job g WHERE g.template_id=? AND"
                + " s.problem_version=CONCAT(CONCAT(CONCAT('generated-',CAST(g.id AS"
                + " VARCHAR(36))),'-r'),CAST(g.revision AS VARCHAR(10))))) ORDER BY a.updated_at"
                + " DESC,a.id LIMIT 10")
        .param(owner)
        .param(ownerArgument1)
        .param(argument2)
        .param(argument3)
        .query(mapper)
        .list();
  }

  public <T> Optional<T> learningContextAiTask(
      UUID id, UUID owner, UUID ownerArgument2, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT a.result_json,s.problem_version FROM ai_task a JOIN submission s ON"
                + " s.id=a.submission_id WHERE a.id=? AND a.user_id=? AND s.user_id=? AND"
                + " a.kind='ANALYSIS' AND a.status='COMPLETED' AND EXISTS (SELECT 1 FROM"
                + " problem_version p WHERE p.id=s.problem_version AND p.review_hold=false)")
        .param(id)
        .param(owner)
        .param(ownerArgument2)
        .query(mapper)
        .optional();
  }

  public Integer learningContextGenerationJob(String argument0, String argument1) {
    return jdbc.sql(
            "SELECT count(*) FROM generation_job WHERE template_id=? AND"
                + " ?=CONCAT(CONCAT(CONCAT('generated-',CAST(id AS"
                + " VARCHAR(36))),'-r'),CAST(revision AS VARCHAR(10)))")
        .param(argument0)
        .param(argument1)
        .query(Integer.class)
        .single();
  }

  public Stream<UUID> listGenerationJob(UUID argument0) {
    return jdbc
        .sql("SELECT id FROM generation_job WHERE owner_id=? ORDER BY created_at DESC LIMIT 30")
        .param(argument0)
        .query(UUID.class)
        .list()
        .stream();
  }

  public Integer viewGenerationJob(UUID id, UUID argument1) {
    return jdbc.sql("SELECT count(*) FROM generation_job WHERE id=? AND owner_id=?")
        .param(id)
        .param(argument1)
        .query(Integer.class)
        .single();
  }

  public <T> Optional<T> findGenerationJob(UUID id, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT g.*,COALESCE(p.review_hold,false) AS problem_held,p.review_reason FROM"
                + " generation_job g LEFT JOIN problem_version p ON"
                + " p.id=CONCAT(CONCAT(CONCAT('generated-',CAST(g.id AS"
                + " VARCHAR(36))),'-r'),CAST(g.revision AS VARCHAR(10))) WHERE g.id=?")
        .param(id)
        .query(mapper)
        .optional();
  }

  public Optional<String> recoveryInfoGenerationJob(UUID id) {
    return jdbc.sql("SELECT repair_json FROM generation_job WHERE id=?")
        .param(id)
        .query(String.class)
        .optional();
  }

  public <T> Optional<T> themeForGenerationJob(UUID id, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT a.status,a.result_json,a.error_code,g.theme_domain FROM generation_job g JOIN"
                + " ai_task a ON a.id=g.theme_task_id WHERE g.id=?")
        .param(id)
        .query(mapper)
        .optional();
  }

  public UUID retryThemeGenerationJob(UUID id) {
    return jdbc.sql("SELECT theme_task_id FROM generation_job WHERE id=?")
        .param(id)
        .query(UUID.class)
        .single();
  }

  public Integer retryThemeGenerationJob2(UUID id, UUID idArgument1) {
    return jdbc.sql(
            "SELECT count(*) FROM generation_job WHERE owner_id=(SELECT owner_id FROM"
                + " generation_job WHERE id=?) AND id<>? AND status IN"
                + " ('QUEUED','GENERATING','AWAITING_REVIEW','VALIDATING')")
        .param(id)
        .param(idArgument1)
        .query(Integer.class)
        .single();
  }

  public int retryThemeGenerationJob3(UUID id) {
    return jdbc.sql("UPDATE generation_job SET status='QUEUED',error_code=NULL WHERE id=?")
        .param(id)
        .update();
  }

  public Optional<String> recentStoriesGenerationJob(UUID id) {
    return jdbc.sql(
            "SELECT a.input_json FROM generation_job g JOIN ai_task a ON a.id=g.theme_task_id WHERE"
                + " g.id=?")
        .param(id)
        .query(String.class)
        .optional();
  }

  public String typeForGenerationJob(UUID id) {
    return jdbc.sql("SELECT template_id FROM generation_job WHERE id=?")
        .param(id)
        .query(String.class)
        .single();
  }

  public String specForGenerationJob(UUID id) {
    return jdbc.sql("SELECT focus FROM generation_job WHERE id=?")
        .param(id)
        .query(String.class)
        .single();
  }

  public <T> Optional<T> specForGenerationJob2(UUID id, RowMapper<T> mapper) {
    return jdbc.sql("SELECT learning_context_json FROM generation_job WHERE id=?")
        .param(id)
        .query(mapper)
        .optional();
  }

  public Optional<String> repairForGenerationJob(UUID id) {
    return jdbc.sql("SELECT repair_json FROM generation_job WHERE id=?")
        .param(id)
        .query(String.class)
        .optional();
  }

  public String repairForGenerationJob2(UUID id) {
    return jdbc.sql("SELECT oracle_json FROM generation_job WHERE id=?")
        .param(id)
        .query(String.class)
        .single();
  }

  public Integer claimGenerationProseReview() {
    return jdbc.sql("SELECT count(*) FROM generation_prose_review WHERE status='GENERATING'")
        .query(Integer.class)
        .single();
  }

  public int claimGenerationJob() {
    return jdbc.sql(
            "UPDATE generation_job SET status='NEEDS_REVIEW',error_code='GENERATION_INTERRUPTED'"
                + " WHERE status='GENERATING' AND lease_until<CURRENT_TIMESTAMP")
        .update();
  }

  public Integer claimGenerationJob2() {
    return jdbc.sql("SELECT count(*) FROM generation_job WHERE status='GENERATING'")
        .query(Integer.class)
        .single();
  }

  public Optional<UUID> claimGenerationJob3() {
    return jdbc.sql(
            "SELECT id FROM generation_job WHERE status='QUEUED' AND (theme_task_id IS NULL OR"
                + " EXISTS (SELECT 1 FROM ai_task a WHERE a.id=theme_task_id AND"
                + " a.status='COMPLETED')) ORDER BY created_at LIMIT 1 FOR UPDATE")
        .query(UUID.class)
        .optional();
  }

  public int claimGenerationJob4(UUID token, OffsetDateTime argument1, UUID argument2) {
    return jdbc.sql(
            "UPDATE generation_job SET"
                + " status='GENERATING',token=?,lease_until=?,updated_at=CURRENT_TIMESTAMP WHERE"
                + " id=?")
        .param(token)
        .param(argument1)
        .param(argument2)
        .update();
  }

  public int claimGenerationAttempt(
      UUID argument0, int argument1, String argument2, String argument3) {
    return jdbc.sql(
            "INSERT INTO generation_attempt (job_id,revision,model,effort,prompt_version) VALUES"
                + " (?,?,?,?,'typed-author-oracle-v5')")
        .param(argument0)
        .param(argument1)
        .param(argument2)
        .param(argument3)
        .update();
  }

  public int completeGenerationJob(String argument0, UUID argument1) {
    return jdbc.sql("UPDATE generation_job SET status='NEEDS_REVIEW',error_code=? WHERE id=?")
        .param(argument0)
        .param(argument1)
        .update();
  }

  public String completeAppUser(UUID argument0) {
    return jdbc.sql(
            "SELECT u.username FROM app_user u JOIN generation_job g ON g.owner_id=u.id WHERE"
                + " g.id=?")
        .param(argument0)
        .query(String.class)
        .single();
  }

  public UUID completeGenerationJob2(UUID id) {
    return jdbc.sql("SELECT token FROM generation_job WHERE id=?")
        .param(id)
        .query(UUID.class)
        .single();
  }

  public Optional<String> completeGenerationAttempt(UUID id, int argument1) {
    return jdbc.sql("SELECT result_json FROM generation_attempt WHERE job_id=? AND revision=?")
        .param(id)
        .param(argument1)
        .query(String.class)
        .optional();
  }

  public Integer completeGenerationJob3(UUID id) {
    return jdbc.sql(
            "SELECT count(*) FROM generation_job WHERE id=? AND lease_until>CURRENT_TIMESTAMP")
        .param(id)
        .query(Integer.class)
        .single();
  }

  public int completeGenerationAttempt2(String audit, String cliVersion, UUID id, int argument3) {
    return jdbc.sql(
            "UPDATE generation_attempt SET result_json=?,cli_version=? WHERE job_id=? AND"
                + " revision=?")
        .param(audit)
        .param(cliVersion)
        .param(id)
        .param(argument3)
        .update();
  }

  public int completeGenerationJob4(String argument0, UUID id) {
    return jdbc.sql("UPDATE generation_job SET status='NEEDS_AUTH',error_code=? WHERE id=?")
        .param(argument0)
        .param(id)
        .update();
  }

  public int completeGenerationJob5(String raw, String argument1, String argument2, UUID id) {
    return jdbc.sql(
            "UPDATE generation_job SET artifacts_json=?,oracle_json=?,artifacts_sha256=? WHERE"
                + " id=?")
        .param(raw)
        .param(argument1)
        .param(argument2)
        .param(id)
        .update();
  }

  public int completeGenerationJob6(String json, String oracleJson, String hash, UUID id) {
    return jdbc.sql(
            "UPDATE generation_job SET"
                + " status='AWAITING_REVIEW',artifacts_json=?,oracle_json=?,artifacts_sha256=?,review_sha256=NULL,error_code=NULL,updated_at=CURRENT_TIMESTAMP"
                + " WHERE id=?")
        .param(json)
        .param(oracleJson)
        .param(hash)
        .param(id)
        .update();
  }

  public String completeAppUser2(UUID id) {
    return jdbc.sql(
            "SELECT u.username FROM app_user u JOIN generation_job g ON g.owner_id=u.id WHERE"
                + " g.id=?")
        .param(id)
        .query(String.class)
        .single();
  }

  public int reviewProblemVersion(String ver, String json, String argument2, String argument3) {
    return jdbc.sql(
            "INSERT INTO problem_version"
                + " (id,package_json,package_sha256,runtime_image,runner_policy,ready) SELECT"
                + " ?,?,?,runtime_image,runner_policy,false FROM problem_version WHERE id=?")
        .param(ver)
        .param(json)
        .param(argument2)
        .param(argument3)
        .update();
  }

  public int reviewProblemVersion2(UUID id, UUID idArgument1, String ver) {
    return jdbc.sql(
            "UPDATE problem_version SET owner_id=(SELECT owner_id FROM generation_job WHERE"
                + " id=?),shared=(SELECT share_on_publish FROM generation_job WHERE id=?) WHERE"
                + " id=?")
        .param(id)
        .param(idArgument1)
        .param(ver)
        .update();
  }

  public int reviewGenerationJob(String hash, UUID id) {
    return jdbc.sql(
            "UPDATE generation_job SET status='VALIDATING',review_sha256=?,validation_json=NULL"
                + " WHERE id=?")
        .param(hash)
        .param(id)
        .update();
  }

  public <T> Optional<T> repairInputLayoutProblemVersion(String oldVersion, RowMapper<T> mapper) {
    return jdbc.sql("SELECT package_json,package_sha256 FROM problem_version WHERE id=?")
        .param(oldVersion)
        .query(mapper)
        .optional();
  }

  public <T> T repairInputLayoutGenerationJob(UUID id, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT g.owner_id,u.username FROM generation_job g JOIN app_user u ON u.id=g.owner_id"
                + " WHERE g.id=?")
        .param(id)
        .query(mapper)
        .single();
  }

  public int repairInputLayoutProblemVersion2(String reason, String argument1) {
    return jdbc.sql(
            "UPDATE problem_version SET"
                + " review_hold=true,review_reason=?,review_held_at=CURRENT_TIMESTAMP WHERE id LIKE"
                + " ? AND review_hold=false")
        .param(reason)
        .param(argument1)
        .update();
  }

  public int repairInputLayoutGenerationJob2(String argument0, UUID id) {
    return jdbc.sql(
            "UPDATE generation_job SET"
                + " revision=revision+1,status='AWAITING_REVIEW',review_sha256=NULL,validation_json=NULL,error_code=NULL,structure_contract=?"
                + " WHERE id=?")
        .param(argument0)
        .param(id)
        .update();
  }

  public int executeSubmission(
      UUID submission,
      String ver,
      String source,
      String argument3,
      UUID submissionArgument4,
      Object argument5,
      Object argument6,
      String json,
      String argument8,
      String verArgument9,
      UUID argument10) {
    return jdbc.sql(
            "INSERT INTO submission"
                + " (id,user_id,problem_version,source_code,source_sha256,idempotency_key,runtime_image,runner_policy,run_input,run_package,run_package_sha256,generation_job_id)"
                + " SELECT ?,g.owner_id,?,?,?,?,p.runtime_image,?,?,?, ?,g.id FROM generation_job g"
                + " JOIN problem_version p ON p.id=? WHERE g.id=?")
        .param(submission)
        .param(ver)
        .param(source)
        .param(argument3)
        .param(submissionArgument4)
        .param(argument5)
        .param(argument6)
        .param(json)
        .param(argument8)
        .param(verArgument9)
        .param(argument10)
        .update();
  }

  public int executeJudgeJob(UUID submission, String argument1) {
    return jdbc.sql("INSERT INTO judge_job (submission_id,priority,execution_mode) VALUES (?,1,?)")
        .param(submission)
        .param(argument1)
        .update();
  }

  public int executeGenerationExecution(
      UUID argument0, int argument1, String role, UUID submission, String expected) {
    return jdbc.sql(
            "INSERT INTO generation_execution (job_id,revision,role,submission_id,expected_verdict)"
                + " VALUES (?,?,?,?,?)")
        .param(argument0)
        .param(argument1)
        .param(role)
        .param(submission)
        .param(expected)
        .update();
  }

  public int retryInvalidArtifactGenerationJob(
      Object argument0, int argument1, String error, UUID argument3) {
    return jdbc.sql(
            "UPDATE generation_job SET status=?,revision=?,error_code=?,repair_json=NULL WHERE"
                + " id=?")
        .param(argument0)
        .param(argument1)
        .param(error)
        .param(argument3)
        .update();
  }

  public <T> List<T> failGenerationExecution(UUID argument0, int argument1, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT e.role,j.verdict FROM generation_execution e JOIN judge_job j ON"
                + " j.submission_id=e.submission_id WHERE e.job_id=? AND e.revision=? AND"
                + " j.status='FINISHED' AND j.verdict<>e.expected_verdict")
        .param(argument0)
        .param(argument1)
        .query(mapper)
        .list();
  }

  public Optional<String> failGenerationProseReview(UUID argument0, int argument1) {
    return jdbc.sql(
            "SELECT completion_json FROM generation_prose_review WHERE job_id=? AND revision=?")
        .param(argument0)
        .param(argument1)
        .query(String.class)
        .optional();
  }

  public int failGenerationJob(
      Object argument0, int argument1, String error, String argument3, UUID argument4) {
    return jdbc.sql(
            "UPDATE generation_job SET"
                + " status=?,revision=?,error_code=?,repair_json=?,review_sha256=NULL WHERE id=?")
        .param(argument0)
        .param(argument1)
        .param(error)
        .param(argument3)
        .param(argument4)
        .update();
  }

  public int advanceGenerationJob() {
    return jdbc.sql(
            "UPDATE generation_job SET status='THEME_FAILED',error_code='THEME_PREPARATION_FAILED'"
                + " WHERE status='QUEUED' AND EXISTS (SELECT 1 FROM ai_task a WHERE"
                + " a.id=theme_task_id AND a.status IN ('FAILED','UNKNOWN'))")
        .update();
  }

  public int advanceGenerationJob2() {
    return jdbc.sql(
            "UPDATE generation_job SET status='NEEDS_REVIEW',error_code='GENERATION_INTERRUPTED'"
                + " WHERE status='GENERATING' AND lease_until<CURRENT_TIMESTAMP")
        .update();
  }

  public List<UUID> advanceGenerationJob3() {
    return jdbc.sql("SELECT id FROM generation_job WHERE status='VALIDATING'")
        .query(UUID.class)
        .list();
  }

  public <T> List<T> advanceGenerationExecution(UUID id, int argument1, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT e.role,e.expected_verdict,j.status,j.verdict,j.result_json FROM"
                + " generation_execution e JOIN judge_job j ON j.submission_id=e.submission_id"
                + " WHERE e.job_id=? AND e.revision=? ORDER BY e.role")
        .param(id)
        .param(argument1)
        .query(mapper)
        .list();
  }

  public int advanceGenerationJob4(UUID id) {
    return jdbc.sql(
            "UPDATE generation_job SET"
                + " status='NEEDS_REVIEW',error_code='RUNNER_INFRASTRUCTURE_FAILURE' WHERE id=?")
        .param(id)
        .update();
  }

  public int advanceGenerationJob5(UUID id) {
    return jdbc.sql(
            "UPDATE generation_job SET status='NEEDS_REVIEW',error_code='TRUSTED_MUTANT_FAILURE'"
                + " WHERE id=?")
        .param(id)
        .update();
  }

  public String advanceGenerationJob6(UUID id) {
    return jdbc.sql("SELECT oracle_json FROM generation_job WHERE id=?")
        .param(id)
        .query(String.class)
        .single();
  }

  public int advanceProblemVersion(String json, String argument1, String ver) {
    return jdbc.sql(
            "UPDATE problem_version SET package_json=?,package_sha256=? WHERE id=? AND ready=false")
        .param(json)
        .param(argument1)
        .param(ver)
        .update();
  }

  public String advanceGenerationJob7(UUID id) {
    return jdbc.sql("SELECT review_sha256 FROM generation_job WHERE id=?")
        .param(id)
        .query(String.class)
        .single();
  }

  public String advanceProblemVersion2(String ver) {
    return jdbc.sql("SELECT package_json FROM problem_version WHERE id=?")
        .param(ver)
        .query(String.class)
        .single();
  }

  public int advanceGenerationJob8(String argument0, UUID id) {
    return jdbc.sql("UPDATE generation_job SET status='NEEDS_REVIEW',error_code=? WHERE id=?")
        .param(argument0)
        .param(id)
        .update();
  }

  public int advanceProblemVersion3(String limits, String argument1, UUID argument2, String ver) {
    return jdbc.sql(
            "UPDATE problem_version SET"
                + " ready=true,time_limits_json=?,teaching_json=?,shared=(SELECT share_on_publish"
                + " FROM generation_job WHERE id=?) WHERE id=? AND ready=false")
        .param(limits)
        .param(argument1)
        .param(argument2)
        .param(ver)
        .update();
  }

  public int advanceGenerationJob9(String argument0, UUID id) {
    return jdbc.sql(
            "UPDATE generation_job SET"
                + " status='READY',validation_json=?,updated_at=CURRENT_TIMESTAMP WHERE id=?")
        .param(argument0)
        .param(id)
        .update();
  }
}
