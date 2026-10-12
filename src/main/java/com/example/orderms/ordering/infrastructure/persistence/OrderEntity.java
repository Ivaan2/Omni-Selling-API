package com.example.orderms.ordering.infrastructure.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "orders")
public class OrderEntity {
  @Id UUID id;
  @Version long version;

  @Column(nullable = false, length = 128)
  String customerId;

  @Column(nullable = false, length = 20)
  String status;

  @Column(nullable = false, precision = 19, scale = 2)
  BigDecimal totalAmount;

  @Column(nullable = false, length = 3)
  String currency;

  Instant createdAt, updatedAt, deletedAt;

  @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
  @JoinColumn(name = "order_id", nullable = false, updatable = false)
  List<OrderItemEntity> items = new ArrayList<>();

  protected OrderEntity() {}
}
