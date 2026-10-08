package dev.gamjaoj;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:completion;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "gamjaoj.worker-token=completion-worker-test-32-characters",
      "AI_API_ENABLED=false",
      "AI_POLL_MS=3600000"
    })
@AutoConfigureMockMvc
class CompletionIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  static HttpServer server;
  static AtomicReference<String> forwarded = new AtomicReference<>();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/complete",
        exchange -> {
          forwarded.set(
              new String(
                  exchange.getRequestBody().readAllBytes(),
                  java.nio.charset.StandardCharsets.UTF_8));
          byte[] body = "{\"items\":[{\"label\":\"nextToken\",\"kind\":2}]}".getBytes();
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    registry.add("COMPLETION_URL", () -> "http://127.0.0.1:" + server.getAddress().getPort());
  }

  @AfterAll
  static void close() {
    server.stop(0);
  }

  String request(String language, String source, int offset) throws Exception {
    return json.writeValueAsString(
        Map.of("language", language, "source", source, "offset", offset));
  }

  @Test
  void authenticationCsrfAndOwnerIsolation() throws Exception {
    String body = request("JAVA", "st.", 3);
    mvc.perform(
            post("/api/editor/completions")
                .with(csrf())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/editor/completions")
                .with(user("alice"))
                .contentType("application/json")
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/editor/completions")
                .with(user("alice"))
                .with(csrf())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].label").value("nextToken"));
    assertThat(json.readTree(forwarded.get()).get("owner").asText()).isEqualTo("alice");
    mvc.perform(
            post("/api/editor/completions")
                .with(user("alice"))
                .with(csrf())
                .contentType("application/json")
                .content(body.replace("{", "{\"owner\":\"forged\",")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void validateLanguageBytesAndUtf16Offset() throws Exception {
    for (String body :
        new String[] {
          request("BASH", "x", 1),
          request("JAVA", "x", 2),
          request("PYTHON", "한".repeat(22000), 1),
          request("JAVA", "😀", 1)
        })
      mvc.perform(
              post("/api/editor/completions")
                  .with(user("alice"))
                  .with(csrf())
                  .contentType("application/json")
                  .content(body))
          .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/editor/completions")
                .with(user("alice"))
                .with(csrf())
                .contentType("application/json")
                .content(request("PYTHON", "# 😀\nx.", 7)))
        .andExpect(status().isOk());
  }
}
