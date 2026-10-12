package com.example.orderms.fulfilment.domain;

import com.example.orderms.shared.domain.BusinessException;
import java.time.Instant;
import java.util.UUID;

public final class Fulfilment {
  public enum Status {
    PENDING,
    PROCESSING,
    DELIVERED,
    FULFILLED
  }

  private final UUID id, orderId;
  private Status status;
  private final Instant createdAt;
  private Instant updatedAt;
  private final long version;

  public Fulfilment(
      UUID id, UUID orderId, Status status, Instant createdAt, Instant updatedAt, long version) {
    if (id == null || orderId == null || status == null || createdAt == null || updatedAt == null)
      throw BusinessException.invalid("Invalid fulfilment");
    this.id = id;
    this.orderId = orderId;
    this.status = status;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
    this.version = version;
  }

  public static Fulfilment pending(UUID id, UUID orderId, Instant now) {
    return new Fulfilment(id, orderId, Status.PENDING, now, now, 0);
  }

  public void advanceTo(Status target, Instant now) {
    Status allowed =
        switch (status) {
          case PENDING -> Status.PROCESSING;
          case PROCESSING -> Status.DELIVERED;
          case DELIVERED -> Status.FULFILLED;
          case FULFILLED -> null;
        };
    if (allowed == null || target != allowed)
      throw BusinessException.conflict("Invalid fulfilment transition");
    status = target;
    updatedAt = now;
  }

  public UUID id() {
    return id;
  }

  public UUID orderId() {
    return orderId;
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

  public long version() {
    return version;
  }
}
