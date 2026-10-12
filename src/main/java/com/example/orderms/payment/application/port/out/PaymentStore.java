package com.example.orderms.payment.application.port.out;

import com.example.orderms.payment.domain.Payment;
import java.time.Instant;
import java.util.*;

public interface PaymentStore {
  Optional<Payment> findByOrder(UUID orderId);

  Payment save(Payment payment);

  record Recoverable(UUID orderId, UUID attemptId, String reference) {}

  List<Recoverable> recoverable(Instant before, int limit);
}
