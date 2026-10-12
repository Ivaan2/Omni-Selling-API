package com.example.orderms.ordering.domain;

import com.example.orderms.shared.domain.*;
import java.util.UUID;

public record OrderItem(
    UUID id, UUID productId, String productName, int quantity, Money unitPrice) {
  public OrderItem {
    if (id == null
        || productId == null
        || productName == null
        || productName.isBlank()
        || unitPrice == null
        || quantity <= 0) throw BusinessException.invalid("Invalid order item");
  }

  public Money subtotal() {
    return unitPrice.multiply(quantity);
  }
}
