package dev.gamjaoj.generation.service;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamjaoj.ai.config.AiSettings;
import dev.gamjaoj.generation.repository.GenerationProseReviewRepository;
import dev.gamjaoj.shared.domain.ArtifactValidation;
import dev.gamjaoj.shared.exception.AccountException;
import dev.gamjaoj.shared.support.JudgeJson;
import java.time.*;
import java.util.*;

/** Independent tag/template prose equivalence; never sees implementation source or hidden tests. */
@org.springframework.stereotype.Service
public class GenerationProseReview {
  private final GenerationProseReviewRepository repository;

  public GenerationProseReview(GenerationProseReviewRepository repository) {
    this.repository = repository;
  }

  public record Decision(UUID job, int revision, String hash, boolean accepted, String error) {}

  public void start(GenerationJobs.View job, JsonNode definition, String rules, JsonNode sample) {
    var input =
        JudgeJson.JSON
            .createObjectNode()
            .put("phase", "TAG_PROSE_REVIEW")
            .put("title", job.artifacts().path("title").asText())
            .put("context", job.artifacts().path("context").asText())
            .put("rules", rules);
    input.set("definition", definition.deepCopy());
    input.set("sample", sample.deepCopy());
    repository.startGenerationProseReview(
        UUID.randomUUID(),
        job.id(),
        job.revision(),
        job.artifactHash(),
        JudgeJson.canonical(input));
  }

  public JsonNode progress(UUID job, int revision) {
    return repository
        .progressGenerationProseReview(job, revision)
        .map(state -> (JsonNode) JudgeJson.JSON.createObjectNode().put("status", state))
        .orElse(null);
  }

  public boolean contains(UUID id) {
    return repository.containsGenerationProseReview(id) > 0;
  }

  public boolean passed(UUID job, int revision, String hash) {
    return repository.passedGenerationProseReview(job, revision, hash) == 1;
  }

  public GenerationJobs.Assignment claim(AiSettings settings) {
    repository.claimGenerationJob();
    repository.claimGenerationProseReview();
    if (repository.claimGenerationProseReview2() > 0) return null;
    var row =
        repository.claimGenerationProseReview3(
            (r, n) -> new String[] {r.getString(1), r.getString(2), r.getString(3)});
    if (row.isEmpty()) return null;
    UUID id = UUID.fromString(row.get()[0]), token = UUID.randomUUID();
    repository.claimGenerationProseReview4(
        token, OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(20), id);
    return new GenerationJobs.Assignment(
        id,
        token,
        Integer.parseInt(row.get()[2]),
        settings.value("CODEX_GENERATION_MODEL", "gpt-6.1-sol"),
        settings.value("CODEX_GENERATION_REASONING", "medium"),
        JudgeJson.parse(row.get()[1]),
        null,
        null,
        null);
  }

  public Decision complete(
      UUID id, UUID token, JsonNode payload, JsonNode oracle, JsonNode usage, String error) {
    var row =
        repository.completeGenerationProseReview(
            id,
            (r, n) ->
                new String[] {
                  r.getString(1),
                  r.getString(2),
                  r.getString(3),
                  r.getString(4),
                  r.getString(5),
                  r.getString(6)
                });
    if (!token.toString().equals(row[3])) throw new AccountException(409, "이전 본문 검수 작업이에요.");
    var receipt = JudgeJson.JSON.createObjectNode();
    receipt.set("artifacts", payload);
    receipt.set("oracle", oracle);
    receipt.set("usage", usage);
    receipt.put("error", error);
    String audit = JudgeJson.canonical(receipt);
    if (audit.length() > 600000) throw new AccountException(400, "본문 검수 결과가 너무 커요.");
    if (row[5] != null) {
      if (row[5].equals(audit)) return null;
      throw new AccountException(409, "저장된 본문 검수와 달라요.");
    }
    if (!"GENERATING".equals(row[4]) || repository.completeGenerationProseReview2(id) != 1)
      throw new AccountException(409, "본문 검수 시간이 지났어요.");
    boolean accepted = false;
    if (error == null)
      try {
        HybridArtifacts.fields(payload, "accepted", "issues");
        HybridArtifacts.require(payload.path("accepted").isBoolean(), "INVALID_PROSE_REVIEW");
        HybridArtifacts.texts(payload.path("issues"), 0, 8, 2000);
        HybridArtifacts.require(oracle == null || oracle.isNull(), "INVALID_PROSE_REVIEW");
        accepted = payload.path("accepted").asBoolean() && payload.path("issues").isEmpty();
        error = accepted ? null : "PROSE_REVIEW_REJECTED";
      } catch (ArtifactValidation.Invalid invalid) {
        error = "INVALID_PROSE_REVIEW";
      }
    repository.completeGenerationProseReview3(audit, accepted ? "PASSED" : "FAILED", id);
    UUID job = UUID.fromString(row[0]);
    int revision = Integer.parseInt(row[1]);
    if (repository.completeGenerationJob(job, revision, row[2]) != 1) return null;
    return new Decision(job, revision, row[2], accepted, error);
  }
}
