package com.example.orderms.ordering.infrastructure.persistence;

import com.example.orderms.ordering.application.port.out.OrderStore;
import com.example.orderms.ordering.domain.*;
import com.example.orderms.shared.domain.Money;
import jakarta.persistence.*;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class JpaOrderStore implements OrderStore {
  private final EntityManager em;

  public JpaOrderStore(EntityManager em) {
    this.em = em;
  }

  public Optional<Order> find(UUID id, boolean lock) {
    var e =
        lock
            ? em.find(OrderEntity.class, id, LockModeType.PESSIMISTIC_WRITE)
            : em.find(OrderEntity.class, id);
    return Optional.ofNullable(e).map(this::map);
  }

  public Order save(Order o) {
    var e = em.find(OrderEntity.class, o.id());
    boolean fresh = e == null;
    if (fresh) {
      e = new OrderEntity();
      e.id = o.id();
      for (var item : o.items()) {
        var i = new OrderItemEntity();
        i.id = item.id();
        i.productId = item.productId();
        i.productName = item.productName();
        i.quantity = item.quantity();
        i.unitPrice = item.unitPrice().amount();
        i.currency = item.unitPrice().currency();
        e.items.add(i);
      }
    } else if (e.version != o.version()) throw new OptimisticLockException();
    e.customerId = o.customerId();
    e.status = o.status().name();
    e.totalAmount = o.total().amount();
    e.currency = o.total().currency();
    e.createdAt = o.createdAt();
    e.updatedAt = o.updatedAt();
    e.deletedAt = o.deletedAt();
    if (fresh) em.persist(e);
    em.flush();
    return map(e);
  }

  private Order map(OrderEntity e) {
    return new Order(
        e.id,
        e.customerId,
        e.items.stream()
            .map(
                i ->
                    new OrderItem(
                        i.id,
                        i.productId,
                        i.productName,
                        i.quantity,
                        new Money(i.unitPrice, i.currency)))
            .toList(),
        Order.Status.valueOf(e.status),
        new Money(e.totalAmount, e.currency),
        e.createdAt,
        e.updatedAt,
        e.deletedAt,
        e.version);
  }
}
