package dev.gamjaoj.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class GenerationWorkerSecurity {
  public @Bean @Order(2) SecurityFilterChain generationChain(
      HttpSecurity http, @Value("${GENERATION_WORKER_TOKEN:}") String token) throws Exception {
    return http.securityMatcher("/internal/generation/**")
        .csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .requestCache(cache -> cache.disable())
        .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("GENERATOR"))
        .exceptionHandling(
            errors ->
                errors.authenticationEntryPoint(
                    (req, res, ex) ->
                        SecurityConfig.error(res, 401, "Generator authentication required")))
        .addFilterBefore(
            new OncePerRequestFilter() {
              @Override
              protected void doFilterInternal(
                  HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                  throws ServletException, IOException {
                String supplied = req.getHeader("Authorization");
                if (token.length() >= 32
                    && supplied != null
                    && MessageDigest.isEqual(
                        ("Bearer " + token).getBytes(StandardCharsets.UTF_8),
                        supplied.getBytes(StandardCharsets.UTF_8))) {
                  var context = SecurityContextHolder.createEmptyContext();
                  context.setAuthentication(
                      new UsernamePasswordAuthenticationToken(
                          "generation-worker",
                          null,
                          List.of(new SimpleGrantedAuthority("ROLE_GENERATOR"))));
                  SecurityContextHolder.setContext(context);
                }
                chain.doFilter(req, res);
              }
            },
            UsernamePasswordAuthenticationFilter.class)
        .build();
  }
}
