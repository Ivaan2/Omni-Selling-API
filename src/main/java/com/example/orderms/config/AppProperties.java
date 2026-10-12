package com.example.orderms.config;

import com.example.orderms.payment.domain.Payment;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app")
public record AppProperties(
    Admin admin, Duration idempotencyRetention, PaymentSettings payment, Outbox outbox) {
  public AppProperties {
    if (idempotencyRetention.isNegative() || idempotencyRetention.isZero())
      throw new IllegalArgumentException("Idempotency retention must be positive");
  }

  public record Admin(String username, String password) {}

  public record PaymentSettings(
      String adapter,
      Payment.Status fakeOutcome,
      Payment.Status fakeLookupOutcome,
      Duration recoveryAge,
      long recoveryInterval) {
    public PaymentSettings {
      if (fakeOutcome != Payment.Status.SUCCEEDED
          && fakeOutcome != Payment.Status.FAILED
          && fakeOutcome != Payment.Status.UNKNOWN)
        throw new IllegalArgumentException("Invalid fake outcome");
      if (fakeLookupOutcome != Payment.Status.SUCCEEDED
          && fakeLookupOutcome != Payment.Status.FAILED
          && fakeLookupOutcome != Payment.Status.UNKNOWN)
        throw new IllegalArgumentException("Invalid fake lookup outcome");
      if (recoveryAge.isNegative() || recoveryInterval < 1)
        throw new IllegalArgumentException("Invalid recovery policy");
    }
  }

  public record Outbox(
      boolean enabled,
      long pollInterval,
      int batchSize,
      Duration lease,
      Duration timeout,
      int maxRetries,
      Duration retryBase,
      Duration retryMax,
      String receiverUrl) {
    public Outbox {
      if (pollInterval < 1
          || timeout.isNegative()
          || timeout.isZero()
          || retryBase.isNegative()
          || retryBase.isZero()
          || retryMax.compareTo(retryBase) < 0
          || batchSize < 1
          || batchSize > 1000
          || maxRetries < 1
          || lease.compareTo(timeout.multipliedBy(batchSize)) <= 0)
        throw new IllegalArgumentException(
            "Lease must exceed batchSize * HTTP timeout; configure a safe bound");
    }
  }
}
