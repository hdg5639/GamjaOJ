package dev.gamjaoj;

import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicit learner confirmation, conservative contract matching, no model calls for recommendations. */
@Service
class PracticeFollowups {
    record Focus(String id,String label) {}
    record Options(List<String> steps,List<Focus> focuses,String type) {}
    record Candidate(String version,String title,String statement) {}
    record Attempt(int round,UUID sessionId,String problemVersion,String status,Boolean usedHelp,UUID reviewedSubmissionId) {}
    record View(UUID id,UUID analysisId,String goal,String focus,String status,List<Candidate> candidates,
                UUID sessionId,String problemVersion,String generationStatus,Boolean usedHelp,UUID reviewedSubmissionId,int round,List<Attempt> attempts) {}
    private record Source(String version,String template,com.fasterxml.jackson.databind.JsonNode result) {}
    private final JdbcClient jdbc;
    private final Submissions submissions;
    private final TrainingSessions training;
    private final GenerationJobs generation;
    private final GenerationSpecDrafts drafts;
    PracticeFollowups(JdbcClient jdbc,Submissions submissions,TrainingSessions training,GenerationJobs generation,GenerationSpecDrafts drafts){
        this.jdbc=jdbc;this.submissions=submissions;this.training=training;this.generation=generation;this.drafts=drafts;
    }
    private String template(String version){
        if(version.equals("total-v1"))return GenerationTemplate.ID;
        if(version.equals("valid-parentheses-v1"))return GenerationType.PARENTHESES.id;
        return jdbc.sql("SELECT template_id FROM generation_job WHERE status='READY' AND ?=CONCAT(CONCAT(CONCAT('generated-',CAST(id AS VARCHAR(36))),'-r'),CAST(revision AS VARCHAR(10)))")
                .param(version).query(String.class).optional().orElse(null);
    }
    private Source source(UUID owner,UUID analysis){
        return jdbc.sql("SELECT s.problem_version,a.result_json,p.review_hold FROM ai_task a JOIN submission s ON s.id=a.submission_id JOIN judge_job j ON j.submission_id=s.id JOIN problem_version p ON p.id=s.problem_version WHERE a.id=? AND a.user_id=? AND s.user_id=? AND a.kind='ANALYSIS' AND a.status='COMPLETED' AND s.run_input IS NULL AND j.status='FINISHED' AND j.verdict<>'IE'")
                .param(analysis).param(owner).param(owner).query((r,n)->{
                    if(r.getBoolean("review_hold"))throw new AccountException(409,"검토 중인 문제의 분석은 훈련 근거로 사용할 수 없어요.");
                    var result=JudgeJson.parse(r.getString("result_json"));
                    if(!AiTasks.validFeedback(result))throw new AccountException(409,"분석 결과를 확인해 주세요.");
                    String version=r.getString("problem_version");return new Source(version,template(version),result);
                }).optional().orElseThrow(()->new AccountException(404,"완료된 본인의 정식 제출 분석을 선택해 주세요."));
    }
    Options options(String username,UUID analysis){
        var source=source(submissions.owner(username,false),analysis);
        var steps=new ArrayList<String>();source.result().path("nextSteps").forEach(step->steps.add(step.asText()));
        var focuses=source.template()==null?List.of(new Focus("custom","확인한 목표 그대로")):
                GenerationType.of(source.template()).focuses().stream().filter(f->List.of("basics","overflow","edge-cases","prefix-balance").contains(f)).map(f->new Focus(f,GenerationChoices.label(f))).toList();
        return new Options(steps,focuses,source.template()==null?"자유 출제 · 개별 검증 필요":GenerationType.of(source.template()).title);
    }
    @Transactional
    View confirm(String username,UUID analysis,int step,String focus){
        UUID owner=submissions.owner(username,true);var source=source(owner,analysis);
        if(step<0||step>=source.result().path("nextSteps").size()||options(username,analysis).focuses().stream().noneMatch(f->f.id().equals(focus)))
            throw new AccountException(400,"연습할 지점과 목표를 선택해 주세요.");
        var old=jdbc.sql("SELECT id FROM practice_followup WHERE user_id=? AND analysis_id=? AND step_index=? AND focus=?").param(owner).param(analysis).param(step).param(focus).query(UUID.class).optional();
        if(old.isPresent())return view(owner,old.get());
        if(source.result().path("nextSteps").get(step).asText().isBlank())throw new AccountException(400,"내용이 있는 학습 목표를 선택해 주세요.");
        UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO practice_followup (id,user_id,analysis_id,step_index,goal,focus,template_id,source_version,round_id) VALUES (?,?,?,?,?,?,?,?,?)")
                .param(id).param(owner).param(analysis).param(step).param(source.result().path("nextSteps").get(step).asText()).param(focus).param(source.template()).param(source.version()).param(id).update();
        return view(owner,id);
    }
    List<View> list(String username){UUID owner=submissions.owner(username,false);return jdbc.sql("SELECT id FROM practice_followup WHERE user_id=? ORDER BY created_at DESC,id LIMIT 30").param(owner).query(UUID.class).list().stream().map(id->view(owner,id)).toList();}
    View detail(String username,UUID id){return view(submissions.owner(username,false),id);}
    private List<Candidate> candidates(UUID owner,String origin,String contract,String focus,UUID id,UUID roundId,boolean requested){
        // Free-form output is eligible only when it was generated for this exact confirmed goal.
        return jdbc.sql("SELECT id,package_json FROM problem_version p WHERE ready=true AND diagnostic_only=false AND review_hold=false AND (owner_id IS NULL OR owner_id=? OR shared=true) AND id<>? AND NOT EXISTS (SELECT 1 FROM submission s JOIN judge_job j ON j.submission_id=s.id WHERE s.user_id=? AND s.problem_version=p.id AND s.run_input IS NULL AND j.verdict='AC') ORDER BY id")
                .param(owner).param(origin).param(owner).query((r,n)->new Candidate(r.getString(1),JudgeJson.parse(r.getString(2)).path("title").asText(),JudgeJson.parse(r.getString(2)).path("statement").asText())).list().stream().filter(p->{
                    if(contract==null)return (requested&&p.version().equals("experimental-check-"+roundId)) || jdbc.sql("SELECT count(*) FROM practice_followup_attempt WHERE followup_id=? AND ?=CONCAT('experimental-check-',CAST(generation_id AS VARCHAR(36)))").param(id).param(p.version()).query(Integer.class).single()>0;
                    if(!contract.equals(template(p.version())))return false;
                    if(p.version().equals(GenerationType.of(contract).baseProblem))return true;
                    return jdbc.sql("SELECT focus FROM generation_job WHERE status='READY' AND ?=CONCAT(CONCAT(CONCAT('generated-',CAST(id AS VARCHAR(36))),'-r'),CAST(revision AS VARCHAR(10)))")
                            .param(p.version()).query(String.class).optional().map(value->Arrays.asList(value.split(",")).contains(focus)).orElse(false);
                }).limit(3).toList();
    }
    private View view(UUID owner,UUID id){
        return jdbc.sql("SELECT f.*,p.review_hold,t.problem_version AS target_version,t.status AS session_status,tp.review_hold AS target_held FROM practice_followup f JOIN problem_version p ON p.id=f.source_version LEFT JOIN training_session t ON t.id=f.session_id LEFT JOIN problem_version tp ON tp.id=t.problem_version WHERE f.id=? AND f.user_id=?")
                .param(id).param(owner).query((r,n)->{
                    UUID session=r.getObject("session_id",UUID.class),reviewed=r.getObject("reviewed_submission_id",UUID.class);
                    Boolean helped=r.getObject("used_help",Boolean.class);boolean held=r.getBoolean("review_hold")||r.getBoolean("target_held");
                    String state=held?"HELD":reviewed!=null?(Boolean.TRUE.equals(helped)?"AC_WITH_HELP":"SELF_REPORTED_UNASSISTED_AC"):session==null?"READY_TO_PRACTICE":"ACTIVE";
                    if(!held&&reviewed==null&&session!=null&&"ENDED".equals(r.getString("session_status"))){
                        int pending=jdbc.sql("SELECT count(*) FROM submission s JOIN judge_job j ON j.submission_id=s.id WHERE s.training_session_id=? AND j.status<>'FINISHED'").param(session).query(Integer.class).single();
                        state=pending>0?"WAITING_JUDGE":latestAc(session)!=null?"AWAITING_REFLECTION":"NEEDS_PRACTICE";
                    }
                    UUID roundId=r.getObject("round_id",UUID.class);
                    boolean requested=r.getBoolean("generation_requested");String contract=r.getString("template_id");
                    String generationStatus=!requested?null:jdbc.sql(contract==null?"SELECT status FROM generation_spec_draft WHERE id=?":"SELECT status FROM generation_job WHERE id=?").param(roundId).query(String.class).optional().orElse("UNAVAILABLE");
                    return new View(id,r.getObject("analysis_id",UUID.class),r.getString("goal"),r.getString("focus"),state,
                            held||session!=null?List.of():candidates(owner,r.getString("source_version"),contract,r.getString("focus"),id,roundId,requested),session,r.getString("target_version"),generationStatus,helped,reviewed,r.getInt("round_number"),attempts(id));
                }).optional().orElseThrow(()->new AccountException(404,"다음 훈련 기록을 찾을 수 없어요."));
    }
    private List<Attempt> attempts(UUID id){
        return jdbc.sql("SELECT a.*,t.problem_version,p.review_hold FROM practice_followup_attempt a JOIN training_session t ON t.id=a.session_id JOIN problem_version p ON p.id=t.problem_version WHERE a.followup_id=? ORDER BY a.round_number DESC")
                .param(id).query((r,n)->new Attempt(r.getInt("round_number"),r.getObject("session_id",UUID.class),r.getString("problem_version"),r.getBoolean("review_hold")?"HELD":r.getObject("reviewed_submission_id",UUID.class)==null?"NEEDS_PRACTICE":r.getBoolean("used_help")?"AC_WITH_HELP":"SELF_REPORTED_UNASSISTED_AC",r.getObject("used_help",Boolean.class),r.getObject("reviewed_submission_id",UUID.class))).list();
    }
    private void requireRound(View saved,int round){
        if(saved.round()!=round)throw new AccountException(409,"다른 시도로 넘어갔어요. 최신 훈련 기록을 확인해 주세요.");
    }
    private UUID roundId(UUID id){return jdbc.sql("SELECT round_id FROM practice_followup WHERE id=?").param(id).query(UUID.class).single();}
    @Transactional
    View repeat(String username,UUID id,int expectedRound){
        UUID owner=submissions.owner(username,true);var saved=view(owner,id);
        // A lost repeat response reuses the already-created next round, never creates a third one.
        if(saved.round()==expectedRound+1)return saved;
        requireRound(saved,expectedRound);
        if(!List.of("NEEDS_PRACTICE","AC_WITH_HELP","SELF_REPORTED_UNASSISTED_AC").contains(saved.status()))
            throw new AccountException(409,"진행 중인 채점과 훈련 확인을 마친 뒤 다시 연습할 수 있어요. 검토 중인 문제는 보류합니다.");
        jdbc.sql("INSERT INTO practice_followup_attempt (followup_id,round_number,session_id,generation_id,reviewed_submission_id,used_help,reviewed_at) SELECT id,round_number,session_id,CASE WHEN generation_requested THEN round_id ELSE NULL END,reviewed_submission_id,used_help,reviewed_at FROM practice_followup WHERE id=?")
                .param(id).update();
        jdbc.sql("UPDATE practice_followup SET round_number=round_number+1,round_id=?,session_id=NULL,generation_requested=false,reviewed_submission_id=NULL,used_help=NULL,reviewed_at=NULL WHERE id=?")
                .param(UUID.randomUUID()).param(id).update();
        return view(owner,id);
    }
    private UUID latestAc(UUID session){
        return jdbc.sql("SELECT s.id,j.verdict,j.status FROM submission s JOIN judge_job j ON j.submission_id=s.id WHERE s.training_session_id=? AND s.run_input IS NULL ORDER BY s.created_at DESC,s.id DESC LIMIT 1")
                .param(session).query((r,n)->("AC".equals(r.getString(2))&&"FINISHED".equals(r.getString(3)))?r.getObject(1,UUID.class):null).optional().orElse(null);
    }
    @Transactional
    View start(String username,UUID id,String version){return start(username,id,version,1);}
    @Transactional
    View start(String username,UUID id,String version,int round){
        UUID owner=submissions.owner(username,true);var saved=view(owner,id);requireRound(saved,round);
        if(saved.sessionId()!=null){if(!Objects.equals(version,saved.problemVersion()))throw new AccountException(409,"이미 시작한 훈련의 문제와 달라요.");return saved;}
        if(saved.candidates().stream().noneMatch(p->p.version().equals(version)))throw new AccountException(409,"현재 사용할 수 있는 추천 문제를 다시 확인해 주세요.");
        String goal="다시 연습: "+saved.goal();
        UUID sessionId=roundId(id);
        training.start(username,sessionId,new TrainingSessionController.Start(version,goal.substring(0,Math.min(120,goal.length()))));
        jdbc.sql("UPDATE practice_followup SET session_id=? WHERE id=?").param(sessionId).param(id).update();return view(owner,id);
    }
    @Transactional
    View generate(String username,UUID id){return generate(username,id,1);}
    @Transactional
    View generate(String username,UUID id,int round){
        UUID owner=submissions.owner(username,true);var saved=view(owner,id);requireRound(saved,round);
        if(saved.status().equals("HELD"))throw new AccountException(409,"문제 검토 중에는 다음 훈련을 생성할 수 없어요.");
        if(saved.generationStatus()!=null)return saved;
        if(saved.sessionId()!=null)throw new AccountException(409,"이미 훈련을 시작했어요.");
        String contract=jdbc.sql("SELECT template_id FROM practice_followup WHERE id=?").param(id).query(String.class).optional().orElse(null);
        UUID generationId=roundId(id);
        if(contract!=null){
            generation.create(username,generationId,contract,saved.focus(),saved.analysisId());
            var context=JudgeJson.JSON.createObjectNode().put("summary","사용자가 확인한 다음 연습 목표: "+saved.goal());
            context.putArray("nextSteps").add(saved.goal());
            jdbc.sql("UPDATE generation_job SET learning_context_json=? WHERE id=?").param(context.toString()).param(generationId).update();
        }
        else {
            String origin=jdbc.sql("SELECT p.package_json FROM practice_followup f JOIN problem_version p ON p.id=f.source_version WHERE f.id=?").param(id).query(String.class).single();
            String request="다음 학습 목표를 다른 상황에서 연습할 새 문제를 작성해 주세요. 목표: "+saved.goal()+"\n이전 문제의 공개 명세(코드나 정답이 아님): "+JudgeJson.parse(origin).path("statement").asText();
            if(saved.goal().length()>1500)throw new AccountException(409,"목표가 길어 자유 출제 입력 한도를 넘어요. 생성 화면에서 목표를 요약해 요청해 주세요.");
            drafts.create(username,generationId,request.length()>2000?request.substring(0,1985)+" (공개 명세 일부)":request);
        }
        jdbc.sql("UPDATE practice_followup SET generation_requested=true WHERE id=?").param(id).update();return view(owner,id);
    }
    @Transactional
    View reflect(String username,UUID id,boolean usedHelp){return reflect(username,id,usedHelp,1);}
    @Transactional
    View reflect(String username,UUID id,boolean usedHelp,int round){
        UUID owner=submissions.owner(username,true);var saved=view(owner,id);requireRound(saved,round);
        if(saved.status().equals("HELD"))throw new AccountException(409,"검토 중인 문제는 학습 확인을 보류해요.");
        if(saved.reviewedSubmissionId()!=null){if(!Objects.equals(saved.usedHelp(),usedHelp))throw new AccountException(409,"이미 저장한 확인과 달라요.");return saved;}
        if(!saved.status().equals("AWAITING_REFLECTION"))throw new AccountException(409,"훈련을 마치고 마지막 정식 제출의 정답 판정을 기다려 주세요.");
        jdbc.sql("UPDATE practice_followup SET reviewed_submission_id=?,used_help=?,reviewed_at=CURRENT_TIMESTAMP WHERE id=?")
                .param(latestAc(saved.sessionId())).param(usedHelp).param(id).update();return view(owner,id);
    }
}
