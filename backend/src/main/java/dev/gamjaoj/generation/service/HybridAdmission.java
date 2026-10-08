package dev.gamjaoj.generation.service;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamjaoj.ai.config.AiSettings;
import dev.gamjaoj.generation.repository.HybridAdmissionRepository;
import dev.gamjaoj.judge.service.Submissions;
import dev.gamjaoj.shared.domain.ArtifactValidation;
import dev.gamjaoj.shared.exception.AccountException;
import dev.gamjaoj.shared.support.JudgeJson;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner opt-in for explicit fixed semantics. The creative-contract compatibility route is separate.
 */
@Service
public class HybridAdmission {
  public static final String PROFILE = "zero-one-items-v1";
  private final HybridAdmissionRepository repository;
  private final HybridExecution execution;
  private final HybridGeneration jobs;
  private final AiSettings settings;
  private final Submissions submissions;
  private final GenerationSpecDrafts drafts;
  private final HybridRuleRegistry registry;

  public HybridAdmission(
      HybridAdmissionRepository repository,
      HybridExecution execution,
      HybridGeneration jobs,
      AiSettings settings,
      Submissions submissions,
      GenerationSpecDrafts drafts,
      HybridRuleRegistry registry) {
    this.repository = repository;
    this.execution = execution;
    this.jobs = jobs;
    this.settings = settings;
    this.submissions = submissions;
    this.drafts = drafts;
    this.registry = registry;
  }

  /**
   * verifiedReference: a previously published implementation of these exact rules can be reused.
   */
  public record Profile(
      String id,
      String label,
      String description,
      List<String> rules,
      boolean verifiedReference,
      String category,
      List<String> tags) {}

  private boolean reuseEnabled() {
    return Boolean.parseBoolean(settings.value("HYBRID_REFERENCE_REUSE_ENABLED", "false"));
  }

  private UUID viewer(String user) {
    return repository.viewerAppUser(user).orElse(null);
  }

  /**
   * Registered packages always reuse their qualified implementation; built-ins follow the reuse
   * flag.
   */
  private boolean reuse(HybridRuleRegistry.Version v) {
    return HybridRulePackage.ENGINE.equals(v.engine()) || reuseEnabled();
  }

  private List<Profile> profiles(String user) {
    return registry.selectable(viewer(user)).stream()
        .map(
            v -> {
              var rules = new ArrayList<String>();
              v.catalog().path("rules").forEach(r -> rules.add(r.asText()));
              var tags = new ArrayList<String>();
              v.catalog().path("tags").forEach(t -> tags.add(t.asText()));
              return new Profile(
                  v.id(),
                  v.label(),
                  v.catalog().path("description").asText(),
                  List.copyOf(rules),
                  reuse(v) && registry.qualifiedReference(v.id()).isPresent(),
                  v.category(),
                  List.copyOf(tags));
            })
        .toList();
  }

  public record Options(boolean enabled, String message, List<Profile> profiles) {}

  public Options options(String user) {
    boolean enabled =
        Boolean.parseBoolean(settings.value("HYBRID_PUBLIC_ADMISSION_ENABLED", "false"))
            && Arrays.stream(settings.value("HYBRID_ALLOWED_USERS", "").split(","))
                .map(String::trim)
                .anyMatch(entry -> entry.equals("*") || entry.equals(user))
            && Boolean.parseBoolean(settings.value("HYBRID_ADMISSION_ENABLED", "false"))
            && Boolean.parseBoolean(settings.value("HYBRID_CONTENT_REVIEW_ENABLED", "false"))
            && Boolean.parseBoolean(settings.value("HYBRID_API_WORKER_ENABLED", "false"))
            && settings.enabled()
            && !settings.key().isBlank()
            && HybridFiniteProfile.PACKAGE_POLICY.equals(
                settings.value("HYBRID_VALIDATION_PROFILE", ""));
    if (enabled)
      try {
        for (var role :
            List.of(
                HybridGeneration.Role.PRESENTATION,
                HybridGeneration.Role.READER,
                HybridGeneration.Role.CONTENT_REVIEW)) HybridModels.slot(settings, role);
      } catch (AccountException unavailable) {
        enabled = false;
      }
    return new Options(
        enabled,
        enabled
            ? "검증을 통과한 문제만 게시합니다. 실패한 단계만 한도 내에서 자동 보완하며, 인증·예산·처리 한도 문제는 상태를 보존하고 중단합니다."
            : "아직 이 계정에서는 실험 출제를 시작할 수 없어요. 기존 출제 방식은 계속 이용할 수 있습니다.",
        profiles(user));
  }

  /**
   * Whether this user may admit the given registered rule version now (flags, allowlist, registry).
   */
  public boolean available(String user, String versionId) {
    var o = options(user);
    return o.enabled() && o.profiles().stream().anyMatch(p -> p.id().equals(versionId));
  }

  public List<Profile> selectable(String user) {
    var o = options(user);
    return o.enabled() ? o.profiles() : List.of();
  }

