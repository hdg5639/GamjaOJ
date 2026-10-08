package dev.gamjaoj.generation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gamjaoj.generation.repository.GenerationDraftRecoveryRepository;
import dev.gamjaoj.shared.support.JudgeJson;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Retries explicit failed stages. Unknown provider receipts and infrastructure never trigger
 * another call.
 */
@org.springframework.stereotype.Service
public class GenerationDraftRecovery {
  private final GenerationActivity generationActivity;
  private final GenerationDraftRecoveryRepository repository;

  public GenerationDraftRecovery(
      GenerationDraftRecoveryRepository repository, GenerationActivity generationActivity) {
    this.generationActivity = generationActivity;
    this.repository = repository;
  }

  public enum Scope {
    TEACHING,
    PROSE,
    IMPLEMENTATION,
    CONTRACT,
    REVIEW,
    FINAL
  }

  private static final List<String> SPEC =
      List.of("token", "completion_json", "spec_json", "spec_sha256");
  private static final List<String> BUILD =
      List.of(
          "build_token",
          "build_completion_json",
          "build_artifacts_json",
          "build_oracle_json",
          "build_sha256",
          "build_report_json",
          "build_inputs_json");
  private static final List<String> REVIEW =
      List.of(
          "review_token",
          "review_completion_json",
          "review_payload_json",
          "review_payload_sha256",
          "review_report_json");
  private static final List<String> FINAL =
      List.of(
          "final_token",
          "final_completion_json",
          "final_plan_json",
          "final_plan_sha256",
          "final_report_json",
          "final_inputs_json");

  public static boolean stopped(String error) {
    if (error == null) return false;
    return List.of(
            "AUTH",
            "QUOTA",
            "TIMEOUT",
            "INTERRUPTED",
            "VERSION_MISMATCH",
            "FENCE",
            "_IE",
            "INFRASTRUCTURE",
            "INCOMPLETE",
            "UNKNOWN",
            "BUDGET",
            "CANCEL",
            "RETRY_LIMIT")
        .stream()
        .anyMatch(error::contains);
  }

  public static Scope scope(String status, String error, JsonNode review) {
    if (stopped(error)) return null;
    if ("FAILED".equals(status)) return Scope.CONTRACT;
    if ("BUILD_FAILED".equals(status))
      return "INVALID_IMPLEMENTATION_PROSE".equals(error) ? Scope.TEACHING : Scope.IMPLEMENTATION;
    if ("REVIEW_REJECTED".equals(status)) {
      try {
        return Scope.valueOf(review.path("failureScope").asText("CONTRACT"));
      } catch (IllegalArgumentException e) {
        return Scope.CONTRACT;
      }
    }
    if ("REVIEW_FAILED".equals(status)) {
      if (error != null
          && (error.startsWith("REVIEW_review-reference_")
              || error.startsWith("REVIEW_review-oracle_")
              || error.startsWith("REVIEW_review-valid-inputs_")
              || error.startsWith("REVIEW_review-invalid-inputs_"))) return Scope.IMPLEMENTATION;
      return Scope.REVIEW;
    }
    if ("FINAL_FAILED".equals(status)) {
      if (error != null
          && (error.contains("REFERENCE")
              || error.contains("ORACLE")
              || error.contains("VALIDATOR"))) return Scope.IMPLEMENTATION;
      return Scope.FINAL;
    }
    if ("FINAL_REJECTED".equals(status)) return Scope.FINAL;
    return null;
  }

  public void advance() {
    for (var id : repository.advanceGenerationSpecDraft()) recover(id);
  }

