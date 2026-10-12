package com.example.orderms.shared.infrastructure.web;

import com.example.orderms.shared.domain.BusinessException;
import jakarta.persistence.OptimisticLockException;
import org.slf4j.*;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ProblemHandler extends ResponseEntityExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ProblemHandler.class);

  @ExceptionHandler(BusinessException.class)
  ResponseEntity<ProblemDetail> domain(BusinessException e) {
    var status =
        switch (e.kind()) {
          case INVALID -> HttpStatus.UNPROCESSABLE_CONTENT;
          case NOT_FOUND -> HttpStatus.NOT_FOUND;
          case CONFLICT -> HttpStatus.CONFLICT;
        };
    return problem(status, e.getMessage());
  }

  @ExceptionHandler({
    OptimisticLockException.class,
    OptimisticLockingFailureException.class,
    CannotAcquireLockException.class,
    DataIntegrityViolationException.class
  })
  ResponseEntity<ProblemDetail> conflict(Exception e) {
    return problem(
        HttpStatus.CONFLICT,
        "Resource changed or operation conflicts with a persistent business constraint");
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<ProblemDetail> invalid(Exception e) {
    return problem(HttpStatus.BAD_REQUEST, "Invalid request or Idempotency-Key");
  }

  @ExceptionHandler({
    DataAccessResourceFailureException.class,
    org.springframework.transaction.CannotCreateTransactionException.class
  })
  ResponseEntity<ProblemDetail> unavailable(Exception e) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Persistence is temporarily unavailable; retry safely using the same idempotency key");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> unexpected(Exception e) {
    log.error("Request failed", e);
    return problem(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
  }

  private ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
    var body = ProblemDetail.forStatusAndDetail(status, detail);
    body.setProperty("correlationId", MDC.get("correlationId"));
    var builder = ResponseEntity.status(status);
    if (status == HttpStatus.CONFLICT || status == HttpStatus.SERVICE_UNAVAILABLE)
      builder.header("Retry-After", "2");
    return builder.body(body);
  }

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    var detail =
        ProblemDetail.forStatusAndDetail(
            status, status.is4xxClientError() ? "Invalid HTTP request" : "Request failed");
    detail.setProperty("correlationId", MDC.get("correlationId"));
    return super.handleExceptionInternal(ex, detail, headers, status, request);
  }
}
