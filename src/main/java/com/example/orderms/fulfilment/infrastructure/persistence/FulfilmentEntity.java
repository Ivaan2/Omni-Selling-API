package com.example.orderms.fulfilment.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "fulfilments")
public class FulfilmentEntity {
  @Id UUID id;
  @Version long version;

  @Column(nullable = false, unique = true)
  UUID orderId;

  @Column(nullable = false, length = 20)
  String status;

  Instant createdAt, updatedAt;

  protected FulfilmentEntity() {}
}
