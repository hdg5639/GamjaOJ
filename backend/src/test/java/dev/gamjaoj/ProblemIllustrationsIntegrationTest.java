package dev.gamjaoj;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.gamjaoj.problem.service.ProblemIllustrations;
import dev.gamjaoj.shared.exception.AccountException;
import dev.gamjaoj.shared.support.JudgeJson;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:problem-images;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "gamjaoj.invite-code=test",
      "AI_API_ENABLED=false",
      "AI_POLL_MS=3600000"
    })
@AutoConfigureMockMvc
class ProblemIllustrationsIntegrationTest {
  @Autowired JdbcClient jdbc;
  @Autowired ProblemIllustrations images;
  @Autowired MockMvc mvc;
  UUID alice, bob;
  byte[] png;
  String version = "image-owned-v1";

  @BeforeEach
  void setup() throws Exception {
    jdbc.sql("DELETE FROM problem_version WHERE id='basic-pool-v1-bfs-medium-02-v1'").update();
    jdbc.sql("DELETE FROM problem_version WHERE id=?").param(version).update();
    jdbc.sql("DELETE FROM app_user").update();
    alice = create("alice");
    bob = create("bob");
    String pkg = "{\"version\":\"" + version + "\",\"title\":\"그림 테스트\",\"statement\":\"공개 규칙\"}";
    jdbc.sql(
            "INSERT INTO"
                + " problem_version(id,package_json,package_sha256,runtime_image,runner_policy,ready,owner_id,shared)"
                + " SELECT ?,?,?,runtime_image,runner_policy,true,?,false FROM problem_version"
                + " WHERE id='sum-v1'")
        .param(version)
        .param(pkg)
        .param(JudgeJson.hash(pkg))
        .param(alice)
        .update();
    png = bitmap("png", 32, 24);
  }

  UUID create(String name) {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO app_user(id,username,password_hash,nickname) VALUES (?,?,?,?)")
        .param(id)
        .param(name)
        .param("unused")
        .param(name)
        .update();
    return id;
  }

  byte[] bitmap(String format, int w, int h) throws Exception {
    var image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    var out = new ByteArrayOutputStream();
    ImageIO.write(image, format, out);
    image.flush();
    return out.toByteArray();
  }

  UUID upload() {
    UUID key = UUID.randomUUID();
    images.upload("alice", version, key, png, "이동 방향", "벽을 넘을 수 없음");
    return key;
  }

  @Test
  void imagePersistsWithoutChangingJudgePackageAndPrivateOwnershipIsEnforced() throws Exception {
    String hash =
        jdbc.sql("SELECT package_sha256 FROM problem_version WHERE id=?")
            .param(version)
            .query(String.class)
            .single();
    UUID key = upload();
    var presentation = images.presentation("alice", version);
    assertThat(presentation.canEdit()).isTrue();
    assertThat(presentation.illustrations()).hasSize(1);
    assertThat(presentation.illustrations().getFirst().width()).isEqualTo(32);
    assertThat(
            ImageIO.read(new java.io.ByteArrayInputStream(images.image("alice", key))).getHeight())
        .isEqualTo(24);
    assertThatThrownBy(() -> images.presentation("bob", version))
        .isInstanceOf(AccountException.class);
    assertThatThrownBy(() -> images.image("bob", key)).isInstanceOf(AccountException.class);
    assertThat(
            jdbc.sql("SELECT package_sha256 FROM problem_version WHERE id=?")
                .param(version)
                .query(String.class)
                .single())
        .isEqualTo(hash);
    assertThat(jdbc.sql("SELECT count(*) FROM ai_task").query(Integer.class).single()).isZero();
    mvc.perform(get("/api/problem-images/" + key).with(user("alice")))
        .andExpect(status().isOk())
        .andExpect(content().contentType("image/png"))
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"));
  }

  @Test
  void sharedViewIsReadOnlyAndRevocationHoldAndStalePackageBlockImage() {
    UUID key = upload();
    jdbc.sql("UPDATE problem_version SET shared=true WHERE id=?").param(version).update();
    assertThat(images.presentation("bob", version).canEdit()).isFalse();
    assertThat(images.image("bob", key)).isNotEmpty();
    assertThatThrownBy(() -> images.upload("bob", version, UUID.randomUUID(), png, "불가", ""))
        .isInstanceOf(AccountException.class);
    jdbc.sql("UPDATE problem_version SET review_hold=true WHERE id=?").param(version).update();
    assertThatThrownBy(() -> images.image("bob", key)).isInstanceOf(AccountException.class);
    jdbc.sql(
            "UPDATE problem_version SET review_hold=false,shared=false,package_sha256=? WHERE id=?")
        .param(JudgeJson.hash("changed"))
        .param(version)
        .update();
    assertThat(images.presentation("alice", version).illustrations()).isEmpty();
    assertThatThrownBy(() -> images.image("alice", key)).isInstanceOf(AccountException.class);
  }

