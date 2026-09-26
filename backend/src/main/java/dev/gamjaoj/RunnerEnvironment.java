package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class RunnerEnvironment {
    private static final JsonNode EXPECTED = read();
    private static JsonNode read() {
        try (var stream=RunnerEnvironment.class.getResourceAsStream("/runner-execution-contract.json")) {
            if(stream==null) throw new IllegalStateException("Runner execution contract was not exported before build");
            return JudgeJson.parse(new String(stream.readAllBytes(),StandardCharsets.UTF_8));
        } catch(IOException e) { throw new IllegalStateException(e); }
    }
    static JsonNode expected() { return EXPECTED.deepCopy(); }
    static boolean matches(JsonNode expected, JsonNode declared) {
        return expected!=null && declared!=null && declared.isObject()
                && expected.equals(declared.path("contract"))
                && java.util.Set.of("cli","engine").contains(declared.path("dockerControl").asText());
    }
}
