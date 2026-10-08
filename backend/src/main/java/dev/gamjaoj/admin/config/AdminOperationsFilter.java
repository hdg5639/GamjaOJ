package dev.gamjaoj.admin.config;

import dev.gamjaoj.account.config.SecurityConfig;
import dev.gamjaoj.admin.repository.AdminSecurityRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(SecurityProperties.DEFAULT_FILTER_ORDER + 1)
public class AdminOperationsFilter extends OncePerRequestFilter {
  private final AdminSecurityRepository repository;

  public AdminOperationsFilter(AdminSecurityRepository repository) {
    this.repository = repository;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getServletPath();
    if (!path.startsWith("/api/")) {
      chain.doFilter(request, response);
      return;
    }
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null
        && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_MEMBER"))) {
      var access = repository.access(auth.getName());
      var session = request.getSession(false);
      boolean stale =
          session != null
              && session.getAttribute("ACCOUNT_ACCESS_EPOCH") instanceof Integer epoch
              && access.isPresent()
              && epoch != access.get().epoch();
      if (access.isEmpty() || access.get().blocked() || stale) {
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        SecurityConfig.error(response, 401, "계정 또는 세션 상태가 변경됐어요. 다시 로그인해 주세요.");
        return;
      }
      if (session != null) session.setAttribute("ACCOUNT_ACCESS_EPOCH", access.get().epoch());
    }
    if (!Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())
        && !path.startsWith("/api/admin/")
        && !Set.of("/api/auth/login", "/api/auth/logout").contains(path)) {
      var message = repository.maintenanceMessage();
      if (message.isPresent()) {
        response.setStatus(503);
        response.setContentType("application/json;charset=UTF-8");
        response
            .getWriter()
            .write(
                dev.gamjaoj.shared.support.JudgeJson.JSON.writeValueAsString(
                    java.util.Map.of(
                        "message",
                        message.get().isBlank()
                            ? "서비스 점검 중이에요. 잠시 후 다시 시도해 주세요."
                            : message.get())));
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
