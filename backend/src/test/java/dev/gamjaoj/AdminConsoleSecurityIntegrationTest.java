package dev.gamjaoj;

import static org.assertj.core.api.Assertions.assertThat;

import dev.gamjaoj.account.dto.AuthDtos;
import dev.gamjaoj.account.service.Accounts;
import dev.gamjaoj.shared.support.JudgeJson;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Real HTTP and JDBC sessions: no mock authentication/CSRF/authorization providers. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=${GAMJA_ADMIN_TEST_DB:jdbc:h2:mem:adminsecurity;MODE=PostgreSQL;DB_CLOSE_DELAY=-1}",
      "spring.datasource.username=${GAMJA_ADMIN_TEST_USER:sa}",
      "spring.datasource.password=${GAMJA_ADMIN_TEST_PASSWORD:}",
      "CONTROL_OJ_HOST=localhost",
      "CONTROL_OJ_ADMIN_USERS=operator",
      "server.servlet.session.cookie.secure=true"
    })
class AdminConsoleSecurityIntegrationTest {
  @LocalServerPort int port;
  @Autowired dev.gamjaoj.judge.service.Submissions submissions;
  @Autowired dev.gamjaoj.learning.service.TrainingSessions training;
  @Autowired Accounts accounts;
  @Autowired dev.gamjaoj.learning.service.TrainingCourses courses;
  @Autowired JdbcClient jdbc;
  @Autowired org.springframework.session.SessionRepository sessionRepository;
  static final String PASSWORD = "admin-fixture-password-42";

  @BeforeEach
  void setup() {
    if (jdbc.sql("SELECT COUNT(*) FROM app_user WHERE username='operator'")
            .query(Integer.class)
            .single()
        == 0) accounts.register(new AuthDtos.Signup("operator", PASSWORD, "운영자", null));
    jdbc.sql(
            "UPDATE app_user SET admin_verify_failures=0,admin_verify_locked_until=0 WHERE"
                + " username='operator'")
        .update();
    jdbc.sql("UPDATE admin_site_setting SET maintenance=false,message='' WHERE id=1").update();
  }

  class Browser {
    final HttpClient client = HttpClient.newHttpClient();
    String cookie = "";

    HttpResponse<String> call(String path, String method, String body, Map<String, String> headers)
        throws Exception {
      var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
      if (!cookie.isEmpty()) request.header("Cookie", cookie);
      headers.forEach(request::header);
      request.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(body));
      var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
      response.headers().allValues("set-cookie").stream()
          .filter(v -> v.startsWith("GAMJAOJ_SESSION="))
          .findFirst()
          .ifPresent(v -> cookie = v.split(";", 2)[0]);
      return response;
    }

    HttpResponse<String> get(String path) throws Exception {
      return call(path, "GET", null, Map.of());
    }

    HttpResponse<String> write(String path, String method, String body, Map<String, String> extra)
        throws Exception {
      var csrf = JudgeJson.JSON.readTree(get("/api/auth/csrf").body());
      var headers = new HashMap<String, String>(extra);
      headers.put(csrf.path("headerName").asText(), csrf.path("token").asText());
      headers.putIfAbsent("Content-Type", "application/json");
      return call(path, method, body, headers);
    }

    void login(String username) throws Exception {
      assertThat(
              write(
                      "/api/auth/login",
                      "POST",
                      "username=" + username + "&password=" + PASSWORD,
                      Map.of("Content-Type", "application/x-www-form-urlencoded"))
                  .statusCode())
          .isEqualTo(204);
    }

