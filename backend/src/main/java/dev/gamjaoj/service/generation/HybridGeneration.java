package dev.gamjaoj.service.generation;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamjaoj.config.AiSettings;
import dev.gamjaoj.domain.ArtifactValidation;
import dev.gamjaoj.exception.AccountException;
import dev.gamjaoj.repository.generation.HybridGenerationRepository;
import dev.gamjaoj.service.judge.Submissions;
import dev.gamjaoj.support.JudgeJson;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persisted hybrid DAG. HybridExecution supplies budgeted dispatch; publication remains gated. */
@Service
public class HybridGeneration {
  private final GenerationDraftRecovery generationDraftRecovery;
  private final GenerationResources generationResources;

  public enum Role {
    CONTRACT,
    CORE,
    PRESENTATION,
    READER,
    VALIDATION,
    CONTENT_REVIEW
  }

  private static final Set<String> TERMINAL =
      Set.of("HELD", "FAILED", "CANCELLED", "DEADLINE_EXCEEDED", "PUBLISHED");
  private final HybridGenerationRepository repository;
  private final Submissions submissions;
  private final AiSettings settings;

  public HybridGeneration(
      HybridGenerationRepository repository,
      Submissions submissions,
      AiSettings settings,
      GenerationDraftRecovery generationDraftRecovery,
      GenerationResources generationResources) {
    this.generationDraftRecovery = generationDraftRecovery;
    this.generationResources = generationResources;
    this.repository = repository;
    this.submissions = submissions;
    this.settings = settings;
  }

  /**
   * Whole-generation deadline. Registered rules with large inputs need several exclusive Runner
   * replays, so 120 s was too short.
   */
  public long deadlineSeconds() {
    try {
      return Math.max(
          120,
          Math.min(
              1800, Long.parseLong(settings.value("HYBRID_GENERATION_SECONDS", "600").trim())));
    } catch (NumberFormatException e) {
      return 600;
    }
  }

  public record Job(
      UUID id,
      UUID owner,
      int revision,
      String status,
      int repairs,
      boolean shared,
      String contractHash,
      String publicHash,
      String error,
      OffsetDateTime acceptedAt,
      OffsetDateTime deadlineAt) {}

  public record Branch(
      UUID id,
      UUID generation,
      int revision,
      Role role,
      int attempt,
      String status,
      JsonNode input,
      String inputHash,
      String contractHash,
      String publicHash,
      UUID token,
      String completion,
      String outputHash) {}

  public record Assignment(
      UUID branchId,
      UUID generationId,
      int revision,
      Role role,
      UUID token,
      String inputHash,
      String contractHash,
      String publicHash,
      JsonNode input) {}

  public record Completion(
      UUID branchId,
      int revision,
      Role role,
      UUID token,
      String inputHash,
      String contractHash,
      String publicHash,
      JsonNode payload,
      JsonNode usage,
      String error) {}

  public record Progress(
      UUID id,
      String pipelineVersion,
      int revision,
      String status,
      int repairRounds,
      boolean shared,
      String contractHash,
      String publicHash,
      String error,
      OffsetDateTime acceptedAt,
      OffsetDateTime deadlineAt,
      Map<Role, String> branches,
      String publishedVersionId,
      boolean problemHeld,
      String profileId,
      boolean referenceReused,
      JsonNode recovery,
      JsonNode resources) {}

  private OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  private AccountException conflict() {
    return new AccountException(409, "현재 출제 단계와 맞지 않는 결과예요.");
  }

  private Job job(UUID id, boolean lock) {
    return repository
        .jobHybridGeneration(
            lock,
            id,
            (r, n) ->
                new Job(
                    id,
                    r.getObject("owner_id", UUID.class),
                    r.getInt("revision"),
                    r.getString("status"),
                    r.getInt("repair_rounds"),
                    r.getBoolean("share_on_publish"),
                    r.getString("contract_sha256"),
                    r.getString("public_sha256"),
                    r.getString("error_code"),
                    r.getObject("created_at", OffsetDateTime.class),
                    r.getObject("deadline_at", OffsetDateTime.class)))
        .orElseThrow(() -> new AccountException(404, "출제 기록을 찾을 수 없어요."));
  }

