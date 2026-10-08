package dev.gamjaoj.export.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamjaoj.shared.support.JudgeJson;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ExportHttp {
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public JsonNode request(String method, String url, Map<String, String> headers, JsonNode body) {
    try {
      var request =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(15))
              .header("User-Agent", "GamjaOJ-Exports/1.0");
      headers.forEach(request::header);
      request.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(JudgeJson.canonical(body)));
      var response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
      {
        byte[] raw = response.body();
        if (raw.length > 2_000_000)
          throw new ExportRemote.Failure("REMOTE_RESPONSE_TOO_LARGE", false);
        int status = response.statusCode();
        if (status < 200 || status >= 300)
          throw new ExportRemote.Failure(
              status == 401
                  ? "RECONNECT_REQUIRED"
                  : status == 403
                      ? "PERMISSION_REQUIRED"
                      : status == 404
                          ? "TARGET_NOT_FOUND"
                          : status == 429
                              ? "RATE_LIMITED"
                              : status == 409 || status == 422 ? "REMOTE_CONFLICT" : "REMOTE_ERROR",
              status == 429 || status == 409 || status >= 500);
        return raw.length == 0
            ? JudgeJson.JSON.createObjectNode()
            : JudgeJson.parse(new String(raw, StandardCharsets.UTF_8));
      }
    } catch (ExportRemote.Failure e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ExportRemote.Failure("NETWORK_ERROR", true);
    } catch (Exception e) {
      throw new ExportRemote.Failure("NETWORK_ERROR", true);
    }
  }
}