    void verify() throws Exception {
      assertThat(
              write(
                      "/api/admin/session/verify",
                      "POST",
                      "{\"password\":\"" + PASSWORD + "\"}",
                      Map.of())
                  .statusCode())
          .isEqualTo(200);
    }
  }

  private String member() {
    String username = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 15);
    accounts.register(new AuthDtos.Signup(username, PASSWORD, "회원", null));
    return username;
  }

  private String notice(boolean published, int revision, String title) {
    return JudgeJson.JSON
        .createObjectNode()
        .put("kind", "공지")
        .put("title", title)
        .put("summary", "안내 요약")
        .put("body", "첫 문단\n\n두 번째 문단")
        .put("pinned", true)
        .put("published", published)
        .put("revision", revision)
        .toString();
  }

  @Test
  void loginAndStepUpRotateIdentifiersAndUnverifiedCookieCannotManage() throws Exception {
    Browser admin = new Browser();
    var cookieResponse = admin.get("/api/auth/csrf");
    String anonymous = admin.cookie;
    String header = cookieResponse.headers().firstValue("set-cookie").orElseThrow();
    assertThat(header).contains("HttpOnly", "Secure", "SameSite=Lax").doesNotContain("Domain=");
    admin.login("operator");
    String loggedIn = admin.cookie;
    assertThat(loggedIn).isNotEqualTo(anonymous);
    assertThat(admin.get("/api/admin/me").statusCode()).isEqualTo(200);
    var blocked = admin.get("/api/admin/overview");
    assertThat(blocked.statusCode()).isEqualTo(403);
    assertThat(blocked.body()).contains("ADMIN_REAUTH_REQUIRED");
    admin.verify();
    assertThat(admin.cookie).isNotEqualTo(loggedIn);
    assertThat(admin.get("/api/admin/overview").statusCode()).isEqualTo(200);
    Browser stolen = new Browser();
    stolen.cookie = loggedIn;
    assertThat(stolen.get("/api/admin/overview").statusCode()).isEqualTo(401);
    assertThat(
            admin
                .call(
                    "/api/admin/announcements",
                    "POST",
                    notice(true, 0, "CSRF 없는 글"),
                    Map.of(
                        "Content-Type",
                        "application/json",
                        "Idempotency-Key",
                        UUID.randomUUID().toString()))
                .statusCode())
        .isEqualTo(403);
    String live = admin.cookie;
    assertThat(admin.write("/api/auth/logout", "POST", null, Map.of()).statusCode()).isEqualTo(204);
    stolen.cookie = live;
    assertThat(stolen.get("/api/admin/overview").statusCode()).isEqualTo(401);
  }

  @Test
  void rolesBlockingAndRevocationAffectPreviouslyAuthenticatedSessions() throws Exception {
    String username = member();
    Browser user = new Browser();
    user.login(username);
    assertThat(user.get("/api/admin/me").statusCode()).isEqualTo(403);
    Browser admin = new Browser();
    admin.login("operator");
    admin.verify();
    String access = "{\"role\":\"ADMIN\",\"blocked\":false,\"revision\":0,\"reason\":\"운영 권한 부여\"}";
    assertThat(
            admin
                .write("/api/admin/members/" + username + "/access", "PUT", access, Map.of())
                .statusCode())
        .isEqualTo(200);
    assertThat(user.get("/api/admin/me").statusCode()).isEqualTo(401);
    user.login(username);
    user.verify();
    assertThat(user.get("/api/admin/overview").statusCode()).isEqualTo(200);
    assertThat(
            admin
                .write("/api/admin/members/" + username + "/access", "PUT", access, Map.of())
                .statusCode())
        .isEqualTo(409);
    assertThat(
            admin
                .write(
                    "/api/admin/members/" + username + "/access",
                    "PUT",
                    "{\"role\":\"MEMBER\",\"blocked\":true,\"revision\":1,\"reason\":\"이상 접근 차단\"}",
                    Map.of())
                .statusCode())
        .isEqualTo(200);
    assertThat(user.get("/api/admin/me").statusCode()).isEqualTo(401);
    assertThat(
            user.write(
                    "/api/auth/login",
                    "POST",
                    "username=" + username + "&password=" + PASSWORD,
                    Map.of("Content-Type", "application/x-www-form-urlencoded"))
                .statusCode())
        .isEqualTo(401);
    assertThat(
            admin
                .write(
                    "/api/admin/members/operator/access",
                    "PUT",
                    "{\"role\":\"MEMBER\",\"blocked\":true,\"revision\":0,\"reason\":\"복구 계정 차단"
                        + " 시도\"}",
                    Map.of())
                .statusCode())
        .isEqualTo(409);
  }

  @Test
  void verificationBruteForceIsLimitedAcrossSessionsAndIdleAndAbsoluteExpiryAreEnforced()
      throws Exception {
    Browser admin = new Browser();
    admin.login("operator");
    for (int i = 0; i < 5; i++)
      assertThat(
              admin
                  .write("/api/admin/session/verify", "POST", "{\"password\":\"wrong\"}", Map.of())
                  .statusCode())
          .isEqualTo(403);
    Browser next = new Browser();
    next.login("operator");
    assertThat(
            next.write(
                    "/api/admin/session/verify",
                    "POST",
                    "{\"password\":\"" + PASSWORD + "\"}",
                    Map.of())
                .statusCode())
        .isEqualTo(429);
    jdbc.sql(
            "UPDATE app_user SET admin_verify_locked_until=0,admin_verify_failures=0 WHERE"
                + " username='operator'")
        .update();
    next.verify();
    assertThat(
            jdbc.sql("SELECT COUNT(*) FROM admin_audit WHERE action='ADMIN_VERIFY_FAILED'")
                .query(Integer.class)
                .single())
        .isGreaterThanOrEqualTo(5);
    String sessionId =
        new String(
            Base64.getDecoder().decode(next.cookie.substring(next.cookie.indexOf('=') + 1)),
            java.nio.charset.StandardCharsets.UTF_8);
    org.springframework.session.Session session =
        (org.springframework.session.Session) sessionRepository.findById(sessionId);
    session.setAttribute(
        dev.gamjaoj.admin.service.AdminSessions.LAST, System.currentTimeMillis() - 301_000);
    sessionRepository.save(session);
    assertThat(next.get("/api/admin/overview").statusCode()).isEqualTo(403);
    next.verify();
    sessionId =
        new String(
            Base64.getDecoder().decode(next.cookie.substring(next.cookie.indexOf('=') + 1)),
            java.nio.charset.StandardCharsets.UTF_8);
    session = (org.springframework.session.Session) sessionRepository.findById(sessionId);
    session.setAttribute(
        dev.gamjaoj.admin.service.AdminSessions.UNTIL, System.currentTimeMillis() - 1);
    sessionRepository.save(session);
    assertThat(next.get("/api/admin/overview").statusCode()).isEqualTo(403);
  }

  @Test
  void announcementDraftReplayConflictPublicationAndWithdrawalAreSafe() throws Exception {
    Browser admin = new Browser();
    admin.login("operator");
    admin.verify();
    String id = UUID.randomUUID().toString();
    Map<String, String> key = Map.of("Idempotency-Key", id);
    assertThat(
            admin
                .write("/api/admin/announcements", "POST", notice(false, 0, "검증 초안"), key)
                .statusCode())
        .isEqualTo(200);
    assertThat(
            admin
                .write("/api/admin/announcements", "POST", notice(false, 0, "검증 초안"), key)
                .statusCode())
        .isEqualTo(200);
    assertThat(new Browser().get("/api/announcements").body()).doesNotContain(id);
    assertThat(
            admin
                .write("/api/admin/announcements", "POST", notice(false, 0, "다른 초안"), key)
                .statusCode())
        .isEqualTo(409);
    assertThat(
            admin
                .write(
                    "/api/admin/announcements/" + id,
                    "PUT",
                    notice(true, 1, "<script>alert(1)</script>"),
                    Map.of())
                .statusCode())
        .isEqualTo(200);
    var response = new Browser().get("/api/announcements");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains(id).doesNotContain("updatedBy", "created_by");
    assertThat(
            admin
                .write("/api/admin/announcements/" + id, "PUT", notice(true, 1, "오래된 수정"), Map.of())
                .statusCode())
        .isEqualTo(409);
    assertThat(
            admin
                .write("/api/admin/announcements/" + id, "PUT", notice(false, 2, "게시 취소"), Map.of())
                .statusCode())
        .isEqualTo(200);
    assertThat(new Browser().get("/api/announcements").body()).doesNotContain(id);
    assertThat(
            jdbc.sql(
                    "SELECT COUNT(*) FROM admin_audit WHERE action='ANNOUNCEMENT_CREATE' AND"
                        + " target=?")
                .param(id)
                .query(Integer.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void pagedManagementQueriesAreBoundAndPrivateArtifactsAreNotReturned() throws Exception {
    Browser admin = new Browser();
    admin.login("operator");
    admin.verify();
    for (String kind : List.of("members", "problems", "training", "plans", "jobs", "audit")) {
      var response = admin.get("/api/admin/lists/" + kind + "?size=10");
      assertThat(response.statusCode()).as(kind + ":" + response.body()).isEqualTo(200);
      assertThat(response.body())
          .doesNotContain("password_hash", "credentials", "package_json", "input_json");
    }
    assertThat(admin.get("/api/admin/availability").statusCode()).isEqualTo(200);
    assertThat(admin.get("/api/admin/settings").statusCode()).isEqualTo(200);
    assertThat(admin.get("/api/admin/lists/members?size=10000").statusCode()).isEqualTo(400);
    assertThat(admin.get("/api/admin/lists/members?query=%27%20OR%201%3D1--&size=10").statusCode())
        .isEqualTo(200);
    var direct =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/admin/me"))
                    .header("Cookie", admin.cookie)
                    .header("X-Forwarded-Host", "localhost")
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(direct.statusCode()).isEqualTo(404);
  }

  @Test
  void maintenanceStopsNewWritesButKeepsReadAuthAdminAndWorkerBoundaries() throws Exception {
    Browser admin = new Browser();
    admin.login("operator");
    admin.verify();
    var settings = JudgeJson.JSON.readTree(admin.get("/api/admin/settings").body());
    String body =
        "{\"maintenance\":true,\"message\":\"테스트 점검\",\"revision\":"
            + settings.path("revision").asInt()
            + ",\"reason\":\"운영 점검 검증\"}";
    assertThat(admin.write("/api/admin/settings", "PUT", body, Map.of()).statusCode())
        .isEqualTo(200);
    Browser user = new Browser();
    user.login(member());
    assertThat(user.get("/api/me").statusCode()).isEqualTo(200);
    assertThat(
            user.write(
                    "/api/auth/signup",
                    "POST",
                    "{\"username\":\"notcreated\",\"password\":\"password123\",\"nickname\":\"테스트\"}",
                    Map.of())
                .statusCode())
        .isEqualTo(503);
    assertThat(admin.get("/api/admin/overview").statusCode()).isEqualTo(200);
    assertThat(user.write("/api/auth/logout", "POST", null, Map.of()).statusCode()).isEqualTo(204);
  }

  @Test
  void courseEditingKeepsExistingSnapshotsAndRejectsPrivateOrUnverifiedProblems() throws Exception {
    Browser admin = new Browser();
    admin.login("operator");
    admin.verify();
    String id = "course-" + UUID.randomUUID().toString();
    var body =
        JudgeJson.JSON
            .createObjectNode()
            .put("id", id)
            .put("title", "새 코스")
            .put("kind", "기초 훈련")
            .put("summary", "공개 문제 훈련")
            .put("prerequisite", "")
            .put("notice", "")
            .put("revision", 0)
            .put("reason", "코스 운영 검증");
    var stage = body.putArray("stages").addObject().put("title", "기초").put("goal", "입출력 연습");
    stage.putArray("versions").add("sum-v1");
    assertThat(admin.write("/api/admin/courses", "POST", body.toString(), Map.of()).statusCode())
        .isEqualTo(200);
    var row =
        jdbc.sql("SELECT enabled,revision,course_json FROM admin_course_setting WHERE id=?")
            .param(id)
            .query()
            .singleRow();
    assertThat(
            row.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase("enabled"))
                .findFirst()
                .orElseThrow()
                .getValue())
        .isEqualTo(false);
    assertThat(
            admin
                .write(
                    "/api/admin/availability/course/" + id,
                    "PUT",
                    "{\"enabled\":true,\"revision\":1,\"reason\":\"코스 공개 검증\"}",
                    Map.of())
                .statusCode())
        .isEqualTo(200);
    String learner = member();
    var enrolled = courses.enroll(learner, UUID.randomUUID(), id, 1);
    String snapshot =
        jdbc.sql("SELECT course_json FROM training_course_enrollment WHERE id=?")
            .param(enrolled.enrollmentId())
            .query(String.class)
            .single();
    body.put("revision", 2).put("title", "개편 코스");
    assertThat(admin.write("/api/admin/courses", "PUT", body.toString(), Map.of()).statusCode())
        .isEqualTo(200);
    assertThat(
            jdbc.sql("SELECT course_json FROM training_course_enrollment WHERE id=?")
                .param(enrolled.enrollmentId())
                .query(String.class)
                .single())
        .isEqualTo(snapshot);
    assertThat(
            courses.catalog(learner).stream()
                .filter(v -> v.course().id().equals(id))
                .findFirst()
                .orElseThrow()
                .course()
                .title())
        .isEqualTo("개편 코스");
    assertThat(admin.write("/api/admin/courses", "PUT", body.toString(), Map.of()).statusCode())
        .isEqualTo(409);
    stage.withArray("versions").removeAll().add("not-existing-problem");
    body.put("revision", 3);
    assertThat(admin.write("/api/admin/courses", "PUT", body.toString(), Map.of()).statusCode())
        .isEqualTo(400);
  }

  @Test
  void metadataAndResourceChangesRespectImmutableJudgeContractsAndHoldAdmission() throws Exception {
    Browser admin = new Browser();
    admin.login("operator");
    admin.verify();
    String id = "admin-problem-" + UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO"
                + " problem_version(id,package_json,package_sha256,runtime_image,runner_policy)"
                + " SELECT ?,package_json,package_sha256,runtime_image,runner_policy FROM"
                + " problem_version WHERE id='sum-v1'")
        .param(id)
        .update();
    String original =
        jdbc.sql("SELECT package_json FROM problem_version WHERE id=?")
            .param(id)
            .query(String.class)
            .single();
    var metadata =
        JudgeJson.JSON
            .createObjectNode()
            .put("title", "관리자 수정 제목")
            .put("category", "graph")
            .put("shared", false)
            .put("revision", 0)
            .put("reason", "문제 표시 정비");
    metadata.putArray("tags").add("그래프");
    metadata
        .putObject("thinking")
        .put("layer", 3)
        .put("insight", 2)
        .put("implementation", 2)
        .put("edgeCases", 3)
        .put("rationale", "조건을 구분하는 문제");
    assertThat(
            admin
                .write("/api/admin/problems/" + id, "PUT", metadata.toString(), Map.of())
                .statusCode())
        .isEqualTo(200);
    var problem =
        submissions.problems("operator").stream()
            .filter(v -> v.version().equals(id))
            .findFirst()
            .orElseThrow();
    assertThat(problem.title()).isEqualTo("관리자 수정 제목");
    assertThat(problem.category()).isEqualTo("그래프");
    assertThat(problem.tags()).containsExactly("그래프");
    assertThat(problem.thinking().layer()).isEqualTo(3);
    assertThat(
            admin
                .write("/api/admin/problems/" + id, "PUT", metadata.toString(), Map.of())
                .statusCode())
        .isEqualTo(409);
    var limits = JudgeJson.JSON.createObjectNode().put("revision", 1).put("reason", "시간 제한 운영 보정");
    var languages = limits.putObject("languages");
    for (String language : List.of("JAVA", "CPP", "PYTHON"))
      languages
          .putObject(language)
          .put("wallSeconds", 10)
          .put("cpuSeconds", 5)
          .put("memoryMb", 128);
    assertThat(
            admin
                .write("/api/admin/problems/" + id + "/limits", "PUT", limits.toString(), Map.of())
                .statusCode())
        .isEqualTo(200);
    problem =
        submissions.problems("operator").stream()
            .filter(v -> v.version().equals(id))
            .findFirst()
            .orElseThrow();
    assertThat(problem.languages())
        .allSatisfy(
            l -> {
              assertThat(l.timeLimitMs()).isEqualTo(5000);
              assertThat(l.memoryMb()).isEqualTo(128);
            });
    limits.put("revision", 2);
    languages.withObject("JAVA").put("memoryMb", 4096);
    assertThat(
            admin
                .write("/api/admin/problems/" + id + "/limits", "PUT", limits.toString(), Map.of())
                .statusCode())
        .isEqualTo(400);
    assertThat(
            jdbc.sql("SELECT package_json FROM problem_version WHERE id=?")
                .param(id)
                .query(String.class)
                .single())
        .isEqualTo(original);
    assertThat(
            admin
                .write(
                    "/api/admin/problems/" + id + "/hold",
                    "POST",
                    "{\"reason\":\"정답 검토 보류\"}",
                    Map.of())
                .statusCode())
        .isEqualTo(200);
    assertThat(
            submissions.problems("operator").stream()
                .filter(v -> v.version().equals(id))
                .findFirst()
                .orElseThrow()
                .problemHeld())
        .isTrue();
  }

  @Test
  void administratorTrainingEndUsesTheExistingLifecycleAndWritesAudit() throws Exception {
    Browser admin = new Browser();
    admin.login("operator");
    admin.verify();
    String learner = member();
    UUID id = UUID.randomUUID();
    training.start(
        learner, id, new dev.gamjaoj.learning.dto.TrainingSessionDtos.Start("sum-v1", "입출력 복습"));
    assertThat(
            admin
                .write(
                    "/api/admin/training/" + id + "/end",
                    "POST",
                    "{\"reason\":\"학습 종료 요청 처리\"}",
                    Map.of())
                .statusCode())
        .isEqualTo(200);
    assertThat(training.detail(learner, id).session().status()).isEqualTo("ENDED");
    assertThat(
            jdbc.sql("SELECT COUNT(*) FROM admin_audit WHERE action='TRAINING_END' AND target=?")
                .param(id.toString())
                .query(Integer.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void supportIsPrivateIdempotentAndAdministratorRepliesRequireVerification() throws Exception {
    Browser owner = new Browser(), other = new Browser(), admin = new Browser();
    assertThat(owner.get("/api/support").statusCode()).isEqualTo(401);
    owner.login(member());
    other.login(member());
    admin.login("operator");
    String body =
        "{\"kind\":\"오류 제보\",\"title\":\"제출 화면 문의\",\"body\":\"회원에게만 보일 내용"
            + " <script>secret</script>\"}";
    UUID id = UUID.randomUUID();
    Map<String, String> headers = Map.of("Idempotency-Key", id.toString());
    assertThat(
            owner
                .call(
                    "/api/support",
                    "POST",
                    body,
                    Map.of("Content-Type", "application/json", "Idempotency-Key", id.toString()))
                .statusCode())
        .isEqualTo(403);
    assertThat(owner.write("/api/support", "POST", body, headers).statusCode()).isEqualTo(200);
    assertThat(owner.write("/api/support", "POST", body, headers).statusCode()).isEqualTo(200);
    assertThat(
            jdbc.sql("SELECT count(*) FROM support_request WHERE id=?")
                .param(id)
                .query(Integer.class)
                .single())
        .isEqualTo(1);
    assertThat(other.write("/api/support", "POST", body, headers).statusCode()).isEqualTo(409);
    assertThat(JudgeJson.JSON.readTree(other.get("/api/support").body()).path("items").size())
        .isZero();
    assertThat(owner.get("/api/admin/support").statusCode()).isEqualTo(403);
    assertThat(admin.get("/api/admin/support").statusCode()).isEqualTo(403);
    admin.verify();
    assertThat(admin.get("/api/admin/support?status=OPEN").statusCode()).isEqualTo(200);
    String reply = "{\"status\":\"RESOLVED\",\"reply\":\"확인해서 수정했어요.\",\"revision\":0}";
    assertThat(admin.write("/api/admin/support/" + id, "PUT", reply, Map.of()).statusCode())
        .isEqualTo(200);
    assertThat(admin.write("/api/admin/support/" + id, "PUT", reply, Map.of()).statusCode())
        .isEqualTo(409);
    var item = JudgeJson.JSON.readTree(owner.get("/api/support").body()).path("items").get(0);
    assertThat(item.path("status").asText()).isEqualTo("RESOLVED");
    assertThat(item.path("reply").asText()).isEqualTo("확인해서 수정했어요.");
    assertThat(
            jdbc.sql(
                    "SELECT after_json FROM admin_audit WHERE action='SUPPORT_UPDATE' AND target=?")
                .param(id.toString())
                .query(String.class)
                .single())
        .doesNotContain("확인해서", "script", "secret");
  }

  @Test
  void supportLimitRejectsNewRequestsButAllowsExactReplay() throws Exception {
    Browser owner = new Browser();
    owner.login(member());
    String body = "{\"kind\":\"이용 문의\",\"title\":\"문의\",\"body\":\"문의 내용\"}";
    UUID first = UUID.randomUUID();
    for (int i = 0; i < 10; i++)
      assertThat(
              owner
                  .write(
                      "/api/support",
                      "POST",
                      body,
                      Map.of("Idempotency-Key", (i == 0 ? first : UUID.randomUUID()).toString()))
                  .statusCode())
          .isEqualTo(200);
    assertThat(
            owner
                .write(
                    "/api/support",
                    "POST",
                    body,
                    Map.of("Idempotency-Key", UUID.randomUUID().toString()))
                .statusCode())
        .isEqualTo(429);
    assertThat(
            owner
                .write("/api/support", "POST", body, Map.of("Idempotency-Key", first.toString()))
                .statusCode())
        .isEqualTo(200);
    assertThat(owner.get("/api/support?page=-1").statusCode()).isEqualTo(400);
  }
}
