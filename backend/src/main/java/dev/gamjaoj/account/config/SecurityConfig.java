package dev.gamjaoj.account.config;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {
  public @Bean PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  public @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/api/auth/csrf", "/api/auth/signup", "/api/auth/login")
                    .permitAll()
                    .requestMatchers("/api/**")
                    .authenticated()
                    .requestMatchers("/internal/**")
                    .denyAll()
                    .anyRequest()
                    .permitAll())
        .requestCache(cache -> cache.disable())
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint((req, res, ex) -> error(res, 401, "로그인이 필요해요."))
                    .accessDeniedHandler(
                        (req, res, ex) -> error(res, 403, "요청을 확인할 수 없어요. 새로고침 후 다시 시도해 주세요.")))
        .formLogin(
            login ->
                login
                    .loginProcessingUrl("/api/auth/login")
                    .successHandler((req, res, auth) -> res.setStatus(204))
                    .failureHandler((req, res, ex) -> error(res, 401, "아이디 또는 비밀번호를 확인해 주세요.")))
        .logout(
            logout ->
                logout
                    .logoutUrl("/api/auth/logout")
                    .invalidateHttpSession(true)
                    .clearAuthentication(true)
                    .deleteCookies("GAMJAOJ_SESSION")
                    .logoutSuccessHandler((req, res, auth) -> res.setStatus(204)))
        .addFilterBefore(new AuthRateLimit(), UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  public static void error(HttpServletResponse response, int status, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json;charset=UTF-8");
    response.getWriter().write("{\"message\":\"" + message + "\"}");
  }
}
