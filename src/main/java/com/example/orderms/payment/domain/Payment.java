package com.example.orderms.payment.domain;

import com.example.orderms.shared.domain.*;
import java.time.Instant;
import java.util.*;

public final class Payment {
  public enum Status {
    PENDING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    UNKNOWN
  }

  private final UUID id, orderId;
  private final Money amount;
  private Status status;
  private final List<PaymentAttempt> attempts;
  private final Instant createdAt;
  private Instant updatedAt;
  private final long version;

  public Payment(
      UUID id,
      UUID orderId,
      Money amount,
      Status status,
      List<PaymentAttempt> attempts,
      Instant createdAt,
      Instant updatedAt,
      long version) {
    if (id == null
        || orderId == null
        || amount == null
        || status == null
        || attempts == null
        || createdAt == null
        || updatedAt == null) throw BusinessException.invalid("Invalid payment");
    if (attempts.stream().filter(PaymentAttempt::blocksCharge).count() > 1)
      throw BusinessException.conflict("Only one unresolved attempt is allowed");
    if (attempts.stream().map(PaymentAttempt::id).distinct().count() != attempts.size())
      throw BusinessException.invalid("Duplicate payment attempts");
    this.id = id;
    this.orderId = orderId;
    this.amount = amount;
    this.status = status;
    this.attempts = new ArrayList<>(attempts);
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
    this.version = version;
  }

  public static Payment create(UUID id, UUID order, Money amount, Instant now) {
    return new Payment(id, order, amount, Status.PENDING, List.of(), now, now, 0);
  }

  public PaymentAttempt acquire(UUID attemptId, String provider, Instant now) {
    if (status == Status.SUCCEEDED || status == Status.CANCELLED || blocksCharge())
      throw BusinessException.conflict("Payment cannot start another charge");
    var attempt =
        new PaymentAttempt(
            attemptId, provider, null, attemptId.toString(), Status.PENDING, now, now);
    attempts.add(attempt);
    status = Status.PENDING;
    updatedAt = now;
    return attempt;
  }

  public void resolve(UUID attemptId, Status result, String reference, Instant now) {
    var attempt =
        attempts.stream()
            .filter(a -> a.id().equals(attemptId))
            .findFirst()
            .orElseThrow(() -> BusinessException.missing("Payment attempt not found"));
    if (status == Status.SUCCEEDED || status == Status.CANCELLED)
      throw BusinessException.conflict("Payment is terminal");
    attempts.set(attempts.indexOf(attempt), attempt.resolved(result, reference, now));
    status = result;
    updatedAt = now;
  }

  public void cancel(Instant now) {
    if (status == Status.SUCCEEDED || blocksCharge())
      throw BusinessException.conflict("Payment cannot be cancelled");
    status = Status.CANCELLED;
    updatedAt = now;
  }

  public boolean blocksCharge() {
    return attempts.stream().anyMatch(PaymentAttempt::blocksCharge);
  }

  public UUID id() {
    return id;
  }

  public UUID orderId() {
    return orderId;
  }

  public Money amount() {
    return amount;
  }

  public Status status() {
    return status;
  }

  public List<PaymentAttempt> attempts() {
    return List.copyOf(attempts);
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