  private void recover(UUID id) {
    // Completed/ended training goals must not initiate new paid work.
    if (repository.recoverDiagnosticPracticePlan(id) > 0) return;
    UUID owner = repository.recoverGenerationSpecDraft(id);
    if (repository.recoverGenerationSpecDraft2(owner, id) > 0
        || generationActivity.active(owner)
        || repository.recoverGenerationJob(owner) > 0) return;
    ObjectNode snapshot =
        repository.recoverGenerationSpecDraft3(
            id,
            (r, n) -> {
              var value = JudgeJson.JSON.createObjectNode();
              var metadata = r.getMetaData();
              for (int i = 1; i <= metadata.getColumnCount(); i++) {
                String key = metadata.getColumnLabel(i).toLowerCase(Locale.ROOT);
                String text = r.getString(i);
                if (text == null) value.putNull(key);
                else value.put(key, text);
              }
              return value;
            });
    var review =
        snapshot.path("review_payload_json").isNull()
            ? JudgeJson.JSON.createObjectNode()
            : JudgeJson.parse(snapshot.path("review_payload_json").asText());
    String error =
        snapshot.path("error_code").isNull() ? null : snapshot.path("error_code").asText();
    if (stopped(error)) {
      repository.recoverGenerationSpecDraft4(id);
      return;
    }
    var scope = scope(snapshot.path("status").asText(), error, review);
    if ("PROSE".equals(snapshot.path("recovery_scope").asText())
        && !stopped(error)
        && "REVIEW_REJECTED".equals(snapshot.path("status").asText())) scope = Scope.PROSE;
    if (scope == null) return;
    int total = repository.recoverGenerationRecoveryAttempt(id);
    int stage = repository.recoverGenerationRecoveryAttempt2(id, scope.name());
    if (total >= 6 || stage >= 3) {
      repository.recoverGenerationSpecDraft5(id);
      return;
    }
    var executions = snapshot.putArray("executionLinks");
    repository
        .recoverGenerationSpecExecution(
            id,
            (r, n) ->
                JudgeJson.JSON
                    .createObjectNode()
                    .put("role", r.getString(1))
                    .put("submissionId", r.getString(2))
                    .put("expected", r.getString(3)))
        .forEach(executions::add);
    repository.recoverGenerationRecoveryAttempt3(
        id, total + 1, scope.name(), error, JudgeJson.canonical(snapshot));
    for (var group : List.of(SPEC, BUILD, REVIEW, FINAL)) {
      var token = snapshot.path(group.get(0));
      var completion = snapshot.path(group.get(1));
      if (!token.isNull()
          && !completion.isNull()
          && repository.recoverGenerationRecoveryReceipt(id, UUID.fromString(token.asText())) == 0)
        repository.recoverGenerationRecoveryReceipt2(
            id, UUID.fromString(token.asText()), completion.asText());
    }
    var clear = new ArrayList<String>();
    clear.addAll(FINAL);
    String state;
    switch (scope) {
      case TEACHING -> {
        state = "BUILD_QUEUED";
        clear.addAll(REVIEW);
        clear.addAll(
            List.of(
                "build_token", "build_completion_json", "build_report_json", "build_inputs_json"));
      }
      case FINAL -> state = "FINAL_QUEUED";
      case REVIEW -> {
        state = "REVIEW_QUEUED";
        clear.addAll(REVIEW);
      }
      case PROSE -> {
        state = "QUEUED";
        clear.addAll(REVIEW);
        clear.add("token");
        clear.add("completion_json");
      }
      case IMPLEMENTATION -> {
        state = "BUILD_QUEUED";
        clear.addAll(REVIEW);
        clear.addAll(BUILD);
      }
      case CONTRACT -> {
        state = "QUEUED";
        clear.addAll(REVIEW);
        clear.addAll(BUILD);
        clear.addAll(SPEC);
      }
      default -> throw new IllegalStateException();
    }
    if (scope == Scope.FINAL) repository.recoverGenerationSpecExecution2(id);
    else if (scope == Scope.REVIEW || scope == Scope.PROSE)
      repository.recoverGenerationSpecExecution3(id);
    else repository.recoverGenerationSpecExecution4(id);
    String feedback =
        "Retry "
            + (total + 1)
            + "/6; scope="
            + scope
            + "; error="
            + error
            + "; review="
            + JudgeJson.canonical(review);
    repository.recoverGenerationSpecDraft6(
        clear,
        state,
        scope.name(),
        feedback,
        OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(Math.min(120, 10L * (total + 1))),
        id);
  }

  public JsonNode progress(String pipeline, UUID id) {
    int attempts = repository.progressGenerationRecoveryAttempt(pipeline, id);
    if (attempts == 0) return null;
    String scope = repository.progressGenerationRecoveryAttempt2(pipeline, id);
    return JudgeJson.JSON
        .createObjectNode()
        .put("attempt", attempts)
        .put("limit", 6)
        .put("scope", scope);
  }

  public String receipt(UUID id, UUID token) {
    return repository.receiptGenerationRecoveryReceipt(id, token).orElse(null);
  }
}