  private Job owned(String username, UUID id, boolean lock) {
    UUID owner = submissions.owner(username, false);
    Job j = job(id, lock);
    if (!j.owner.equals(owner)) throw new AccountException(404, "출제 기록을 찾을 수 없어요.");
    return j;
  }

  private List<Branch> branches(UUID id, int revision) {
    return repository.branchesHybridBranch(
        id,
        revision,
        (r, n) ->
            new Branch(
                r.getObject("id", UUID.class),
                id,
                revision,
                Role.valueOf(r.getString("role")),
                r.getInt("attempt"),
                r.getString("status"),
                JudgeJson.parse(r.getString("input_json")),
                r.getString("input_sha256"),
                r.getString("contract_sha256"),
                r.getString("public_sha256"),
                r.getObject("attempt_token", UUID.class),
                r.getString("completion_json"),
                r.getString("output_sha256")));
  }

  private Branch branch(UUID branchId) {
    var location =
        repository
            .branchHybridBranch(
                branchId, (r, n) -> new Object[] {r.getObject(1, UUID.class), r.getInt(2)})
            .orElseThrow(this::conflict);
    return branches((UUID) location[0], (Integer) location[1]).stream()
        .filter(b -> b.id.equals(branchId))
        .findFirst()
        .orElseThrow();
  }

  private Map<Role, Branch> latest(Job j) {
    var out = new EnumMap<Role, Branch>(Role.class);
    branches(j.id, j.revision).forEach(b -> out.put(b.role, b));
    return out;
  }

  private void budgetLock() {
    repository.budgetLockAiBudgetLock();
  }

  private void status(UUID id, String value, String error) {
    repository.statusHybridGeneration(value, error, now(), id);
    if (TERMINAL.contains(value)
        && !Set.of("VALIDATION_ADAPTER_NOT_CONNECTED", "CONTENT_REVIEW_REQUIRED")
            .contains(error == null ? "" : error)) repository.statusAiAttempt(id);
  }

  private void cancelPending(UUID id, int revision, Set<Role> roles) {
    for (Role role : roles) repository.cancelPendingHybridBranch(now(), id, revision, role.name());
  }

  private boolean expire(Job j) {
    if ((!TERMINAL.contains(j.status)
            || (j.status.equals("HELD")
                && Set.of("VALIDATION_ADAPTER_NOT_CONNECTED", "CONTENT_REVIEW_REQUIRED")
                    .contains(j.error == null ? "" : j.error)))
        && !now().isBefore(j.deadlineAt)) {
      status(j.id, "DEADLINE_EXCEEDED", "INTERACTIVE_DEADLINE_EXCEEDED");
      cancelPending(j.id, j.revision, EnumSet.allOf(Role.class));
      return true;
    }
    return j.status.equals("DEADLINE_EXCEEDED") || !now().isBefore(j.deadlineAt);
  }

  private Branch enqueue(Job j, Role role, JsonNode input, String state) {
    int attempt =
        branches(j.id, j.revision).stream()
                .filter(b -> b.role == role)
                .mapToInt(Branch::attempt)
                .max()
                .orElse(-1)
            + 1;
    if (role == Role.PRESENTATION || role == Role.READER)
      attempt =
          Math.max(attempt, repository.enqueueHybridApiReservation(j.id, j.revision, role.name()));
    String payload = JudgeJson.canonical(HybridArtifacts.bounded(input));
    UUID id = UUID.randomUUID();
    repository.enqueueHybridBranch(
        id,
        j.id,
        j.revision,
        role.name(),
        attempt,
        state,
        payload,
        JudgeJson.hash(payload),
        j.contractHash,
        role == Role.READER || role == Role.VALIDATION || role == Role.CONTENT_REVIEW
            ? j.publicHash
            : null,
        now());
    return branch(id);
  }