  public @Transactional HybridGeneration.Progress create(String user, UUID id, JsonNode request) {
    try {
      HybridArtifacts.require(request != null && request.isObject(), "INVALID_REQUEST");
      var shape = (com.fasterxml.jackson.databind.node.ObjectNode) request.deepCopy();
      shape.remove("theme");
      HybridArtifacts.fields(shape, "profileId", "shared", "publishOnSuccess");
      HybridArtifacts.require(
          !request.has("theme")
              || (request.path("theme").isTextual()
                  && request.path("theme").asText().length() <= 1000),
          "INVALID_THEME");
      HybridArtifacts.require(
          request.path("profileId").isTextual()
              && registry.resolve(request.path("profileId").asText(), viewer(user)).isPresent(),
          "UNSUPPORTED_PROFILE");
      HybridArtifacts.require(
          request.path("shared").isBoolean()
              && request.path("publishOnSuccess").isBoolean()
              && request.path("publishOnSuccess").asBoolean(),
          "EXPLICIT_PUBLICATION_REQUIRED");
    } catch (ArtifactValidation.Invalid invalid) {
      throw new AccountException(400, "지원되는 규칙과 검증 후 게시 여부를 선택해 주세요. 자유 요청은 직접 요청하기를 이용해 주세요.");
    }
    repository.createAiBudgetLock();
    String canonical = JudgeJson.canonical(request);
    boolean shared = request.path("shared").asBoolean();
    // An acknowledged or uncertain request can be recovered even after new admission is disabled.
    if (repository.createHybridPublicRequest(id) > 0)
      return jobs.start(user, id, canonical, shared);
    if (!options(user).enabled()) throw new AccountException(503, options(user).message());
    if (repository.createHybridGeneration(id) > 0)
      throw new AccountException(409, "다른 출제 경로에서 사용한 요청 키예요.");
    UUID owner = submissions.owner(user, false);
    if (drafts.active(owner) || repository.createGenerationJob(owner) > 0)
      throw new AccountException(409, "진행 중인 출제를 먼저 마쳐 주세요.");
    execution.admit(user, id, canonical, shared);
    String versionId = request.path("profileId").asText();
    var selected =
        registry
            .resolve(versionId, viewer(user))
            .orElseThrow(() -> new AccountException(409, "선택한 규칙을 지금은 사용할 수 없어요. 목록을 새로 확인해 주세요."));
    var contract = selected.contract();
    repository.createHybridGeneration2(id);
    repository.createHybridPublicRequest2(
        id,
        selected.id(),
        selected.hash(),
        JudgeJson.hash(JudgeJson.canonical(contract)),
        versionId);
    // A reusable rule binds mechanics, not its creator's original story. Instance preferences are
    // separate.
    var requirements = JudgeJson.JSON.createObjectNode().put("mode", "FIXED_RULE");
    requirements.set("selectedContract", contract);
    requirements
        .putObject("presentation")
        .put("policy", "RETHEME_V1")
        .put("theme", request.path("theme").asText("").strip());
    repository
        .createHybridRuleOnboarding(versionId, owner)
        .ifPresent(
            raw -> {
              var original = JudgeJson.parse(raw);
              var targeting = requirements.putObject("targeting");
              for (String key : List.of("difficulty", "style", "category", "target"))
                if (original.has(key)) targeting.set(key, original.get(key));
              // Review retains independent evidence of algorithm/mechanic requirements; the writer
              // never sees this.
              requirements.put("ruleDesignRequest", original.path("request").asText(""));
              requirements.put("sourceThemeBinding", false);
            });
    String requirementsJson = JudgeJson.canonical(requirements);
    repository.createHybridPublicRequest3(requirementsJson, JudgeJson.hash(requirementsJson), id);
    // The selected semantics are already complete. Freeze server data through the same DAG
    // acceptance.
    // There is no model call that merely copies this contract, and no claim of novel semantics.
    var a = jobs.claim(id, HybridGeneration.Role.CONTRACT);
    jobs.complete(
        new HybridGeneration.Completion(
            a.branchId(),
            a.revision(),
            a.role(),
            a.token(),
            a.inputHash(),
            a.contractHash(),
            a.publicHash(),
            contract,
            JudgeJson.JSON
                .createObjectNode()
                .put("executor", "SERVER_FIXED_CONTRACT_V1")
                .put("billingMode", "NONE"),
            null));
    // A verified implementation of the same rule version replaces the author call. Story, reader,
    // every Runner check and final review still run for this instance.
    boolean registered = selected.pkg() != null;
    var reference =
        registered || reuseEnabled()
            ? registry.qualifiedReference(versionId)
            : Optional.<HybridRuleRegistry.Reference>empty();
    // A registered package has no author path of its own: its qualified reference is part of the
    // rule.
    if (registered && reference.isEmpty())
      throw new AccountException(409, "이 규칙의 검증된 정답 코드를 지금은 사용할 수 없어요.");
    if (reference.isPresent()) {
      var core = jobs.claim(id, HybridGeneration.Role.CORE);
      if (core != null) {
        repository.createHybridPublicRequest4(reference.get().id(), id);
        jobs.complete(
            new HybridGeneration.Completion(
                core.branchId(),
                core.revision(),
                core.role(),
                core.token(),
                core.inputHash(),
                core.contractHash(),
                core.publicHash(),
                reference.get().payload(),
                JudgeJson.JSON
                    .createObjectNode()
                    .put("executor", HybridRuleRegistry.REUSED_EXECUTOR)
                    .put("billingMode", "NONE")
                    .put("artifactId", reference.get().id().toString()),
                null));
      }
    }
    return jobs.view(user, id);
  }

  public List<HybridGeneration.Progress> list(String user) {
    UUID owner = submissions.owner(user, false);
    return repository.listHybridGeneration(owner).map(id -> jobs.view(user, id)).toList();
  }
}
