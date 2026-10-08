package dev.gamjaoj.ai.infrastructure;

import dev.gamjaoj.ai.service.AiTasks;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AiWorker {
  private final AiTasks tasks;
  private final AiProvider provider;

  public AiWorker(AiTasks tasks, AiProvider provider) {
    this.tasks = tasks;
    this.provider = provider;
  }

  @Scheduled(fixedDelayString = "${AI_POLL_MS:5000}", initialDelayString = "${AI_POLL_MS:5000}")
  public void tick() {
    tasks.enqueueEndedSessions();
    AiTasks.Work work = tasks.claim();
    if (work == null) return;
    OpenAiResponses.Result result = null;
    OpenAiResponses.Failure failure = null;
    try {
      result = provider.feedback(work.settings(), work.input());
    } catch (OpenAiResponses.Failure e) {
      failure = e;
    } catch (RuntimeException e) {
      failure = new OpenAiResponses.Failure("PROVIDER_FAILURE_USAGE_UNKNOWN", null, null);
    }
    tasks.finish(work, result, failure);
  }
}
