package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Arrays;

/** Trusted serializers, called only after semantic token/count/range validation. */
final class InputLayout {
    static String sequence(String input) {
        String[] words=input.strip().split("\\s+");
        return words[0]+"\n"+String.join(" ",Arrays.copyOfRange(words,1,words.length))+"\n";
    }
    static String graph(String input) {
        String[] words=input.strip().split("\\s+");
        var result=new StringBuilder(String.join(" ",Arrays.copyOfRange(words,0,4))).append('\n');
        for(int i=4;i<words.length;i+=3)result.append(words[i]).append(' ').append(words[i+1]).append(' ').append(words[i+2]).append('\n');
        return result.toString();
    }
    static boolean matches(GenerationType type,JsonNode plan) {
        if(!plan.path("tests").isArray()||plan.path("tests").isEmpty())return false;
        try {
            for(var test:plan.path("tests")) {
                var trusted=type.test(test.path("id").asText(),test.path("input").asText());
                if(!trusted.path("input").equals(test.path("input"))||!trusted.path("output").equals(test.path("output")))return false;
            }
            return true;
        }catch(AccountException e){return false;}
    }
}
