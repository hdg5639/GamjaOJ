package dev.gamjaoj.admin.config;

import dev.gamjaoj.account.config.SecurityConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class ControlSecurity {
  @Bean
  @Order(3)
  public SecurityFilterChain controlChain(
      HttpSecurity http, ControlSettings settings, dev.gamjaoj.admin.service.AdminSessions sessions)
      throws Exception {
    return http.securityMatcher("/api/admin", "/api/admin/**")
        .requestCache(cache -> cache.disable())
        .authorizeHttpRequests(
            auth ->
                auth.anyRequest()
                    .access(
                        (authentication, context) ->
                            new AuthorizationDecision(
                                settings.matches(context.getRequest())
                                    && settings.administrator(authentication.get())
                                    && (java.util.Set.of(
                                                "/api/admin/me", "/api/admin/session/verify")
                                            .contains(context.getRequest().getServletPath())
                                        || sessions.verified(
                                            context.getRequest(),
                                            authentication.get().getName())))))
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(
                        (req, res, ex) -> SecurityConfig.error(res, 401, "로그인이 필요해요."))
                    .accessDeniedHandler(
                        (req, res, ex) -> {
                          if (ex instanceof org.springframework.security.web.csrf.CsrfException) {
                            res.setStatus(403);
                            res.setContentType("application/json;charset=UTF-8");
                            res.getWriter()
                                .write(
                                    "{\"message\":\"요청을 확인할 수 없어요. 다시 시도해"
                                        + " 주세요.\",\"code\":\"CSRF_REJECTED\"}");
                            return;
                          }
                          if (settings.matches(req)
                              && settings.administrator(
                                  org.springframework.security.core.context.SecurityContextHolder
                                      .getContext()
                                      .getAuthentication())) {
                            res.setStatus(403);
                            res.setContentType("application/json;charset=UTF-8");
                            res.getWriter()
                                .write(
                                    "{\"message\":\"관리자 비밀번호를 다시 확인해"
                                        + " 주세요.\",\"code\":\"ADMIN_REAUTH_REQUIRED\"}");
                          } else SecurityConfig.error(res, 403, "관리자 권한이 필요해요.");
                        }))
        .build();
  }
}
