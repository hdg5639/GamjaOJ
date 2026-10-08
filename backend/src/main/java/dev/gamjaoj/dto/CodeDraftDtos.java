package dev.gamjaoj.dto;

public final class CodeDraftDtos {
  private CodeDraftDtos() {}

  public record Save(String scope, String language, String source) {}
}
