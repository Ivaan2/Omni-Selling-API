package com.example.orderms.ordering.application.port.out;

import com.example.orderms.ordering.application.OrderView;
import java.util.UUID;

public interface IdempotencyStore {
  record Claim(UUID id, OrderView replay) {}

  Claim acquire(String customer, String operation, String key, String canonicalHash);

  void associate(UUID id, UUID resourceId);

  void complete(UUID id, UUID resourceId, OrderView response);

  void completePendingPayments(UUID orderId, OrderView response);
}
