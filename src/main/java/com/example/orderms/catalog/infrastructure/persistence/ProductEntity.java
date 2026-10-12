package com.example.orderms.catalog.infrastructure.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "products")
public class ProductEntity {
  @Id UUID id;
  @Version long version;

  @Column(nullable = false, length = 200)
  String name;

  @Column(nullable = false, length = 1000)
  String description;

  @Column(nullable = false, precision = 19, scale = 2)
  BigDecimal price;

  @Column(nullable = false, length = 3)
  String currency;

  @Column(nullable = false)
  boolean available;

  Instant createdAt, updatedAt, deletedAt;

  protected ProductEntity() {}
}
