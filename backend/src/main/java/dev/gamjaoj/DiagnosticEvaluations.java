package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DiagnosticEvaluations {
    private final JdbcClient jdbc;
    private final Diagnostics diagnostics;
    private final Submissions submissions;
    private final AiTasks tasks;
    public DiagnosticEvaluations(JdbcClient jdbc,Diagnostics diagnostics,Submissions submissions,AiTasks tasks) {
        this.jdbc=jdbc;this.diagnostics=diagnostics;this.submissions=submissions;this.tasks=tasks;
    }
    public record View(UUID id,UUID sessionId,String evidenceHash,JsonNode facts,String status,JsonNode interpretation,String errorCode,List<Correction> corrections) {}
    public record Correction(UUID id,int observationIndex,String note,java.time.OffsetDateTime createdAt) {}
    @Transactional
    public View request(String username,UUID session) {
        UUID owner=submissions.owner(username,true);
        var saved=diagnostics.detail(username,session); // Reconcile and lock final verdicts before snapshotting.
        if(saved.items().stream().anyMatch(i->i.pending()>0))throw new AccountException(409,"진행 중인 정식 채점이 끝난 뒤 평가해 주세요.");
        var input=JudgeJson.JSON.createObjectNode().put("kind","DIAGNOSTIC").put("policy","diagnostic-evidence-v1")
                .put("sessionId",session.toString()).put("complete",saved.status().equals("COMPLETED"));
        int revision=jdbc.sql("SELECT exposure_revision FROM diagnostic_session WHERE id=?").param(session).query(Integer.class).single();
        input.put("exposureRevision",revision);
        var evidence=input.putArray("items");
        var facts=JudgeJson.JSON.createObjectNode().put("complete",saved.status().equals("COMPLETED"));
        if(saved.sourceSessionId()!=null) {
            String correspondence=jdbc.sql("SELECT correspondence_json FROM diagnostic_session WHERE id=?").param(session).query(String.class).single();
            input.put("sourceSessionId",saved.sourceSessionId().toString());
            input.set("correspondence",JudgeJson.parse(correspondence));
            facts.put("sourceSessionId",saved.sourceSessionId().toString());
            facts.put("exposureScope","NO_PRIOR_DIAGNOSTIC_ASSIGNMENT");
        }
        var coverage=facts.putArray("items");
        int submissionsCount=0;
        for(var item:saved.items()) {
            var fact=coverage.addObject().put("itemId",item.id().toString()).put("category",item.category()).put("difficulty",item.difficulty())
                    .put("status",item.status()).put("attempts",item.attempts()).put("externallySeen",item.externallySeen());
            if(item.skipReason()!=null&&!item.skipReason().equals("UNSPECIFIED"))fact.put("skipReason",item.skipReason());
            if(item.status().equals("OPEN"))continue; // Never send open-question source, statement or rubric for interpretation.
            var data=jdbc.sql("SELECT package_sha256,package_json,rubric_json,runtime_image,runner_policy FROM diagnostic_item WHERE id=?")
                    .param(item.id()).query((r,n)-> {
                        var node=JudgeJson.JSON.createObjectNode().put("itemId",item.id().toString()).put("category",item.category())
                                .put("difficulty",item.difficulty()).put("status",item.status()).put("problemHash",r.getString(1))
                                .put("statement",JudgeJson.parse(r.getString(2)).path("statement").asText())
                                .put("referenceRuntimeImage",r.getString(4)).put("referenceRunnerPolicy",r.getString(5));
                        node.set("rubric",JudgeJson.parse(r.getString(3)));return node;
                    }).single();
            data.put("externallySeen",item.externallySeen());
            if(item.skipReason()!=null&&!item.skipReason().equals("UNSPECIFIED"))data.put("skipReason",item.skipReason());
            var attempts=data.putArray("submissions");
            if(!item.externallySeen())jdbc.sql("SELECT s.id,s.source_code,s.source_sha256,j.verdict,j.result_sha256,s.language,s.execution_profile_json,s.runtime_image,s.runner_policy FROM submission s JOIN judge_job j ON j.submission_id=s.id WHERE s.diagnostic_item_id=? AND s.run_input IS NULL AND j.status='FINISHED' AND j.verdict<>'IE' ORDER BY s.created_at,s.id")
                    .param(item.id()).query((r,n)-> {
                        attempts.addObject().put("submissionId",r.getObject(1,UUID.class).toString()).put("source",r.getString(2))
                                .put("sourceHash",r.getString(3)).put("verdict",r.getString(4)).put("resultHash",r.getString(5)).put("language",r.getString(6)).put("executionProfile",r.getString(7)).put("runtimeImage",r.getString(8)).put("runnerPolicy",r.getString(9));return true;
                    }).list();
            submissionsCount+=attempts.size();evidence.add(data);
        }
        if(evidence.isEmpty())throw new AccountException(409,"완료하거나 건너뛴 문항이 생기면 부분 결과를 확인할 수 있어요.");
        input.set("coverage",coverage.deepCopy());
        String json=JudgeJson.canonical(input);
        if(bytes(json)>EVIDENCE_LIMIT) {
            // Deterministic, declared reduction: keep each item's first and last two sources; others keep verdict and hashes only.
            for(var item:evidence) {
                var attempts=item.path("submissions");
                for(int i=1;i<attempts.size()-2;i++) {
                    var attempt=(com.fasterxml.jackson.databind.node.ObjectNode)attempts.get(i);
                    attempt.remove("source");attempt.put("sourceOmitted",true);
                }
            }
            input.put("sourceCompaction","FIRST_AND_LAST_TWO_PER_ITEM");
            json=JudgeJson.canonical(input);
        }
        String hash=JudgeJson.hash(json);
        if(bytes(json)>EVIDENCE_LIMIT)
            throw new AccountException(413,"평가 근거가 한 번에 처리할 수 있는 크기를 넘었어요. 제출 기록은 보존되어 있어요.");
        var old=jdbc.sql("SELECT id FROM diagnostic_evaluation WHERE session_id=? AND evidence_sha256=?").param(session).param(hash).query(UUID.class).optional();
        if(old.isPresent())return find(owner,old.get());
        UUID id=UUID.randomUUID();
        // Partial results stay deterministic: free-text inference could hint at remaining related questions.
        UUID task=saved.status().equals("COMPLETED")&&submissionsCount>0?tasks.diagnostic(owner,session,input):null;
        jdbc.sql("INSERT INTO diagnostic_evaluation(id,session_id,evidence_sha256,evidence_json,facts_json,ai_task_id,exposure_revision) VALUES (?,?,?,?,?,?,?)")
                .param(id).param(session).param(hash).param(json).param(JudgeJson.canonical(facts)).param(task).param(revision).update();
        return find(owner,id);
    }
    @Transactional
    public List<View> list(String username,UUID session) {
        UUID owner=submissions.owner(username,true);diagnostics.detail(username,session);
        return jdbc.sql("SELECT id FROM diagnostic_evaluation WHERE session_id=? ORDER BY created_at DESC,id DESC")
                .param(session).query(UUID.class).list().stream().map(id->find(owner,id)).toList();
    }
    @Transactional
    public View correct(String username,UUID session,UUID evaluation,UUID key,int observation,String note) {
        UUID owner=submissions.owner(username,true);
        if(note==null||note.isBlank()||note.length()>1000||observation<0)throw new AccountException(400,"정정할 관찰과 1~1000자의 설명을 확인해 주세요.");
        View saved=find(owner,evaluation);
        if(!saved.sessionId().equals(session))throw new AccountException(404,"진단 평가를 찾을 수 없어요.");
        var previous=jdbc.sql("SELECT observation_index,note FROM diagnostic_correction WHERE evaluation_id=? AND request_key=?")
                .param(evaluation).param(key).query((r,n)->new Object[]{r.getInt(1),r.getString(2)}).optional();
        if(previous.isPresent()) {
            if((int)previous.get()[0]!=observation||!previous.get()[1].equals(note))throw new AccountException(409,"같은 요청 키의 정정 내용이 달라요.");
            return saved;
        }
        if(!saved.status().equals("COMPLETED")||saved.interpretation()==null||observation>=saved.interpretation().path("observations").size())
            throw new AccountException(409,"현재 확인할 수 있는 완료 평가의 관찰을 선택해 주세요.");
        jdbc.sql("INSERT INTO diagnostic_correction(id,evaluation_id,request_key,observation_index,interpretation_sha256,note) VALUES (?,?,?,?,?,?)")
                .param(UUID.randomUUID()).param(evaluation).param(key).param(observation)
                .param(JudgeJson.hash(JudgeJson.canonical(saved.interpretation()))).param(note).update();
        return find(owner,evaluation);
    }
    private List<Correction> corrections(UUID evaluation) {
        return jdbc.sql("SELECT id,observation_index,note,created_at FROM diagnostic_correction WHERE evaluation_id=? ORDER BY created_at,id")
                .param(evaluation).query((r,n)->new Correction(r.getObject(1,UUID.class),r.getInt(2),r.getString(3),r.getObject(4,java.time.OffsetDateTime.class))).list();
    }
    @Transactional
    public View detail(String username,UUID id) { return find(submissions.owner(username,true),id); }
    private View find(UUID owner,UUID id) {
        return jdbc.sql("SELECT e.*,a.status,a.result_json,a.error_code FROM diagnostic_evaluation e JOIN diagnostic_session d ON d.id=e.session_id LEFT JOIN ai_task a ON a.id=e.ai_task_id WHERE e.id=? AND d.user_id=?")
                .param(id).param(owner).query((r,n)-> {
                    boolean hidden=jdbc.sql("SELECT count(*) FROM diagnostic_session WHERE user_id=? AND status<>'COMPLETED'").param(owner).query(Integer.class).single()>0;
                    boolean stale=jdbc.sql("SELECT exposure_revision FROM diagnostic_session WHERE id=?").param(r.getObject("session_id",UUID.class)).query(Integer.class).single()!=r.getInt("exposure_revision");
                    boolean held=jdbc.sql("SELECT count(*) FROM diagnostic_item i JOIN problem_version p ON p.id=i.problem_version WHERE i.session_id=? AND p.review_hold=true")
                            .param(r.getObject("session_id",UUID.class)).query(Integer.class).single()>0;
                    String result=r.getString("result_json"),state=r.getString("status");
                    return new View(id,r.getObject("session_id",UUID.class),r.getString("evidence_sha256"),JudgeJson.parse(r.getString("facts_json")),
                            stale?"STALE_EXPOSURE":held?"HELD_REVIEW":state==null?"FACTS_ONLY":hidden?"HIDDEN_DURING_ASSESSMENT":state,
                            stale||held||hidden||result==null?null:JudgeJson.parse(result),r.getString("error_code"),stale||held||hidden?List.of():corrections(id));
                }).optional().orElseThrow(()->new AccountException(404,"진단 평가를 찾을 수 없어요."));
    }
    static final int EVIDENCE_LIMIT=524288;
    private static int bytes(String json){return json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;}
}
