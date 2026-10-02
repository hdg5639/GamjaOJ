package dev.gamjaoj;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Personal observations, not an inferred skill score. Reads never enqueue model work. */
@RestController
class LearningProgress {
    static final ZoneId ZONE=ZoneId.of("Asia/Seoul");
    static final String FORMAL="s.user_id=? AND s.run_input IS NULL AND s.generation_job_id IS NULL AND s.spec_draft_id IS NULL AND s.hybrid_branch_id IS NULL";
    private final JdbcClient jdbc;
    private final Submissions submissions;
    LearningProgress(JdbcClient jdbc,Submissions submissions){this.jdbc=jdbc;this.submissions=submissions;}
    record Day(LocalDate date,int solved) {}
    record Category(String category,int attempted,int solved,int available) {}
    record Suggestion(String version,String title,String category,String difficulty,String reason,String confidence) {}
    record Dashboard(LocalDate start,LocalDate end,String timezone,List<Day> days,int activeDays,int currentStreak,int longestStreak,
                     int practicedProblems,String dominantCategory,List<Category> categories,List<Suggestion> explore,List<Suggestion> revisit) {}
    record Reflection(String problemVersion,UUID submissionId,UUID latestAcceptedSubmissionId,String confidence,String note,OffsetDateTime updatedAt) {}
    record ReflectionRequest(@NotNull UUID submissionId,@Pattern(regexp="SOLID|SHAKY|REVISIT") String confidence,@NotNull @Size(max=500) String note) {}
    private record Candidate(String version,String title,String category,String difficulty) {}
    private record Attempt(String version,String category,boolean accepted) {}

