package com.example.orderms.config;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
  @Bean
  UserDetailsService users(AppProperties config) {
    var admin = config.admin();
    if (admin.password() == null || admin.password().isBlank())
      throw new IllegalArgumentException("ADMIN_PASSWORD is required");
    return new InMemoryUserDetailsManager(
        User.withUsername(admin.username())
            .password("{bcrypt}" + new BCryptPasswordEncoder().encode(admin.password()))
            .roles("ADMIN")
            .build());
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    return http.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/internal/**", "/actuator/metrics/**")
                    .hasRole("ADMIN")
                    .requestMatchers(
                        "/api/v1/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/actuator/health",
                        "/actuator/health/liveness",
                        "/actuator/health/readiness")
                    .permitAll()
                    .anyRequest()
                    .denyAll())
        .httpBasic(
            b ->
                b.authenticationEntryPoint(
                    (req, res, e) -> {
                      res.setStatus(401);
                      res.setHeader("WWW-Authenticate", "Basic realm=\"admin\"");
                      res.setContentType("application/problem+json");
                      res.getWriter()
                          .write(
                              "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401}");
                    }))
        .exceptionHandling(
            e ->
                e.accessDeniedHandler(
                    (req, res, ex) -> {
                      res.setStatus(403);
                      res.setContentType("application/problem+json");
                      res.getWriter()
                          .write(
                              "{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403}");
                    }))
        .build();
  }
}
