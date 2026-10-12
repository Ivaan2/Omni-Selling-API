package com.example.orderms.payment.infrastructure.persistence;

import com.example.orderms.ordering.application.port.out.PaymentGuard;
import com.example.orderms.payment.application.port.out.PaymentStore;
import com.example.orderms.payment.domain.*;
import com.example.orderms.shared.domain.Money;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JpaPaymentStore implements PaymentStore, PaymentGuard {
  private final EntityManager em;
  private final JdbcTemplate jdbc;

  public JpaPaymentStore(EntityManager em, JdbcTemplate jdbc) {
    this.em = em;
    this.jdbc = jdbc;
  }

  public Optional<Payment> findByOrder(UUID orderId) {
    return em.createQuery("select p from PaymentEntity p where p.orderId=:id", PaymentEntity.class)
        .setParameter("id", orderId)
        .getResultStream()
        .findFirst()
        .map(this::map);
  }

  public Payment save(Payment p) {
    var e = em.find(PaymentEntity.class, p.id());
    boolean fresh = e == null;
    if (fresh) {
      e = new PaymentEntity();
      e.id = p.id();
      e.orderId = p.orderId();
    } else if (e.version != p.version()) throw new OptimisticLockException();
    e.status = p.status().name();
    e.amount = p.amount().amount();
    e.currency = p.amount().currency();
    e.createdAt = p.createdAt();
    e.updatedAt = p.updatedAt();
    for (var a : p.attempts()) {
      var ae = e.attempts.stream().filter(i -> i.id.equals(a.id())).findFirst().orElse(null);
      if (ae == null) {
        ae = new PaymentAttemptEntity();
        ae.id = a.id();
        e.attempts.add(ae);
      }
      ae.provider = a.provider();
      ae.providerReference = a.providerReference();
      ae.idempotencyReference = a.idempotencyReference();
      ae.status = a.status().name();
      ae.createdAt = a.createdAt();
      ae.updatedAt = a.updatedAt();
    }
    if (fresh) em.persist(e);
    em.flush();
    return map(e);
  }

  public boolean blocksChange(UUID orderId) {
    return findByOrder(orderId).map(Payment::blocksCharge).orElse(false);
  }

  public void cancelObligation(UUID orderId, Instant now) {
    findByOrder(orderId)
        .ifPresent(
            p -> {
              if (p.status() != Payment.Status.CANCELLED) {
                p.cancel(now);
                save(p);
              }
            });
  }

  public List<Recoverable> recoverable(Instant before, int limit) {
    return jdbc.query(
        "select p.order_id,a.id,a.idempotency_reference from payment_attempts a join payments p on"
            + " p.id=a.payment_id where a.status in ('PENDING','UNKNOWN') and a.updated_at < ?"
            + " order by a.updated_at limit ?",
        (rs, n) ->
            new Recoverable(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3)),
        java.sql.Timestamp.from(before),
        limit);
  }

  private Payment map(PaymentEntity e) {
    return new Payment(
        e.id,
        e.orderId,
        new Money(e.amount, e.currency),
        Payment.Status.valueOf(e.status),
        e.attempts.stream()
            .sorted(
                Comparator.comparing((PaymentAttemptEntity a) -> a.createdAt)
                    .thenComparing(a -> a.id))
            .map(
                a ->
                    new PaymentAttempt(
                        a.id,
                        a.provider,
                        a.providerReference,
                        a.idempotencyReference,
                        Payment.Status.valueOf(a.status),
                        a.createdAt,
                        a.updatedAt))
            .toList(),
        e.createdAt,
        e.updatedAt,
        e.version);
  }
}
