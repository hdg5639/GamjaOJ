package dev.gamjaoj.generation.service;

import dev.gamjaoj.generation.repository.GenerationActivityRepository;
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
