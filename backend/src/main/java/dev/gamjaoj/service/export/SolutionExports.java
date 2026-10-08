package dev.gamjaoj.service.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gamjaoj.config.ExportSettings;
import dev.gamjaoj.domain.ExecutionMetrics;
import dev.gamjaoj.domain.ProblemTitles;
import dev.gamjaoj.exception.AccountException;
import dev.gamjaoj.infrastructure.export.ExportRemote;
import dev.gamjaoj.infrastructure.export.ExportVault;
import dev.gamjaoj.repository.export.SolutionExportsRepository;
import dev.gamjaoj.service.problem.ProblemCatalogMetadata;
import dev.gamjaoj.service.problem.ThinkingDifficulty;
import dev.gamjaoj.support.JudgeJson;
import java.time.*;
import java.util.*;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SolutionExports {
  public record Accepted(UUID submission) {}

  public record Connection(
      UUID id,
      UUID owner,
      String provider,
      String credentials,
      String label,
      JsonNode target,
      boolean auto,
      String status,
      int tokenVersion) {}

  public record ConnectionView(
      String provider,
      boolean available,
      boolean connected,
      String status,
      String account,
      JsonNode target,
      boolean autoEnabled,
      String installUrl) {}

  public record Delivery(
      UUID id,
      String provider,
      String problemVersion,
      String language,
      UUID submissionId,
      String status,
      String error,
      String url,
      OffsetDateTime updatedAt) {}

  public record Work(
      UUID id,
      UUID user,
      String provider,
      UUID lease,
      int revision,
      int attempts,
      JsonNode target,
      JsonNode payload,
      ObjectNode remote) {}

  final SolutionExportsRepository repository;
  final ExportSettings settings;
  final ExportVault vault;
  final ExportRemote remote;
  final NotionTableRegistry tables;
  final TransactionTemplate tx;

  public SolutionExports(
      SolutionExportsRepository repository,
      ExportSettings settings,
      ExportVault vault,
      ExportRemote remote,
      NotionTableRegistry tables,
      PlatformTransactionManager transactions) {
    this.repository = repository;
    this.settings = settings;
    this.vault = vault;
    this.remote = remote;
    this.tables = tables;
    tx = new TransactionTemplate(transactions);
  }

  public static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  public UUID owner(String username) {
    return repository
        .ownerAppUser(username)
        .orElseThrow(() -> new AccountException(401, "다시 로그인해 주세요."));
  }

  public Connection connection(UUID user, String provider) {
    return repository
        .connectionExportConnection(
            user,
            provider,
            (r, n) ->
                new Connection(
                    r.getObject("id", UUID.class),
                    user,
                    provider,
                    r.getString("credentials"),
                    r.getString("account_label"),
                    r.getString("target_json") == null
                        ? null
                        : JudgeJson.parse(r.getString("target_json")),
                    r.getBoolean("auto_enabled"),
                    r.getString("status"),
                    r.getInt("token_version")))
        .orElseThrow(() -> new AccountException(409, "먼저 계정을 연결해 주세요."));
  }

  public List<ConnectionView> connections(String username) {
    UUID user = owner(username);
    var result = new ArrayList<ConnectionView>();
    for (String provider : List.of("GITHUB", "NOTION")) {
      Connection c = null;
      try {
        c = connection(user, provider);
      } catch (AccountException ignored) {
      }
      result.add(
          new ConnectionView(
              provider,
              settings.ready(provider),
              c != null,
              c == null ? "DISCONNECTED" : c.status,
              c == null ? null : c.label,
              c == null ? null : c.target,
              c != null && c.auto,
              provider.equals("GITHUB") ? settings.installUrl() : null));
    }
    return result;
  }

  public String start(String username, String provider) {
    if (!settings.ready(provider)) throw new AccountException(503, "이 서비스의 연동을 준비 중이에요.");
    UUID user = owner(username);
    String state = vault.random(), verifier = vault.random();
    tx.executeWithoutResult(
        s -> {
          repository.startAppUser(user);
          repository.startExportOauth(user, provider);
          repository.startExportOauth2(
              user,
              provider,
              JudgeJson.hash(state),
              vault.seal(
                  "oauth:" + user + ":" + provider,
                  JudgeJson.JSON.getNodeFactory().textNode(verifier)),
              now().plusMinutes(10));
        });
    return remote.authorization(provider, state, verifier);
  }

  public void callback(String username, String provider, String state, String code) {
    if (!settings.ready(provider)
        || state == null
        || state.length() > 200
        || code == null
        || code.isBlank()
        || code.length() > 2000) throw new AccountException(400, "계정 연결을 다시 시작해 주세요.");
    UUID user = owner(username);
    String hash = JudgeJson.hash(state);
    String encrypted =
        tx.execute(
            s -> {
              var row =
                  repository
                      .callbackExportOauth(user, provider, hash, now())
                      .orElseThrow(() -> new AccountException(400, "만료되거나 이미 사용한 연결 요청이에요."));
              repository.callbackExportOauth2(user, provider, hash);
              return row;
            });
    var credentials =
        normalize(
            remote.exchange(
                provider, code, vault.open("oauth:" + user + ":" + provider, encrypted).asText()),
            null);
    String account = remote.account(provider, credentials);
    tx.executeWithoutResult(
        s -> {
          if (repository.callbackExportOauth3(user, provider, hash, now()).isEmpty())
            throw new AccountException(409, "취소된 연결 요청이에요.");
          boolean sameAccount = false;
          var old = repository.callbackExportConnection(user, provider);
          if (old.isPresent() && !credentials.path("provider_account_id").asText().isBlank()) {
            try {
              sameAccount =
                  credentials
                      .path("provider_account_id")
                      .asText()
                      .equals(
                          vault
                              .open(user + ":" + provider, old.get())
                              .path("provider_account_id")
                              .asText());
            } catch (ExportRemote.Failure ignored) {
            }
          }
          if (sameAccount) {
            repository.callbackExportConnection2(
                UUID.randomUUID(),
                vault.seal(user + ":" + provider, credentials),
                account.substring(0, Math.min(200, account.length())),
                user,
                provider);
            repository.callbackSolutionExport(now(), user, provider);
          } else {
            repository.callbackExportConnection3(user, provider);
            repository.callbackExportConnection4(
                UUID.randomUUID(),
                user,
                provider,
                vault.seal(user + ":" + provider, credentials),
                account.substring(0, Math.min(200, account.length())));
          }
          repository.callbackExportOauth4(user, provider, hash);
        });
  }

  public static ObjectNode normalize(JsonNode token, JsonNode old) {
    var result = (ObjectNode) token.deepCopy();
    if (!result.hasNonNull("refresh_token") && old != null && old.hasNonNull("refresh_token"))
      result.set("refresh_token", old.path("refresh_token"));
    if (old != null && old.hasNonNull("provider_account_id"))
      result.set("provider_account_id", old.path("provider_account_id"));
    result.put(
        "expires_at",
        result.has("expires_in")
            ? now().plusSeconds(result.path("expires_in").asLong()).toString()
            : "");
    return result;
  }

  public String access(Connection c) {
    if (!c.status.equals("CONNECTED")) throw new ExportRemote.Failure("RECONNECT_REQUIRED", false);
    var tokens = vault.open(c.owner + ":" + c.provider, c.credentials);
    String expires = tokens.path("expires_at").asText();
    if (expires.isBlank() || OffsetDateTime.parse(expires).isAfter(now().plusMinutes(2)))
      return tokens.path("access_token").asText();
    if (tokens.path("refresh_token").asText().isBlank())
      throw new ExportRemote.Failure("RECONNECT_REQUIRED", false);
    int claimed =
        repository.accessExportConnection(now().plusMinutes(1), c.id, c.tokenVersion, now());
    if (claimed != 1) throw new ExportRemote.Failure("CONNECTION_BUSY", true);
    try {
      var next =
          normalize(remote.refresh(c.provider, tokens.path("refresh_token").asText()), tokens);
      if (repository.accessExportConnection2(
              vault.seal(c.owner + ":" + c.provider, next), c.id, c.tokenVersion)
          != 1) throw new ExportRemote.Failure("CONNECTION_CHANGED", false);
      return next.path("access_token").asText();
    } catch (ExportRemote.Failure e) {
      // Rotating refresh-token responses can be lost. Require reconnection instead of replaying an
      // unknown exchange.
      repository.accessExportConnection3(c.id, c.tokenVersion);
      throw e;
    }
  }

  public List<ExportRemote.Target> targets(String username, String provider, String search) {
    if (!settings.ready(provider)) throw new AccountException(503, "연동을 준비 중이에요.");
    var c = connection(owner(username), provider);
    return remote.targets(
        provider,
        access(c),
        search == null ? "" : search.substring(0, Math.min(100, search.length())));
  }

  public void save(
      String username, String provider, String target, String branch, String prefix, boolean auto) {
    save(username, provider, target, branch, prefix, auto, "legacy");
  }

  public void save(
      String username,
      String provider,
      String target,
      String branch,
      String prefix,
      boolean auto,
      String layout) {
    if (!settings.ready(provider)) throw new AccountException(503, "연동을 준비 중이에요.");
    var c = connection(owner(username), provider);
    var selected = remote.target(provider, access(c), target, branch, prefix);
    if (provider.equals("GITHUB")) {
      String style = layout == null || layout.isBlank() ? "problem-v1" : layout;
      if (!Set.of("legacy", "problem-v1").contains(style))
        throw new AccountException(400, "저장 경로 형식을 확인해 주세요.");
      selected = ((ObjectNode) selected.deepCopy()).put("layout", style);
    }
    final JsonNode savedTarget = selected;
    tx.executeWithoutResult(
        s -> {
          if (repository.saveExportConnection(JudgeJson.canonical(savedTarget), auto, c.id) != 1)
            throw new AccountException(409, "연결이 변경됐어요. 새로고침해 주세요.");
          repository.saveSolutionExport(c.owner, provider, targetHash(savedTarget));
        });
  }

  public void automatic(String username, String provider, boolean enabled) {
    if (!settings.ready(provider)) throw new AccountException(503, "연동을 준비 중이에요.");
    UUID user = owner(username);
    if (repository.automaticExportConnection(enabled, user, provider) != 1)
      throw new AccountException(409, "연결과 저장 위치를 먼저 설정해 주세요.");
  }

  public void disconnect(String username, String provider) {
    UUID user = owner(username);
    tx.executeWithoutResult(
        s -> {
          repository.disconnectAppUser(user);
          repository.disconnectExportOauth(user, provider);
          repository.disconnectExportConnection(user, provider);
        });
  }

  @EventListener
  @Transactional(propagation = Propagation.MANDATORY)
  public void accepted(Accepted event) {
    if (!settings.enabled()) return;
    UUID user = repository.acceptedSubmission(event.submission);
    for (String provider : repository.acceptedExportConnection(user))
      enqueue(user, provider, event.submission, false);
  }

  public UUID request(String username, String provider, UUID submission) {
    if (!settings.ready(provider)) throw new AccountException(503, "연동을 준비 중이에요.");
    return tx.execute(s -> enqueue(owner(username), provider, submission, true));
  }

  public UUID enqueue(UUID user, String provider, UUID submission, boolean manual) {
    // Ownership and exportability are resolved in one query; internal/diagnostic/custom-run code is
    // never exported.
    var rows =
        repository.enqueueSubmission(
            submission,
            user,
            (r, n) -> {
              var p = JudgeJson.parse(r.getString("package_json"));
              String language = r.getString("language");
              String version = r.getString("problem_version");
              if (!version.matches("[A-Za-z0-9_.-]{1,80}")
                  || version.equals(".")
                  || version.equals(".."))
                throw new AccountException(409, "이 문제의 저장 경로를 만들 수 없어요.");
              var metadata = ProblemCatalogMetadata.read(r, version);
              var result =
                  ExportRemote.obj()
                      .put("difficulty", metadata.difficulty())
                      .put("difficultySource", metadata.difficultySource())
                      .put("category", metadata.category());
              result.set("tags", JudgeJson.JSON.valueToTree(metadata.tags()));
              result.set("thinking", JudgeJson.JSON.valueToTree(ThinkingDifficulty.read(r)));
              var report = JudgeJson.parse(r.getString("judge_result_json"));
              Long wall = ExecutionMetrics.maximum(report, "wall_ms"),
                  memory = ExecutionMetrics.maximum(report, "memory_peak_bytes");
              if (wall != null) result.put("maxWallMs", wall);
              if (memory != null) result.put("maxMemoryBytes", memory);
              return result
                  .put("username", r.getString("username"))
                  .put("problemVersion", version)
                  .put("language", language)
                  .put("title", ProblemTitles.display(p))
                  .put("source", r.getString("source_code"))
                  .put("createdAt", r.getObject("created_at", OffsetDateTime.class).toString())
                  .put("finishedAt", r.getObject("finished_at", OffsetDateTime.class).toString())
                  .put(
                      "problemUrl",
                      settings.origin() + "/?problem=" + ExportRemote.enc(version) + "#practice")
                  .put(
                      "filename",
                      language.equals("JAVA")
                          ? (r.getString("callable_package") != null || p.has("api")
                              ? "UserSolution.java"
                              : "Main.java")
                          : language.equals("CPP")
                              ? (r.getString("callable_package") != null || p.has("api")
                                  ? "UserSolution.cpp"
                                  : "Main.cpp")
                              : (r.getString("callable_package") != null || p.has("api")
                                  ? "UserSolution.py"
                                  : "Main.py"));
            });
    if (rows.isEmpty()) {
      if (manual) throw new AccountException(404, "저장할 수 있는 본인의 정식 통과 제출을 선택해 주세요.");
      return null;
    }
    if (repository.enqueueExportConnection(user, provider).isEmpty()) {
      if (manual) throw new AccountException(409, "연결과 저장 위치를 먼저 설정해 주세요.");
      return null;
    }
    var c = connection(user, provider);
    if (!manual && !c.auto) return null;
    var p = rows.get();
    String target = JudgeJson.canonical(c.target), hash = targetHash(c.target);
    var created = OffsetDateTime.parse(p.path("createdAt").asText());
    var existing =
        repository.enqueueSolutionExport(
            user,
            provider,
            hash,
            p.path("problemVersion").asText(),
            p.path("language").asText(),
            (r, n) ->
                new Object[] {
                  r.getObject(1, UUID.class),
                  r.getObject(2, UUID.class),
                  r.getObject(3, OffsetDateTime.class),
                  r.getString(4)
                });
    if (existing.isPresent()) {
      var row = existing.get();
      UUID id = (UUID) row[0];
      if (row[1].equals(submission) || created.isBefore((OffsetDateTime) row[2])) {
        if (manual && "CANCELLED".equals(row[3]))
          repository.enqueueSolutionExport2(now(), target, id);
        return id;
      }
      repository.enqueueSolutionExport3(
          submission, created, JudgeJson.canonical(p), target, now(), now(), id);
      return id;
    }
    UUID id = UUID.randomUUID();
    repository.enqueueSolutionExport4(
        id,
        user,
        provider,
        hash,
        target,
        p.path("problemVersion").asText(),
        p.path("language").asText(),
        submission,
        created,
        JudgeJson.canonical(p));
    return id;
  }

  public static String targetHash(JsonNode target) {
    var identity = ExportRemote.obj().put("id", target.path("id").asText());
    if (target.path("layout").asText().equals("problem-v1")) identity.put("layout", "problem-v1");
    if (target.has("kind")) identity.put("kind", target.path("kind").asText());
    if (target.has("repo"))
      identity
          .put("branch", target.path("branch").asText())
          .put("prefix", target.path("prefix").asText());
    return JudgeJson.hash(JudgeJson.canonical(identity));
  }

  public List<Delivery> deliveries(String username, UUID submission) {
    return repository.findDeliveries(
        owner(username),
        submission,
        (r, n) ->
            new Delivery(
                r.getObject("id", UUID.class),
                r.getString("provider"),
                r.getString("problem_version"),
                r.getString("language"),
                r.getObject("submission_id", UUID.class),
                r.getString("status"),
                r.getString("error_code"),
                r.getString("external_url"),
                r.getObject("updated_at", OffsetDateTime.class)));
  }

  public void retry(String username, UUID id) {
    UUID user = owner(username);
    int changed = repository.retrySolutionExport(now(), id, user);
    if (changed != 1) throw new AccountException(409, "연결 상태와 전송 상태를 확인해 주세요.");
  }

  public Work claim() {
    return tx.execute(
        s -> {
          repository.claimSolutionExport(now(), now());
          var candidates = repository.claimSolutionExport2(now());
          if (candidates.isEmpty()) return null;
          UUID id = candidates.getFirst(), lease = UUID.randomUUID();
          repository.claimSolutionExport3(lease, now().plusSeconds(60), now(), id);
          return repository.claimSolutionExport4(
              id,
              (r, n) ->
                  new Work(
                      id,
                      r.getObject("user_id", UUID.class),
                      r.getString("provider"),
                      lease,
                      r.getInt("revision"),
                      r.getInt("attempts"),
                      JudgeJson.parse(r.getString("target_json")),
                      JudgeJson.parse(r.getString("payload_json")),
                      (ObjectNode) JudgeJson.parse(r.getString("remote_json"))));
        });
  }

  public void fence(Work w) {
    if (repository.fenceSolutionExport(now().plusSeconds(60), w.id, w.lease, now()) != 1)
      throw new ExportRemote.Failure("CONNECTION_CHANGED", false);
  }

  public void checkpoint(Work w, ObjectNode state) {
    fence(w);
    if (repository.checkpointSolutionExport(JudgeJson.canonical(state), w.id, w.lease) != 1)
      throw new ExportRemote.Failure("CONNECTION_CHANGED", false);
  }

  public void finish(Work w, String url, ExportRemote.Failure failure) {
    tx.executeWithoutResult(
        s -> {
          repository.finishExportConnection(w.user, w.provider);
          if (repository.finishSolutionExport(w.id, w.lease).isEmpty()) return;
          String status =
              failure == null
                  ? "SUCCEEDED"
                  : failure.retryable && w.attempts < 6 ? "RETRY" : "FAILED";
          if (failure != null && failure.code.equals("RECONNECT_REQUIRED"))
            repository.finishExportConnection2(w.user, w.provider);
          repository.finishSolutionExport2(
              w.revision,
              status,
              url,
              failure == null ? null : failure.code,
              now()
                  .plusSeconds(
                      failure == null ? 0 : Math.min(900, 10L * (1L << Math.min(w.attempts, 6)))),
              now(),
              w.id,
              w.lease);
        });
  }

  public boolean runOne() {
    var w = claim();
    if (w == null) return false;
    try {
      if (!settings.ready(w.provider)) throw new ExportRemote.Failure("SERVICE_UNAVAILABLE", false);
      var c = connection(w.user, w.provider);
      String token = access(c);
      fence(w);
      JsonNode target = w.target;
      if (w.provider.equals("NOTION") && target.path("kind").asText().equals("notion_table_parent"))
        target = tables.resolve(w.user, token, target, remote, () -> fence(w));
      String url =
          remote.publish(
              w.provider,
              token,
              target,
              w.payload,
              w.remote,
              state -> checkpoint(w, state),
              () -> fence(w));
      finish(w, url, null);
    } catch (ExportRemote.Failure e) {
      finish(w, null, e);
    } catch (AccountException e) {
      finish(w, null, new ExportRemote.Failure("CONNECTION_CHANGED", false));
    } catch (RuntimeException e) {
      finish(w, null, new ExportRemote.Failure("DELIVERY_ERROR", true));
    }
    return true;
  }
}
