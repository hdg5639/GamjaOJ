package dev.gamjaoj.service.generation;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamjaoj.domain.VerificationEntry;
import dev.gamjaoj.repository.generation.VerificationLedgerRepository;
import dev.gamjaoj.support.JudgeJson;
import java.util.*;
import org.springframework.stereotype.Service;

/** All mutations run under the existing ai_budget_lock in the caller's transaction. */
@Service
public class VerificationLedger {
  private final VerificationLedgerRepository repository;

  public VerificationLedger(VerificationLedgerRepository repository) {
    this.repository = repository;
  }

  private static String version(UUID job, int revision) {
    return "generated-" + job + "-r" + revision;
  }

  public VerificationEntry entry(UUID job, int revision) {
    return repository.entryGenerationEvidence(job, revision).orElse(null);
  }

  public boolean active(VerificationEntry entry) {
    return entry != null
        && JudgeJson.hash(entry.snapshotJson()).equals(entry.snapshotSha256())
        && repository.activeGenerationEvidenceRevocation(entry.id()) == 0;
  }

  public UUID freeze(UUID job, int revision, JsonNode report) {
    if (!valid(job)) throw new IllegalStateException("Revoked generation dependency");
    var payload =
        repository.freezeGenerationJob(
            version(job, revision),
            job,
            (r, n) -> {
              var snapshot =
                  JudgeJson.JSON
                      .createObjectNode()
                      .put("format", "generation-evidence-v1")
                      .put("jobId", job.toString())
                      .put("revision", revision)
                      .put("contract", r.getString(3))
                      .put("runtimeImage", r.getString(5))
                      .put("runnerPolicy", r.getString(6));
              snapshot.set("artifacts", JudgeJson.parse(r.getString(1)));
              snapshot.set("oracle", JudgeJson.parse(r.getString(2)));
              snapshot.set("package", JudgeJson.parse(r.getString(4)));
              var validation = report.deepCopy();
              ((com.fasterxml.jackson.databind.node.ObjectNode) validation).remove("evidenceId");
              snapshot.set("validation", validation);
              return snapshot;
            });
    var parent = repository.freezeGenerationDependency(job);
    parent.ifPresent(id -> payload.put("sourceEvidenceId", id.toString()));
    String json = JudgeJson.canonical(payload);
    var existing = entry(job, revision);
    if (existing != null) {
      if (!existing.snapshotJson().equals(json) || !active(existing))
        throw new IllegalStateException("Evidence is immutable or revoked");
      return existing.id();
    }
    UUID id = UUID.randomUUID();
    repository.freezeGenerationEvidence(id, job, revision, json, JudgeJson.hash(json));
    return id;
  }

  public boolean bind(
      UUID target, UUID source, JsonNode artifacts, JsonNode oracle, JsonNode validation) {
    var data = repository.bindGenerationJob(source, target);
    if (data.isEmpty() || !valid(source)) return false;
    var evidence = entry(source, data.get());
    if (!active(evidence) || !available(source, data.get())) return false;
    var saved = JudgeJson.parse(evidence.snapshotJson());
    var validated = validation.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) validated).remove("evidenceId");
    if (!saved.path("artifacts").equals(artifacts)
        || !saved.path("oracle").equals(oracle)
        || !saved.path("validation").equals(validated)) return false;
    repository.bindGenerationDependency(
        target, source, data.get(), evidence.id(), evidence.snapshotSha256());
    return true;
  }

  private boolean available(UUID job, int revision) {
    return repository.availableProblemVersion(version(job, revision)) == 1;
  }

  public boolean valid(UUID job) {
    var seen = new HashSet<UUID>();
    UUID current = job;
    while (seen.add(current)) {
      var dependency =
          repository.validGenerationDependency(
              current,
              (r, n) ->
                  new Object[] {
                    r.getObject(1, UUID.class),
                    r.getInt(2),
                    r.getObject(3, UUID.class),
                    r.getString(4)
                  });
      if (dependency.isEmpty()) {
        // Never silently detach a legacy/partial snapshot from its source.
        return repository.validGenerationJob(current) == 1;
      }
      var d = dependency.get();
      UUID parent = (UUID) d[0];
      int revision = (Integer) d[1];
      var evidence = entry(parent, revision);
      if (!active(evidence)
          || !evidence.id().equals(d[2])
          || !evidence.snapshotSha256().equals(d[3])
          || !available(parent, revision)) return false;
      if (repository.validGenerationJob2(current, parent) != 1) return false;
      current = parent;
    }
    return false;
  }

  public void block(UUID job) {
    repository.blockGenerationJob(job);
  }

  public void revokeTree(UUID owner, UUID root, String reason) {
    // Include old snapshots for conservative hold propagation; do not certify them as evidence.
    var links = new HashMap<UUID, UUID>();
    repository.revokeTreeGenerationJob(
        owner,
        (r, n) -> {
          UUID id = r.getObject(1, UUID.class);
          String raw = r.getString(2);
          if (raw != null) {
            var parent = JudgeJson.parse(raw).path("sourceJobId").asText();
            if (!parent.isBlank()) links.put(id, UUID.fromString(parent));
          }
          return id;
        });
    repository.revokeTreeGenerationDependency(
        owner,
        (r, n) -> {
          links.put(r.getObject(1, UUID.class), r.getObject(2, UUID.class));
          return 0;
        });
    var affected = new HashSet<UUID>();
    affected.add(root);
    boolean changed;
    do {
      changed = false;
      for (var link : links.entrySet())
        if (affected.contains(link.getValue())) changed |= affected.add(link.getKey());
    } while (changed);
    for (UUID id : affected) {
      String propagated = id.equals(root) ? reason : "원본 검증 근거 보류: " + root + " · " + reason;
      propagated = propagated.substring(0, Math.min(500, propagated.length()));
      for (var evidence : repository.revokeTreeGenerationEvidence(id))
        if (repository.revokeTreeGenerationEvidenceRevocation(evidence) == 0)
          repository.revokeTreeGenerationEvidenceRevocation2(evidence, root, propagated);
      // Keep READY records and every prior attempt/report; only new use is held.
      repository.revokeTreeProblemVersion(propagated, owner, id);
      repository.revokeTreeGenerationJob2(id, owner);
    }
  }
}