  // Trusted compatibility-adapter entry point. Not exposed to HTTP until budget/profile admission
  // exists.
  public @Transactional Progress start(String username, UUID id, String request, boolean shared) {
    UUID owner = submissions.owner(username, false);
    if (request == null || request.isBlank() || request.length() > 2000)
      throw new AccountException(400, "출제 요청을 확인해 주세요.");
    repository.startAiBudgetLock();
    String input =
        JudgeJson.canonical(
            JudgeJson.JSON.createObjectNode().put("request", request).put("shared", shared));
    if (repository.startHybridGeneration(id) > 0) {
      owned(username, id, true);
      String old = repository.startHybridGeneration2(id);
      if (!old.equals(JudgeJson.hash(input))) throw conflict();
      return view(username, id);
    }
    OffsetDateTime accepted = now();
    repository.startHybridGeneration3(
        id,
        owner,
        HybridArtifacts.VERSION,
        input,
        JudgeJson.hash(input),
        shared,
        accepted,
        accepted.plusSeconds(deadlineSeconds()),
        accepted);
    enqueue(
        job(id, false),
        Role.CONTRACT,
        JudgeJson.JSON.createObjectNode().put("request", request),
        "QUEUED");
    return view(username, id);
  }

  public Progress view(String username, UUID id) {
    Job j = owned(username, id, false);
    var states = new EnumMap<Role, String>(Role.class);
    for (Role role : Role.values()) states.put(role, "NOT_STARTED");
    latest(j).forEach((r, b) -> states.put(r, b.status));
    return new Progress(
        id,
        HybridArtifacts.VERSION,
        j.revision,
        j.status,
        j.repairs,
        j.shared,
        j.contractHash,
        j.publicHash,
        j.error,
        j.acceptedAt,
        j.deadlineAt,
        Collections.unmodifiableMap(states),
        repository.viewHybridGeneration(id).orElse(null),
        repository.viewProblemVersion(id) > 0,
        repository.viewHybridPublicRequest(id).orElse(null),
        repository
            .viewHybridBranch(id)
            .filter(Objects::nonNull)
            .map(JudgeJson::parse)
            .map(
                c ->
                    HybridRuleRegistry.REUSED_EXECUTOR.equals(
                        c.path("usage").path("executor").asText()))
            .orElse(false),
        generationDraftRecovery.progress("RULE", id),
        generationResources.progress("RULE", id));
  }

  public @Transactional Assignment claim(UUID id, Role role) {
    budgetLock();
    Job j = job(id, true);
    if (expire(j) || TERMINAL.contains(j.status) || role == Role.VALIDATION) return null;
    Branch b = latest(j).get(role);
    if (b == null || !b.status.equals("QUEUED")) return null;
    UUID token = UUID.randomUUID();
    repository.claimHybridBranch(token, now(), b.id);
    if (role == Role.CONTRACT) status(id, "DESIGNING", null);
    return new Assignment(
        b.id,
        id,
        j.revision,
        role,
        token,
        b.inputHash,
        b.contractHash,
        b.publicHash,
        b.input.deepCopy());
  }