  @Test
  void exactUploadRetryIsSingleAttachmentAndChangedRetryConflicts() {
    UUID key = upload();
    images.upload("alice", version, key, png, "이동 방향", "벽을 넘을 수 없음");
    assertThat(images.presentation("alice", version).illustrations()).hasSize(1);
    assertThatThrownBy(() -> images.upload("alice", version, key, png, "다른 내용", ""))
        .isInstanceOf(AccountException.class);
    assertThatThrownBy(() -> images.delete("bob", version, key))
        .isInstanceOf(AccountException.class);
    images.delete("alice", version, key);
    images.delete("alice", version, key);
    assertThat(images.presentation("alice", version).illustrations()).isEmpty();
  }

  @Test
  void rejectsActiveFormatsOversizedDimensionsAndEmptyAlternativeText() throws Exception {
    for (byte[] bad :
        List.of(
            "<svg onload='alert(1)'/>".getBytes(),
            bitmap("gif", 32, 24),
            bitmap("png", 5001, 1),
            new byte[3 * 1024 * 1024 + 1]))
      assertThatThrownBy(() -> images.upload("alice", version, UUID.randomUUID(), bad, "규칙", ""))
          .isInstanceOf(AccountException.class);
    assertThatThrownBy(() -> images.upload("alice", version, UUID.randomUUID(), png, " ", ""))
        .isInstanceOf(AccountException.class);
    var normalized =
        images.upload("alice", version, UUID.randomUUID(), bitmap("jpeg", 30, 20), "JPEG", "");
    assertThat(images.image("alice", normalized.illustrations().getFirst().id()))
        .startsWith((byte) 137, (byte) 80, (byte) 78, (byte) 71);
  }

  @Test
  void uploadCapAndOwnerDeletionPreserveArchivedSharedProblem() {
    for (int i = 0; i < 8; i++) upload();
    assertThatThrownBy(this::upload).isInstanceOf(AccountException.class);
    jdbc.sql("UPDATE problem_version SET owner_id=NULL,shared=true WHERE id=?")
        .param(version)
        .update();
    jdbc.sql("DELETE FROM app_user WHERE id=?").param(alice).update();
    assertThat(images.presentation("bob", version).illustrations()).hasSize(8);
  }

  @Test
  void diagnosticProblemsAreNotAnAttachmentUploadBackdoor() {
    UUID key = upload();
    jdbc.sql("UPDATE problem_version SET diagnostic_only=true WHERE id=?").param(version).update();
    assertThatThrownBy(() -> images.image("alice", key)).isInstanceOf(AccountException.class);
    assertThatThrownBy(() -> images.upload("alice", version, UUID.randomUUID(), png, "금지", ""))
        .isInstanceOf(AccountException.class);
  }

  @Test
  void multipartUsesCsrfAndSameAuthenticatedMediaContract() throws Exception {
    UUID key = UUID.randomUUID();
    var file = new MockMultipartFile("file", "drawing.png", "image/png", png);
    mvc.perform(
            multipart("/api/problems/" + version + "/illustrations")
                .file(file)
                .param("alt", "그림 규칙")
                .header("Idempotency-Key", key)
                .with(user("alice")))
        .andExpect(status().isForbidden());
    mvc.perform(
            multipart("/api/problems/" + version + "/illustrations")
                .file(file)
                .param("alt", "그림 규칙")
                .header("Idempotency-Key", key)
                .with(user("alice"))
                .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.illustrations[0].id").value(key.toString()));
    mvc.perform(get("/api/problem-images/" + key).with(user("bob")))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/problem-images/" + key)).andExpect(status().isUnauthorized());
  }

  @Test
  void curatedInlineContractIncludesExactAnchorAndHidesImagesWhenRulesChange() throws Exception {
    String v = "basic-pool-v1-bfs-medium-02-v1";
    String statement =
        new org.springframework.core.io.ClassPathResource("illustrations-inline-door-statement.txt")
            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    var pkg =
        JudgeJson.JSON
            .createObjectNode()
            .put("version", v)
            .put("title", "한 박자 늦게 열리는 문")
            .put("statement", statement);
    String serialized = pkg.toString();
    jdbc.sql(
            "INSERT INTO"
                + " problem_version(id,package_json,package_sha256,runtime_image,runner_policy,ready,shared)"
                + " SELECT ?,?,?,runtime_image,runner_policy,true,true FROM problem_version WHERE"
                + " id='sum-v1'")
        .param(v)
        .param(serialized)
        .param(JudgeJson.hash(serialized))
        .update();
    var figure = images.presentation("alice", v).illustrations().getFirst();
    assertThat(figure.afterParagraph()).isEqualTo(statement.split("\n\n")[1]);
    assertThat(figure.explanation()).contains("도착 시각");
    assertThat(figure.id()).isNull();
    mvc.perform(get("/api/problems/" + v + "/illustrations").with(user("bob")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.illustrations[0].afterParagraph").value(figure.afterParagraph()))
        .andExpect(jsonPath("$.illustrations[0].explanation").value(figure.explanation()));
    jdbc.sql("UPDATE problem_version SET package_json=? WHERE id=?")
        .param(pkg.put("statement", statement + "새로운 규칙").toString())
        .param(v)
        .update();
    assertThat(images.presentation("alice", v).illustrations()).isEmpty();
  }
}
