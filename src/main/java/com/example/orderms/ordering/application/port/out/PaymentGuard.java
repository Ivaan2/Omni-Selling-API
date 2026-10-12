package com.example.orderms.ordering.application.port.out;

import java.time.Instant;
import java.util.UUID;

public interface PaymentGuard {
  boolean blocksChange(UUID orderId);

  void cancelObligation(UUID orderId, Instant now);
}
