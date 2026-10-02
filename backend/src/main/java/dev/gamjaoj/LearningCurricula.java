package dev.gamjaoj;

import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Saved learning goals, not generated questions or a model judgement of mastery. */
@Service
public class LearningCurricula {
    private final JdbcClient jdbc;
    private final Submissions submissions;
    private final DiagnosticEvaluations evaluations;
    private final DiagnosticPlans plans;
    private final LearningProblemPreparation preparation;
    LearningCurricula(JdbcClient jdbc,Submissions submissions,DiagnosticEvaluations evaluations,DiagnosticPlans plans,LearningProblemPreparation preparation) {
        this.jdbc=jdbc;this.submissions=submissions;this.evaluations=evaluations;this.plans=plans;this.preparation=preparation;
    }
    private static final Map<String,String> CATEGORIES=Map.ofEntries(
        Map.entry("implementation","구현"),Map.entry("arrays-strings","배열·문자열"),Map.entry("basic-data-structures","기초 자료구조"),Map.entry("basic-search","기초 탐색"),
        Map.entry("bfs","너비 우선 탐색"),Map.entry("dfs","깊이 우선 탐색"),Map.entry("backtracking","백트래킹"),Map.entry("dp","동적 계획법"),
        Map.entry("binary-search","이분 탐색"),Map.entry("greedy","탐욕법"),Map.entry("graph","그래프·최단 경로"),Map.entry("mst","최소 신장 트리"));
    static String categoryLabel(String category){return category==null?"기초 개념":CATEGORIES.getOrDefault(category,"기초 개념");}
    public record Created(UUID evaluationId,List<DiagnosticPlans.Plan> plans,int manualReviewCount) {}
    public record Candidate(String version,String title,String category,String difficulty,ThinkingDifficulty.Profile thinking) {}
    public record Progress(int submissions,int accepted,int pending,String latestVerdict) {}
    public record Step(DiagnosticPlans.Plan plan,String category,String basis,String problemTitle,Candidate candidate,Progress progress,LearningProblemPreparation.State preparation) {}
    public record Track(UUID evaluationId,UUID diagnosticSessionId,String bankId,OffsetDateTime createdAt,List<Step> steps,int manualReviewCount) {}

