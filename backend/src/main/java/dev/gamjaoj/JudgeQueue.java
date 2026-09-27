package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JudgeQueue {
    private final JdbcClient jdbc;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final int functionalSlots;
    /** functionalSlots must match the Runner host's GAMJAOJ_FUNCTIONAL_SLOTS; the host lock is the physical cap. */
    public JudgeQueue(JdbcClient jdbc,org.springframework.context.ApplicationEventPublisher events,
                      @org.springframework.beans.factory.annotation.Value("${RUNNER_FUNCTIONAL_SLOTS:2}") int functionalSlots) {
        this.jdbc = jdbc;this.events=events;this.functionalSlots=Math.max(1,Math.min(16,functionalSlots));
    }
    record Job(UUID submissionId, String status, int attempt, UUID token, UUID workerId,
               OffsetDateTime leaseUntil, String verdict, String resultJson, String resultSha256, String executionMode) {}
    public record Assignment(UUID submissionId, int attempt, UUID token, String source,
                             String sourceSha256, JsonNode problem, String problemSha256,
                             String runtimeImage, String runnerPolicy, int heartbeatSeconds, String executionMode, JsonNode runnerEnvironment, String language, JsonNode executionProfile) {}
    private static final String JOB_COLUMNS = "submission_id,status,attempt,token,worker_id,lease_until,verdict,result_json,result_sha256,execution_mode";
    private static OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }
    private Job job(UUID id) {
        return jdbc.sql("SELECT " + JOB_COLUMNS + " FROM judge_job WHERE submission_id=? FOR UPDATE")
                .param(id).query(Job.class).optional().orElseThrow(() -> new AccountException(404, "Unknown judge job"));
    }

    @Transactional
    public Optional<Assignment> claim(UUID worker) {
        // Short coordinator-only lock makes retries with the same worker ID resume one active attempt.
        jdbc.sql("SELECT id FROM judge_queue_lock WHERE id=1 FOR UPDATE").query(Integer.class).single();
        OffsetDateTime now = now();
        var exhausted = jdbc.sql("SELECT submission_id FROM judge_job WHERE status='RUNNING' AND lease_until<=? AND attempt>=3 FOR UPDATE")
                .param(now).query(UUID.class).list();
        for (UUID id : exhausted) {
            String report = "{\"verdict\":\"IE\",\"error\":\"worker lease exhausted\"}";
            jdbc.sql("UPDATE judge_job SET status='FINISHED',verdict='IE',result_json=?,result_sha256=?,finished_at=? WHERE submission_id=?")
                    .param(report).param(JudgeJson.hash(report)).param(now).param(id).update();
            jdbc.sql("UPDATE judge_attempt SET status='EXPIRED',finished_at=? WHERE submission_id=? AND status='RUNNING'")
                    .param(now).param(id).update();
        }
        var resumed = jdbc.sql("SELECT submission_id FROM judge_job WHERE status='RUNNING' AND worker_id=? AND lease_until>? ORDER BY created_at LIMIT 1")
                .param(worker).param(now).query(UUID.class).optional();
        if (resumed.isPresent()) return Optional.of(assignment(job(resumed.get())));
        var next = jdbc.sql("SELECT submission_id FROM judge_job WHERE (status='QUEUED' OR (status='RUNNING' AND lease_until<=? AND attempt<3)) AND EXISTS (SELECT 1 FROM submission s WHERE s.id=judge_job.submission_id AND (s.hybrid_branch_id IS NULL OR EXISTS (SELECT 1 FROM hybrid_branch b JOIN hybrid_generation g ON g.id=b.generation_id WHERE b.id=s.hybrid_branch_id AND b.revision=g.revision AND g.deadline_at>CURRENT_TIMESTAMP AND ((b.status='RUNNING' AND g.status='VALIDATING') OR (b.status='EARLY' AND g.status='BUILDING') OR (b.status='BLOCKED' AND g.status='HELD' AND g.error_code='VALIDATION_ADAPTER_NOT_CONNECTED') OR (b.status='RUNNING' AND g.status='QUALIFYING'))))) ORDER BY priority,created_at,submission_id LIMIT 1 FOR UPDATE SKIP LOCKED")
                .param(now).query(UUID.class).optional();
        if (next.isEmpty()) return Optional.empty();
        Job old = job(next.get());
        // An exclusive job at the queue head drains every functional slot. Do not skip the queue head.
        var active = jdbc.sql("SELECT execution_mode FROM judge_job WHERE status='RUNNING' AND lease_until>?")
                .param(now).query(String.class).list();
        if (!active.isEmpty() && (!old.executionMode().equals("FUNCTIONAL")
                || active.size() >= functionalSlots || active.stream().anyMatch(mode -> !mode.equals("FUNCTIONAL"))))
            return Optional.empty();
        if (old.attempt() > 0) jdbc.sql("UPDATE judge_attempt SET status='SUPERSEDED',finished_at=? WHERE submission_id=? AND attempt=?")
                .param(now).param(old.submissionId()).param(old.attempt()).update();
        UUID token = UUID.randomUUID();
        jdbc.sql("UPDATE judge_job SET status='RUNNING',attempt=?,token=?,worker_id=?,lease_until=? WHERE submission_id=?")
                .param(old.attempt()+1).param(token).param(worker).param(now.plusSeconds(60)).param(old.submissionId()).update();
        jdbc.sql("INSERT INTO judge_attempt (submission_id,attempt,token,worker_id,status,execution_environment_json) VALUES (?,?,?,?,'RUNNING',?)")
                .param(old.submissionId()).param(old.attempt()+1).param(token).param(worker)
                .param(JudgeJson.canonical(RunnerEnvironment.expected())).update();
        return Optional.of(assignment(job(old.submissionId())));
    }

    private Assignment assignment(Job job) {
        String environment=jdbc.sql("SELECT execution_environment_json FROM judge_attempt WHERE submission_id=? AND attempt=?")
                .param(job.submissionId()).param(job.attempt()).query(String.class).optional().orElse(null);
        return jdbc.sql("SELECT s.source_code,s.source_sha256,COALESCE(s.run_package,d.package_json,p.package_json) AS package_json,COALESCE(s.run_package_sha256,d.package_sha256,p.package_sha256) AS package_sha256,s.runtime_image,s.runner_policy,s.language,s.execution_profile_json FROM submission s JOIN problem_version p ON p.id=s.problem_version LEFT JOIN diagnostic_item d ON d.id=s.diagnostic_item_id WHERE s.id=?")
                .param(job.submissionId()).query((row, index) -> new Assignment(job.submissionId(), job.attempt(), job.token(),
                        row.getString("source_code"), row.getString("source_sha256"), JudgeJson.parse(row.getString("package_json")),
                        row.getString("package_sha256"), row.getString("runtime_image"), row.getString("runner_policy"), 10, job.executionMode(), environment==null?null:JudgeJson.parse(environment), row.getString("language"),row.getString("execution_profile_json")==null?null:JudgeJson.parse(row.getString("execution_profile_json")))).single();
    }

    @Transactional
    public void heartbeat(UUID id, UUID token) {
        OffsetDateTime now = now();
        if (jdbc.sql("UPDATE judge_job SET lease_until=? WHERE submission_id=? AND token=? AND status='RUNNING' AND lease_until>?")
                .param(now.plusSeconds(60)).param(id).param(token).param(now).update() != 1)
            throw new AccountException(409, "Expired or superseded judge attempt");
    }

    @Transactional
    public void complete(UUID id, UUID token, JsonNode report) {
        String json = JudgeJson.canonical(report);
        if (json.length() > 262144) throw new AccountException(400, "Judge report too large");
        String hash = JudgeJson.hash(json);
        Job current = job(id);
        if (current.status().equals("FINISHED") && token.equals(current.token()) && hash.equals(current.resultSha256())) return;
        if (!current.status().equals("RUNNING") || !token.equals(current.token()) || !current.leaseUntil().isAfter(now()))
            throw new AccountException(409, "Expired or superseded judge attempt");
        validate(assignment(current), report);
        OffsetDateTime now = now();
        jdbc.sql("UPDATE judge_job SET status='FINISHED',verdict=?,result_json=?,result_sha256=?,finished_at=? WHERE submission_id=?")
                .param(report.path("verdict").asText()).param(json).param(hash).param(now).param(id).update();
        jdbc.sql("UPDATE judge_attempt SET status='COMPLETED',result_json=?,finished_at=? WHERE submission_id=? AND token=?")
                .param(json).param(now).param(id).param(token).update();
        if(jdbc.sql("SELECT count(*) FROM submission WHERE id=? AND hybrid_branch_id IS NOT NULL").param(id).query(Integer.class).single()>0)
            events.publishEvent(new HybridExecution.Wakeup());
    }

    private void validate(Assignment expected, JsonNode report) {
        if(expected.runnerEnvironment()!=null && !RunnerEnvironment.matches(expected.runnerEnvironment(),report.get("runner_environment")))
            throw new AccountException(400,"Runner environment does not match the saved attempt");
        if(expected.executionProfile()!=null && (!expected.executionProfile().equals(report.path("execution_profile"))
                || !expected.language().equals(report.path("language").asText())))
            throw new AccountException(400,"Language/limits do not match the saved submission");
        String verdict = report.path("verdict").asText();
        boolean run = expected.problem().path("output_policy").asText().equals("RUN_ONLY");
        String success = run ? "OK" : "AC";
        Set<String> allowed = run ? Set.of("OK","CE","RE","TLE","MLE","OLE","IE") : Set.of("AC","WA","CE","RE","TLE","MLE","OLE","IE");
        if (!allowed.contains(verdict)
                || !expected.executionMode().equals(report.path("execution_mode").asText("EXCLUSIVE"))
                || !expected.sourceSha256().equals(report.path("source_sha256").asText())
                || !expected.problemSha256().equals(report.path("problem_sha256").asText())
                || !expected.runtimeImage().equals(report.path("image").asText())
                || !expected.runnerPolicy().equals(report.path("policy").asText())
                || !expected.problem().path("version").asText().equals(report.path("problem_version").asText()))
            throw new AccountException(400, "Judge report does not match the saved execution plan");
        JsonNode tests = report.path("tests");
        // Saved explicit tests first, then the plan's generated large tests in order (Runner-side inputs).
        var expectedTests = JudgeJson.JSON.createArrayNode();expectedTests.addAll((com.fasterxml.jackson.databind.node.ArrayNode) expected.problem().path("tests"));
        int explicit = expectedTests.size();
        for (JsonNode g : expected.problem().path("generated").path("tests")) expectedTests.add(g);
        if (!tests.isArray() || tests.size() > expectedTests.size()) throw new AccountException(400, "Invalid test evidence");
        for (int i = 0; i < tests.size(); i++) {
            if (!tests.get(i).path("id").equals(expectedTests.get(i).path("id"))
                    || (i >= explicit) != "generated".equals(tests.get(i).path("kind").asText())
                    || (i < tests.size()-1 && !tests.get(i).path("verdict").asText().equals(success)))
                throw new AccountException(400, "Invalid test order or evidence");
        }
        if (verdict.equals(success) && (tests.size() != expectedTests.size()
                || tests.findValuesAsText("verdict").stream().anyMatch(value -> !value.equals(success))))
            throw new AccountException(400, "AC requires all saved tests to pass");
        if (run) for (JsonNode test : tests) {
            if (!test.path("stdout").isTextual() || test.path("stdout").asText().length() > 32768
                    || !test.path("stderr").isTextual() || test.path("stderr").asText().length() > 8192
                    || !test.path("stdout_truncated").isBoolean())
                throw new AccountException(400, "Invalid custom execution output");
        }
        if (!Set.of("CE","IE").contains(verdict) && (tests.isEmpty()
                || !verdict.equals(tests.get(tests.size()-1).path("verdict").asText())))
            throw new AccountException(400, "Final verdict does not match evidence");
    }
}
