package com.example.orderms.shared.infrastructure.web;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class CorrelationFilter extends OncePerRequestFilter {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(CorrelationFilter.class);

  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var supplied = request.getHeader("X-Correlation-ID");
    var id =
        supplied != null && supplied.matches("[a-zA-Z0-9_-]{1,64}")
            ? supplied
            : UUID.randomUUID().toString();
    MDC.put("correlationId", id);
    response.setHeader("X-Correlation-ID", id);
    try {
      chain.doFilter(request, response);
    } finally {
      log.info(
          "HTTP {} {} completed with {}",
          request.getMethod(),
          request.getRequestURI(),
          response.getStatus());
      MDC.remove("correlationId");
    }
  }
}
