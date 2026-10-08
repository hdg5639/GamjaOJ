package dev.gamjaoj.admin.config;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class ControlSettings {
  private final String host;
  private final Set<String> users;

  public ControlSettings(
      @Value("${CONTROL_OJ_HOST:}") String host,
      @Value("${CONTROL_OJ_ADMIN_USERS:}") String users) {
    this.host = host.strip().toLowerCase(Locale.ROOT);
    if (!this.host.isEmpty() && !this.host.matches("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?"))
      throw new IllegalArgumentException(
          "CONTROL_OJ_HOST must be a hostname without scheme, port or path");
    this.users =
        Arrays.stream(users.split(","))
            .map(String::strip)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
  }

  public boolean matches(HttpServletRequest request) {
    return !host.isEmpty() && host.equalsIgnoreCase(request.getServerName());
  }

  public boolean administrator(Authentication authentication) {
    return authentication != null
        && authentication.isAuthenticated()
        && users.contains(authentication.getName())
        && authentication.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_MEMBER"));
  }
}
