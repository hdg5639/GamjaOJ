package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

/** Judge evidence only. Never contributes to mastery, growth, or diagnostic scores. */
@RestController
class PerformanceHistory {
    private final JdbcClient jdbc;
    private final Submissions submissions;
    PerformanceHistory(JdbcClient jdbc,Submissions submissions){this.jdbc=jdbc;this.submissions=submissions;}
    record Entry(UUID submissionId,String version,String title,OffsetDateTime submittedAt,String language,
                 Long maxWallMs,Long maxMemoryBytes,JsonNode executionProfile,JsonNode comparisonProfile,String comparisonKey,String eligibility,String sourceHash) {}
    record Retry(String version,String title,String metric,double ratio,int currentSamples,int baselineSamples,String reason) {}
    record Page(List<Entry> items,int page,boolean hasMore) {}
    @GetMapping("/api/my/performance") Page history(Principal user,@RequestParam(defaultValue="0") int page){
        if(page<0||page>100000)throw new AccountException(400,"잘못된 페이지예요.");
        var rows=load(submissions.owner(user.getName(),false),21,page*20,null);
        return new Page(rows.stream().limit(20).toList(),page,rows.size()>20);
    }
    List<Entry> load(UUID owner,int limit,int offset,OffsetDateTime until){
        return jdbc.sql("SELECT s.id,s.problem_version,p.package_json,s.created_at,s.language,s.source_sha256,j.result_json,a.worker_id FROM submission s JOIN judge_job j ON j.submission_id=s.id JOIN problem_version p ON p.id=s.problem_version LEFT JOIN judge_attempt a ON a.submission_id=s.id AND a.attempt=j.attempt AND a.status='COMPLETED' WHERE "+LearningProgress.FORMAL+" AND s.example_check=false AND s.diagnostic_item_id IS NULL AND p.diagnostic_only=false AND p.review_hold=false AND p.ready=true AND (p.owner_id IS NULL OR p.owner_id=? OR p.shared=true) AND j.status='FINISHED' AND j.verdict='AC'"+(until==null?"":" AND s.created_at<?")+" ORDER BY s.created_at DESC,s.id DESC LIMIT ? OFFSET ?")
            .params(until==null?List.of(owner,owner,limit,offset):List.of(owner,owner,until,limit,offset))
            .query((r,n)->entry(r.getObject(1,UUID.class),r.getString(2),ProblemTitles.display(JudgeJson.parse(r.getString(3))),r.getObject(4,OffsetDateTime.class),r.getString(5),r.getString(6),r.getString(7),r.getString(8))).list();
    }
    static Entry entry(UUID id,String version,String title,OffsetDateTime date,String language,String source,String json,String worker){
        JsonNode report=json==null?JudgeJson.JSON.createObjectNode():JudgeJson.parse(json);
        Long wall=completeMaximum(report,"wall_ms"),memory=completeMaximum(report,"memory_peak_bytes");
        JsonNode profile=report.get("execution_profile");
        String state="COMPARABLE",key=null;JsonNode identity=null;
        if(wall==null&&memory==null)state="MISSING_METRICS";
        else if(profile==null||!profile.isObject()||profile.isEmpty()||!language.equals(report.path("language").asText())
                ||!report.path("runner_environment").isObject()||!report.path("runner_environment").path("contract").isObject()
                ||worker==null||worker.isBlank()||source==null||source.isBlank()
                ||!report.path("image").asText().contains("@sha256:")||report.path("policy").asText().isBlank()
                ||report.path("problem_sha256").asText().isBlank())state="UNKNOWN_PROFILE";
        else if(!"EXCLUSIVE".equals(report.path("execution_mode").asText()))state="SHARED_EXECUTION";
        else {
            var conditions=JudgeJson.JSON.createObjectNode();
            for(String field:List.of("problem_sha256","image","policy","language","execution_profile","runner_environment","execution_mode"))conditions.set(field,report.get(field));
            conditions.put("worker",worker);identity=conditions;key=JudgeJson.hash(JudgeJson.canonical(identity));
        }
        return new Entry(id,version,title,date,language,wall,memory,profile,identity,key,state,source);
    }
    static Long completeMaximum(JsonNode report,String field){
        var tests=report.path("tests");if(!tests.isArray()||tests.isEmpty())return null;
        for(var test:tests){var value=test.path(field);if(!"AC".equals(test.path("verdict").asText())||!value.isIntegralNumber()||!value.canConvertToLong()||value.asLong()<0)return null;}
        return ExecutionMetrics.maximum(report,field);
    }
    static List<Retry> retries(List<Entry> history){
        // Latest accepted solution must itself qualify; never resurrect a stale regression.
        Map<String,Entry> latest=new LinkedHashMap<>();history.forEach(e->latest.putIfAbsent(e.version(),e));
        List<Retry> result=new ArrayList<>();
        for(var current:latest.values()){
            if(current.comparisonKey()==null)continue;
            var comparable=history.stream().filter(e->e.version().equals(current.version())&&current.comparisonKey().equals(e.comparisonKey())).toList();
            var same=comparable.stream().filter(e->current.sourceHash().equals(e.sourceHash())).toList();
            var firstCurrent=same.stream().map(Entry::submittedAt).min(Comparator.naturalOrder()).orElseThrow();
            Map<String,List<Entry>> previous=new HashMap<>();
            comparable.stream().filter(e->!current.sourceHash().equals(e.sourceHash())&&e.submittedAt().isBefore(firstCurrent)).forEach(e->previous.computeIfAbsent(e.sourceHash(),k->new ArrayList<>()).add(e));
            for(String metric:List.of("TIME","MEMORY")){
                var values=values(same,metric);if(values.size()<3||!stable(values))continue;
                var baseline=previous.values().stream().map(es->values(es,metric)).filter(v->v.size()>=3&&stable(v)).min(Comparator.comparingDouble(PerformanceHistory::median)).orElse(null);
                if(baseline==null)continue;
                double before=median(baseline),now=median(values);double floor=metric.equals("TIME")?50:8*1048576;
                if(before<=0||now<before*1.5||now-before<floor)continue;
                double ratio=now/before;
                String reason=String.format(Locale.ROOT,"동일 문제·언어·실행 조건의 본인 AC 풀이 중앙값보다 %s가 %.2f배예요 (현재 %d회, 이전 %d회). 효율을 다시 살펴볼 수 있어요. 숙련도 평가에는 반영하지 않아요.",metric.equals("TIME")?"실행 시간":"cgroup 최대 메모리",ratio,values.size(),baseline.size());
                result.add(new Retry(current.version(),current.title(),metric,ratio,values.size(),baseline.size(),reason));
            }
        }
        return result.stream().limit(4).toList();
    }
    private static List<Long> values(List<Entry> entries,String metric){return entries.stream().map(e->metric.equals("TIME")?e.maxWallMs():e.maxMemoryBytes()).filter(Objects::nonNull).sorted().toList();}
    private static double median(List<Long> v){int n=v.size();return n%2==1?v.get(n/2):v.get(n/2-1)/2.0+v.get(n/2)/2.0;}
    private static boolean stable(List<Long> v){return v.getLast()<=Math.max(1,v.getFirst())*1.2;}
}
