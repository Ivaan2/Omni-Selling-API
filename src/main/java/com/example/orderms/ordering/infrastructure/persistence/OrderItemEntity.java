package com.example.orderms.ordering.infrastructure.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "order_items")
public class OrderItemEntity {
  @Id UUID id;

  @Column(nullable = false)
  UUID productId;

  @Column(nullable = false, length = 200)
  String productName;

  @Column(nullable = false)
  int quantity;

  @Column(nullable = false, precision = 19, scale = 2)
  BigDecimal unitPrice;

  @Column(nullable = false, length = 3)
  String currency;

  protected OrderItemEntity() {}
}
