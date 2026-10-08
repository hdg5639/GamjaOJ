package dev.gamjaoj.account.service;

import dev.gamjaoj.account.dto.AuthDtos;
import dev.gamjaoj.account.repository.AccountsRepository;
import dev.gamjaoj.shared.exception.AccountException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Accounts implements UserDetailsService {
  private final AccountsRepository repository;
  private final PasswordEncoder passwords;

  public Accounts(AccountsRepository repository, PasswordEncoder passwords) {
    this.repository = repository;
    this.passwords = passwords;
  }

  public record Profile(UUID id, String username, String nickname, String trainingGoal) {}

  @Override
  public org.springframework.security.core.userdetails.UserDetails loadUserByUsername(
      String username) {
    return repository
        .loadUserByUsernameAppUser(
            username,
            (row, index) ->
                User.withUsername(row.getString("username"))
                    .password(row.getString("password_hash"))
                    .roles("MEMBER")
                    .disabled(row.getBoolean("blocked"))
                    .build())
        .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
  }

  public int accessEpoch(String username) {
    return repository.accessEpoch(username);
  }

  public boolean verifyPassword(String username, String password) {
    if (password.getBytes(StandardCharsets.UTF_8).length > 72) return false;
    return repository
        .loadUserByUsernameAppUser(
            username,
            (row, index) ->
                !row.getBoolean("blocked")
                    && passwords.matches(password, row.getString("password_hash")))
        .orElse(false);
  }

  @Transactional
  public void register(AuthDtos.Signup request) {
    if (request.password().getBytes(StandardCharsets.UTF_8).length > 72)
      throw new AccountException(400, "비밀번호는 UTF-8 기준 72바이트 이내로 입력해 주세요.");
    try {
      repository.registerAppUser(
          UUID.randomUUID(),
          request.username(),
          passwords.encode(request.password()),
          request.nickname().strip());
    } catch (DuplicateKeyException exception) {
      throw new AccountException(409, "이미 사용 중인 아이디예요.");
    }
  }

  public Profile profile(String username) {
    return repository
        .profileAppUser(
            username,
            (row, index) ->
                new Profile(
                    row.getObject("id", UUID.class), row.getString("username"),
                    row.getString("nickname"), row.getString("training_goal")))
        .orElseThrow(() -> new AccountException(401, "다시 로그인해 주세요."));
  }

  @Transactional
  public Profile update(String authenticatedUsername, AuthDtos.Preferences request) {
    repository.updateAppUser(
        request.nickname().strip(), request.trainingGoal().strip(), authenticatedUsername);
    return profile(authenticatedUsername);
  }
}
