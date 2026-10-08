package dev.gamjaoj.repository.learning;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence operations for PracticeFollowups; transaction ownership remains in the service. */
@Repository
public class PracticeFollowupsRepository {
  private final JdbcClient jdbc;

  public PracticeFollowupsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<String> templateHybridGeneration(String version) {
    return jdbc.sql(
            "SELECT r.rule_version_id FROM hybrid_generation g JOIN hybrid_public_request r ON"
                + " r.generation_id=g.id WHERE g.published_version_id=? AND g.status='PUBLISHED'"
                + " AND r.rule_version_id IS NOT NULL")
        .param(version)
        .query(String.class)
        .optional();
  }

  public Optional<String> templateGenerationJob(String version) {
    return jdbc.sql(
            "SELECT template_id FROM generation_job WHERE status='READY' AND"
                + " ?=CONCAT(CONCAT(CONCAT('generated-',CAST(id AS"
                + " VARCHAR(36))),'-r'),CAST(revision AS VARCHAR(10)))")
        .param(version)
        .query(String.class)
        .optional();
  }

  public <T> Optional<T> sourceAiTask(
      UUID analysis, UUID owner, UUID ownerArgument2, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT s.problem_version,a.result_json,p.review_hold FROM ai_task a JOIN submission s"
                + " ON s.id=a.submission_id JOIN judge_job j ON j.submission_id=s.id JOIN"
                + " problem_version p ON p.id=s.problem_version WHERE a.id=? AND a.user_id=? AND"
                + " s.user_id=? AND a.kind='ANALYSIS' AND a.status='COMPLETED' AND s.run_input IS"
                + " NULL AND j.status='FINISHED' AND j.verdict<>'IE'")
        .param(analysis)
        .param(owner)
        .param(ownerArgument2)
        .query(mapper)
        .optional();
  }

  public Optional<UUID> confirmPracticeFollowup(UUID owner, UUID analysis, int step, String focus) {
    return jdbc.sql(
            "SELECT id FROM practice_followup WHERE user_id=? AND analysis_id=? AND step_index=?"
                + " AND focus=?")
        .param(owner)
        .param(analysis)
        .param(step)
        .param(focus)
        .query(UUID.class)
        .optional();
  }

  public int confirmPracticeFollowup2(
      UUID id,
      UUID owner,
      UUID analysis,
      int step,
      String argument4,
      String focus,
      String argument6,
      String argument7,
      UUID idArgument8) {
    return jdbc.sql(
            "INSERT INTO practice_followup"
                + " (id,user_id,analysis_id,step_index,goal,focus,template_id,source_version,round_id)"
                + " VALUES (?,?,?,?,?,?,?,?,?)")
        .param(id)
        .param(owner)
        .param(analysis)
        .param(step)
        .param(argument4)
        .param(focus)
        .param(argument6)
        .param(argument7)
        .param(idArgument8)
        .update();
  }

  public Stream<UUID> listPracticeFollowup(UUID owner) {
    return jdbc
        .sql(
            "SELECT id FROM practice_followup WHERE user_id=? ORDER BY created_at DESC,id LIMIT 30")
        .param(owner)
        .query(UUID.class)
        .list()
        .stream();
  }

  public <T> Stream<T> candidatesProblemVersion(
      UUID owner, String origin, UUID ownerArgument2, RowMapper<T> mapper) {
    return jdbc
        .sql(
            "SELECT id,package_json FROM problem_version p WHERE ready=true AND"
                + " diagnostic_only=false AND review_hold=false AND (owner_id IS NULL OR owner_id=?"
                + " OR shared=true) AND id<>? AND NOT EXISTS (SELECT 1 FROM submission s JOIN"
                + " judge_job j ON j.submission_id=s.id WHERE s.user_id=? AND"
                + " s.problem_version=p.id AND s.run_input IS NULL AND j.verdict='AC') ORDER BY id")
        .param(owner)
        .param(origin)
        .param(ownerArgument2)
        .query(mapper)
        .list()
        .stream();
  }

  public Integer candidatesPracticeFollowupAttempt(UUID id, String argument1) {
    return jdbc.sql(
            "SELECT count(*) FROM practice_followup_attempt WHERE followup_id=? AND"
                + " ?=CONCAT('experimental-check-',CAST(generation_id AS VARCHAR(36)))")
        .param(id)
        .param(argument1)
        .query(Integer.class)
        .single();
  }

  public Optional<String> candidatesGenerationJob(String argument0) {
    return jdbc.sql(
            "SELECT focus FROM generation_job WHERE status='READY' AND"
                + " ?=CONCAT(CONCAT(CONCAT('generated-',CAST(id AS"
                + " VARCHAR(36))),'-r'),CAST(revision AS VARCHAR(10)))")
        .param(argument0)
        .query(String.class)
        .optional();
  }

  public <T> Optional<T> viewPracticeFollowup(UUID id, UUID owner, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT f.*,p.review_hold,t.problem_version AS target_version,t.status AS"
                + " session_status,tp.review_hold AS target_held FROM practice_followup f JOIN"
                + " problem_version p ON p.id=f.source_version LEFT JOIN training_session t ON"
                + " t.id=f.session_id LEFT JOIN problem_version tp ON tp.id=t.problem_version WHERE"
                + " f.id=? AND f.user_id=?")
        .param(id)
        .param(owner)
        .query(mapper)
        .optional();
  }

  public Integer viewSubmission(UUID argument0) {
    return jdbc.sql(
            "SELECT count(*) FROM submission s JOIN judge_job j ON j.submission_id=s.id WHERE"
                + " s.training_session_id=? AND j.status<>'FINISHED'")
        .param(argument0)
        .query(Integer.class)
        .single();
  }

  public Optional<String> viewHybridGeneration(UUID argument0) {
    return jdbc.sql("SELECT status FROM hybrid_generation WHERE id=?")
        .param(argument0)
        .query(String.class)
        .optional();
  }

  public Optional<String> viewGenerationSpecDraft(UUID argument0) {
    return jdbc.sql("SELECT status FROM generation_spec_draft WHERE id=?")
        .param(argument0)
        .query(String.class)
        .optional();
  }

  public Optional<String> viewGenerationSpecDraft2(boolean missingContract, UUID argument1) {
    return jdbc.sql(
            missingContract
                ? "SELECT status FROM generation_spec_draft WHERE id=?"
                : "SELECT status FROM generation_job WHERE id=?")
        .param(argument1)
        .query(String.class)
        .optional();
  }

  public <T> List<T> attemptsPracticeFollowupAttempt(UUID id, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT a.*,t.problem_version,p.review_hold FROM practice_followup_attempt a JOIN"
                + " training_session t ON t.id=a.session_id JOIN problem_version p ON"
                + " p.id=t.problem_version WHERE a.followup_id=? ORDER BY a.round_number DESC")
        .param(id)
        .query(mapper)
        .list();
  }

  public UUID roundIdPracticeFollowup(UUID id) {
    return jdbc.sql("SELECT round_id FROM practice_followup WHERE id=?")
        .param(id)
        .query(UUID.class)
        .single();
  }

  public int repeatPracticeFollowupAttempt(UUID id) {
    return jdbc.sql(
            "INSERT INTO practice_followup_attempt"
                + " (followup_id,round_number,session_id,generation_id,reviewed_submission_id,used_help,reviewed_at)"
                + " SELECT id,round_number,session_id,CASE WHEN generation_requested THEN round_id"
                + " ELSE NULL END,reviewed_submission_id,used_help,reviewed_at FROM"
                + " practice_followup WHERE id=?")
        .param(id)
        .update();
  }

  public int repeatPracticeFollowup(UUID argument0, UUID id) {
    return jdbc.sql(
            "UPDATE practice_followup SET"
                + " round_number=round_number+1,round_id=?,session_id=NULL,generation_requested=false,reviewed_submission_id=NULL,used_help=NULL,reviewed_at=NULL"
                + " WHERE id=?")
        .param(argument0)
        .param(id)
        .update();
  }

  public <T> Optional<T> latestAcSubmission(UUID session, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT s.id,j.verdict,j.status FROM submission s JOIN judge_job j ON"
                + " j.submission_id=s.id WHERE s.training_session_id=? AND s.run_input IS NULL"
                + " ORDER BY s.created_at DESC,s.id DESC LIMIT 1")
        .param(session)
        .query(mapper)
        .optional();
  }

  public int startPracticeFollowup(UUID sessionId, UUID id) {
    return jdbc.sql("UPDATE practice_followup SET session_id=? WHERE id=?")
        .param(sessionId)
        .param(id)
        .update();
  }

  public Optional<String> generatePracticeFollowup(UUID id) {
    return jdbc.sql("SELECT template_id FROM practice_followup WHERE id=?")
        .param(id)
        .query(String.class)
        .optional();
  }

  public int generatePracticeFollowup2(UUID id) {
    return jdbc.sql("UPDATE practice_followup SET generation_requested=true WHERE id=?")
        .param(id)
        .update();
  }

  public int generateGenerationJob(String argument0, UUID generationId) {
    return jdbc.sql("UPDATE generation_job SET learning_context_json=? WHERE id=?")
        .param(argument0)
        .param(generationId)
        .update();
  }

  public String generatePracticeFollowup3(UUID id) {
    return jdbc.sql(
            "SELECT p.package_json FROM practice_followup f JOIN problem_version p ON"
                + " p.id=f.source_version WHERE f.id=?")
        .param(id)
        .query(String.class)
        .single();
  }

  public int generatePracticeFollowup4(UUID id) {
    return jdbc.sql("UPDATE practice_followup SET generation_requested=true WHERE id=?")
        .param(id)
        .update();
  }

  public int reflectPracticeFollowup(UUID argument0, boolean usedHelp, UUID id) {
    return jdbc.sql(
            "UPDATE practice_followup SET"
                + " reviewed_submission_id=?,used_help=?,reviewed_at=CURRENT_TIMESTAMP WHERE id=?")
        .param(argument0)
        .param(usedHelp)
        .param(id)
        .update();
  }
}
