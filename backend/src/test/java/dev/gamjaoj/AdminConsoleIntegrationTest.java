package dev.gamjaoj;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.gamjaoj.admin.config.ControlSettings;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:control;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "CONTROL_OJ_HOST=admin-console.localhost",
      "CONTROL_OJ_ADMIN_USERS=operator",
      "server.servlet.session.cookie.secure=false"
    })
@AutoConfigureMockMvc
class AdminConsoleIntegrationTest {
  @Autowired MockMvc mvc;

  private MockHttpServletRequestBuilder request(String path, String host) {
    return get(path)
        .with(
            r -> {
              r.setServerName(host);
              r.setServletPath(path);
              return r;
            });
  }

  @Test
  void publicHostCannotReachControlDocumentsOrApisEvenAsAdministrator() throws Exception {
    for (String path :
        new String[] {
          "/admin-console", "/admin-console.html", "/admin-console.txt", "/api/admin/me", "/api/admin/overview"
        })
      mvc.perform(request(path, "gamjaoj.localhost").with(user("operator").roles("MEMBER")))
          .andExpect(status().isNotFound());
  }

  @Test
  void controlHostStillRequiresAnAuthenticatedAdministrator() throws Exception {
    mvc.perform(request("/api/admin/me", "admin-console.localhost"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            request("/api/admin/me", "admin-console.localhost").with(user("member").roles("MEMBER")))
        .andExpect(status().isForbidden());
    mvc.perform(
            request("/api/admin/me", "admin-console.localhost").with(user("operator").roles("MEMBER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("operator"));
    mvc.perform(
            request("/api/admin/overview", "admin-console.localhost")
                .with(user("operator").roles("MEMBER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.members").isNumber())
        .andExpect(jsonPath("$.problems").isNumber())
        .andExpect(jsonPath("$.judgeQueue").isMap())
        .andExpect(jsonPath("$.measuredAt").exists());
  }

  @Test
  void forwardedHostDoesNotOverrideHostRouting() throws Exception {
    mvc.perform(
            request("/api/admin/me", "gamjaoj.localhost")
                .header("X-Forwarded-Host", "admin-console.localhost")
                .with(user("operator").roles("MEMBER")))
        .andExpect(status().isNotFound());
  }

  @Test
  void routingIsDisabledByDefaultAndHostnameComparisonIsExact() {
    var request = new MockHttpServletRequest();
    request.setServerName("admin-console.localhost");
    assertThat(new ControlSettings("", "operator").matches(request)).isFalse();
    var settings = new ControlSettings("admin-console.localhost", "");
    assertThat(
            settings.administrator(
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                    .authenticated(
                        "operator",
                        "unused",
                        java.util.List.of(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_MEMBER")))))
        .isFalse();
    request.setServerName("ADMIN-CONSOLE.LOCALHOST");
    assertThat(settings.matches(request)).isTrue();
    request.setServerName("admin-console.localhost.attacker.invalid");
    assertThat(settings.matches(request)).isFalse();
  }
}
