package dev.gamjaoj.service.generation;

import dev.gamjaoj.repository.generation.GenerationActivityRepository;
import java.util.*;

@org.springframework.stereotype.Service
public class GenerationActivity {
  private final GenerationActivityRepository repository;

  public GenerationActivity(GenerationActivityRepository repository) {
    this.repository = repository;
  }

  public boolean active(UUID owner) {
    return repository.activeHybridGeneration(owner) > 0;
  }
}
