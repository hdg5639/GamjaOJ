package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Submissions {
    private final JdbcClient jdbc;
    private final boolean enabled;
    public Submissions(JdbcClient jdbc, @Value("${gamjaoj.submissions-enabled:false}") boolean enabled) {
        this.jdbc = jdbc; this.enabled = enabled;
    }
    public record Problem(String version, String title, String statement, String sampleInput,
                          String sampleOutput, int sourceLimitBytes, boolean submissionsEnabled) {}
    public record View(UUID id, String problemVersion, String sourceSha256, String source,
                       String status, String verdict, String compileMessage, OffsetDateTime createdAt,
                       OffsetDateTime finishedAt, String input, String stdout, String stderr, boolean outputTruncated, UUID sessionId, String runnerPolicy) {}

    UUID owner(String username, boolean lock) {
        return jdbc.sql("SELECT id FROM app_user WHERE username = ?" + (lock ? " FOR UPDATE" : ""))
                .param(username).query(UUID.class).optional()
                .orElseThrow(() -> new AccountException(401, "다시 로그인해 주세요."));
    }

    public List<Problem> problems(String username) {
        UUID owner = owner(username, false);
        boolean canSubmit = enabled || jdbc.sql("SELECT count(*) FROM execution_grant WHERE user_id = ?")
                .param(owner).query(Integer.class).single() > 0;
        return jdbc.sql("SELECT id, package_json FROM problem_version WHERE ready = true ORDER BY id")
                .query((row, index) -> {
                    JsonNode data = JudgeJson.parse(row.getString("package_json"));
                    // Explicit public fields only: never serialize a private problem package.
                    return new Problem(row.getString("id"), data.path("title").asText(), data.path("statement").asText(),
                            data.path("tests").get(0).path("input").asText(), data.path("tests").get(0).path("output").asText(),
                            65536, canSubmit);
                }).list();
    }

    @Transactional
    public View submit(String username, UUID key, SubmissionController.Request request) {
        return save(username, key, request, null);
    }

    @Transactional
    public View run(String username, UUID key, RunController.Request request) {
        if (request.input().getBytes(StandardCharsets.UTF_8).length > 16384)
            throw new AccountException(400, "입력은 UTF-8 기준 16 KiB 이내로 작성해 주세요.");
        return save(username, key, new SubmissionController.Request(request.problemVersion(), request.source(), request.sessionId()), request.input());
    }

    private View save(String username, UUID key, SubmissionController.Request request, String input) {
        if (request.source().getBytes(StandardCharsets.UTF_8).length > 65536)
            throw new AccountException(400, "코드는 UTF-8 기준 64 KiB 이내로 제출해 주세요.");
        String hash = JudgeJson.hash(request.source());
        UUID user = owner(username, true); // Serialize admission for this user's idempotency and pending cap.
        var existing = jdbc.sql("SELECT id FROM submission WHERE user_id = ? AND idempotency_key = ?")
                .param(user).param(key).query(UUID.class).optional();
        if (existing.isPresent()) {
            View view = find(user, existing.get(), true);
            if (!view.sourceSha256().equals(hash) || !view.problemVersion().equals(request.problemVersion())
                    || !java.util.Objects.equals(view.input(), input) || !java.util.Objects.equals(view.sessionId(), request.sessionId()))
                throw new AccountException(409, "같은 요청 키에 다른 코드가 들어왔어요. 새 제출로 보내 주세요.");
            return view;
        }
        if (!enabled && jdbc.sql("SELECT count(*) FROM execution_grant WHERE user_id = ? AND source_sha256 = ?")
                .param(user).param(hash).query(Integer.class).single() == 0)
            throw new AccountException(503, "코드 채점을 준비하고 있어요. 잠시 후 다시 확인해 주세요.");
        if (jdbc.sql("SELECT count(*) FROM problem_version WHERE id = ? AND ready = true")
                .param(request.problemVersion()).query(Integer.class).single() == 0)
            throw new AccountException(404, "제출할 수 있는 문제 버전이 아니에요.");
        if (jdbc.sql("SELECT count(*) FROM submission s JOIN judge_job j ON s.id=j.submission_id WHERE s.user_id=? AND j.status <> 'FINISHED'")
                .param(user).query(Integer.class).single() >= 3)
            throw new AccountException(429, "진행 중인 채점이 끝나면 다시 제출해 주세요.");
        if (request.sessionId() != null) {
            var session = jdbc.sql("SELECT problem_version,status FROM training_session WHERE id=? AND user_id=?")
                    .param(request.sessionId()).param(user).query((r,n)->new String[]{r.getString(1),r.getString(2)}).optional()
                    .orElseThrow(()->new AccountException(404,"훈련 기록을 찾을 수 없어요."));
            if (!session[0].equals(request.problemVersion()) || !session[1].equals("ACTIVE"))
                throw new AccountException(409,"진행 중인 훈련의 문제를 확인해 주세요. 종료된 훈련에는 새 작업을 추가할 수 없어요.");
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO submission (id,user_id,problem_version,source_code,source_sha256,idempotency_key,runtime_image,runner_policy) SELECT ?,?,?,?,?,?,runtime_image,CASE WHEN ? THEN 'java8-run-v1' ELSE runner_policy END FROM problem_version WHERE id=?")
                .param(id).param(user).param(request.problemVersion()).param(request.source()).param(hash).param(key).param(input != null).param(request.problemVersion()).update();
        if (request.sessionId() != null) jdbc.sql("UPDATE submission SET training_session_id=? WHERE id=?")
                .param(request.sessionId()).param(id).update();
        if (input != null) {
            var plan = JudgeJson.JSON.createObjectNode().put("version", request.problemVersion()).put("output_policy", "RUN_ONLY");
            plan.putArray("tests").addObject().put("id", "custom-input").put("input", input).put("output", "");
            String json = JudgeJson.canonical(plan);
            jdbc.sql("UPDATE submission SET run_input=?,run_package=?,run_package_sha256=? WHERE id=?")
                    .param(input).param(json).param(JudgeJson.hash(json)).param(id).update();
        }
        jdbc.sql("INSERT INTO judge_job (submission_id) VALUES (?)").param(id).update();
        return find(user, id, true);
    }

    public List<View> history(String username) { return history(username, false); }
    public List<View> runs(String username) { return history(username, true); }
    private List<View> history(String username, boolean run) {
        UUID user = owner(username, false);
        return jdbc.sql("SELECT id FROM submission WHERE user_id = ? AND run_input IS " + (run ? "NOT NULL" : "NULL") + " ORDER BY created_at DESC, id DESC LIMIT 50")
                .param(user).query(UUID.class).list().stream().map(id -> find(user, id, false)).toList();
    }
    public View detail(String username, UUID id) { return detail(username, id, false); }
    public View runDetail(String username, UUID id) { return detail(username, id, true); }
    private View detail(String username, UUID id, boolean run) {
        View view = find(owner(username, false), id, true);
        if ((view.input() != null) != run) throw new AccountException(404, "기록을 찾을 수 없어요.");
        return view;
    }
    private View find(UUID user, UUID id, boolean includeSource) {
        return jdbc.sql("SELECT s.*,j.status,j.verdict,j.result_json,j.finished_at FROM submission s JOIN judge_job j ON s.id=j.submission_id WHERE s.id=? AND s.user_id=?")
                .param(id).param(user).query((row, index) -> {
                    String verdict = row.getString("verdict"), result = row.getString("result_json");
                    String compile = "CE".equals(verdict) && result != null
                            ? JudgeJson.parse(result).path("compile").path("stderr").asText("") : "";
                    String input = row.getString("run_input");
                    JsonNode output = includeSource && input != null && result != null
                            ? JudgeJson.parse(result).path("tests").path(0) : JudgeJson.JSON.createObjectNode();
                    return new View(id, row.getString("problem_version"), row.getString("source_sha256"),
                            includeSource ? row.getString("source_code") : null, row.getString("status"), verdict, compile,
                            row.getObject("created_at", OffsetDateTime.class), row.getObject("finished_at", OffsetDateTime.class), input,
                            output.path("stdout").asText(""), output.path("stderr").asText(""), output.path("stdout_truncated").asBoolean(), row.getObject("training_session_id", UUID.class), row.getString("runner_policy"));
                }).optional().orElseThrow(() -> new AccountException(404, "제출 기록을 찾을 수 없어요."));
    }
}
