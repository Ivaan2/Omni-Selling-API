package com.example.orderms.payment.infrastructure.web;

import com.example.orderms.ordering.application.OrderView;
import com.example.orderms.payment.application.PaymentService;
import com.example.orderms.shared.infrastructure.web.IdempotencyHeader;
import io.swagger.v3.oas.annotations.*;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
public class PaymentController {
  private final PaymentService payments;

  public PaymentController(PaymentService payments) {
    this.payments = payments;
  }

  @Operation(
      summary = "Pay the order's immutable amount",
      description =
          "No amount body is accepted. UNKNOWN is reported as a CREATED order with UNKNOWN payment;"
              + " it is not a payment confirmation. A processing key returns 409 with Retry-After."
              + " Definitive FAILED permits a new key; UNKNOWN blocks new charges.")
  @PostMapping("/api/v1/orders/{orderId}/pay")
  public OrderView pay(
      @PathVariable UUID orderId,
      @Parameter(description = "Required client-generated idempotency key", required = true)
          @RequestHeader("Idempotency-Key")
          String key,
      @RequestBody(required = false) String body) {
    if (body != null && !body.isBlank())
      throw new IllegalArgumentException("Payment does not accept a request body");
    IdempotencyHeader.validate(key);
    return payments.pay(orderId, key);
  }
}
