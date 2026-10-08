package dev.gamjaoj.account.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence operations for ContentDeletion; transaction ownership remains in the service. */
@Repository
public class ContentDeletionRepository {
  private final JdbcClient jdbc;

  public ContentDeletionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private static final String in = "SELECT id FROM hybrid_rule_version WHERE family_id=?";
  private static final String subs = "SELECT id FROM submission WHERE problem_version=?";

  public Integer problemProblemVersion(String version, UUID user) {
    return jdbc.sql(
            "SELECT count(*) FROM problem_version WHERE id=? AND owner_id=? AND"
                + " diagnostic_only=false")
        .param(version)
        .param(user)
        .query(Integer.class)
        .single();
  }

  public Integer problemJudgeJob(String version) {
    return jdbc.sql(
            "SELECT count(*) FROM judge_job j JOIN submission s ON s.id=j.submission_id WHERE"
                + " s.problem_version=? AND j.status<>'FINISHED'")
        .param(version)
        .query(Integer.class)
        .single();
  }

  public Integer problemProblemVersion2(
      String version, UUID user, UUID userArgument2, UUID userArgument3) {
    return jdbc.sql(
            """
SELECT count(*) FROM problem_version p WHERE p.id=? AND (
  EXISTS (SELECT 1 FROM submission s WHERE s.problem_version=p.id AND s.user_id<>?)
  OR EXISTS (SELECT 1 FROM training_session t WHERE t.problem_version=p.id AND t.user_id<>?)
  OR EXISTS (SELECT 1 FROM practice_followup f WHERE f.source_version=p.id AND f.user_id<>?))""")
        .param(version)
        .param(user)
        .param(userArgument2)
        .param(userArgument3)
        .query(Integer.class)
        .single();
  }

  public int problemProblemVersion3(String version) {
    return jdbc.sql("UPDATE problem_version SET shared=false WHERE id=?").param(version).update();
  }

  public int problemHybridGeneration(String version) {
    return jdbc.sql(
            "UPDATE hybrid_generation SET published_version_id=NULL WHERE published_version_id=?")
        .param(version)
        .update();
  }

  public int problemGenerationExecution(String version) {
    return jdbc.sql("DELETE FROM generation_execution WHERE submission_id IN (" + subs + ")")
        .param(version)
        .update();
  }

  public int problemGenerationSpecExecution(String version) {
    return jdbc.sql("DELETE FROM generation_spec_execution WHERE submission_id IN (" + subs + ")")
        .param(version)
        .update();
  }

  public int problemPracticeFollowup(String version) {
    return jdbc.sql("DELETE FROM practice_followup WHERE source_version=?").param(version).update();
  }

  public int problemSubmission(String version) {
    return jdbc.sql("DELETE FROM submission WHERE problem_version=?").param(version).update();
  }

  public int problemTrainingSession(String version) {
    return jdbc.sql("DELETE FROM training_session WHERE problem_version=?").param(version).update();
  }

  public int problemCodeDraft(String argument0) {
    return jdbc.sql("DELETE FROM code_draft WHERE scope=?").param(argument0).update();
  }

  public int problemProblemVersion4(String version) {
    return jdbc.sql("DELETE FROM problem_version WHERE id=?").param(version).update();
  }

  public Optional<String> ruleHybridRuleFamily(String versionId, UUID user) {
    return jdbc.sql(
            "SELECT f.id FROM hybrid_rule_family f JOIN hybrid_rule_version v ON v.family_id=f.id"
                + " WHERE v.id=? AND f.owner_id=? FOR UPDATE")
        .param(versionId)
        .param(user)
        .query(String.class)
        .optional();
  }

  public List<String> ruleHybridRuleVersion(String family) {
    return jdbc.sql("SELECT id FROM hybrid_rule_version WHERE family_id=?")
        .param(family)
        .query(String.class)
        .list();
  }

  public Integer ruleHybridGeneration(String family) {
    return jdbc.sql(
            "SELECT count(*) FROM hybrid_generation g JOIN hybrid_public_request r ON"
                + " r.generation_id=g.id WHERE r.rule_version_id IN ("
                + in
                + ") AND g.status IN ('QUEUED','DESIGNING','BUILDING','VALIDATING','REVIEWING') AND"
                + " g.deadline_at>CURRENT_TIMESTAMP")
        .param(family)
        .query(Integer.class)
        .single();
  }

  public Integer ruleHybridGeneration2(String family, UUID user) {
    return jdbc.sql(
            "SELECT count(*) FROM hybrid_generation g JOIN hybrid_public_request r ON"
                + " r.generation_id=g.id WHERE r.rule_version_id IN ("
                + in
                + ") AND g.owner_id<>?")
        .param(family)
        .param(user)
        .query(Integer.class)
        .single();
  }

  public int ruleHybridRuleOnboarding(UUID user, String v) {
    return jdbc.sql("DELETE FROM hybrid_rule_onboarding WHERE owner_id=? AND version_id=?")
        .param(user)
        .param(v)
        .update();
  }

  public int ruleHybridRuleArtifact(String family) {
    return jdbc.sql(
            "UPDATE hybrid_rule_artifact SET source_onboarding_id=NULL WHERE rule_version_id IN ("
                + in
                + ")")
        .param(family)
        .update();
  }

  public int ruleHybridRuleVersion2(String family) {
    return jdbc.sql(
            "UPDATE hybrid_rule_version SET"
                + " status='RETIRED',status_reason='OWNER_DELETED',updated_at=CURRENT_TIMESTAMP"
                + " WHERE family_id=?")
        .param(family)
        .update();
  }

  public int ruleHybridRuleFamily2(UUID argument0, String family) {
    return jdbc.sql("UPDATE hybrid_rule_family SET owner_id=?,shared=false WHERE id=?")
        .param(argument0)
        .param(family)
        .update();
  }

  public int ruleHybridPublicRequest(String family) {
    return jdbc.sql(
            "UPDATE hybrid_public_request SET rule_version_id=NULL,reference_artifact_id=NULL WHERE"
                + " rule_version_id IN ("
                + in
                + ")")
        .param(family)
        .update();
  }

  public int ruleHybridRuleArtifact2(String family) {
    return jdbc.sql("DELETE FROM hybrid_rule_artifact WHERE rule_version_id IN (" + in + ")")
        .param(family)
        .update();
  }

  public int ruleHybridRuleVersion3(String family) {
    return jdbc.sql("DELETE FROM hybrid_rule_version WHERE family_id=?").param(family).update();
  }

  public int ruleHybridRuleFamily3(String family) {
    return jdbc.sql("DELETE FROM hybrid_rule_family WHERE id=?").param(family).update();
  }
}
