package com.example.orderms.ordering.application;

import com.example.orderms.ordering.application.port.out.*;
import com.example.orderms.ordering.domain.*;
import com.example.orderms.shared.application.*;
import com.example.orderms.shared.domain.*;
import java.time.Clock;
import java.util.*;

public final class OrderService {
  private final OrderStore orders;
  private final CatalogPort catalog;
  private final IdempotencyStore idempotency;
  private final OrderEvents events;
  private final PaymentGuard payments;
  private final OrderSummaries summaries;
  private final TransactionBoundary tx;
  private final IdGenerator ids;
  private final Clock clock;
  private final BusinessMetrics metrics;

  public OrderService(
      OrderStore orders,
      CatalogPort catalog,
      IdempotencyStore idempotency,
      OrderEvents events,
      PaymentGuard payments,
      OrderSummaries summaries,
      TransactionBoundary tx,
      IdGenerator ids,
      Clock clock,
      BusinessMetrics metrics) {
    this.orders = orders;
    this.catalog = catalog;
    this.idempotency = idempotency;
    this.events = events;
    this.payments = payments;
    this.summaries = summaries;
    this.tx = tx;
    this.ids = ids;
    this.clock = clock;
    this.metrics = metrics;
  }

  public OrderView create(CreateOrderCommand command) {
    return tx.execute(
        () -> {
          var claim =
              idempotency.acquire(
                  command.customerId(),
                  "create-order",
                  command.idempotencyKey(),
                  command.canonicalHash());
          if (claim.replay() != null) return claim.replay();
          if (command.items().stream().map(CreateOrderCommand.Line::productId).distinct().count()
              != command.items().size())
            throw BusinessException.invalid("Duplicate products are not allowed");
          var items =
              command.items().stream()
                  .map(
                      line -> {
                        var p = catalog.resolve(line.productId());
                        return new OrderItem(
                            ids.next(), p.id(), p.name(), line.quantity(), p.price());
                      })
                  .toList();
          var order =
              orders.save(Order.create(ids.next(), command.customerId(), items, clock.instant()));
          events.append("OrderCreated", order, null);
          var response = represent(order);
          idempotency.complete(claim.id(), order.id(), response);
          metrics.increment("orders.created");
          return response;
        });
  }

  public OrderView get(UUID id) {
    return tx.read(
        () -> {
          var order = find(id, false);
          order.requireVisible();
          return represent(order);
        });
  }

  public OrderView cancel(UUID id) {
    return tx.execute(
        () -> {
          var order = find(id, true);
          order.cancel(payments.blocksChange(id), clock.instant());
          payments.cancelObligation(id, clock.instant());
          order = orders.save(order);
          events.append("OrderCancelled", order, summaries.payment(id));
          return represent(order);
        });
  }

  public void softDelete(UUID id) {
    tx.execute(
        () -> {
          var order = find(id, true);
          if (order.softDelete(payments.blocksChange(id), clock.instant())) {
            payments.cancelObligation(id, clock.instant());
            order = orders.save(order);
            events.append("OrderDeleted", order, summaries.payment(id));
          }
          return null;
        });
  }

  // Module-facing operations below require the caller's short transaction.
  public Order findForPayment(UUID id) {
    var order = find(id, true);
    order.requireVisible();
    return order;
  }

  public Order claimPayment(Order order) {
    order.touchForPayment(clock.instant());
    return orders.save(order);
  }

  public Order confirmPayment(Order order, Money amount, OrderView.PaymentSummary payment) {
    order.markPaid(amount, clock.instant());
    var saved = orders.save(order);
    events.append("OrderPaid", saved, payment);
    return saved;
  }

  public OrderView represent(Order order) {
    return OrderView.from(order, summaries.payment(order.id()), summaries.fulfilment(order.id()));
  }

  private Order find(UUID id, boolean lock) {
    return orders.find(id, lock).orElseThrow(() -> BusinessException.missing("Order not found"));
  }
}
