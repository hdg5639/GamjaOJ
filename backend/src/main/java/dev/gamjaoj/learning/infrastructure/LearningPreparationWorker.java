package dev.gamjaoj.learning.infrastructure;

import dev.gamjaoj.learning.service.LearningProblemPreparation;
import dev.gamjaoj.shared.exception.AccountException;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LearningPreparationWorker {
  private final LearningProblemPreparation preparation;

  public LearningPreparationWorker(LearningProblemPreparation preparation) {
    this.preparation = preparation;
  }

  public @Scheduled(
      fixedDelayString = "${LEARNING_PREPARATION_POLL_MS:5000}",
      initialDelayString = "${LEARNING_PREPARATION_POLL_MS:5000}") void advance() {
    for (var row : preparation.pending())
      try {
        preparation.prepare((String) row[0], (UUID) row[1], false);
      } catch (AccountException e) {
        try {
          preparation.failed((String) row[0], (UUID) row[1], e.getMessage());
        } catch (AccountException gone) {
          if (gone.status != 404 && gone.status != 401) throw gone;
        }
      }
  }
}
