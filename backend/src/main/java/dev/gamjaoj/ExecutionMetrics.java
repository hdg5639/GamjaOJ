package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;

final class ExecutionMetrics {
    static Double maximumCpu(JsonNode report){
        Double max=null;
        for(var test:report.path("tests")){
            var value=test.path("cpu_ms");
            if(value.isNumber()&&Double.isFinite(value.asDouble())&&value.asDouble()>=0)max=max==null?value.asDouble():Math.max(max,value.asDouble());
        }
        return max;
    }
    static Long maximum(JsonNode report,String field){
        Long max=null;
        for(var test:report.path("tests")){
            var value=test.path(field);
            if(value.isIntegralNumber()&&value.canConvertToLong()&&value.asLong()>=0)max=max==null?value.asLong():Math.max(max,value.asLong());
        }
        return max;
    }
    static String time(JsonNode payload){return payload.hasNonNull("maxWallMs")?payload.path("maxWallMs").asLong()+" ms":"미측정";}
    static String memory(JsonNode payload){return payload.hasNonNull("maxMemoryBytes")?String.format(Locale.ROOT,"%.2f MiB",payload.path("maxMemoryBytes").asLong()/1048576.0):"미측정";}
}
