package dev.gamjaoj;

import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

@RestController
class MyActivity {
    private final JdbcClient jdbc;private final Submissions submissions;
    MyActivity(JdbcClient jdbc,Submissions submissions){this.jdbc=jdbc;this.submissions=submissions;}
    record Problem(String version,String title,long attempts,long accepted,OffsetDateTime lastSubmitted,boolean held,boolean diagnostic,String category,String confidence,String reflectionNote) {}
    record Summary(long submitted,long attemptedProblems,long solvedProblems) {}
    record Page(List<Problem> items,long total) {}
    private static final String FILTER="s.user_id=? AND s.run_input IS NULL AND s.generation_job_id IS NULL AND s.spec_draft_id IS NULL AND s.hybrid_branch_id IS NULL";
    @GetMapping("/api/my/summary") Summary summary(Principal user){
        var owner=submissions.owner(user.getName(),false);
        return jdbc.sql("SELECT count(*) submitted,count(DISTINCT s.problem_version) attempted,count(DISTINCT CASE WHEN j.verdict='AC' AND p.review_hold=false THEN s.problem_version END) solved FROM submission s JOIN judge_job j ON j.submission_id=s.id JOIN problem_version p ON p.id=s.problem_version WHERE "+FILTER)
                .param(owner).query((r,n)->new Summary(r.getLong(1),r.getLong(2),r.getLong(3))).single();
    }
    @GetMapping("/api/my/growth") GrowthLevels.Growth growth(Principal user){
        var owner=submissions.owner(user.getName(),false);
        var solved=jdbc.sql("SELECT p.id,CASE WHEN p.catalog_category IS NOT NULL THEN p.catalog_category WHEN p.id IN ('sum-v1','total-v1') THEN '수열' WHEN p.id='valid-parentheses-v1' THEN '문자열' ELSE '미분류' END category,t.layer,t.source FROM problem_version p "+ThinkingDifficulty.JOIN+" WHERE p.ready=true AND p.review_hold=false AND p.diagnostic_only=false AND (p.owner_id IS NULL OR p.owner_id=? OR p.shared=true) AND EXISTS (SELECT 1 FROM submission s JOIN judge_job j ON j.submission_id=s.id WHERE s.problem_version=p.id AND "+FILTER+" AND s.diagnostic_item_id IS NULL AND s.example_check=false AND j.status='FINISHED' AND j.verdict='AC')")
            .param(owner).param(owner).query((r,n)->new GrowthLevels.Solved(r.getString(1),ProblemCategories.display(r.getString(2)),r.getObject(3,Integer.class),r.getString(4))).list();
        return GrowthLevels.calculate(solved);
    }
    @GetMapping("/api/my/problems") Page problems(Principal user,@RequestParam(defaultValue="0") int page){
        if(page<0||page>100000)throw new AccountException(400,"잘못된 페이지예요.");
        var owner=submissions.owner(user.getName(),false);
        long count=jdbc.sql("SELECT count(DISTINCT s.problem_version) FROM submission s WHERE "+FILTER).param(owner).query(Long.class).single();
        var items=jdbc.sql("SELECT p.id,p.package_json,p.review_hold,p.diagnostic_only,count(*) attempts,sum(CASE WHEN j.verdict='AC' THEN 1 ELSE 0 END) accepted,max(s.created_at) latest,p.catalog_category,max(r.confidence) confidence,max(r.note) reflection_note FROM submission s JOIN judge_job j ON j.submission_id=s.id JOIN problem_version p ON p.id=s.problem_version LEFT JOIN problem_reflection r ON r.user_id=s.user_id AND r.problem_version=p.id WHERE "+FILTER+" GROUP BY p.id,p.package_json,p.review_hold,p.diagnostic_only,p.catalog_category ORDER BY latest DESC,p.id DESC LIMIT 20 OFFSET ?")
                .param(owner).param(page*20).query((r,n)->new Problem(r.getString(1),ProblemTitles.display(JudgeJson.parse(r.getString(2))),r.getLong(5),r.getLong(6),r.getObject(7,OffsetDateTime.class),r.getBoolean(3),r.getBoolean(4),ProblemCategories.display(r.getString(8)),r.getBoolean(3)?null:r.getString(9),r.getBoolean(3)?null:r.getString(10))).list();
        return new Page(items,count);
    }
}
