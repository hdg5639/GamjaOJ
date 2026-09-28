package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "spring.datasource.url=jdbc:h2:mem:auth;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "gamjaoj.invite-code=test-invite-only", "server.servlet.session.cookie.secure=false"})
class AuthIntegrationTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder passwords;
    static final String PASSWORD = "a-test-password-123";

    final class Browser {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
        HttpResponse<String> call(String method, String path, String body, String contentType, boolean csrf) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
            if (csrf) {
                JsonNode token = json.readTree(call("GET", "/api/auth/csrf", "", "", false).body());
                request.header(token.get("headerName").asText(), token.get("token").asText());
            }
            if (!contentType.isEmpty()) request.header("Content-Type", contentType);
            return client.send(request.method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> signup(String username, String invite) throws Exception {
            return call("POST", "/api/auth/signup", json.writeValueAsString(Map.of(
                    "username", username, "password", PASSWORD, "nickname", "감자", "inviteCode", invite)), "application/json", true);
        }
        HttpResponse<String> login(String username) throws Exception {
            return call("POST", "/api/auth/login", "username=" + username + "&password=" + PASSWORD,
                    "application/x-www-form-urlencoded", true);
        }
        HttpResponse<String> me() throws Exception { return call("GET", "/api/me", "", "", false); }
        String session() { return cookies.getCookieStore().getCookies().stream()
                .filter(c -> c.getName().equals("GAMJAOJ_SESSION")).findFirst().orElseThrow().getValue(); }
    }

    String username() { return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12); }

    @Test
    void loginRotatesSessionAndLogoutRevokesReplay() throws Exception {
        Browser browser = new Browser();
        String name = username();
        assertThat(browser.me().statusCode()).isEqualTo(401);
        assertThat(browser.signup(name, "test-invite-only").statusCode()).isEqualTo(201);
        String anonymous = browser.session();
        var login = browser.login(name);
        assertThat(login.statusCode()).isEqualTo(204);
        String authenticated = browser.session();
        assertThat(authenticated).isNotEqualTo(anonymous);
        assertThat(login.headers().allValues("set-cookie").toString()).contains("HttpOnly", "SameSite=Lax");
        assertThat(browser.me().body()).contains(name).doesNotContain("password", "hash");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?").param(name)
                .query(Integer.class).single()).isEqualTo(1);
        String hash = jdbc.sql("SELECT password_hash FROM app_user WHERE username = ?").param(name).query(String.class).single();
        assertThat(hash).isNotEqualTo(PASSWORD);
        assertThat(passwords.matches(PASSWORD, hash)).isTrue();
        assertThat(browser.call("POST", "/api/auth/logout", "", "", true).statusCode()).isEqualTo(204);
        assertThat(browser.me().statusCode()).isEqualTo(401);
        var replay = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/me"))
                .header("Cookie", "GAMJAOJ_SESSION=" + authenticated).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(replay.statusCode()).isEqualTo(401);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?").param(name)
                .query(Integer.class).single()).isZero();
    }

    @Test
    void csrfIsRequiredForSignupLoginAndLogout() throws Exception {
        Browser browser = new Browser();
        assertThat(browser.call("POST", "/api/auth/signup", "{}", "application/json", false).statusCode()).isEqualTo(403);
        assertThat(browser.call("POST", "/api/auth/login", "username=bad&password=bad", "application/x-www-form-urlencoded", false).statusCode()).isEqualTo(403);
        String name = username();
        assertThat(browser.signup(name, "test-invite-only").statusCode()).isEqualTo(201);
        assertThat(browser.login(name).statusCode()).isEqualTo(204);
        assertThat(browser.call("POST", "/api/auth/logout", "", "", false).statusCode()).isEqualTo(403);
        browser.call("GET", "/api/auth/logout", "", "", false);
        assertThat(browser.me().statusCode()).isEqualTo(200);
    }

    @Test
    void signupNeedsNoInviteAndRejectsDuplicatesAndInvalidCredentials() throws Exception {
        Browser browser = new Browser();
        String name = username();
        assertThat(browser.call("POST", "/api/auth/signup", json.writeValueAsString(Map.of(
                "username", username(), "password", PASSWORD, "nickname", "초대 없음")), "application/json", true).statusCode()).isEqualTo(201);
        assertThat(browser.signup(name, "any-old-client-value").statusCode()).isEqualTo(201); // ignored if still sent
        assertThat(browser.signup(name, "test-invite-only").statusCode()).isEqualTo(409);
        assertThat(browser.call("POST", "/api/auth/login", "username=" + name + "&password=incorrect",
                "application/x-www-form-urlencoded", true).statusCode()).isEqualTo(401);
        assertThat(browser.me().statusCode()).isEqualTo(401);
    }

    @Test
    void accountsHaveSeparateIdsAndPreferencesAndCannotChooseAnotherOwner() throws Exception {
        Browser alice = new Browser(), bob = new Browser();
        String a = username(), b = username();
        assertThat(alice.signup(a, "test-invite-only").statusCode()).isEqualTo(201);
        assertThat(bob.signup(b, "test-invite-only").statusCode()).isEqualTo(201);
        assertThat(alice.login(a).statusCode()).isEqualTo(204);
        assertThat(bob.login(b).statusCode()).isEqualTo(204);
        JsonNode first = json.readTree(alice.me().body()), second = json.readTree(bob.me().body());
        assertThat(first.get("id")).isNotEqualTo(second.get("id"));
        assertThat(alice.call("PATCH", "/api/me", "{\"nickname\":\"감자A\",\"trainingGoal\":\"DFS 복원\"}", "application/json", true).statusCode()).isEqualTo(200);
        assertThat(json.readTree(alice.me().body()).get("trainingGoal").asText()).isEqualTo("DFS 복원");
        assertThat(json.readTree(bob.me().body()).get("trainingGoal").asText()).isEmpty();
        assertThat(alice.call("PATCH", "/api/me", json.writeValueAsString(Map.of(
                "nickname", "침범", "trainingGoal", "bad", "id", second.get("id").asText())), "application/json", true).statusCode()).isEqualTo(400);
        assertThat(json.readTree(bob.me().body()).get("nickname").asText()).isEqualTo("감자");
    }
}
