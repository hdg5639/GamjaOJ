package dev.gamjaoj.account.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence operations for AccountDeletion; transaction ownership remains in the service. */
@Repository
public class AccountDeletionRepository {
  private final JdbcClient jdbc;

  public AccountDeletionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private static final String jobs = "SELECT id FROM generation_job WHERE owner_id=?";
  private static final String specs = "SELECT id FROM generation_spec_draft WHERE owner_id=?";
  private static final String mine = "SELECT id FROM submission WHERE user_id=?";

  public <T> Optional<T> deleteAppUser(String username, RowMapper<T> mapper) {
    return jdbc.sql("SELECT id,password_hash FROM app_user WHERE username=? FOR UPDATE")
        .param(username)
        .query(mapper)
        .optional();
  }

  public List<String> deleteProblemVersion(
      UUID user, UUID userArgument1, UUID userArgument2, UUID userArgument3) {
    return jdbc.sql(
            """
SELECT p.id FROM problem_version p WHERE p.owner_id=? AND p.diagnostic_only=false AND (p.shared=true
  OR EXISTS (SELECT 1 FROM submission s WHERE s.problem_version=p.id AND s.user_id<>?)
  OR EXISTS (SELECT 1 FROM training_session t WHERE t.problem_version=p.id AND t.user_id<>?)
  OR EXISTS (SELECT 1 FROM practice_followup f WHERE f.source_version=p.id AND f.user_id<>?))""")
        .param(user)
        .param(userArgument1)
        .param(userArgument2)
        .param(userArgument3)
        .query(String.class)
        .list();
  }

  public List<String> deleteHybridRuleFamily(UUID user, UUID userArgument1) {
    return jdbc.sql(
            """
SELECT f.id FROM hybrid_rule_family f WHERE f.owner_id=? AND (f.shared=true OR EXISTS (
  SELECT 1 FROM hybrid_rule_version v JOIN hybrid_public_request r ON r.rule_version_id=v.id JOIN hybrid_generation g ON g.id=r.generation_id
  WHERE v.family_id=f.id AND g.owner_id<>?))""")
        .param(user)
        .param(userArgument1)
        .query(String.class)
        .list();
  }

  public int deleteHybridRuleArtifact(String family) {
    return jdbc.sql(
            "UPDATE hybrid_rule_artifact SET source_onboarding_id=NULL,source_generation_id=NULL"
                + " WHERE rule_version_id IN (SELECT id FROM hybrid_rule_version WHERE"
                + " family_id=?)")
        .param(family)
        .update();
  }

  public int deleteHybridRuleFamily2(UUID archive, String family) {
    return jdbc.sql("UPDATE hybrid_rule_family SET owner_id=? WHERE id=?")
        .param(archive)
        .param(family)
        .update();
  }

  public int deleteHybridRuleArtifact2(UUID user, UUID userArgument1) {
    return jdbc.sql(
            """
UPDATE hybrid_rule_artifact SET source_generation_id=NULL WHERE source_generation_id IN (SELECT id FROM hybrid_generation WHERE owner_id=?)
  AND rule_version_id NOT IN (SELECT v.id FROM hybrid_rule_version v JOIN hybrid_rule_family f ON f.id=v.family_id WHERE f.owner_id=?)""")
        .param(user)
        .param(userArgument1)
        .update();
  }

  public int deleteHybridRuleArtifact3(UUID user, UUID userArgument1) {
    return jdbc.sql(
            """
UPDATE hybrid_rule_artifact SET source_onboarding_id=NULL WHERE source_onboarding_id IN (SELECT id FROM hybrid_rule_onboarding WHERE owner_id=?)
  AND rule_version_id NOT IN (SELECT v.id FROM hybrid_rule_version v JOIN hybrid_rule_family f ON f.id=v.family_id WHERE f.owner_id=?)""")
        .param(user)
        .param(userArgument1)
        .update();
  }

