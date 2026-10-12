package com.example.orderms.ordering.application;

import java.util.*;

public record CreateOrderCommand(String customerId, List<Line> items, String idempotencyKey) {
  public record Line(UUID productId, int quantity) {}

  public CreateOrderCommand {
    items = List.copyOf(items);
  }

  public String canonicalHash() {
    return RequestHash.of(
        RequestHash.field(customerId)
            + items.stream()
                .sorted(Comparator.comparing(l -> l.productId().toString()))
                .map(l -> RequestHash.field(l.productId().toString()) + ":" + l.quantity() + ";")
                .reduce("", String::concat));
  }
}
