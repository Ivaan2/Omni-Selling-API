package com.example.orderms.integration.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public record IntegrationEvent(
    UUID eventId,
    String eventType,
    int eventVersion,
    UUID aggregateId,
    long aggregateVersion,
    Instant occurredAt,
    Data data) {
  public record Item(
      UUID productId, String productName, int quantity, BigDecimal unitPrice, String currency) {}

  public record Payment(UUID paymentId, String status, BigDecimal amount, String currency) {}

  public record Data(
      UUID orderId,
      String customerId,
      String status,
      BigDecimal totalAmount,
      String currency,
      List<Item> items,
      Instant createdAt,
      Instant deletedAt,
      Payment payment,
      String correlationId) {
    public Data {
      items = List.copyOf(items);
    }
  }
}
