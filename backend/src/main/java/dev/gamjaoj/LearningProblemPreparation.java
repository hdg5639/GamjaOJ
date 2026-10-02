package dev.gamjaoj;

import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable opt-in work: accessible catalog first, one existing generation pipeline otherwise. */
@Service
class LearningProblemPreparation {
    private final JdbcClient jdbc;
    private final Submissions submissions;
    private final DiagnosticPlans plans;
    private final GenerationSpecDrafts drafts;
    LearningProblemPreparation(JdbcClient jdbc,Submissions submissions,DiagnosticPlans plans,GenerationSpecDrafts drafts) {
        this.jdbc=jdbc;this.submissions=submissions;this.plans=plans;this.drafts=drafts;
    }
    record State(String status,String message,String problemVersion) {}
    State state(UUID id) {
        return jdbc.sql("SELECT status,message,problem_version FROM learning_problem_preparation WHERE plan_id=?").param(id)
            .query((r,n)->new State(r.getString(1),r.getString(2),r.getString(3))).optional().orElse(null);
    }
    void enroll(UUID id) {
        if(state(id)==null)jdbc.sql("INSERT INTO learning_problem_preparation(plan_id) VALUES (?)").param(id).update();
    }
    @Transactional
    public State prepare(String username,UUID id,boolean retry) {
        UUID owner=submissions.owner(username,true);var plan=plans.view(username,owner,id);
        if(!"READY".equals(plan.status())||plan.sessionId()!=null) {
            jdbc.sql("UPDATE learning_problem_preparation SET updated_at=CURRENT_TIMESTAMP WHERE plan_id=?").param(id).update();return state(id);
        }
        enroll(id);var saved=state(id);
        if("FAILED".equals(saved.status())&&!retry)return saved;
        var options=plans.options(username,plan.evaluationId(),plan.observationIndex(),plan.sourceKind());
        var available=options.problems().stream().filter(p->!p.problemHeld()&&p.submissionsEnabled()).toList();
        if(plan.generationId()!=null) {
            if(plan.generationStatus()!=null&&(plan.generationStatus().contains("FAILED")||plan.generationStatus().contains("REJECTED")||"NEEDS_REVIEW".equals(plan.generationStatus()))) {
                save(id,"FAILED","문제 생성·검증을 통과하지 못했어요. 상세 결과를 확인해 주세요.",null);return state(id);
            }
            if("PUBLISHED".equals(plan.generationStatus())&&plan.generatedVersion()==null) {
                save(id,"FAILED","게시된 문제가 보류되었거나 접근할 수 없어요. 상세 결과를 확인해 주세요.",null);return state(id);
            }
            // Resume the exact stored draft through every existing check, never a second paid job.
            if(drafts.contains(plan.generationId())) {
                var draft=drafts.view(username,plan.generationId());
                if(draft.status().contains("FAILED")||draft.status().contains("REJECTED")||"NEEDS_REVIEW".equals(draft.status())) {
                    save(id,"FAILED",draft.error()==null?"생성·검증을 통과하지 못했어요. 상세 결과를 확인해 주세요.":draft.error(),null);return state(id);
                }
                if(List.of("DRAFT_READY","CHECKED","REVIEW_CHECKED").contains(draft.status())&&busy(owner)) {save(id,"GENERATING","다른 출제 작업이 끝나면 자동으로 이어져요.",null);return state(id);}
                switch(draft.status()) {
                    case "DRAFT_READY" -> drafts.build(username,draft.id(),draft.specHash());
                    case "CHECKED" -> drafts.review(username,draft.id(),draft.specHash());
                    case "REVIEW_CHECKED" -> drafts.publish(username,draft.id(),draft.specHash());
                    default -> {}
                }
            }
            save(id,"PUBLISHED".equals(plan.generationStatus())?"MAPPED":"GENERATING",null,plan.generatedVersion());return state(id);
        }
        if(saved.problemVersion()!=null&&available.stream().anyMatch(p->saved.problemVersion().equals(p.version()))) {
            save(id,"MAPPED",null,saved.problemVersion());return state(id);
        }
        var used=new HashSet<>(jdbc.sql("SELECT t.problem_version FROM diagnostic_practice_plan p JOIN training_session t ON t.id=p.training_session_id WHERE p.user_id=? AND p.evaluation_id=? UNION SELECT w.problem_version FROM learning_problem_preparation w JOIN diagnostic_practice_plan p ON p.id=w.plan_id WHERE p.user_id=? AND p.evaluation_id=? AND w.plan_id<>? AND w.problem_version IS NOT NULL")
            .param(owner).param(plan.evaluationId()).param(owner).param(plan.evaluationId()).param(id).query(String.class).list());
        var candidate=match(plan,options,available,used);
        if(candidate!=null) {save(id,"MAPPED",null,candidate.version());return state(id);}
        // Admission retains the existing one-job-per-owner guard. Waiting goals retry after it finishes.
        if(busy(owner)){save(id,"WAITING","다른 출제 작업이 끝나면 자동으로 시작해요.",null);return state(id);}
        plans.generate(username,id);
        save(id,"GENERATING",null,null);
        return state(id);
    }
    private boolean busy(UUID owner) {
        return drafts.active(owner)||HybridAdmission.active(jdbc,owner)||jdbc.sql("SELECT count(*) FROM generation_job WHERE owner_id=? AND status IN ('QUEUED','GENERATING','AWAITING_REVIEW','VALIDATING')").param(owner).query(Integer.class).single()>0;
    }
    @Transactional
    public void failed(String username,UUID id,String message) {
        UUID owner=submissions.owner(username,true);plans.view(username,owner,id);save(id,"FAILED",message,null);
    }
    private void save(UUID id,String status,String message,String version) {
        jdbc.sql("UPDATE learning_problem_preparation SET status=?,message=?,problem_version=?,updated_at=CURRENT_TIMESTAMP WHERE plan_id=?")
            .param(status).param(message).param(version).param(id).update();
    }
    private static final Map<String,List<String>> FAMILIES=Map.ofEntries(
        Map.entry("implementation",List.of("구현","시뮬레이션")),
        Map.entry("arrays-strings",List.of("배열·문자열","배열","문자열","수열","누적 합","두 포인터","슬라이딩 윈도")),
        Map.entry("basic-data-structures",List.of("기초 자료구조","자료구조","스택","큐","덱","힙","해시")),
        Map.entry("basic-search",List.of("기초 탐색","탐색","완전 탐색","너비 우선 탐색","깊이 우선 탐색")),
        Map.entry("bfs",List.of("너비 우선 탐색")),Map.entry("dfs",List.of("깊이 우선 탐색")),
        Map.entry("dp",List.of("동적 계획법")),Map.entry("binary-search",List.of("이분 탐색")),
        Map.entry("greedy",List.of("탐욕법")),Map.entry("graph",List.of("그래프·최단 경로","그래프","최단 경로","다익스트라")),
        Map.entry("mst",List.of("최소 신장 트리")));
    // For a basic self-report, family + EASY is the explicit goal. Code goals additionally require topic evidence.
    static Submissions.Problem match(DiagnosticPlans.Plan plan,DiagnosticPlans.Options options,List<Submissions.Problem> problems,Set<String> used) {
        String category=options.category();if(category==null)return null;
        boolean basic="SELF_REPORT".equals(plan.sourceKind());
        String goal=normalize(plan.goal()+" "+options.observation().path("pattern").asText()+" "+options.observation().path("recommendation").asText());
        var topics=List.of("bfs","dfs","오버플로","자료형","정수 범위","누적합","누적 합","투 포인터","슬라이딩","스택","큐","덱","해시","괄호","방문","최단","경계","이분","이진","재귀","백트래킹","배낭","점화식","메모이제이션","정렬","그리디","탐욕","유니온","크루스칼","프림","문자열","입력");
        var focus=topics.stream().map(LearningProblemPreparation::normalize).filter(goal::contains).distinct().toList();
        if(!basic&&focus.isEmpty())return null;
        return problems.stream().filter(p->!p.problemHeld()&&p.submissionsEnabled()&&!used.contains(p.version())&&!"SOLVED".equals(p.solveStatus())&&p.pendingSubmissions()==0)
            .filter(p->FAMILIES.getOrDefault(category,List.of()).stream().anyMatch(name->normalize(name).equals(normalize(p.category()))))
            .filter(p->"EASY".equals(p.difficulty())||(!basic&&"MEDIUM".equals(p.difficulty())))
            .filter(p->basic||focus.stream().allMatch(normalize(p.title()+" "+String.join(" ",p.tags())+" "+p.statement())::contains))
            .sorted(Comparator.comparingInt((Submissions.Problem p)->"EASY".equals(p.difficulty())?0:1).thenComparing(Submissions.Problem::version)).findFirst().orElse(null);
    }
    static String normalize(String value){return value.toLowerCase(Locale.ROOT).replaceAll("\\s+","").replace("투포인터","두포인터").replace("너비우선탐색","bfs").replace("깊이우선탐색","dfs");}
    List<Object[]> pending() {
        return jdbc.sql("SELECT u.username,w.plan_id FROM learning_problem_preparation w JOIN diagnostic_practice_plan p ON p.id=w.plan_id JOIN app_user u ON u.id=p.user_id WHERE (w.status IN ('WAITING','GENERATING') OR (w.status='MAPPED' AND (w.problem_version IS NULL OR w.updated_at<CURRENT_TIMESTAMP-INTERVAL '30' SECOND))) AND p.training_session_id IS NULL AND NOT EXISTS (SELECT 1 FROM diagnostic_practice_plan n WHERE n.previous_plan_id=p.id) ORDER BY w.updated_at,w.plan_id LIMIT 50")
            .query((r,n)->new Object[]{r.getString(1),r.getObject(2,UUID.class)}).list();
    }
}
