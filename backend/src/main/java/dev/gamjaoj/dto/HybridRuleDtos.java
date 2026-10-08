package dev.gamjaoj.dto;

import java.util.UUID;

public final class HybridRuleDtos {
  private HybridRuleDtos() {}

  public record Request(
      String request,
      String difficulty,
      String style,
      String category,
      Boolean publish,
      Boolean shared,
      UUID evaluationId,
      Integer observationIndex,
      Integer thinkingLayer) {}

  public record Sharing(Boolean shared) {}

  public record Retry(java.util.UUID attemptId) {}

  public record Owned(String id, String label, String category, String status, boolean shared) {}
}
