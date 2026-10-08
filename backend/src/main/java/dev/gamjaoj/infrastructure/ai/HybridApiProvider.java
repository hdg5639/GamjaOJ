package dev.gamjaoj.infrastructure.ai;

import dev.gamjaoj.service.generation.HybridExecution;
import java.time.*;
import java.util.concurrent.*;
import org.springframework.transaction.event.*;

public interface HybridApiProvider {
  public OpenAiResponses.Result generate(HybridExecution.Work work);
}
