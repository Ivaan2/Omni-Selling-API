package com.example.orderms.ordering.domain;

import com.example.orderms.shared.domain.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public final class Order {
  public enum Status {
    CREATED,
    PAID,
    CANCELLED
  }

  private final UUID id;
  private final String customerId;
  private final List<OrderItem> items;
  private final Money total;
  private Status status;
  private final Instant createdAt;
  private Instant updatedAt, deletedAt;
  private final long version;

  public Order(
      UUID id,
      String customerId,
      List<OrderItem> items,
      Status status,
      Money total,
      Instant createdAt,
      Instant updatedAt,
      Instant deletedAt,
      long version) {
    if (id == null
        || customerId == null
        || customerId.isBlank()
        || customerId.length() > 128
        || items == null
        || items.isEmpty())
      throw BusinessException.invalid("An order requires a customer and items");
    if (items.stream().anyMatch(Objects::isNull))
      throw BusinessException.invalid("Order items must not be null");
    this.items = List.copyOf(items);
    if (items.stream().map(OrderItem::productId).distinct().count() != items.size())
      throw BusinessException.invalid("Duplicate products are not allowed");
    Money calculated =
        items.stream()
            .map(OrderItem::subtotal)
            .reduce(new Money(BigDecimal.ZERO, "EUR"), Money::add);
    if (!calculated.equals(total))
      throw BusinessException.invalid("Order total does not match item subtotals");
    this.id = id;
    this.customerId = customerId;
    this.status = Objects.requireNonNull(status);
    this.total = total;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
    this.deletedAt = deletedAt;
    this.version = version;
  }

  public static Order create(UUID id, String customer, List<OrderItem> items, Instant now) {
    if (items == null || items.isEmpty() || items.stream().anyMatch(Objects::isNull))
      throw BusinessException.invalid("An order requires non-null items");
    Money total =
        items.stream()
            .map(OrderItem::subtotal)
            .reduce(new Money(BigDecimal.ZERO, "EUR"), Money::add);
    return new Order(id, customer, items, Status.CREATED, total, now, now, null, 0);
  }

  public void requirePayable() {
    requireVisible();
    if (status != Status.CREATED)
      throw BusinessException.conflict("Only CREATED orders can be paid");
  }

  public void markPaid(Money paid, Instant now) {
    requirePayable();
    if (!total.equals(paid)) throw BusinessException.invalid("Payment amount mismatch");
    status = Status.PAID;
    updatedAt = now;
  }

  public void cancel(boolean blocked, Instant now) {
    requirePayable();
    requireNoCharge(blocked);
    status = Status.CANCELLED;
    updatedAt = now;
  }

  public boolean softDelete(boolean blocked, Instant now) {
    if (deletedAt != null) return false;
    if (status == Status.PAID) throw BusinessException.conflict("Paid orders cannot be deleted");
    requireNoCharge(blocked);
    deletedAt = now;
    updatedAt = now;
    return true;
  }

  public void touchForPayment(Instant now) {
    requirePayable();
    updatedAt = now;
  }

  public void requireVisible() {
    if (deletedAt != null) throw BusinessException.missing("Order not found");
  }

  private void requireNoCharge(boolean blocked) {
    if (blocked)
      throw BusinessException.conflict("An active or unresolved payment blocks this operation");
  }

  public UUID id() {
    return id;
  }

  public String customerId() {
    return customerId;
  }

  public List<OrderItem> items() {
    return items;
  }

  public Money total() {
    return total;
  }

  public Status status() {
    return status;
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
