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
  public SecurityFilterChain controlChain(HttpSecurity http, ControlSettings settings)
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
                                    && settings.administrator(authentication.get()))))
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(
                        (req, res, ex) -> SecurityConfig.error(res, 401, "로그인이 필요해요."))
                    .accessDeniedHandler(
                        (req, res, ex) -> SecurityConfig.error(res, 403, "관리자 권한이 필요해요.")))
        .build();
  }
}
