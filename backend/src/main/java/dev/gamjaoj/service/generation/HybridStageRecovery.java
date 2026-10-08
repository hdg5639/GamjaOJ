package dev.gamjaoj.service.generation;

import static dev.gamjaoj.service.generation.HybridGeneration.Role.*;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamjaoj.config.AiSettings;
import dev.gamjaoj.exception.AccountException;
import dev.gamjaoj.repository.generation.HybridStageRecoveryRepository;
import dev.gamjaoj.service.ai.AiTasks;
import dev.gamjaoj.support.JudgeJson;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/**
 * Repair the writer/core DAG with fresh budget reservations, retaining every old receipt and
 * branch.
 */
@org.springframework.stereotype.Service
public class HybridStageRecovery {
  private final HybridStageRecoveryRepository repository;

  public HybridStageRecovery(HybridStageRecoveryRepository repository) {
    this.repository = repository;
  }

  public static HybridGeneration.Role target(HybridGeneration.Role failed, JsonNode receipt) {
    if (failed == CONTENT_REVIEW) {
      var review = receipt.path("payload");
      if (!review.path("implementationAligned").asBoolean(true)) return CORE;
      return PRESENTATION;
    }
    return Set.of(CORE, PRESENTATION, READER).contains(failed) ? failed : null;
  }

  public void advance(HybridGeneration jobs, AiSettings settings, AiTasks ledger) {
    for (var id : repository.advanceHybridGeneration()) {
      if (repository.advanceDiagnosticPracticePlan(id) > 0) continue;
      if (repository.advanceHybridApiReservation(id) > 0) continue;
      UUID owner = repository.advanceHybridGeneration2(id);
      if (repository.advanceGenerationSpecDraft(owner) > 0
          || repository.advanceHybridGeneration3(owner, id) > 0) continue;
      var failure =
          repository.advanceHybridBranch(
              id, (r, n) -> new String[] {r.getString(1), r.getString(2), r.getString(3)});
      String resourceError = repository.advanceHybridGeneration4(id);
      if (resourceError != null && resourceError.startsWith("RESOURCE_REFERENCE_")) {
        var root = JudgeJson.JSON.createObjectNode();
        root.putObject("payload")
            .putArray("issues")
            .add(
                resourceError
                    + ": Original reference/validator failed actual full-domain qualification."
                    + " Rebuild the implementation/validator against the same contract; do not"
                    + " shrink maximum inputs. Avoid repeating whole-input regex patterns that can"
                    + " overflow Java8's stack.");
        failure = Optional.of(new String[] {"CORE", resourceError, JudgeJson.canonical(root)});
      }
      if (failure.isEmpty() || GenerationDraftRecovery.stopped(failure.get()[1])) continue;
      // Provider errors without a structured result require explicit recovery.
      if (failure.get()[2] == null) continue;
      var receipt = JudgeJson.parse(failure.get()[2]);
      if (receipt.hasNonNull("error")) continue;
      var target = target(HybridGeneration.Role.valueOf(failure.get()[0]), receipt);
      if (target == null) continue;
      int total = repository.advanceGenerationRecoveryAttempt(id);
      int stage = repository.advanceHybridBranch2(id, target.name());
      if (total >= 6 || stage >= 3) {
        repository.advanceHybridGeneration5(id);
        continue;
      }
      var payable = EnumSet.of(CONTENT_REVIEW);
      if (target == PRESENTATION) {
        payable.add(PRESENTATION);
        payable.add(READER);
      }
      if (target == READER) payable.add(READER);
      var models =
          new EnumMap<HybridGeneration.Role, AiSettings.Model>(HybridGeneration.Role.class);
      BigDecimal amount = BigDecimal.ZERO;
      try {
        for (var role : payable) {
          var model = HybridModels.slot(settings, role);
          models.put(role, model);
          amount = amount.add(HybridExecution.reserve(role, model));
        }
      } catch (AccountException unavailable) {
        continue;
      }
      var budget = ledger.budget();
      if (budget.spentUsd().add(budget.reservedUsd()).add(amount).compareTo(budget.limitUsd()) > 0)
        continue;
      int revision = repository.advanceHybridGeneration6(id);
      for (var role : payable) {
        int next = repository.advanceHybridApiReservation2(id, revision, role.name());
        var model = models.get(role);
        UUID attempt = UUID.randomUUID();
        repository.advanceAiAttempt(
            attempt,
            YearMonth.now(ZoneOffset.UTC).toString(),
            HybridExecution.reserve(role, model),
            JudgeJson.canonical(JudgeJson.JSON.valueToTree(model)));
        repository.advanceHybridApiReservation3(attempt, id, revision, role.name(), next);
      }
      repository.advanceGenerationRecoveryAttempt2(
          id, total + 1, target.name(), failure.get()[1], JudgeJson.canonical(receipt));
      jobs.recoverStage(id, target, receipt.path("payload").path("issues"));
    }
  }
}
