package com.example.orderms.catalog.domain;

import com.example.orderms.shared.domain.*;
import java.time.Instant;
import java.util.UUID;

public final class Product {
  private final UUID id;
  private final String name, description;
  private final Money price;
  private final boolean available;
  private final Instant createdAt;
  private Instant updatedAt, deletedAt;
  private final long version;

  public Product(
      UUID id,
      String name,
      String description,
      Money price,
      boolean available,
      Instant createdAt,
      Instant updatedAt,
      Instant deletedAt,
      long version) {
    if (id == null || name == null || name.isBlank() || price == null)
      throw BusinessException.invalid("Invalid product");
    this.id = id;
    this.name = name;
    this.description = description;
    this.price = price;
    this.available = available;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
    this.deletedAt = deletedAt;
    this.version = version;
  }

  public void requirePurchasable() {
    if (deletedAt != null || !available)
      throw BusinessException.invalid("Product is not commercially available");
  }

  public boolean softDelete(Instant now) {
    if (deletedAt != null) return false;
    deletedAt = now;
    updatedAt = now;
    return true;
  }

  public UUID id() {
    return id;
  }

  public String name() {
    return name;
  }

  public String description() {
    return description;
  }

  public Money price() {
    return price;
  }

  public boolean available() {
    return available;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  public Instant deletedAt() {
    return deletedAt;
  }

  public long version() {
    return version;
  }
}
