package dev.gamjaoj;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Accounts implements UserDetailsService {
    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final byte[] invite;

    public Accounts(JdbcClient jdbc, PasswordEncoder passwords,
                    @Value("${gamjaoj.invite-code}") String invite) {
        if (invite.isBlank()) throw new IllegalArgumentException("INVITE_CODE must not be blank");
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.invite = invite.getBytes(StandardCharsets.UTF_8);
    }

    public record Profile(UUID id, String username, String nickname, String trainingGoal) {}

    @Override
    public org.springframework.security.core.userdetails.UserDetails loadUserByUsername(String username) {
        return jdbc.sql("SELECT username, password_hash FROM app_user WHERE username = :username")
                .param("username", username)
                .query((row, index) -> User.withUsername(row.getString("username"))
                        .password(row.getString("password_hash")).roles("MEMBER").build())
                .optional().orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }

    @Transactional
    public void register(AuthController.Signup request) {
        if (!MessageDigest.isEqual(invite, request.inviteCode().getBytes(StandardCharsets.UTF_8)))
            throw new AccountException(400, "초대코드를 확인해 주세요.");
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72)
            throw new AccountException(400, "비밀번호는 UTF-8 기준 72바이트 이내로 입력해 주세요.");
        try {
            jdbc.sql("INSERT INTO app_user (id, username, password_hash, nickname) VALUES (:id, :username, :hash, :nickname)")
                    .param("id", UUID.randomUUID()).param("username", request.username())
                    .param("hash", passwords.encode(request.password())).param("nickname", request.nickname().strip())
                    .update();
        } catch (DuplicateKeyException exception) {
            throw new AccountException(409, "이미 사용 중인 아이디예요.");
        }
    }

    public Profile profile(String username) {
        return jdbc.sql("SELECT id, username, nickname, training_goal FROM app_user WHERE username = :username")
                .param("username", username).query((row, index) -> new Profile(
                        row.getObject("id", UUID.class), row.getString("username"),
                        row.getString("nickname"), row.getString("training_goal")))
                .optional().orElseThrow(() -> new AccountException(401, "다시 로그인해 주세요."));
    }

    @Transactional
    public Profile update(String authenticatedUsername, AuthController.Preferences request) {
        jdbc.sql("UPDATE app_user SET nickname = :nickname, training_goal = :goal WHERE username = :username")
                .param("nickname", request.nickname().strip()).param("goal", request.trainingGoal().strip())
                .param("username", authenticatedUsername).update();
        return profile(authenticatedUsername);
    }
}
