package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.List;
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
        this.jdbc = jdbc;this.events=events;this.functionalSlots=Math.max(1,Math.min(32,functionalSlots));
    }
    record Job(UUID submissionId, String status, int attempt, UUID token, UUID workerId,
               OffsetDateTime leaseUntil, String verdict, String resultJson, String resultSha256, String executionMode) {}
    public record Assignment(UUID submissionId, int attempt, UUID token, String source,
                             String sourceSha256, JsonNode problem, String problemSha256,
                             String runtimeImage, String runnerPolicy, int heartbeatSeconds, String executionMode, JsonNode runnerEnvironment, String language, JsonNode executionProfile, boolean judgeAll) {}
    private static final String RESOURCE_ALLOWED="(b.role='VALIDATION' AND b.status='CHECKED' AND g.status='REVIEWING' AND EXISTS (SELECT 1 FROM generation_resource_execution re JOIN generation_resource_check rc ON rc.id=re.check_id WHERE re.submission_id=s.id AND rc.pipeline='RULE' AND rc.job_id=g.id AND rc.status IN ('MEASURING','REPLAYING')))";
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
        // Checks of a generation that ended (deadline passed, finished or stopped, or its branch closed) can never be
        // claimed again. Close them as IE so they do not look RUNNING/QUEUED forever; live generations are untouched.
        var abandoned = jdbc.sql("""
                SELECT j.submission_id FROM judge_job j JOIN submission s ON s.id=j.submission_id
                JOIN hybrid_branch b ON b.id=s.hybrid_branch_id JOIN hybrid_generation g ON g.id=b.generation_id
                WHERE (j.status='QUEUED' OR (j.status='RUNNING' AND j.lease_until<=?))
                  AND (g.deadline_at<=? OR g.status IN ('FAILED','CANCELLED','DEADLINE_EXCEEDED','PUBLISHED')
                       OR (b.status IN ('FAILED','CANCELLED','SUPERSEDED','CHECKED','SUCCEEDED') AND NOT %s) OR b.revision<>g.revision)""".formatted(RESOURCE_ALLOWED)).param(now).param(now).query(UUID.class).list();
        for (UUID id : abandoned) {
            String report = "{\"verdict\":\"IE\",\"error\":\"generation ended before this check ran\"}";
            jdbc.sql("UPDATE judge_job SET status='FINISHED',verdict='IE',result_json=?,result_sha256=?,finished_at=? WHERE submission_id=?")
                    .param(report).param(JudgeJson.hash(report)).param(now).param(id).update();
            jdbc.sql("UPDATE judge_attempt SET status='EXPIRED',finished_at=? WHERE submission_id=? AND status='RUNNING'")
                    .param(now).param(id).update();
        }
        // A job whose hybrid generation was stopped is not resumed: a worker failing on it would otherwise renew and retry forever.
        var resumed = jdbc.sql("SELECT submission_id FROM judge_job WHERE status='RUNNING' AND worker_id=? AND lease_until>? AND EXISTS (SELECT 1 FROM submission s WHERE s.id=judge_job.submission_id AND (s.hybrid_branch_id IS NULL OR EXISTS (SELECT 1 FROM hybrid_branch b JOIN hybrid_generation g ON g.id=b.generation_id WHERE b.id=s.hybrid_branch_id AND b.revision=g.revision AND g.deadline_at>CURRENT_TIMESTAMP AND ((b.status='RUNNING' AND g.status='VALIDATING') OR (b.status='EARLY' AND g.status='BUILDING') OR (b.status='BLOCKED' AND g.status='HELD' AND g.error_code='VALIDATION_ADAPTER_NOT_CONNECTED') OR (b.status='RUNNING' AND g.status='QUALIFYING') OR "+RESOURCE_ALLOWED+")))) ORDER BY created_at LIMIT 1")
                .param(worker).param(now).query(UUID.class).optional();
        if (resumed.isPresent()) return Optional.of(assignment(job(resumed.get())));
        var next = jdbc.sql("SELECT submission_id FROM judge_job WHERE (status='QUEUED' OR (status='RUNNING' AND lease_until<=? AND attempt<3)) AND EXISTS (SELECT 1 FROM submission s WHERE s.id=judge_job.submission_id AND (s.hybrid_branch_id IS NULL OR EXISTS (SELECT 1 FROM hybrid_branch b JOIN hybrid_generation g ON g.id=b.generation_id WHERE b.id=s.hybrid_branch_id AND b.revision=g.revision AND g.deadline_at>CURRENT_TIMESTAMP AND ((b.status='RUNNING' AND g.status='VALIDATING') OR (b.status='EARLY' AND g.status='BUILDING') OR (b.status='BLOCKED' AND g.status='HELD' AND g.error_code='VALIDATION_ADAPTER_NOT_CONNECTED') OR (b.status='RUNNING' AND g.status='QUALIFYING') OR "+RESOURCE_ALLOWED+")))) ORDER BY priority,created_at,submission_id LIMIT 1 FOR UPDATE SKIP LOCKED")
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
        return jdbc.sql("SELECT s.source_code,s.source_sha256,COALESCE(s.callable_package,s.run_package,d.package_json,p.package_json) AS package_json,COALESCE(s.callable_package_sha256,s.run_package_sha256,d.package_sha256,p.package_sha256) AS package_sha256,s.runtime_image,s.runner_policy,s.language,s.execution_profile_json,(s.run_input IS NULL AND s.generation_job_id IS NULL AND s.spec_draft_id IS NULL AND s.hybrid_branch_id IS NULL) AS judge_all FROM submission s JOIN problem_version p ON p.id=s.problem_version LEFT JOIN diagnostic_item d ON d.id=s.diagnostic_item_id WHERE s.id=?")
                .param(job.submissionId()).query((row, index) -> new Assignment(job.submissionId(), job.attempt(), job.token(),
                        row.getString("source_code"), row.getString("source_sha256"), JudgeJson.parse(row.getString("package_json")),
                        row.getString("package_sha256"), row.getString("runtime_image"), row.getString("runner_policy"), 10, job.executionMode(), environment==null?null:JudgeJson.parse(environment), row.getString("language"),row.getString("execution_profile_json")==null?null:JudgeJson.parse(row.getString("execution_profile_json")),row.getBoolean("judge_all"))).single();
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
        Assignment expected=assignment(current);
        validate(expected, report);
        OffsetDateTime now = now();
        // One isolated replay protects timing boundaries during normalization.
        // Keep the provisional evidence but never publish it as a final verdict.
        if(expected.executionMode().equals("FUNCTIONAL")&&expected.executionProfile()!=null
                &&jdbc.sql("SELECT count(*) FROM submission WHERE id=? AND generation_job_id IS NULL AND spec_draft_id IS NULL AND hybrid_branch_id IS NULL").param(id).query(Integer.class).single()==1) {
            boolean cpu=expected.executionProfile().has("testCpuSeconds");
            double budget=expected.executionProfile().path(cpu?"testCpuSeconds":"testWallSeconds").asDouble()*1000;
            boolean boundary=false;
            for(var test:report.path("tests"))if(test.path("verdict").asText().equals("TLE")||test.path(cpu?"cpu_ms":"wall_ms").asDouble()>=budget*.8)boundary=true;
            if(boundary) {
                jdbc.sql("UPDATE judge_attempt SET status='COMPLETED',result_json=?,finished_at=? WHERE submission_id=? AND token=?").param(json).param(now).param(id).param(token).update();
                jdbc.sql("UPDATE judge_job SET status='QUEUED',execution_mode='EXCLUSIVE',token=NULL,worker_id=NULL,lease_until=NULL WHERE submission_id=?").param(id).update();
                return;
            }
        }
        jdbc.sql("UPDATE judge_job SET status='FINISHED',verdict=?,result_json=?,result_sha256=?,finished_at=? WHERE submission_id=?")
                .param(report.path("verdict").asText()).param(json).param(hash).param(now).param(id).update();
        jdbc.sql("UPDATE judge_attempt SET status='COMPLETED',result_json=?,finished_at=? WHERE submission_id=? AND token=?")
                .param(json).param(now).param(id).param(token).update();
        if (report.path("verdict").asText().equals("AC")) events.publishEvent(new SolutionExports.Accepted(id));
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
        // Learner formal submissions may continue after a failure (judge_all); every other plan stops at the first one.
        boolean judgeAll = report.path("judge_all").asBoolean(false);
        if (judgeAll && !expected.judgeAll()) throw new AccountException(400, "Judge-all report for a plan that stops at the first failure");
        String firstFailure = null;
        for (int i = 0; i < tests.size(); i++) {
            if(expected.executionProfile()!=null&&expected.executionProfile().has("testCpuSeconds")) {
                var cpu=tests.get(i).path("cpu_ms");
                if(!cpu.isNumber()||!Double.isFinite(cpu.asDouble())||cpu.asDouble()<0||!tests.get(i).path("cpu_measurement").asText().equals("cgroup-v2-delta"))throw new AccountException(400,"CPU evidence missing or invalid");
                if(List.of("AC","OK").contains(tests.get(i).path("verdict").asText())&&cpu.asDouble()>expected.executionProfile().path("testCpuSeconds").asDouble()*1000)throw new AccountException(400,"Successful result exceeded CPU budget");
            }
            var memory=tests.get(i).get("memory_peak_bytes");
            if(memory!=null&&(!memory.isIntegralNumber()||!memory.canConvertToLong()||memory.asLong()<0||!tests.get(i).path("memory_measurement").asText().equals("cgroup-peak-observed")))throw new AccountException(400,"Invalid memory evidence");
            String testVerdict = tests.get(i).path("verdict").asText();
            if (!tests.get(i).path("id").equals(expectedTests.get(i).path("id"))
                    || (i >= explicit) != "generated".equals(tests.get(i).path("kind").asText())
                    || (!judgeAll && !run && i < tests.size()-1 && !testVerdict.equals(success)))
                throw new AccountException(400, "Invalid test order or evidence");
            if (firstFailure == null && !testVerdict.equals(success)) firstFailure = testVerdict;
        }
        if (verdict.equals(success) && (tests.size() != expectedTests.size()
                || tests.findValuesAsText("verdict").stream().anyMatch(value -> !value.equals(success))))
            throw new AccountException(400, "AC requires all saved tests to pass");
        if(run&&!Set.of("CE","IE").contains(verdict)&&tests.size()!=expectedTests.size())
            throw new AccountException(400,"Custom execution requires every saved input result");
        if (run) for (JsonNode test : tests) {
            if (!test.path("stdout").isTextual() || test.path("stdout").asText().length() > 32768
                    || !test.path("stderr").isTextual() || test.path("stderr").asText().length() > 8192
                    || !test.path("stdout_truncated").isBoolean())
                throw new AccountException(400, "Invalid custom execution output");
        }
        if (!Set.of("CE","IE").contains(verdict) && (tests.isEmpty()
                || !verdict.equals(firstFailure == null ? success : firstFailure)))
            throw new AccountException(400, "Final verdict does not match evidence");
    }
}
