package dev.gamjaoj.admin.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Host is routing information; authorization is independently enforced by Spring Security. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ControlHostFilter extends OncePerRequestFilter {
  private final ControlSettings settings;

  public ControlHostFilter(ControlSettings settings) {
    this.settings = settings;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getServletPath();
    boolean managed =
        path.equals("/api/admin")
            || path.startsWith("/api/admin/")
            || path.equals("/controloj")
            || path.startsWith("/controloj/")
            || path.startsWith("/controloj.");
    if (managed) {
      response.setHeader("Cache-Control", "no-store");
      response.setHeader("Vary", "Host");
    }
    if (managed && !settings.matches(request)) {
      response.setStatus(404);
      response.setHeader("Cache-Control", "no-store");
      response.setHeader("Vary", "Host");
      return;
    }
    chain.doFilter(request, response);
  }
}
