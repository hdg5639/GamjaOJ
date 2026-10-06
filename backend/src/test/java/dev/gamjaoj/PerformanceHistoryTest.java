package dev.gamjaoj;

import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PerformanceHistoryTest {
    String report(long time,long memory){return """
        {"language":"JAVA","image":"java@sha256:abc","policy":"policy-v1","problem_sha256":"problem-hash",
         "execution_profile":{"language":"JAVA","version":"8"},"runner_environment":{"contract":{"cpu":1},"dockerControl":"cli"},
         "execution_mode":"EXCLUSIVE","tests":[{"verdict":"AC","wall_ms":%d,"memory_peak_bytes":%d}]}
        """.formatted(time,memory);}
    PerformanceHistory.Entry entry(int day,String source,String json){return PerformanceHistory.entry(UUID.randomUUID(),"v1","title",OffsetDateTime.parse("2026-10-01T00:00:00Z").plusDays(day),"JAVA",source,json,"worker1");}
    List<PerformanceHistory.Entry> history(){return new ArrayList<>(List.of(entry(6,"new",report(300,30*1048576)),entry(5,"new",report(310,31*1048576)),entry(4,"new",report(290,29*1048576)),entry(3,"old",report(100,10*1048576)),entry(2,"old",report(105,11*1048576)),entry(1,"old",report(95,10*1048576))));}
    @Test void repeatedComparableSolutionsProduceSeparateExplainedSignals(){
        var retry=PerformanceHistory.retries(history());assertThat(retry).hasSize(2);
        assertThat(retry).extracting(PerformanceHistory.Retry::metric).containsExactly("TIME","MEMORY");
        assertThat(retry.getFirst().reason()).contains("현재 3회", "이전 3회", "숙련도 평가에는 반영하지");
    }
    @Test void missingAndPartialMetricsStayUnknownRatherThanZero(){
        var absent=entry(1,"s","{}");assertThat(absent.maxWallMs()).isNull();assertThat(absent.comparisonKey()).isNull();
        var partial=entry(1,"s",report(300,100).replace("\"memory_peak_bytes\":100","\"memory_peak_bytes\":null"));
        assertThat(partial.maxMemoryBytes()).isNull();assertThat(partial.maxWallMs()).isEqualTo(300);
        assertThat(PerformanceHistory.completeMaximum(JudgeJson.parse("{\"tests\":[{\"verdict\":\"AC\",\"wall_ms\":1},{\"verdict\":\"AC\"}]}"),"wall_ms")).isNull();
    }
    @Test void profileWorkerProblemAndSharedModeCannotBeCompared(){
        for(String modified:List.of(report(300,100).replace("version\":\"8","version\":\"21"),report(300,100).replace("problem-hash","other-tests"),report(300,100).replace("EXCLUSIVE","FUNCTIONAL"))){
            var rows=history();rows.set(0,entry(6,"new",modified));assertThat(PerformanceHistory.retries(rows)).isEmpty();
        }
        assertThat(PerformanceHistory.entry(UUID.randomUUID(),"v1","t",OffsetDateTime.now(),"JAVA","s",report(100,100),null).comparisonKey()).isNull();
    }
    @Test void insufficientSamplesNoiseAndFastLatestSolutionSuppressRetry(){
        var rows=history();rows.remove(2);assertThat(PerformanceHistory.retries(rows)).isEmpty();
        rows=history();rows.set(0,entry(6,"new",report(900,90*1048576)));assertThat(PerformanceHistory.retries(rows)).isEmpty();
        rows=history();rows.addFirst(entry(7,"latest",report(90,9*1048576)));assertThat(PerformanceHistory.retries(rows)).isEmpty();
    }
    @Test void missingMemoryDoesNotInventMemorySignalAndSmallAbsoluteDifferencesAreIgnored(){
        var rows=history().stream().map(e->entry(e.submittedAt().getDayOfMonth()-1,e.sourceHash(),report(e.maxWallMs(),0).replace("\"memory_peak_bytes\":0","\"memory_peak_bytes\":null"))).toList();
        assertThat(PerformanceHistory.retries(rows)).extracting(PerformanceHistory.Retry::metric).containsExactly("TIME");
        var small=new ArrayList<PerformanceHistory.Entry>();
        for(int day=6;day>=1;day--)small.add(entry(day,day>=4?"new":"old",report(day>=4?30:10,day>=4?300:100)));
        assertThat(PerformanceHistory.retries(small)).isEmpty();
    }

}
