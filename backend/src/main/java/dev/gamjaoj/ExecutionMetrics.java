package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;

final class ExecutionMetrics {
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
