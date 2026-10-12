package com.example.orderms.payment.infrastructure.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "payments")
public class PaymentEntity {
  @Id UUID id;
  @Version long version;

  @Column(nullable = false, unique = true)
  UUID orderId;

  @Column(nullable = false, length = 20)
  String status;

  @Column(nullable = false, precision = 19, scale = 2)
  BigDecimal amount;

  @Column(nullable = false, length = 3)
  String currency;

  Instant createdAt, updatedAt;

  @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
  @JoinColumn(name = "payment_id", nullable = false, updatable = false)
  List<PaymentAttemptEntity> attempts = new ArrayList<>();

  protected PaymentEntity() {}
}