    @Transactional
    public Created create(String username,UUID key,UUID evaluationId) {
        UUID owner=submissions.owner(username,true); // Serialize duplicate clicks/tabs and manual confirmations for this owner.
        var previous=jdbc.sql("SELECT user_id,evaluation_id,result_json FROM learning_curriculum_request WHERE id=?").param(key)
            .query((r,n)->new Object[]{r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3)}).optional();
        if(previous.isPresent()) {
            var saved=previous.get();
            if(!owner.equals(saved[0])||!evaluationId.equals(saved[1]))throw new AccountException(409,"같은 요청 키로 다른 계획을 만들 수 없어요.");
            var result=JudgeJson.parse((String)saved[2]);
            var ids=new ArrayList<DiagnosticPlans.Plan>();for(var id:result.path("planIds"))ids.add(plans.view(username,owner,UUID.fromString(id.asText())));
            ids.forEach(p->preparation.enroll(p.id()));
            return new Created(evaluationId,List.copyOf(ids),result.path("manualReviewCount").asInt());
        }
        var evaluation=evaluations.detail(username,evaluationId);
        if(!DiagnosticPlans.basicAvailable(evaluation)||jdbc.sql("SELECT count(*) FROM diagnostic_session WHERE user_id=? AND status<>'COMPLETED'").param(owner).query(Integer.class).single()>0)
            throw new AccountException(409,"진단 종료와 근거 확인이 끝난 뒤 맞춤 계획을 만들 수 있어요.");
        var created=new ArrayList<DiagnosticPlans.Plan>();int manual=0;
        var savedPlans=plans.list(username,evaluationId);
        var seenCategories=new HashSet<String>();
        var facts=evaluation.facts().path("items");
        for(int index=0;index<facts.size();index++) {
            var item=facts.get(index);
            if("NOT_SURE".equals(item.path("skipReason").asText())&&!item.path("externallySeen").asBoolean()&&"SKIPPED".equals(item.path("status").asText())&&seenCategories.add(item.path("category").asText()))
                created.add(confirmDefault(username,evaluationId,index,"SELF_REPORT",savedPlans));
        }
        if("COMPLETED".equals(evaluation.status())&&evaluation.interpretation()!=null) {
            var observations=evaluation.interpretation().path("observations");
            // A strength, uncertain hypothesis or ASSESS proposal is never converted into a deficit.
            for(String tone:List.of("RISK","WATCH"))for(int index=0;index<observations.size();index++) {
                var o=observations.get(index);
                if(!tone.equals(o.path("tone").asText())||!"SUPPORTED".equals(o.path("confidence").asText())||!"PRACTICE".equals(o.path("nextAction").asText()))continue;
                final int position=index;
                if(evaluation.corrections().stream().anyMatch(c->c.observationIndex()==position)){manual++;continue;}
                created.add(confirmDefault(username,evaluationId,index,"CODE_OBSERVATION",savedPlans));
            }
        }
        if(created.isEmpty())throw new AccountException(409,manual>0?"정정 의견이 있는 제안은 수동으로 확인하고 목표를 정해 주세요.":"바로 만들 수 있는 보완 목표가 없어요. 수동 계획이나 다른 분야의 진단을 선택해 주세요.");
        var result=JudgeJson.JSON.createObjectNode().put("manualReviewCount",manual);var ids=result.putArray("planIds");created.forEach(p->ids.add(p.id().toString()));
        jdbc.sql("INSERT INTO learning_curriculum_request(id,user_id,evaluation_id,result_json) VALUES (?,?,?,?)")
            .param(key).param(owner).param(evaluationId).param(JudgeJson.canonical(result)).update();
        created.forEach(p->preparation.enroll(p.id()));
        return new Created(evaluationId,List.copyOf(created),manual);
    }
    private DiagnosticPlans.Plan confirmDefault(String username,UUID evaluation,int index,String kind,List<DiagnosticPlans.Plan> saved) {
        var options=plans.options(username,evaluation,index,kind);
        // Preserve the user's existing goal and rounds rather than creating a parallel default goal.
        for(var plan:saved)if(plan.observationIndex()==index&&kind.equals(plan.sourceKind())&&plan.previousPlanId()==null&&!List.of("HELD","NEEDS_REVIEW").contains(plan.status()))return plan;
        String goal=options.observation().path("recommendation").asText().strip();
        if(goal.isEmpty())goal=options.observation().path("pattern").asText().strip();
        if(goal.isEmpty())throw new AccountException(409,"연습 목표가 없는 제안은 수동으로 확인해 주세요.");
        if(goal.length()>120){int end=119;if(Character.isHighSurrogate(goal.charAt(end-1)))end--;goal=goal.substring(0,end)+"…";}
        return plans.confirm(username,UUID.randomUUID(),evaluation,index,options.reviewHash(),goal,kind);
    }
    @Transactional
    public List<Track> overview(String username) {
        UUID owner=submissions.owner(username,true);
        var evaluationsWithPlans=jdbc.sql("SELECT evaluation_id FROM diagnostic_practice_plan WHERE user_id=? GROUP BY evaluation_id ORDER BY MAX(created_at) DESC,evaluation_id")
            .param(owner).query(UUID.class).list();
        var problems=submissions.problems(username);
        var tracks=new ArrayList<Track>();
        for(var evaluationId:evaluationsWithPlans) {
            var evaluation=evaluations.detail(username,evaluationId);
            var metadata=jdbc.sql("SELECT d.bank_id,d.created_at FROM diagnostic_session d WHERE d.id=?").param(evaluation.sessionId())
                .query((r,n)->new Object[]{r.getString(1),r.getObject(2,OffsetDateTime.class)}).single();
            var all=plans.list(username,evaluationId);var steps=new ArrayList<Step>();
            for(var root:all) {
                if(root.previousPlanId()!=null)continue;
                var plan=root;
                while(true){final UUID id=plan.id();var next=all.stream().filter(p->id.equals(p.previousPlanId())).findFirst();if(next.isEmpty())break;plan=next.get();}
                String category=null;Candidate candidate=null;Progress progress=null;String title=null;
                if(!"HELD".equals(plan.status())) {
                    var options=plans.options(username,evaluationId,plan.observationIndex(),plan.sourceKind());category=options.category();
                    if(plan.problemVersion()!=null){final String version=plan.problemVersion();title=problems.stream().filter(p->version.equals(p.version())).map(Submissions.Problem::title).findFirst().orElse("목록에 없는 문제");}
                    if(plan.generatedVersion()!=null){final String generated=plan.generatedVersion();candidate=problems.stream().filter(p->generated.equals(p.version())&&!p.problemHeld()&&p.submissionsEnabled()).map(p->new Candidate(p.version(),p.title(),p.category(),p.difficulty(),p.thinking())).findFirst().orElse(null);}
                    var mapping=preparation.state(plan.id());
                    if(candidate==null&&"READY".equals(plan.status())&&mapping!=null&&mapping.problemVersion()!=null) {
                        final String version=mapping.problemVersion();candidate=problems.stream().filter(p->version.equals(p.version())&&!p.problemHeld()&&p.submissionsEnabled()).map(p->new Candidate(p.version(),p.title(),p.category(),p.difficulty(),p.thinking())).findFirst().orElse(null);
                    }
                    // Unenrolled manual plans retain a catalog preview without scheduling model work.
                    if(candidate==null&&"READY".equals(plan.status())&&mapping==null&&plan.generationId()==null) {
                        var matched=LearningProblemPreparation.match(plan,options,problems,Set.of());
                        if(matched!=null)candidate=new Candidate(matched.version(),matched.title(),matched.category(),matched.difficulty(),matched.thinking());
                    }
                    if(plan.sessionId()!=null) {
                        var session=jdbc.sql("SELECT (SELECT count(*) FROM submission WHERE training_session_id=t.id AND run_input IS NULL) AS submissions,(SELECT count(*) FROM submission s JOIN judge_job j ON j.submission_id=s.id WHERE s.training_session_id=t.id AND s.run_input IS NULL AND j.verdict='AC') AS accepted,(SELECT count(*) FROM submission s JOIN judge_job j ON j.submission_id=s.id WHERE s.training_session_id=t.id AND j.status<>'FINISHED') AS pending FROM training_session t WHERE t.id=? AND t.user_id=?")
                            .param(plan.sessionId()).param(owner).query((r,n)->new int[]{r.getInt(1),r.getInt(2),r.getInt(3)}).single();
                        String verdict=jdbc.sql("SELECT j.verdict FROM submission s JOIN judge_job j ON j.submission_id=s.id WHERE s.training_session_id=? AND s.user_id=? AND s.run_input IS NULL ORDER BY s.created_at DESC,s.id DESC LIMIT 1")
                            .param(plan.sessionId()).param(owner).query((r,n)->r.getString(1)).list().stream().filter(Objects::nonNull).findFirst().orElse(null);
                        progress=new Progress(session[0],session[1],session[2],verdict);
                    }
                }
                steps.add(new Step(plan,category,plan.sourceKind(),title,candidate,progress,preparation.state(plan.id())));
            }
            var correctedGoals=new HashSet<Integer>();
            if("COMPLETED".equals(evaluation.status())&&evaluation.interpretation()!=null)for(var correction:evaluation.corrections()) {
                var observation=evaluation.interpretation().path("observations").path(correction.observationIndex());
                if(List.of("RISK","WATCH").contains(observation.path("tone").asText())&&"SUPPORTED".equals(observation.path("confidence").asText())&&"PRACTICE".equals(observation.path("nextAction").asText()))correctedGoals.add(correction.observationIndex());
            }
            tracks.add(new Track(evaluationId,evaluation.sessionId(),(String)metadata[0],(OffsetDateTime)metadata[1],List.copyOf(steps),correctedGoals.size()));
        }
        return List.copyOf(tracks);
    }
}