  public @Transactional boolean complete(Completion c) {
    budgetLock();
    Branch location = branch(c.branchId);
    Job j = job(location.generation, true);
    Branch b = branch(c.branchId);
    if (c.token == null
        || !c.token.equals(b.token)
        || c.revision != b.revision
        || c.role != b.role
        || !Objects.equals(c.inputHash, b.inputHash)
        || !Objects.equals(c.contractHash, b.contractHash)
        || !Objects.equals(c.publicHash, b.publicHash)) throw conflict();
    // Provider usage is retained even for cancelled/superseded work. This is not an API ledger
    // settlement.
    JsonNode wire = JudgeJson.JSON.valueToTree(c);
    String completion = JudgeJson.canonical(HybridArtifacts.bounded(wire));
    if (b.completion != null) {
      if (!b.completion.equals(completion)) throw conflict();
      return b.status.equals("SUCCEEDED");
    }
    boolean expired = expire(j);
    boolean late =
        expired
            || Set.of("FAILED", "CANCELLED", "DEADLINE_EXCEEDED", "PUBLISHED").contains(j.status)
            || j.revision != b.revision
            || !b.status.equals("RUNNING")
            || !latest(j).get(b.role).id.equals(b.id);
    repository.completeHybridBranch(completion, late, now(), b.id);
    if (late) return false;
    try {
      HybridArtifacts.require(c.error == null, "PROVIDER_FAILED");
      JsonNode payload =
          switch (b.role) {
            case CONTRACT -> HybridArtifacts.contract(c.payload);
            case CORE -> HybridCoreSupport.assemble(b.input, c.payload);
            case PRESENTATION ->
                HybridPresentationRules.assemble(
                    b.input, c.payload, artifact(latest(j).get(Role.CONTRACT)));
            case READER -> HybridArtifacts.reader(c.payload, b.input.path("semantics"));
            case CONTENT_REVIEW ->
                HybridArtifacts.contentReview(
                    c.payload,
                    b.inputHash,
                    b.input.has("requirements"),
                    b.input.has("thinkingRubric"));
            case VALIDATION ->
                throw new ArtifactValidation.Invalid("VALIDATION_ADAPTER_NOT_CONNECTED");
          };
      String raw = JudgeJson.canonical(payload), hash = JudgeJson.hash(raw);
      repository.completeHybridArtifact(
          b.id, "hybrid-" + b.role.name().toLowerCase(Locale.ROOT) + "-v1", raw, hash, now());
      repository.completeHybridBranch2(hash, b.id);
      if (b.role == Role.CONTRACT) {
        repository.completeHybridGeneration(hash, now(), j.id);
        Job accepted = job(j.id, false);
        var coreInput = JudgeJson.JSON.createObjectNode().set("contract", payload);
        var selected = repository.completeHybridPublicRequest(j.id).map(HybridProfiles::byId);
        if (selected.isPresent()) {
          HybridArtifacts.require(
              payload.equals(selected.get().contract()), "ADMISSION_PROFILE_FENCE");
          ((com.fasterxml.jackson.databind.node.ObjectNode) coreInput)
              .set("serverSupport", HybridCoreSupport.bundle(selected.get()));
        }
        enqueue(accepted, Role.CORE, coreInput, "QUEUED");
        var writerInput = JudgeJson.JSON.createObjectNode().put("language", "ko");
        writerInput.set("semantics", HybridArtifacts.publicSemantics(payload));
        if (coreInput.has("serverSupport"))
          writerInput.set("serverRules", HybridPresentationRules.bundle(selected.orElseThrow()));
        repository
            .completeHybridPublicRequest2(j.id, (r, n) -> r.getString(1))
            .filter(java.util.Objects::nonNull)
            .map(JudgeJson::parse)
            .filter(r -> r.path("presentation").path("policy").asText().equals("RETHEME_V1"))
            .ifPresent(r -> writerInput.set("presentation", r.path("presentation").deepCopy()));
        enqueue(accepted, Role.PRESENTATION, writerInput, "QUEUED");
      }
      if (b.role == Role.PRESENTATION) {
        JsonNode publicInput = HybridArtifacts.publicSnapshot(payload);
        String publicHash = JudgeJson.hash(JudgeJson.canonical(publicInput));
        repository.completeHybridGeneration2(publicHash, now(), j.id);
        enqueue(job(j.id, false), Role.READER, publicInput, "QUEUED");
      }
      join(job(j.id, false));
      return true;
    } catch (ArtifactValidation.Invalid invalid) {
      repository.completeHybridBranch3(invalid.getMessage(), b.id);
      // Stop new claims, but preserve independent in-flight results for a targeted repair.
      // Queued siblings remain durable and are claimable only after explicit repair unholds the
      // job.
      status(j.id, "HELD", invalid.getMessage());
      return false;
    }
  }

  /**
   * Records a quota-limited Codex outcome and queues the same frozen input as a new attempt.
   * Returns the new branch, or null for duplicate/late delivery. Caller owns the API reservation.
   */
  public @Transactional Branch reroute(Completion c) {
    budgetLock();
    Branch location = branch(c.branchId);
    Job j = job(location.generation, true);
    Branch b = branch(c.branchId);
    if (c.token == null
        || !c.token.equals(b.token)
        || c.revision != b.revision
        || c.role != b.role
        || !Objects.equals(c.inputHash, b.inputHash)
        || !Objects.equals(c.contractHash, b.contractHash)
        || !Objects.equals(c.publicHash, b.publicHash)
        || !HybridModels.author(b.role)) throw conflict();
    String completion = JudgeJson.canonical(HybridArtifacts.bounded(JudgeJson.JSON.valueToTree(c)));
    if (b.completion != null) {
      if (!b.completion.equals(completion)) throw conflict();
      return null;
    }
    boolean late =
        expire(j)
            || TERMINAL.contains(j.status)
            || j.revision != b.revision
            || !b.status.equals("RUNNING")
            || !latest(j).get(b.role).id.equals(b.id);
    repository.rerouteHybridBranch(completion, late, now(), b.id);
    if (late) return null;
    repository.rerouteHybridBranch2(b.id);
    return enqueue(j, b.role, b.input, "QUEUED");
  }

