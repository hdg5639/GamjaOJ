package dev.gamjaoj.generation.infrastructure.ai;

import dev.gamjaoj.ai.infrastructure.OpenAiResponses;
import dev.gamjaoj.generation.service.HybridExecution;
import java.time.*;
import java.util.concurrent.*;
import org.springframework.transaction.event.*;

public interface HybridApiProvider {
  public OpenAiResponses.Result generate(HybridExecution.Work work);
}
