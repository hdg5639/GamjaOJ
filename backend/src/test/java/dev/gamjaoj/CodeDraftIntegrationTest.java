package dev.gamjaoj;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.gamjaoj.account.service.CodeDrafts;
import dev.gamjaoj.shared.exception.AccountException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:codedrafts;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "gamjaoj.invite-code=test-only",
      "gamjaoj.worker-token=code-drafts-worker-32-characters-x",
      "gamjaoj.submissions-enabled=true",
      "AI_API_ENABLED=false",
      "AI_POLL_MS=3600000"
    })
@AutoConfigureMockMvc
class CodeDraftIntegrationTest {
  @Autowired JdbcClient jdbc;
  @Autowired MockMvc mvc;
  @Autowired CodeDrafts drafts;

  String member() {
    String name = "d" + UUID.randomUUID().toString().substring(0, 8);
    jdbc.sql("INSERT INTO app_user(id,username,password_hash,nickname) VALUES (?,?,'!',?)")
        .param(UUID.randomUUID())
        .param(name)
        .param(name)
        .update();
    return name;
  }

  @Test
  void draftsAreSavedPerMemberScopeAndLanguageAndValidated() throws Exception {
    String alice = member(), bob = member();
    mvc.perform(
            get("/api/drafts")
                .param("scope", "p:sum-v1")
                .param("language", "JAVA")
                .with(user(alice)))
        .andExpect(status().isOk())
        .andExpect(content().json("{}"));
    mvc.perform(
            put("/api/drafts")
                .with(user(alice))
                .contentType("application/json")
                .content("{\"scope\":\"p:sum-v1\",\"language\":\"JAVA\",\"source\":\"class A{}\"}"))
        .andExpect(status().isForbidden()); // CSRF
    mvc.perform(
            put("/api/drafts")
                .with(user(alice))
                .with(csrf())
                .contentType("application/json")
                .content("{\"scope\":\"p:sum-v1\",\"language\":\"JAVA\",\"source\":\"class A{}\"}"))
        .andExpect(status().isOk());
    mvc.perform(
            put("/api/drafts")
                .with(user(alice))
                .with(csrf())
                .contentType("application/json")
                .content("{\"scope\":\"p:sum-v1\",\"language\":\"JAVA\",\"source\":\"class B{}\"}"))
        .andExpect(status().isOk());
    mvc.perform(
            get("/api/drafts")
                .param("scope", "p:sum-v1")
                .param("language", "JAVA")
                .with(user(alice)))
        .andExpect(jsonPath("$.source").value("class B{}"))
        .andExpect(jsonPath("$.updatedAt").exists());
    mvc.perform(
            get("/api/drafts")
                .param("scope", "p:sum-v1")
                .param("language", "PYTHON")
                .with(user(alice)))
        .andExpect(content().json("{}"));
    mvc.perform(
            get("/api/drafts").param("scope", "p:sum-v1").param("language", "JAVA").with(user(bob)))
        .andExpect(content().json("{}"));
    mvc.perform(
            put("/api/drafts")
                .with(user(alice))
                .with(csrf())
                .contentType("application/json")
                .content("{\"scope\":\"../x\",\"language\":\"JAVA\",\"source\":\"x\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            put("/api/drafts")
                .with(user(alice))
                .with(csrf())
                .contentType("application/json")
                .content("{\"scope\":\"p:x\",\"language\":\"BASH\",\"source\":\"x\"}"))
        .andExpect(status().isBadRequest());
    assertThatThrownBy(() -> drafts.save(alice, "p:x", "JAVA", "x".repeat(65537)))
        .isInstanceOf(AccountException.class);
  }

  @Test
  void oldestDraftsBeyondTheLimitAreDropped() {
    String carol = member();
    for (int i = 0; i < CodeDrafts.MAX_DRAFTS + 3; i++)
      drafts.save(carol, "d:item-" + i, "JAVA", "//" + i);
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM code_draft c JOIN app_user u ON u.id=c.user_id WHERE"
                        + " u.username=?")
                .param(carol)
                .query(Integer.class)
                .single())
        .isLessThanOrEqualTo(CodeDrafts.MAX_DRAFTS);
    assertThat(drafts.get(carol, "d:item-" + (CodeDrafts.MAX_DRAFTS + 2), "JAVA")).isPresent();
    assertThat(drafts.get(carol, "d:item-0", "JAVA")).isEmpty();
  }
}
