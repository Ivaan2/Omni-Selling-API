package com.example.orderms.ordering.application;

import com.example.orderms.ordering.domain.Order;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public record OrderView(
    UUID id,
    String customerId,
    String status,
    List<Item> items,
    BigDecimal totalAmount,
    String currency,
    Instant createdAt,
    long version,
    PaymentSummary payment,
    FulfilmentSummary fulfilment) {
  public record Item(
      UUID productId, String productName, int quantity, BigDecimal unitPrice, String currency) {}

  public record PaymentSummary(UUID id, String status, BigDecimal amount, String currency) {}

  public record FulfilmentSummary(UUID id, String status) {}

  public OrderView {
    items = List.copyOf(items);
  }

  public static OrderView from(Order order, PaymentSummary payment, FulfilmentSummary fulfilment) {
    return new OrderView(
        order.id(),
        order.customerId(),
        order.status().name(),
        order.items().stream()
            .map(
                i ->
                    new Item(
                        i.productId(),
                        i.productName(),
                        i.quantity(),
                        i.unitPrice().amount(),
                        i.unitPrice().currency()))
            .toList(),
        order.total().amount(),
        order.total().currency(),
        order.createdAt(),
        order.version(),
        payment,
        fulfilment);
  }
}
