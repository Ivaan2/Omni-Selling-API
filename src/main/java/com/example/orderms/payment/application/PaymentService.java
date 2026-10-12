package com.example.orderms.payment.application;

import com.example.orderms.ordering.application.*;
import com.example.orderms.ordering.application.port.out.IdempotencyStore;
import com.example.orderms.payment.application.port.out.*;
import com.example.orderms.payment.domain.*;
import com.example.orderms.shared.application.*;
import com.example.orderms.shared.domain.BusinessException;
import java.time.*;
import java.util.*;

public final class PaymentService {
  private static final System.Logger LOG = System.getLogger(PaymentService.class.getName());
  private final PaymentStore payments;
  private final PaymentProcessorPort processor;
  private final OrderService orders;
  private final IdempotencyStore idempotency;
  private final TransactionBoundary tx;
  private final IdGenerator ids;
  private final Clock clock;
  private final BusinessMetrics metrics;

  private record Acquisition(
      UUID orderId, UUID attemptId, PaymentProcessorPort.Operation operation, OrderView replay) {}

  public PaymentService(
      PaymentStore payments,
      PaymentProcessorPort processor,
      OrderService orders,
      IdempotencyStore idempotency,
      TransactionBoundary tx,
      IdGenerator ids,
      Clock clock,
      BusinessMetrics metrics) {
    this.payments = payments;
    this.processor = processor;
    this.orders = orders;
    this.idempotency = idempotency;
    this.tx = tx;
    this.ids = ids;
    this.clock = clock;
    this.metrics = metrics;
  }

  public OrderView pay(UUID orderId, String key) {
    Acquisition acquired;
    try {
      acquired = acquire(orderId, key);
    } catch (BusinessException e) {
      if (e.kind() == BusinessException.Kind.CONFLICT) metrics.increment("payments.conflicts");
      throw e;
    }
    if (acquired.replay() != null) return acquired.replay();
    PaymentProcessorPort.Result result;
    try {
      result = processor.process(acquired.operation());
    } catch (RuntimeException e) {
      metrics.increment("payments.provider.errors");
      LOG.log(
          System.Logger.Level.WARNING,
          "Payment attempt {0} encountered {1}; outcome remains unresolved",
          acquired.attemptId(),
          e.getClass().getSimpleName());
      result = new PaymentProcessorPort.Result(Payment.Status.UNKNOWN, null);
    }
    return persistResult(orderId, acquired.attemptId(), result);
  }

  private Acquisition acquire(UUID orderId, String key) {
    return tx.execute(
        () -> {
          var order = orders.findForPayment(orderId);
          var claim =
              idempotency.acquire(
                  order.customerId(),
                  "pay-order:" + orderId,
                  key,
                  RequestHash.of(orderId.toString()));
          if (claim.replay() != null) return new Acquisition(orderId, null, null, claim.replay());
          var payment =
              payments
                  .findByOrder(orderId)
                  .orElseGet(
                      () -> Payment.create(ids.next(), orderId, order.total(), clock.instant()));
          if (payment.status() == Payment.Status.SUCCEEDED) {
            var response = orders.represent(order);
            idempotency.complete(claim.id(), orderId, response);
            return new Acquisition(orderId, null, null, response);
          }
          order.requirePayable();
          var attempt = payment.acquire(ids.next(), processor.provider(), clock.instant());
          orders.claimPayment(order);
          payments.save(payment);
          idempotency.associate(claim.id(), orderId);
          return new Acquisition(
              orderId,
              attempt.id(),
              new PaymentProcessorPort.Operation(attempt.idempotencyReference(), payment.amount()),
              null);
        });
  }

  public OrderView persistResult(UUID orderId, UUID attemptId, PaymentProcessorPort.Result result) {
    return tx.execute(
        () -> {
          var order = orders.findForPayment(orderId);
          var payment = payments.findByOrder(orderId).orElseThrow();
          var attempt =
              payment.attempts().stream()
                  .filter(a -> a.id().equals(attemptId))
                  .findFirst()
                  .orElseThrow();
          // A competing recovery worker may already have committed the definitive outcome.
          if (!attempt.blocksCharge()) return orders.represent(order);
          payment.resolve(attemptId, result.outcome(), result.providerReference(), clock.instant());
          payment = payments.save(payment);
          if (result.outcome() == Payment.Status.SUCCEEDED)
            order =
                orders.confirmPayment(
                    order,
                    payment.amount(),
                    new OrderView.PaymentSummary(
                        payment.id(),
                        payment.status().name(),
                        payment.amount().amount(),
                        payment.amount().currency()));
          var response = orders.represent(order);
          idempotency.completePendingPayments(orderId, response);
          if (attempt.status() != result.outcome())
            metrics.increment("payments." + result.outcome().name().toLowerCase(Locale.ROOT));
          return response;
        });
  }

  public int recover(Duration age, int limit) {
    var candidates = tx.execute(() -> payments.recoverable(clock.instant().minus(age), limit));
    int recovered = 0;
    for (var candidate : candidates) {
      try {
        // Lookup first; only the fake adapter guarantees safe replay of a missing operation.
        var result = processor.lookup(candidate.reference());
        if (result.isEmpty() && processor.supportsIdempotentReplay()) {
          var amount =
              tx.execute(() -> payments.findByOrder(candidate.orderId()).orElseThrow().amount());
          result =
              Optional.of(
                  processor.process(
                      new PaymentProcessorPort.Operation(candidate.reference(), amount)));
        }
        persistResult(
            candidate.orderId(),
            candidate.attemptId(),
            result.orElse(new PaymentProcessorPort.Result(Payment.Status.UNKNOWN, null)));
        recovered++;
      } catch (RuntimeException e) {
        metrics.increment("payments.recovery.errors");
        LOG.log(
            System.Logger.Level.WARNING,
            "Recovery of attempt {0} failed with {1}; durable state is retained",
            candidate.attemptId(),
            e.getClass().getSimpleName());
      }
    }
    return recovered;
  }
}
