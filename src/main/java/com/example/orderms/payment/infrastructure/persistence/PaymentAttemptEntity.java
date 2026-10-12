package com.example.orderms.payment.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_attempts")
public class PaymentAttemptEntity {
  @Id UUID id;

  @Column(nullable = false, length = 50)
  String provider;

  @Column(length = 200)
  String providerReference;

  @Column(nullable = false, length = 100)
  String idempotencyReference;

  @Column(nullable = false, length = 20)
  String status;

  Instant createdAt, updatedAt;

  protected PaymentAttemptEntity() {}
}
