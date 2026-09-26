package dev.gamjaoj;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrainingSessions {
    private final JdbcClient jdbc;
    private final Submissions submissions;
    public TrainingSessions(JdbcClient jdbc, Submissions submissions) { this.jdbc=jdbc; this.submissions=submissions; }
    public record View(UUID id, String problemVersion, String goal, String note, String status,
                       OffsetDateTime startedAt, OffsetDateTime endedAt, int submissions, int accepted, int runs, int pending, boolean problemHeld) {}
    public record Entry(UUID id, String kind, String status, String verdict, OffsetDateTime createdAt) {}
    public record Detail(View session, List<Entry> entries) {}

    @Transactional
    public View start(String username, UUID id, TrainingSessionController.Start request) {
        UUID user = submissions.owner(username,true);
        var previous = jdbc.sql("SELECT id FROM training_session WHERE id=? AND user_id=?").param(id).param(user).query(UUID.class).optional();
        if (previous.isPresent()) {
            View saved = find(user,id);
            if (!saved.problemVersion().equals(request.problemVersion()) || !saved.goal().equals(request.goal()))
                throw new AccountException(409,"같은 요청 키로 다른 훈련을 시작할 수 없어요.");
            return saved;
        }
        if (jdbc.sql("SELECT count(*) FROM training_session WHERE id=?").param(id).query(Integer.class).single()>0)
            throw new AccountException(409,"새 요청 키로 다시 시작해 주세요.");
        if (jdbc.sql("SELECT count(*) FROM training_session WHERE user_id=? AND status='ACTIVE'").param(user).query(Integer.class).single()>0)
            throw new AccountException(409,"진행 중인 훈련을 먼저 마쳐 주세요.");
        if (jdbc.sql("SELECT count(*) FROM problem_version WHERE id=? AND ready=true AND diagnostic_only=false AND review_hold=false AND (owner_id IS NULL OR owner_id=? OR shared=true)").param(request.problemVersion()).param(user).query(Integer.class).single()==0)
            throw new AccountException(404,"훈련할 수 있는 문제 버전이 아니에요.");
        jdbc.sql("INSERT INTO training_session (id,user_id,problem_version,goal,active_owner) VALUES (?,?,?,?,?)")
                .param(id).param(user).param(request.problemVersion()).param(request.goal()).param(user).update();
        return find(user,id);
    }
    @Transactional
    public View end(String username, UUID id, String note) {
        UUID user = submissions.owner(username,true);
        View saved = find(user,id);
        if (saved.status().equals("ENDED")) {
            if (!saved.note().equals(note)) throw new AccountException(409,"이미 저장한 마무리 메모와 달라요. 기록을 다시 확인해 주세요.");
            return saved;
        }
        jdbc.sql("UPDATE training_session SET status='ENDED',note=?,active_owner=NULL,ended_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=?")
                .param(note).param(id).param(user).update();
        return find(user,id);
    }
    public List<View> history(String username) {
        UUID user = submissions.owner(username,false);
        return jdbc.sql("SELECT id FROM training_session WHERE user_id=? ORDER BY started_at DESC,id DESC LIMIT 20")
                .param(user).query(UUID.class).list().stream().map(id -> find(user,id)).toList();
    }
    public Detail detail(String username, UUID id) {
        UUID user = submissions.owner(username,false);
        View view=find(user,id);
        var entries=jdbc.sql("SELECT s.id,CASE WHEN s.run_input IS NULL THEN 'SUBMISSION' ELSE 'RUN' END AS kind,j.status,j.verdict,s.created_at FROM submission s JOIN judge_job j ON j.submission_id=s.id WHERE s.training_session_id=? AND s.user_id=? AND s.run_input IS NULL ORDER BY s.created_at DESC,s.id DESC LIMIT 50")
                .param(id).param(user).query(Entry.class).list();
        return new Detail(view,entries);
    }
    private View find(UUID user, UUID id) {
        return jdbc.sql("SELECT t.*,p.review_hold, (SELECT count(*) FROM submission s WHERE s.training_session_id=t.id AND s.run_input IS NULL) AS submissions, (SELECT count(*) FROM submission s JOIN judge_job j ON j.submission_id=s.id WHERE s.training_session_id=t.id AND s.run_input IS NULL AND j.verdict='AC') AS accepted, (SELECT count(*) FROM submission s WHERE s.training_session_id=t.id AND s.run_input IS NOT NULL) AS runs, (SELECT count(*) FROM submission s JOIN judge_job j ON j.submission_id=s.id WHERE s.training_session_id=t.id AND j.status<>'FINISHED') AS pending FROM training_session t JOIN problem_version p ON p.id=t.problem_version WHERE t.id=? AND t.user_id=?")
                .param(id).param(user).query((r,n)->new View(id,r.getString("problem_version"),r.getString("goal"),r.getString("note"),r.getString("status"),r.getObject("started_at",OffsetDateTime.class),r.getObject("ended_at",OffsetDateTime.class),r.getInt("submissions"),r.getInt("accepted"),r.getInt("runs"),r.getInt("pending"),r.getBoolean("review_hold")))
                .optional().orElseThrow(()->new AccountException(404,"훈련 기록을 찾을 수 없어요."));
    }
}
