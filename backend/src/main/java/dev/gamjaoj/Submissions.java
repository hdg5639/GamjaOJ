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
    private final Diagnostics diagnostics;
    private final String executionMode;
    /** RUNNER_USER_EXECUTION_MODE=FUNCTIONAL lets learner submissions/runs share Runner slots (each sandbox stays CPU/memory capped). */
    public Submissions(JdbcClient jdbc, @Value("${gamjaoj.submissions-enabled:false}") boolean enabled, Diagnostics diagnostics,
                       @Value("${RUNNER_USER_EXECUTION_MODE:EXCLUSIVE}") String executionMode) {
        this.executionMode = "FUNCTIONAL".equals(executionMode) ? "FUNCTIONAL" : "EXCLUSIVE";
        this.jdbc = jdbc; this.enabled = enabled; this.diagnostics=diagnostics;
    }
    public record Problem(String version, String title, String statement, String sampleInput,
                          String sampleOutput, List<Example> examples, int sourceLimitBytes, boolean submissionsEnabled, boolean problemHeld, String reviewReason, boolean mine, boolean shared, boolean generated, String category, List<String> tags, String difficulty, String difficultySource, String solveStatus, long pendingSubmissions, List<LanguageProfiles.Option> languages, JsonNode api) {}
    public record View(UUID id, String problemVersion, String sourceSha256, String source,
                       String status, String verdict, String compileMessage, OffsetDateTime createdAt,
                       OffsetDateTime finishedAt, String input, String stdout, String stderr, boolean outputTruncated, UUID sessionId, String runnerPolicy, boolean problemHeld, UUID diagnosticItemId, String language, LanguageProfiles.Option execution,
                       List<TestResult> tests, int testCount,Long wallMs,Long memoryPeakBytes) {}
    /** One judged test of a formal submission, in plan order: number and verdict only, never its input or output. */
    public record TestResult(int number, String verdict, Integer wallMs,Long memoryPeakBytes) {}
    private static List<TestResult> testResults(String result) {
        var out = new java.util.ArrayList<TestResult>();
        if (result == null) return out;
        int number = 1;
        for (JsonNode t : JudgeJson.parse(result).path("tests"))
            out.add(new TestResult(number++, t.path("verdict").asText(), t.has("wall_ms") ? t.path("wall_ms").asInt() : null,t.hasNonNull("memory_peak_bytes")?t.path("memory_peak_bytes").asLong():null));
        return out;
    }
    /** A public example; explanation only for worked examples confirmed by the Runner (ExampleEnrichment). */
    public record Example(String input, String output, String explanation) {}
    /** Published samples when the package lists them (generated problems), otherwise the first test; then verified worked examples. */
    static List<Example> examples(JsonNode data, String verified) {
        var out = new java.util.ArrayList<Example>();
        for (JsonNode s : data.path("samples"))
            if (s.path("input").isTextual() && s.path("output").isTextual() && out.size() < 5)
                out.add(new Example(s.path("input").asText(), s.path("output").asText(), null));
        if (out.isEmpty()) out.add(new Example(data.path("tests").get(0).path("input").asText(), data.path("tests").get(0).path("output").asText(), null));
        if (verified != null) for (JsonNode e : JudgeJson.parse(verified))
            out.add(new Example(e.path("input").asText(), e.path("output").asText(), e.path("explanation").asText(null)));
        return out;
    }
    private static int testCount(String plan) {
        if (plan == null) return 0;
        JsonNode p = JudgeJson.parse(plan);
        return p.path("tests").size() + p.path("generated").path("tests").size();
    }

    UUID owner(String username, boolean lock) {
        return jdbc.sql("SELECT id FROM app_user WHERE username = ?" + (lock ? " FOR UPDATE" : ""))
                .param(username).query(UUID.class).optional()
                .orElseThrow(() -> new AccountException(401, "다시 로그인해 주세요."));
    }

    public List<Problem> problems(String username) {
        UUID owner = owner(username, false);
        boolean canSubmit = enabled || jdbc.sql("SELECT count(*) FROM execution_grant WHERE user_id = ?")
                .param(owner).query(Integer.class).single() > 0;
        return jdbc.sql("SELECT p.*,g.template_id,g.focus,d.spec_json,COALESCE(progress.submissions,0) AS my_submissions,COALESCE(progress.accepted,0) AS my_accepted,COALESCE(progress.pending,0) AS my_pending FROM problem_version p LEFT JOIN (SELECT s.problem_version,COUNT(*) AS submissions,SUM(CASE WHEN j.status='FINISHED' AND j.verdict='AC' THEN 1 ELSE 0 END) AS accepted,SUM(CASE WHEN j.status<>'FINISHED' THEN 1 ELSE 0 END) AS pending FROM submission s JOIN judge_job j ON j.submission_id=s.id WHERE s.user_id=? AND s.run_input IS NULL AND s.generation_job_id IS NULL AND s.spec_draft_id IS NULL AND s.hybrid_branch_id IS NULL AND s.diagnostic_item_id IS NULL GROUP BY s.problem_version) progress ON progress.problem_version=p.id LEFT JOIN generation_job g ON p.id=CONCAT(CONCAT(CONCAT('generated-',CAST(g.id AS VARCHAR(36))),'-r'),CAST(g.revision AS VARCHAR(10))) LEFT JOIN generation_spec_draft d ON p.id=CONCAT('experimental-check-',CAST(d.id AS VARCHAR(36))) WHERE p.ready=true AND p.diagnostic_only=false AND (p.owner_id IS NULL OR p.owner_id=? OR (p.shared=true AND p.review_hold=false)) ORDER BY p.id").param(owner).param(owner)
                .query((row, index) -> {
                    JsonNode data = JudgeJson.parse(row.getString("package_json"));
                    var metadata=ProblemCatalogMetadata.read(row);
                    // Explicit public fields only: never serialize a private problem package.
                    return new Problem(row.getString("id"), data.path("title").asText(), data.path("statement").asText(),
                            data.path("tests").get(0).path("input").asText(), data.path("tests").get(0).path("output").asText(),
                            examples(data, row.getString("examples_json")), 65536, canSubmit && !row.getBoolean("review_hold"),row.getBoolean("review_hold"),row.getString("review_reason"),owner.equals(row.getObject("owner_id",UUID.class)),row.getObject("owner_id")==null||row.getBoolean("shared"),row.getObject("owner_id")!=null,metadata.category(),metadata.tags(),metadata.difficulty(),metadata.difficultySource(),row.getLong("my_accepted")>0?"SOLVED":row.getLong("my_submissions")>0?"ATTEMPTED":"UNATTEMPTED",row.getLong("my_pending"),LanguageProfiles.options(row.getString("time_limits_json")).stream().filter(l->!data.has("api")||l.id().equals("JAVA")).toList(),data.has("api")?data.path("api"):null);
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
        return save(username, key, new SubmissionController.Request(request.problemVersion(), request.source(), request.sessionId(), request.diagnosticItemId(), request.language()), request.input());
    }

    private View save(String username, UUID key, SubmissionController.Request request, String input) {
        if (request.source().getBytes(StandardCharsets.UTF_8).length > 65536)
            throw new AccountException(400, "코드는 UTF-8 기준 64 KiB 이내로 제출해 주세요.");
        String language = LanguageProfiles.normalize(request.language());
        String hash = JudgeJson.hash(request.source());
        UUID user = owner(username, true); // Serialize admission for this user's idempotency and pending cap.
        var existing = jdbc.sql("SELECT id FROM submission WHERE user_id = ? AND idempotency_key = ?")
                .param(user).param(key).query(UUID.class).optional();
        if (existing.isPresent()) {
            View view = find(user, existing.get(), true);
            if (!view.language().equals(language) || !view.sourceSha256().equals(hash) || !view.problemVersion().equals(request.problemVersion())
                    || !java.util.Objects.equals(view.input(), input) || !java.util.Objects.equals(view.sessionId(), request.sessionId())
                    || !java.util.Objects.equals(view.diagnosticItemId(),request.diagnosticItemId()))
                throw new AccountException(409, "같은 요청 키에 다른 코드가 들어왔어요. 새 제출로 보내 주세요.");
            return view;
        }
        if (!enabled && jdbc.sql("SELECT count(*) FROM execution_grant WHERE user_id = ? AND source_sha256 = ?")
                .param(user).param(hash).query(Integer.class).single() == 0)
            throw new AccountException(503, "코드 채점을 준비하고 있어요. 잠시 후 다시 확인해 주세요.");
        if (jdbc.sql("SELECT count(*) FROM problem_version WHERE id = ? AND ready = true AND review_hold=false AND (owner_id IS NULL OR owner_id=? OR shared=true)")
                .param(request.problemVersion()).param(user).query(Integer.class).single() == 0)
            throw new AccountException(404, "제출할 수 있는 문제 버전이 아니에요.");
        if (jdbc.sql("SELECT count(*) FROM submission s JOIN judge_job j ON s.id=j.submission_id WHERE s.user_id=? AND s.generation_job_id IS NULL AND s.spec_draft_id IS NULL AND s.hybrid_branch_id IS NULL AND s.example_check=false AND j.status <> 'FINISHED'")
                .param(user).query(Integer.class).single() >= 3)
            throw new AccountException(429, "진행 중인 채점이 끝나면 다시 제출해 주세요.");
        var problemData=JudgeJson.parse(jdbc.sql("SELECT package_json FROM problem_version WHERE id=?").param(request.problemVersion()).query(String.class).single());
        if(problemData.has("api")&&!language.equals("JAVA"))throw new AccountException(400,"이 API 문제는 Java로 제출해 주세요.");
        boolean diagnosticProblem=jdbc.sql("SELECT diagnostic_only FROM problem_version WHERE id=?").param(request.problemVersion()).query(Boolean.class).single();
        if(diagnosticProblem != (request.diagnosticItemId()!=null) || (request.diagnosticItemId()!=null && request.sessionId()!=null))
            throw new AccountException(409,"진단 문항은 현재 진단에서 제출해 주세요.");
        Diagnostics.Snapshot snapshot=request.diagnosticItemId()==null?null:diagnostics.admit(user,request.diagnosticItemId(),request.problemVersion(),input!=null);
        if (request.sessionId() != null) {
            var session = jdbc.sql("SELECT problem_version,status FROM training_session WHERE id=? AND user_id=?")
                    .param(request.sessionId()).param(user).query((r,n)->new String[]{r.getString(1),r.getString(2)}).optional()
                    .orElseThrow(()->new AccountException(404,"훈련 기록을 찾을 수 없어요."));
            if (!session[0].equals(request.problemVersion()) || !session[1].equals("ACTIVE"))
                throw new AccountException(409,"진행 중인 훈련의 문제를 확인해 주세요. 종료된 훈련에는 새 작업을 추가할 수 없어요.");
        }
        String limits=snapshot==null?jdbc.sql("SELECT time_limits_json FROM problem_version WHERE id=?").param(request.problemVersion()).query((r,n)->r.getString(1)).optional().orElse(null):snapshot.limits();
        JsonNode execution=LanguageProfiles.profile(language,limits);
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO submission (id,user_id,problem_version,source_code,source_sha256,idempotency_key,runtime_image,runner_policy,language,execution_profile_json) VALUES (?,?,?,?,?,?,?,?,?,?)")
                .param(id).param(user).param(request.problemVersion()).param(request.source()).param(hash).param(key)
                .param(execution.path("image").asText()).param(execution.path(input==null?"policy":"runPolicy").asText())
                .param(language).param(JudgeJson.canonical(execution)).update();
        if (request.sessionId() != null) jdbc.sql("UPDATE submission SET training_session_id=? WHERE id=?")
                .param(request.sessionId()).param(id).update();
        if(snapshot!=null) {
            jdbc.sql("UPDATE submission SET diagnostic_item_id=? WHERE id=?")
                    .param(request.diagnosticItemId()).param(id).update();
        }
        if (input != null || problemData.has("api")) {
            var plan = input==null?(com.fasterxml.jackson.databind.node.ObjectNode)problemData.deepCopy():JudgeJson.JSON.createObjectNode().put("version", request.problemVersion()).put("output_policy", "RUN_ONLY");
            if(input!=null)plan.putArray("tests").addObject().put("id", "custom-input").put("input", input).put("output", "");
            if(problemData.has("api"))plan.set("callable",problemData.path("api"));
            String json = JudgeJson.canonical(plan);
            if(input==null)jdbc.sql("UPDATE submission SET callable_package=?,callable_package_sha256=? WHERE id=?").param(json).param(JudgeJson.hash(json)).param(id).update();
            else jdbc.sql("UPDATE submission SET run_input=?,run_package=?,run_package_sha256=? WHERE id=?")
                    .param(input).param(json).param(JudgeJson.hash(json)).param(id).update();
        }
        jdbc.sql("INSERT INTO judge_job (submission_id,execution_mode) VALUES (?,?)").param(id).param(executionMode).update();
        return find(user, id, true);
    }

    public List<View> history(String username) { return history(username,null,0); }
    public List<View> history(String username,String problemVersion,int page) {return history(username,problemVersion,page,50);}
    public List<View> history(String username,String problemVersion,int page,int size) {
        if(size<1||size>50)throw new AccountException(400,"페이지 크기는 1~50개로 설정해 주세요.");
        if(page<0||page>100000)throw new AccountException(400,"잘못된 페이지예요.");
        UUID user=owner(username,false);
        String filter=problemVersion==null?"":" AND problem_version=?";
        var query=jdbc.sql("SELECT id FROM submission WHERE generation_job_id IS NULL AND spec_draft_id IS NULL AND hybrid_branch_id IS NULL AND user_id=? AND run_input IS NULL"+filter+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?").param(user);
        if(problemVersion!=null)query=query.param(problemVersion);
        return query.param(size).param(page*size).query(UUID.class).list().stream().map(id->find(user,id,false)).toList();
    }
    public List<View> runs(String username) { owner(username,false);return List.of(); }
    public View detail(String username, UUID id) { return detail(username, id, false); }
    public View runDetail(String username, UUID id) { return detail(username, id, true); }
    private View detail(String username, UUID id, boolean run) {
        View view = find(owner(username, false), id, true);
        if ((view.input() != null) != run) throw new AccountException(404, "기록을 찾을 수 없어요.");
        return view;
    }
    private View find(UUID user, UUID id, boolean includeSource) {
        return jdbc.sql("SELECT s.*,p.review_hold,j.status,j.verdict,j.result_json,j.finished_at,"+(includeSource?"CASE WHEN s.run_input IS NULL AND j.status='FINISHED' THEN COALESCE(s.callable_package,d.package_json,p.package_json) END":"NULL")+" AS plan_json FROM submission s JOIN problem_version p ON p.id=s.problem_version JOIN judge_job j ON s.id=j.submission_id LEFT JOIN diagnostic_item d ON d.id=s.diagnostic_item_id WHERE s.id=? AND s.user_id=? AND s.spec_draft_id IS NULL AND s.hybrid_branch_id IS NULL")
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
                            output.path("stdout").asText(""), output.path("stderr").asText(""), output.path("stdout_truncated").asBoolean(), row.getObject("training_session_id", UUID.class), row.getString("runner_policy"),row.getBoolean("review_hold"),row.getObject("diagnostic_item_id",UUID.class),row.getString("language"),
                            row.getString("execution_profile_json")==null?null:LanguageProfiles.option(JudgeJson.parse(row.getString("execution_profile_json"))),
                            includeSource && input == null ? testResults(result) : List.of(),
                            includeSource && input == null && "FINISHED".equals(row.getString("status")) ? testCount(row.getString("plan_json")) : 0,
                            result==null?null:ExecutionMetrics.maximum(JudgeJson.parse(result),"wall_ms"),
                            result==null?null:ExecutionMetrics.maximum(JudgeJson.parse(result),"memory_peak_bytes"));
                }).optional().orElseThrow(() -> new AccountException(404, "제출 기록을 찾을 수 없어요."));
    }
}
