package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

/** Same session/CSRF boundary as other editor APIs. Analysis never submits or runs code. */
@RestController
@RequestMapping("/api/editor")
public class CompletionController {
    private final String endpoint;
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Semaphore capacity = new Semaphore(3);
    private final java.util.Set<String> active = ConcurrentHashMap.newKeySet();

    public CompletionController(@Value("${COMPLETION_URL:}") String endpoint, ObjectMapper json) {
        this.endpoint = endpoint;
        this.json = json;
    }
    public record Request(@NotNull @Pattern(regexp="JAVA|CPP|PYTHON") String language,
                          @NotNull @Size(max=65536) String source,
                          @Min(0) @Max(65536) int offset) {}

    @PostMapping("/completions")
    public JsonNode complete(Principal principal, @Valid @RequestBody Request request) {
        if (request.offset() > request.source().length()
                || request.source().getBytes(StandardCharsets.UTF_8).length > 65536
                || (request.offset() > 0 && request.offset() < request.source().length()
                    && Character.isHighSurrogate(request.source().charAt(request.offset() - 1)))) {
            throw new AccountException(400, "코드와 커서 위치를 확인해 주세요.");
        }
        JsonNode unavailable = json.valueToTree(Map.of("items", java.util.List.of(), "unavailable", true));
        if (endpoint.isBlank() || !capacity.tryAcquire()) return unavailable;
        String owner = principal.getName();
        if (!active.add(owner)) { capacity.release(); return unavailable; }
        try {
            String body = json.writeValueAsString(Map.of("owner", owner, "language", request.language(),
                    "source", request.source(), "offset", request.offset()));
            var http = HttpRequest.newBuilder(URI.create(endpoint + "/complete"))
                    .timeout(Duration.ofSeconds(28)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = client.send(http, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body().length() > 2_000_000) return unavailable;
            JsonNode result = json.readTree(response.body());
            return result.has("items") && result.get("items").isArray() ? result : unavailable;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unavailable;
        } catch (Exception e) {
            return unavailable;
        } finally {
            active.remove(owner);
            capacity.release();
        }
    }
}