  public int deleteGenerationExecution(UUID user, UUID userArgument1) {
    return jdbc.sql(
            "DELETE FROM generation_execution WHERE job_id IN ("
                + jobs
                + ") OR submission_id IN ("
                + mine
                + ")")
        .param(user)
        .param(userArgument1)
        .update();
  }

  public int deleteGenerationAttempt(UUID user) {
    return jdbc.sql("DELETE FROM generation_attempt WHERE job_id IN (" + jobs + ")")
        .param(user)
        .update();
  }

  public int deleteGenerationSpecExecution(UUID user, UUID userArgument1) {
    return jdbc.sql(
            "DELETE FROM generation_spec_execution WHERE draft_id IN ("
                + specs
                + ") OR submission_id IN ("
                + mine
                + ")")
        .param(user)
        .param(userArgument1)
        .update();
  }

  public int deleteDiagnosticPracticePlan(UUID user) {
    return jdbc.sql("DELETE FROM diagnostic_practice_plan WHERE user_id=?").param(user).update();
  }

  public int deletePracticeFollowup(UUID user) {
    return jdbc.sql("DELETE FROM practice_followup WHERE user_id=?").param(user).update();
  }

  public int deleteSubmission(UUID user) {
    return jdbc.sql("DELETE FROM submission WHERE user_id=?").param(user).update();
  }

  public int deleteTrainingSession(UUID user) {
    return jdbc.sql("DELETE FROM training_session WHERE user_id=?").param(user).update();
  }

  public int deleteHybridGeneration(UUID user) {
    return jdbc.sql("DELETE FROM hybrid_generation WHERE owner_id=?").param(user).update();
  }

  public int deleteGenerationJob(UUID user) {
    return jdbc.sql("DELETE FROM generation_job WHERE owner_id=?").param(user).update();
  }

  public int deleteGenerationSpecDraft(UUID user) {
    return jdbc.sql("DELETE FROM generation_spec_draft WHERE owner_id=?").param(user).update();
  }

  public int deleteProblemVersion2(UUID user) {
    return jdbc.sql("DELETE FROM problem_version WHERE owner_id=?").param(user).update();
  }

  public int deleteSpringSession(String username) {
    return jdbc.sql("DELETE FROM spring_session WHERE principal_name=?").param(username).update();
  }

  public int deleteAppUser2(UUID user) {
    return jdbc.sql("DELETE FROM app_user WHERE id=?").param(user).update();
  }

  public Integer activeJudgeJob(UUID user) {
    return jdbc.sql(
            """
SELECT count(*) FROM judge_job j JOIN submission s ON s.id=j.submission_id WHERE s.user_id=? AND j.status<>'FINISHED'
  AND (s.hybrid_branch_id IS NULL OR EXISTS (SELECT 1 FROM hybrid_branch b JOIN hybrid_generation g ON g.id=b.generation_id
    WHERE b.id=s.hybrid_branch_id AND g.deadline_at>CURRENT_TIMESTAMP))""")
        .param(user)
        .query(Integer.class)
        .single();
  }

  public Integer activeGenerationJob(UUID user) {
    return jdbc.sql(
            "SELECT count(*) FROM generation_job WHERE owner_id=? AND status IN"
                + " ('QUEUED','GENERATING','AWAITING_REVIEW','VALIDATING')")
        .param(user)
        .query(Integer.class)
        .single();
  }

  public Integer activeHybridRuleOnboarding(UUID user) {
    return jdbc.sql(
            "SELECT count(*) FROM hybrid_rule_onboarding WHERE owner_id=? AND status IN"
                + " ('QUEUED','AUTHORING','AUTHORED','ORACLE','QUALIFYING') AND"
                + " deadline_at>CURRENT_TIMESTAMP")
        .param(user)
        .query(Integer.class)
        .single();
  }

  public Integer activeAiTask(UUID user) {
    return jdbc.sql(
            "SELECT count(*) FROM ai_task WHERE user_id=? AND status IN ('QUEUED','RUNNING')")
        .param(user)
        .query(Integer.class)
        .single();
  }
}