  /** Moves a queued, never-claimed author branch to the API lane; no provider call has happened. */
  public boolean queuedAuthor(UUID id, Role role) {
    Job j = job(id, false);
    Branch b = latest(j).get(role);
    return HybridModels.author(role)
        && b != null
        && b.status.equals("QUEUED")
        && !TERMINAL.contains(j.status);
  }

  public void hold(UUID id, String error) {
    status(id, "HELD", error);
  }

  private JsonNode artifact(Branch b) {
    if (b == null || !b.status.equals("SUCCEEDED")) throw conflict();
    var row =
        repository.artifactHybridArtifact(
            b.id, (r, n) -> new String[] {r.getString(1), r.getString(2)});
    if (!JudgeJson.hash(row[0]).equals(row[1]) || !row[1].equals(b.outputHash))
      throw new ArtifactValidation.Invalid("ARTIFACT_INTEGRITY_FAILURE");
    return JudgeJson.parse(row[0]);
  }

  private void join(Job j) {
    var latest = latest(j);
    Branch early =
        latest.get(Role.VALIDATION) != null && latest.get(Role.VALIDATION).status.equals("EARLY")
            ? latest.get(Role.VALIDATION)
            : null;
    if (latest.containsKey(Role.VALIDATION)
        && early == null
        && !latest.get(Role.VALIDATION).status.equals("SUPERSEDED")) return;
    for (Role role : List.of(Role.CONTRACT, Role.CORE, Role.PRESENTATION, Role.READER)) {
      Branch b = latest.get(role);
      if (b == null || !b.status.equals("SUCCEEDED")) return;
      artifact(b);
      if (role != Role.CONTRACT && !Objects.equals(b.contractHash, j.contractHash))
        throw new ArtifactValidation.Invalid("CONTRACT_REVISION_MISMATCH");
    }
    if (!Objects.equals(latest.get(Role.READER).publicHash, j.publicHash))
      throw new ArtifactValidation.Invalid("PUBLIC_REVISION_MISMATCH");
    var manifest =
        JudgeJson.JSON
            .createObjectNode()
            .put("pipelineVersion", HybridArtifacts.VERSION)
            .put("revision", j.revision)
            .put("contractHash", j.contractHash)
            .put("publicHash", j.publicHash);
    var hashes = manifest.putObject("artifacts");
    for (Role role : List.of(Role.CONTRACT, Role.CORE, Role.PRESENTATION, Role.READER))
      hashes.put(role.name(), latest.get(role).outputHash);
    if (early != null) {
      // Checks already queued under this branch were bound to exactly these CONTRACT and CORE
      // outputs.
      var partial = early.input.path("artifacts");
      if (!partial.path("CONTRACT").asText().equals(hashes.path("CONTRACT").asText())
          || !partial.path("CORE").asText().equals(hashes.path("CORE").asText())
          || !early.input.path("contractHash").asText().equals(j.contractHash))
        throw new ArtifactValidation.Invalid("EARLY_VALIDATION_FENCE");
      String raw = JudgeJson.canonical(HybridArtifacts.bounded(manifest));
      repository.joinHybridBranch(raw, JudgeJson.hash(raw), j.publicHash, early.id);
    } else enqueue(j, Role.VALIDATION, manifest, "BLOCKED");
    // A fixture join is never validation evidence. No READY or problem_version write exists here.
    status(j.id, "HELD", "VALIDATION_ADAPTER_NOT_CONNECTED");
  }

