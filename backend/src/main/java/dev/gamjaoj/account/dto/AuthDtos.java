package dev.gamjaoj.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthDtos {
  private AuthDtos() {}

  public record Signup(
      @NotBlank @Pattern(regexp = "[a-z0-9_]{3,24}") String username,
      @NotBlank @Size(min = 8, max = 72) String password,
      @NotBlank @Size(max = 24) String nickname,
      @Size(max = 128) String inviteCode) {}

  public record Preferences(
      @NotBlank @Size(max = 24) String nickname,
      @jakarta.validation.constraints.NotNull @Size(max = 120) String trainingGoal) {}

  public record Deletion(
      @NotBlank @Size(max = 72) String password, @NotBlank @Size(max = 24) String confirmation) {}
}
