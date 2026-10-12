package com.example.orderms.payment.domain;

import com.example.orderms.shared.domain.BusinessException;
import java.time.Instant;
import java.util.UUID;

/** Immutable history entry, changed only by replacing it through the Payment aggregate. */
public record PaymentAttempt(
    UUID id,
    String provider,
    String providerReference,
    String idempotencyReference,
    Payment.Status status,
    Instant createdAt,
    Instant updatedAt) {
  public PaymentAttempt {
    if (id == null
        || provider == null
        || provider.isBlank()
        || idempotencyReference == null
        || idempotencyReference.isBlank()
        || status == null
        || createdAt == null
        || updatedAt == null) throw BusinessException.invalid("Invalid payment attempt");
  }

  public PaymentAttempt resolved(Payment.Status outcome, String reference, Instant now) {
    if (!blocksCharge()) throw BusinessException.conflict("Attempt is already resolved");
    if (outcome != Payment.Status.SUCCEEDED
        && outcome != Payment.Status.FAILED
        && outcome != Payment.Status.UNKNOWN)
      throw BusinessException.invalid("Invalid provider outcome");
    return new PaymentAttempt(
        id, provider, reference, idempotencyReference, outcome, createdAt, now);
  }

  public boolean blocksCharge() {
    return status == Payment.Status.PENDING || status == Payment.Status.UNKNOWN;
  }
}