  public @Transactional Progress cancel(String username, UUID id) {
    budgetLock();
    Job j = owned(username, id, true);
    if (Set.of("FAILED", "CANCELLED", "DEADLINE_EXCEEDED", "PUBLISHED").contains(j.status))
      return view(username, id);
    if (j.status.equals("HELD") || !expire(j)) {
      status(id, "CANCELLED", "CANCELLED_BY_OWNER");
      cancelPending(id, j.revision, EnumSet.allOf(Role.class));
    }
    return view(username, id);
  }

  public @Transactional Progress repair(
      String username, UUID id, int revision, Role role, String expectedInputHash) {
    budgetLock();
    owned(username, id, false);
    if (repository.repairHybridApiReservation(id) > 0)
      throw new AccountException(409, "실행 경로의 추가 수정은 별도 예산 정책 연결 후 지원해요.");
    Job j = owned(username, id, true);
    if (!now().isBefore(j.deadlineAt)
        || j.status.equals("CANCELLED")
        || j.status.equals("DEADLINE_EXCEEDED")
        || j.revision != revision
        || j.repairs != 0
        || !Set.of(Role.CORE, Role.PRESENTATION, Role.READER).contains(role)) throw conflict();
    Branch old = latest(j).get(role);
    if (old == null || !old.inputHash.equals(expectedInputHash)) throw conflict();
    // The global round is shared by roles. Existing inputs contain no other implementation.
    repository.repairHybridGeneration(now(), id);
    var affected = EnumSet.of(role, Role.VALIDATION);
    if (role == Role.PRESENTATION) affected.add(Role.READER);
    cancelPending(id, revision, affected);
    // Completed downstream records remain immutable, but no longer qualify as the current branch.
    for (Role dependent : affected)
      if (dependent != role) {
        Branch prior = latest(j).get(dependent);
        if (prior != null) repository.repairHybridBranch(prior.id);
      }
    if (role == Role.PRESENTATION) repository.repairHybridGeneration2(id);
    enqueue(job(id, false), role, old.input, "QUEUED");
    return view(username, id);
  }

  /** Called only under the execution budget lock after affected role reservations were admitted. */
  public void recoverStage(UUID id, Role role, JsonNode issues) {
    Job j = job(id, true);
    Branch previous = latest(j).get(role);
    if (!j.status.equals("HELD") || previous == null) throw conflict();
    var affected = EnumSet.of(role, Role.VALIDATION, Role.CONTENT_REVIEW);
    if (role == Role.PRESENTATION) affected.add(Role.READER);
    cancelPending(id, j.revision, affected);
    for (var dependent : affected) {
      var prior = latest(j).get(dependent);
      if (prior != null) repository.recoverStageHybridBranch(prior.id);
    }
    var input = (com.fasterxml.jackson.databind.node.ObjectNode) previous.input.deepCopy();
    if (role != Role.READER && issues.isArray())
      input.put("recoveryFeedback", JudgeJson.canonical(issues));
    var now = now();
    repository.recoverStageHybridGeneration(now.plusMinutes(20), now, id);
    if (role == Role.PRESENTATION) repository.recoverStageHybridGeneration2(id);
    enqueue(job(id, false), role, input, "QUEUED");
  }

  public @Transactional Progress reviseContract(
      String username, UUID id, int revision, String expectedContractHash) {
    budgetLock();
    owned(username, id, false);
    if (repository.reviseContractHybridApiReservation(id) > 0)
      throw new AccountException(409, "실행 경로의 추가 수정은 별도 예산 정책 연결 후 지원해요.");
    Job j = owned(username, id, true);
    if (j.revision != revision
        || !Objects.equals(j.contractHash, expectedContractHash)
        || j.repairs != 0
        || !now().isBefore(j.deadlineAt)
        || Set.of("CANCELLED", "DEADLINE_EXCEEDED").contains(j.status)) throw conflict();
    cancelPending(id, revision, EnumSet.allOf(Role.class));
    repository.reviseContractHybridGeneration(now(), id);
    Branch design = latest(j).get(Role.CONTRACT);
    enqueue(job(id, false), Role.CONTRACT, design.input, "QUEUED");
    return view(username, id);
  }

  public @Transactional void expirePending() {
    budgetLock();
    var ids = repository.expirePendingHybridGeneration(now());
    for (UUID id : ids) expire(job(id, true));
  }
}
