package dev.gamjaoj.infrastructure.ai;

import dev.gamjaoj.config.AiSettings;

public interface AiProvider {
  public OpenAiResponses.Result feedback(AiSettings.Model settings, String input);
}
