package dev.gamjaoj.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Internal transport only. The caller must reserve budget and persist an attempt before calling.
 * No automatic retries: timeout or transport failure may still have incurred provider usage.
 * Never expose provider bodies, prompts or credentials through exception messages.
 */
public final class OpenAiResponses {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client;
    private final URI endpoint;
    private final String apiKey;

    public OpenAiResponses(String apiKey) {
        this(apiKey, URI.create("https://api.openai.com/v1/responses"));
    }

    // Package-private endpoint override for local contract tests, never a user-supplied URL.
    OpenAiResponses(String apiKey, URI endpoint) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("OPENAI_API_KEY is required");
        this.apiKey = apiKey;
        this.endpoint = endpoint;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public record Result(JsonNode value, JsonNode usage, String responseId, String requestId, String model) {}

    public static final class Failure extends RuntimeException {
        private final String code;
        private final JsonNode usage;
        private final String requestId;
        public Failure(String code, JsonNode usage, String requestId) {
            super("OpenAI request failed: " + code);
            this.code = code; this.usage = usage; this.requestId = requestId;
        }
        public String code() { return code; }
        /** Null means unknown, not zero. */
        public JsonNode usage() { return usage; }
        public String requestId() { return requestId; }
    }

    public Result generate(String model, String instructions, String input, String schemaName,
                           JsonNode schema, int maxOutputTokens) {
        return generate(model, "low", instructions, input, schemaName, schema, maxOutputTokens);
    }

    public Result generate(String model, String effort, String instructions, String input, String schemaName,
                           JsonNode schema, int maxOutputTokens) {
        if (model == null || model.isBlank() || maxOutputTokens < 1 || maxOutputTokens > 32768
                || schema == null || !schema.isObject() || instructions == null || input == null
                || schemaName == null || !schemaName.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid Responses request configuration");
        }
        ObjectNode body = JSON.createObjectNode().put("model", model).put("instructions", instructions)
                .put("input", input).put("store", false).put("service_tier", "default").put("max_output_tokens", maxOutputTokens);
        body.putObject("reasoning").put("effort", effort);
        body.putObject("text").putObject("format").put("type", "json_schema")
                .put("name", schemaName).put("strict", true).set("schema", schema);
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure("INTERRUPTED_USAGE_UNKNOWN", null, null);
        } catch (IOException e) {
            throw new Failure("TRANSPORT_USAGE_UNKNOWN", null, null);
        }
        String requestId = response.headers().firstValue("x-request-id").orElse(null);
        if (response.statusCode() != 200) {
            String code = switch (response.statusCode()) {
                case 401, 403 -> "NEEDS_AUTH";
                case 429 -> "PROVIDER_LIMIT";
                default -> response.statusCode() >= 500 ? "PROVIDER_UNAVAILABLE" : "REQUEST_REJECTED";
            };
            try {
                String providerCode=JSON.readTree(response.body()).path("error").path("code").asText();
                if ("model_not_found".equals(providerCode) || "model_not_available".equals(providerCode)) code="MODEL_UNAVAILABLE";
            } catch (Exception ignored) { /* Never expose raw provider errors. */ }
            throw new Failure(code, null, requestId);
        }
        JsonNode payload;
        try { payload = JSON.readTree(response.body()); }
        catch (IOException e) { throw new Failure("INVALID_RESPONSE", null, requestId); }
        if (payload == null || !payload.isObject()) throw new Failure("INVALID_RESPONSE", null, requestId);
        JsonNode usage = payload.get("usage");
        if (usage != null && !usage.isObject()) usage = null;
        if (!"completed".equals(payload.path("status").asText())) {
            throw new Failure("INCOMPLETE_RESPONSE", usage, requestId);
        }
        StringBuilder output = new StringBuilder();
        for (JsonNode item : payload.path("output")) {
            if (!"message".equals(item.path("type").asText())) continue;
            for (JsonNode part : item.path("content")) {
                if ("refusal".equals(part.path("type").asText())) throw new Failure("REFUSED", usage, requestId);
                if ("output_text".equals(part.path("type").asText())) output.append(part.path("text").asText());
            }
        }
        try {
            JsonNode value = JSON.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(output.toString());
            if (value == null || !value.isObject()) throw new Failure("INVALID_OUTPUT", usage, requestId);
            // Schema conformance is not semantic correctness. The feature validator must check the artifact.
            return new Result(value, usage, payload.path("id").asText(), requestId, payload.path("model").asText());
        } catch (IOException e) { throw new Failure("INVALID_OUTPUT", usage, requestId); }
    }
}
