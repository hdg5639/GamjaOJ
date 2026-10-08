package dev.gamjaoj.generation.infrastructure.ai;

import dev.gamjaoj.ai.config.AiSettings;
import dev.gamjaoj.ai.infrastructure.OpenAiResponses;
import dev.gamjaoj.generation.service.HybridExecution;
import dev.gamjaoj.generation.service.HybridGeneration;
import dev.gamjaoj.generation.service.HybridModels;
import dev.gamjaoj.shared.support.JudgeJson;
import java.time.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;

@Component
public class ResponsesHybridProvider implements HybridApiProvider {
  private final AiSettings config;

  public ResponsesHybridProvider(AiSettings config) {
    this.config = config;
  }

  public OpenAiResponses.Result generate(HybridExecution.Work work) {
    Duration timeout = timeout(work, OffsetDateTime.now(ZoneOffset.UTC));
    var r = work.request();
    var m = r.settings();
    return new OpenAiResponses(config.key())
        .generate(
            m.model(),
            m.effort(),
            r.instructions(),
            r.input(),
            r.schemaName(),
            r.schema(),
            m.maxOutputTokens(),
            timeout);
  }

  public static Duration timeout(HybridExecution.Work work, OffsetDateTime now) {
    Duration remaining = Duration.between(now, work.deadlineAt());
    if (remaining.isNegative() || remaining.isZero())
      throw new OpenAiResponses.Failure(
          "DEADLINE_BEFORE_DISPATCH",
          JudgeJson.JSON.createObjectNode().put("input_tokens", 0).put("output_tokens", 0),
          null);
    // A callable reader also implements JSON decoding and typed API semantics in its independent
    // oracle.
    var a = work.request().assignment();
    Duration ceiling =
        Duration.ofSeconds(
            a.role() == HybridGeneration.Role.READER && HybridModels.callableInput(a.input())
                ? 280
                : 90);
    return remaining.compareTo(ceiling) > 0 ? ceiling : remaining;
  }
}
