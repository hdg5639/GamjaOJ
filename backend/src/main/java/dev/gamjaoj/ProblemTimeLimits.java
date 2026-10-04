package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Per-test wall budgets; estimates and measured Java evidence are deliberately distinguished. */
final class ProblemTimeLimits {
    // Infrastructure safety ceiling, not a problem-design target or a published default.
    static final int PROFILING_SECONDS=20;
    static int calibratedJavaSeconds(long maximumMs) {
        HybridArtifacts.require(maximumMs>0,"TIME_LIMIT_EVIDENCE_MISSING");
        HybridArtifacts.require(maximumMs<=PROFILING_SECONDS*500L,"TIME_LIMIT_CAPACITY_EXCEEDED");
        // Subsequent isolated JVM runs vary. Reserve 25% (at least 250 ms) before the 2x safety check,
        // rather than putting the measured maximum immediately below a later rejection boundary.
        long repeatAllowance=Math.max(250,(maximumMs+3)/4);
        long seconds=Math.max(1,((maximumMs+repeatAllowance)*2+999)/1000);
        HybridArtifacts.require(seconds<=PROFILING_SECONDS,"TIME_LIMIT_CAPACITY_EXCEEDED");
        return (int)seconds;
    }
    static int legacyCalibratedJavaSeconds(long maximumMs) {
        HybridArtifacts.require(maximumMs>0&&maximumMs<=PROFILING_SECONDS*500L,"RULE_TIMING_EVIDENCE");
        return (int)Math.max(1,(maximumMs*2+999)/1000);
    }
    static String javaProfile(int seconds) {
        HybridArtifacts.require(seconds>=1&&seconds<=PROFILING_SECONDS,"INVALID_TIME_LIMITS");
        var profile=(com.fasterxml.jackson.databind.node.ObjectNode)LanguageProfiles.profile("JAVA");
        profile.put("testWallSeconds",seconds);return JudgeJson.canonical(profile);
    }
    static final List<String> LANGUAGES=List.of("JAVA","CPP","PYTHON");
    static JsonNode parse(String json) {return json==null?null:validate(JudgeJson.parse(json));}
    static JsonNode validate(JsonNode limits) {
        if(limits.has("memory"))HybridArtifacts.fields(limits,"JAVA","CPP","PYTHON","analysis","memory");
        else HybridArtifacts.fields(limits,"JAVA","CPP","PYTHON","analysis");
        for(String language:LANGUAGES) {
            var n=limits.path(language);
            HybridArtifacts.require(n.isNumber()&&Double.isFinite(n.asDouble())&&n.asDouble()>=0.1&&n.asDouble()<=20&&Math.abs(n.asDouble()*1000-Math.rint(n.asDouble()*1000))<0.00001,"INVALID_TIME_LIMITS");
        }
        if(limits.has("memory")) {
            HybridArtifacts.fields(limits.path("memory"),"JAVA","CPP","PYTHON");
            for(String language:LANGUAGES) {
                var n=limits.path("memory").path(language);
                int ceiling=LanguageProfiles.profile(language).path("memoryMb").asInt();
                HybridArtifacts.require(n.isIntegralNumber()&&n.canConvertToInt()&&n.asInt()>=32&&n.asInt()<=ceiling,"INVALID_MEMORY_LIMITS");
            }
        }
        HybridArtifacts.text(limits.path("analysis"),6000);
        return limits;
    }
    static long maximum(JsonNode report) {
        long max=0;
        for(var t:report.path("tests"))if(List.of("AC","OK").contains(t.path("verdict").asText()))max=Math.max(max,t.path("wall_ms").asLong());
        return max;
    }
    static String reviewed(JsonNode review,long maximumMs) {
        return reviewed(review,maximumMs,20);
    }
    static String reviewed(JsonNode review,long maximumMs,int javaCeilingSeconds) {
        var limits=validate(review.path("timeLimits"));
        HybridArtifacts.require(limits.path("JAVA").asDouble()<=javaCeilingSeconds,"TIME_LIMIT_WITNESS_CEILING");
        HybridArtifacts.require(maximumMs>0&&maximumMs*2<=limits.path("JAVA").asDouble()*1000,"TIME_LIMIT_REFERENCE_MARGIN");
        return JudgeJson.canonical(limits);
    }
    /** Trusted templates have no free model proposal. Cross-language budgets are estimates. */
    static String measured(long maximumMs) {
        HybridArtifacts.require(maximumMs>0,"TIME_LIMIT_EVIDENCE_MISSING");
        var result=JudgeJson.JSON.createObjectNode();
        result.put("JAVA",seconds(maximumMs,4,1)).put("CPP",seconds(maximumMs,3,1)).put("PYTHON",seconds(maximumMs,12,2));
        result.put("analysis","Reference Java maximum="+maximumMs+" ms on checked cases. Java margin 4x; C++ 3x and Python 12x of Java are conservative estimates, not measurements of those languages. These cases do not prove every worst-case input.");
        return JudgeJson.canonical(validate(result));
    }
    private static int seconds(long ms,int margin,int minimum) {
        long value=Math.max(minimum,(ms*margin+999)/1000);
        HybridArtifacts.require(value<=20,"TIME_LIMIT_CAPACITY_EXCEEDED");
        return (int)value;
    }
}