    @GetMapping("/api/my/learning") Dashboard learning(Principal user){return dashboard(user.getName(),LocalDate.now(ZONE));}
    Dashboard dashboard(String username,LocalDate today){
        UUID owner=submissions.owner(username,false);LocalDate start=today.minusDays(364);
        var since=start.atStartOfDay(ZONE).toOffsetDateTime();var until=today.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();
        var activity=jdbc.sql("SELECT s.created_at,s.problem_version FROM submission s JOIN judge_job j ON j.submission_id=s.id JOIN problem_version p ON p.id=s.problem_version WHERE "+FORMAL+" AND p.diagnostic_only=false AND p.review_hold=false AND j.status='FINISHED' AND j.verdict='AC' AND s.created_at>=? AND s.created_at<?")
                .param(owner).param(since).param(until).query((r,n)->Map.entry(r.getObject(1,OffsetDateTime.class).atZoneSameInstant(ZONE).toLocalDate(),r.getString(2))).list();
        Map<LocalDate,Set<String>> solvedByDay=new HashMap<>();
        for(var row:activity)solvedByDay.computeIfAbsent(row.getKey(),k->new HashSet<>()).add(row.getValue());
        List<Day> days=new ArrayList<>();int active=0,longest=0,run=0;
        for(LocalDate date=start;!date.isAfter(today);date=date.plusDays(1)){
            int count=solvedByDay.getOrDefault(date,Set.of()).size();days.add(new Day(date,count));
            if(count>0){active++;run++;longest=Math.max(longest,run);}else run=0;
        }
        // An unfinished today does not erase the streak completed through yesterday.
        LocalDate streakEnd=solvedByDay.containsKey(today)?today:today.minusDays(1);int streak=0;
        while(!streakEnd.isBefore(start)&&solvedByDay.containsKey(streakEnd)){streak++;streakEnd=streakEnd.minusDays(1);}
        var catalog=jdbc.sql("SELECT p.id,p.package_json,p.catalog_category,p.catalog_difficulty FROM problem_version p WHERE p.ready=true AND p.review_hold=false AND p.diagnostic_only=false AND (p.owner_id IS NULL OR p.owner_id=? OR p.shared=true) ORDER BY p.id")
                .param(owner).query((r,n)->new Candidate(r.getString(1),JudgeJson.parse(r.getString(2)).path("title").asText(),ProblemCategories.display(r.getString(3)),r.getString(4))).list();
        var attempts=jdbc.sql("SELECT s.problem_version,p.catalog_category,j.verdict FROM submission s JOIN judge_job j ON j.submission_id=s.id JOIN problem_version p ON p.id=s.problem_version WHERE "+FORMAL+" AND p.diagnostic_only=false AND p.review_hold=false AND j.status='FINISHED' AND j.verdict<>'IE' AND s.created_at>=? AND s.created_at<?")
                .param(owner).param(today.minusDays(89).atStartOfDay(ZONE).toOffsetDateTime()).param(until)
                .query((r,n)->new Attempt(r.getString(1),ProblemCategories.display(r.getString(2)),"AC".equals(r.getString(3)))).list();
        Map<String,Set<String>> tried=new TreeMap<>(),accepted=new HashMap<>();Map<String,Integer> available=new HashMap<>();
        for(var p:catalog){tried.computeIfAbsent(p.category(),k->new HashSet<>());available.merge(p.category(),1,Integer::sum);}
        for(var a:attempts){tried.computeIfAbsent(a.category(),k->new HashSet<>()).add(a.version());if(a.accepted())accepted.computeIfAbsent(a.category(),k->new HashSet<>()).add(a.version());}
        List<Category> categories=tried.entrySet().stream().map(e->new Category(e.getKey(),e.getValue().size(),accepted.getOrDefault(e.getKey(),Set.of()).size(),available.getOrDefault(e.getKey(),0)))
                .sorted(Comparator.comparingInt(Category::attempted).reversed().thenComparing(Category::category)).toList();
        int total=categories.stream().mapToInt(Category::attempted).sum();
        String dominant=total>=5&&!categories.isEmpty()&&categories.getFirst().attempted()*100L>=total*60L?categories.getFirst().category():null;
        Set<String> solved=new HashSet<>(jdbc.sql("SELECT DISTINCT s.problem_version FROM submission s JOIN judge_job j ON j.submission_id=s.id WHERE "+FORMAL+" AND j.status='FINISHED' AND j.verdict='AC'").param(owner).query(String.class).list());
        List<Suggestion> explore=new ArrayList<>();Set<String> selectedCategories=new HashSet<>();
        var candidates=catalog.stream().filter(p->!solved.contains(p.version()))
                .sorted(Comparator.comparingInt((Candidate p)->tried.getOrDefault(p.category(),Set.of()).size()).thenComparingInt(p->difficulty(p.difficulty())).thenComparing(Candidate::version)).toList();
        for(var p:candidates){
            if(!selectedCategories.add(p.category()))continue;
            int count=tried.getOrDefault(p.category(),Set.of()).size();
            explore.add(new Suggestion(p.version(),p.title(),p.category(),p.difficulty(),count==0?"최근 90일 동안 도전하지 않은 분야예요.":"최근 90일에 이 분야의 "+count+"문제에 도전했어요. 다른 유형도 함께 연습해 봐요.",null));
            if(explore.size()==4)break;
        }
        var revisit=jdbc.sql("SELECT p.id,p.package_json,p.catalog_category,p.catalog_difficulty,r.confidence FROM problem_reflection r JOIN problem_version p ON p.id=r.problem_version WHERE r.user_id=? AND r.confidence IN ('SHAKY','REVISIT') AND p.ready=true AND p.review_hold=false AND p.diagnostic_only=false AND (p.owner_id IS NULL OR p.owner_id=? OR p.shared=true) ORDER BY CASE WHEN r.confidence='REVISIT' THEN 0 ELSE 1 END,r.updated_at,p.id LIMIT 3")
                .param(owner).param(owner).query((r,n)->new Suggestion(r.getString(1),JudgeJson.parse(r.getString(2)).path("title").asText(),ProblemCategories.display(r.getString(3)),r.getString(4),"REVISIT".equals(r.getString(5))?"다시 풀어야 한다고 남긴 문제예요.":"조금 애매하다고 남긴 문제예요.",r.getString(5))).list();
        return new Dashboard(start,today,ZONE.getId(),days,active,streak,longest,total,dominant,categories,explore,revisit);
    }
    private static int difficulty(String value){return "EASY".equals(value)?0:"MEDIUM".equals(value)?1:2;}
    @GetMapping("/api/my/reflections") Reflection reflection(Principal user,@RequestParam String problemVersion){return find(submissions.owner(user.getName(),false),problemVersion);}
    private Reflection find(UUID owner,String version){
        UUID latest=jdbc.sql("SELECT s.id FROM submission s JOIN judge_job j ON j.submission_id=s.id JOIN problem_version p ON p.id=s.problem_version WHERE "+FORMAL+" AND s.problem_version=? AND p.diagnostic_only=false AND p.review_hold=false AND j.status='FINISHED' AND j.verdict='AC' ORDER BY s.created_at DESC,s.id DESC LIMIT 1")
                .param(owner).param(version).query(UUID.class).optional().orElseThrow(()->new AccountException(404,"정답을 맞힌 일반 문제의 기록을 선택해 주세요."));
        return jdbc.sql("SELECT submission_id,confidence,note,updated_at FROM problem_reflection WHERE user_id=? AND problem_version=?")
                .param(owner).param(version).query((r,n)->new Reflection(version,r.getObject(1,UUID.class),latest,r.getString(2),r.getString(3),r.getObject(4,OffsetDateTime.class))).optional()
                .orElse(new Reflection(version,null,latest,null,"",null));
    }
    @Transactional
    @PutMapping("/api/my/reflections") Reflection reflect(Principal user,@Valid @RequestBody ReflectionRequest request){
        UUID owner=submissions.owner(user.getName(),true);
        String version=jdbc.sql("SELECT s.problem_version FROM submission s JOIN judge_job j ON j.submission_id=s.id JOIN problem_version p ON p.id=s.problem_version WHERE "+FORMAL+" AND s.id=? AND p.diagnostic_only=false AND p.review_hold=false AND j.status='FINISHED' AND j.verdict='AC'")
                .param(owner).param(request.submissionId()).query(String.class).optional().orElseThrow(()->new AccountException(404,"본인이 정답을 맞힌 일반 문제 제출을 선택해 주세요."));
        if(request.confidence()==null){jdbc.sql("DELETE FROM problem_reflection WHERE user_id=? AND problem_version=?").param(owner).param(version).update();}
        else {
            int changed=jdbc.sql("UPDATE problem_reflection SET submission_id=?,confidence=?,note=?,updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND problem_version=?")
                    .param(request.submissionId()).param(request.confidence()).param(request.note().strip()).param(owner).param(version).update();
            if(changed==0)jdbc.sql("INSERT INTO problem_reflection(user_id,problem_version,submission_id,confidence,note) VALUES (?,?,?,?,?)")
                    .param(owner).param(version).param(request.submissionId()).param(request.confidence()).param(request.note().strip()).update();
        }
        return find(owner,version);
    }
}
