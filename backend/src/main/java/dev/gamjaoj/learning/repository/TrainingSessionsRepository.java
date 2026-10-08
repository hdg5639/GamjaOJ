package dev.gamjaoj.learning.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence operations for TrainingSessions; transaction ownership remains in the service. */
@Repository
public class TrainingSessionsRepository {
  private final JdbcClient jdbc;

  public TrainingSessionsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<UUID> startTrainingSession(UUID id, UUID user) {
    return jdbc.sql("SELECT id FROM training_session WHERE id=? AND user_id=?")
        .param(id)
        .param(user)
        .query(UUID.class)
        .optional();
  }

  public Integer startTrainingSession2(UUID id) {
    return jdbc.sql("SELECT count(*) FROM training_session WHERE id=?")
        .param(id)
        .query(Integer.class)
        .single();
  }

  public Integer startTrainingSession3(UUID user) {
    return jdbc.sql("SELECT count(*) FROM training_session WHERE user_id=? AND status='ACTIVE'")
        .param(user)
        .query(Integer.class)
        .single();
  }

  public Integer startProblemVersion(String argument0, UUID user) {
    return jdbc.sql(
            "SELECT count(*) FROM problem_version WHERE id=? AND ready=true AND"
                + " diagnostic_only=false AND review_hold=false AND (owner_id IS NULL OR owner_id=?"
                + " OR shared=true)")
        .param(argument0)
        .param(user)
        .query(Integer.class)
        .single();
  }

  public int startTrainingSession4(
      UUID id, UUID user, String argument2, String argument3, UUID userArgument4) {
    return jdbc.sql(
            "INSERT INTO training_session (id,user_id,problem_version,goal,active_owner) VALUES"
                + " (?,?,?,?,?)")
        .param(id)
        .param(user)
        .param(argument2)
        .param(argument3)
        .param(userArgument4)
        .update();
  }

  public int endTrainingSession(String note, UUID id, UUID user) {
    return jdbc.sql(
            "UPDATE training_session SET"
                + " status='ENDED',note=?,active_owner=NULL,ended_at=CURRENT_TIMESTAMP WHERE id=?"
                + " AND user_id=?")
        .param(note)
        .param(id)
        .param(user)
        .update();
  }

  public Stream<UUID> historyTrainingSession(UUID user) {
    return jdbc
        .sql(
            "SELECT id FROM training_session WHERE user_id=? ORDER BY started_at DESC,id DESC LIMIT"
                + " 20")
        .param(user)
        .query(UUID.class)
        .list()
        .stream();
  }

  public List<dev.gamjaoj.learning.dto.TrainingSessionDtos.Entry> detailSubmission(
      UUID id, UUID user) {
    return jdbc.sql(
            "SELECT s.id,CASE WHEN s.run_input IS NULL THEN 'SUBMISSION' ELSE 'RUN' END AS"
                + " kind,j.status,j.verdict,s.created_at FROM submission s JOIN judge_job j ON"
                + " j.submission_id=s.id WHERE s.training_session_id=? AND s.user_id=? AND"
                + " s.run_input IS NULL ORDER BY s.created_at DESC,s.id DESC LIMIT 50")
        .param(id)
        .param(user)
        .query(dev.gamjaoj.learning.dto.TrainingSessionDtos.Entry.class)
        .list();
  }

  public <T> Optional<T> findSubmission(UUID id, UUID user, RowMapper<T> mapper) {
    return jdbc.sql(
            "SELECT t.*,p.review_hold, (SELECT count(*) FROM submission s WHERE"
                + " s.training_session_id=t.id AND s.run_input IS NULL) AS submissions, (SELECT"
                + " count(*) FROM submission s JOIN judge_job j ON j.submission_id=s.id WHERE"
                + " s.training_session_id=t.id AND s.run_input IS NULL AND j.verdict='AC') AS"
                + " accepted, (SELECT count(*) FROM submission s WHERE s.training_session_id=t.id"
                + " AND s.run_input IS NOT NULL) AS runs, (SELECT count(*) FROM submission s JOIN"
                + " judge_job j ON j.submission_id=s.id WHERE s.training_session_id=t.id AND"
                + " j.status<>'FINISHED') AS pending FROM training_session t JOIN problem_version p"
                + " ON p.id=t.problem_version WHERE t.id=? AND t.user_id=?")
        .param(id)
        .param(user)
        .query(mapper)
        .optional();
  }
}
