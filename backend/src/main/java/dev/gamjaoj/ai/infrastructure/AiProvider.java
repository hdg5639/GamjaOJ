package dev.gamjaoj.ai.infrastructure;

import dev.gamjaoj.ai.config.AiSettings;

public interface AiProvider {
  public OpenAiResponses.Result feedback(AiSettings.Model settings, String input);
}
