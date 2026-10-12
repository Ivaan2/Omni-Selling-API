package com.example.orderms.payment.application.port.out;

import com.example.orderms.payment.domain.Payment;
import com.example.orderms.shared.domain.Money;
import java.util.Optional;

public interface PaymentProcessorPort {
  record Operation(String idempotencyReference, Money amount) {}

  record Result(Payment.Status outcome, String providerReference) {
    public Result {
      if (outcome != Payment.Status.SUCCEEDED
          && outcome != Payment.Status.FAILED
          && outcome != Payment.Status.UNKNOWN)
        throw new IllegalArgumentException("Not a provider outcome");
    }
  }

  Result process(Operation operation);

  Optional<Result> lookup(String idempotencyReference);

  /** Only true when the provider contract guarantees stable-key replay cannot charge twice. */
  default boolean supportsIdempotentReplay() {
    return false;
  }

  String provider();
}
