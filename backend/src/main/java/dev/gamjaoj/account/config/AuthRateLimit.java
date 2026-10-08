package dev.gamjaoj.account.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.springframework.web.filter.OncePerRequestFilter;

/** Small, bounded per-IP gate. No forwarded header is trusted. Resets on restart. */
public final class AuthRateLimit extends OncePerRequestFilter {
  private final Map<String, Window> windows = new HashMap<>();

  private record Window(long start, int count) {}

  private synchronized boolean allow(String address) {
    long now = System.currentTimeMillis();
    windows.entrySet().removeIf(entry -> now - entry.getValue().start() >= 60_000);
    Window window = windows.get(address);
    if (window == null) {
      if (windows.size() >= 2048) return false;
      windows.put(address, new Window(now, 1));
      return true;
    }
    if (window.count() >= 30) return false;
    windows.put(address, new Window(window.start(), window.count() + 1));
    return true;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getServletPath();
    if (request.getMethod().equals("POST")
        && (path.equals("/api/auth/login") || path.equals("/api/auth/signup"))
        && !allow(request.getRemoteAddr())) {
      response.setHeader("Retry-After", "60");
      SecurityConfig.error(response, 429, "잠시 후 다시 시도해 주세요.");
      return;
    }
    chain.doFilter(request, response);
  }
}
