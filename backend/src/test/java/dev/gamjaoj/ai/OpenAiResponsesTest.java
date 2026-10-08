package dev.gamjaoj.ai;
import dev.gamjaoj.infrastructure.ai.OpenAiResponses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OpenAiResponsesTest {
    static final ObjectMapper JSON = new ObjectMapper();
    HttpServer server;
    AtomicInteger calls = new AtomicInteger();
    AtomicReference<JsonNode> request = new AtomicReference<>();
    AtomicReference<String> authorization = new AtomicReference<>();

    OpenAiResponses provider(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            calls.incrementAndGet();
            request.set(JSON.readTree(exchange.getRequestBody()));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("x-request-id", "test-request");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new OpenAiResponses("test-secret", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/responses"));
    }
    @AfterEach void stop() { if (server != null) server.stop(0); }
    @Test void remainingDeadlineTimesOutWithoutRetryOrInventedUsage() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/responses",exchange->{
            calls.incrementAndGet();
            try {Thread.sleep(300);} catch(InterruptedException e){Thread.currentThread().interrupt();}
            exchange.close();
        });
        server.start();
        var api=new OpenAiResponses("fixture",URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/responses"));
        assertThatThrownBy(()->api.generate("fixture","low","instructions","input","artifact",JSON.createObjectNode(),32,java.time.Duration.ofMillis(50)))
                .isInstanceOfSatisfying(OpenAiResponses.Failure.class,e->{
                    assertThat(e.code()).isEqualTo("TRANSPORT_USAGE_UNKNOWN");assertThat(e.usage()).isNull();
                });
        assertThat(calls.get()).isLessThanOrEqualTo(1);
    }
    OpenAiResponses.Result generate(OpenAiResponses api) throws Exception {
        return api.generate("configured-model", "Return a structured artifact", "test input", "artifact",
                JSON.readTree("{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"}},\"required\":[\"title\"],\"additionalProperties\":false}"), 1024);
    }
    @Test void structuredRequestAndOutputPreserveUsageAndProviderIdentity() throws Exception {
        var result = generate(provider(200, """
            {"id":"resp_test","model":"provider-model","status":"completed",
             "usage":{"input_tokens":12,"output_tokens":5,"output_tokens_details":{"reasoning_tokens":2}},
             "output":[{"type":"reasoning","summary":[]},{"type":"message","content":[{"type":"output_text","text":"{\"title\":\"Test\"}"}]}]}
            """.replace("{\"title\":\"Test\"}", "{\\\"title\\\":\\\"Test\\\"}")));
        assertThat(result.value().path("title").asText()).isEqualTo("Test");
        assertThat(result.usage().path("output_tokens_details").path("reasoning_tokens").asInt()).isEqualTo(2);
        assertThat(result.model()).isEqualTo("provider-model");
        assertThat(result.requestId()).isEqualTo("test-request");
        assertThat(request.get().path("model").asText()).isEqualTo("configured-model");
        assertThat(request.get().path("store").asBoolean()).isFalse();
        assertThat(request.get().at("/text/format/strict").asBoolean()).isTrue();
        assertThat(request.get().path("max_output_tokens").asInt()).isEqualTo(1024);
        assertThat(authorization.get()).isEqualTo("Bearer test-secret");
    }
    @Test void rateLimitDoesNotRetryOrLeakBodyAndUnknownUsageIsNotZero() throws Exception {
        var api = provider(429, "test-secret private prompt");
        assertThatThrownBy(() -> generate(api)).isInstanceOfSatisfying(OpenAiResponses.Failure.class, e -> {
            assertThat(e.code()).isEqualTo("PROVIDER_LIMIT");
            assertThat(e.usage()).isNull();
            assertThat(e.getMessage()).doesNotContain("test-secret", "private prompt");
        });
        assertThat(calls).hasValue(1);
    }
    @Test void incompleteRetainsBillableUsage() throws Exception {
        var api = provider(200, "{\"status\":\"incomplete\",\"usage\":{\"output_tokens\":1024},\"output\":[]}");
        assertThatThrownBy(() -> generate(api)).isInstanceOfSatisfying(OpenAiResponses.Failure.class, e -> {
            assertThat(e.code()).isEqualTo("INCOMPLETE_RESPONSE");
            assertThat(e.usage().path("output_tokens").asInt()).isEqualTo(1024);
        });
    }
    @Test void refusalNeverBecomesAnArtifact() throws Exception {
        var api = provider(200, "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"refusal\",\"refusal\":\"private\"}]}]}");
        assertThatThrownBy(() -> generate(api)).isInstanceOfSatisfying(OpenAiResponses.Failure.class,
                e -> assertThat(e.code()).isEqualTo("REFUSED"));
    }
    @Test void malformedSuccessIsRejectedWithoutLeakingProviderContent() throws Exception {
        var api = provider(200, "private malformed body");
        assertThatThrownBy(() -> generate(api)).isInstanceOfSatisfying(OpenAiResponses.Failure.class,
                e -> assertThat(e.code()).isEqualTo("INVALID_RESPONSE"));
    }
}
