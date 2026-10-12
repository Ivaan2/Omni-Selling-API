package com.example.orderms.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.orderms.ordering.application.*;
import com.example.orderms.ordering.application.port.out.*;
import com.example.orderms.ordering.domain.Order;
import com.example.orderms.ordering.domain.OrderItem;
import com.example.orderms.payment.application.*;
import com.example.orderms.payment.application.port.out.*;
import com.example.orderms.payment.domain.Payment;
import com.example.orderms.shared.application.*;
import com.example.orderms.shared.domain.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;

class PaymentServiceTest {
  private static class TrackingTransactions implements TransactionBoundary {
    boolean active;
    int commits;

    public <T> T execute(Supplier<T> work) {
      assertThat(active).isFalse();
      active = true;
      try {
        var result = work.get();
        commits++;
        return result;
      } finally {
        active = false;
      }
    }
  }

  private final TrackingTransactions tx = new TrackingTransactions();
  private final PaymentStore store = mock(PaymentStore.class);
  private final PaymentProcessorPort provider = mock(PaymentProcessorPort.class);
  private final OrderService orders = mock(OrderService.class);
  private final IdempotencyStore idem = mock(IdempotencyStore.class);
  private final BusinessMetrics metrics = mock(BusinessMetrics.class);
  private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
  private final AtomicReference<Payment> persisted = new AtomicReference<>();
  private final Order order =
      Order.create(
          UUID.randomUUID(),
          "customer",
          List.of(
              new OrderItem(
                  UUID.randomUUID(),
                  UUID.randomUUID(),
                  "Mouse",
                  2,
                  new Money(new BigDecimal("25"), "EUR"))),
          now);
  private final PaymentService service =
      new PaymentService(
          store,
          provider,
          orders,
          idem,
          tx,
          UUID::randomUUID,
          Clock.fixed(now, ZoneOffset.UTC),
          metrics);

  @BeforeEach
  void prepare() {
    when(orders.findForPayment(order.id())).thenReturn(order);
    when(idem.acquire(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(new IdempotencyStore.Claim(UUID.randomUUID(), null));
    when(store.findByOrder(order.id())).thenAnswer(c -> Optional.ofNullable(persisted.get()));
    when(store.save(any()))
        .thenAnswer(
            c -> {
              Payment p = c.getArgument(0);
              persisted.set(p);
              return p;
            });
    when(provider.provider()).thenReturn("test");
    when(orders.represent(any()))
        .thenAnswer(
            c -> {
              var p = persisted.get();
              return OrderView.from(
                  order,
                  p == null
                      ? null
                      : new OrderView.PaymentSummary(
                          p.id(), p.status().name(), p.amount().amount(), p.amount().currency()),
                  null);
            });
    when(orders.confirmPayment(any(), any(), any()))
        .thenAnswer(
            c -> {
              order.markPaid(c.getArgument(1), now);
              return order;
            });
  }

  @Test
  void acquisitionCommitsBeforeProviderAndSuccessConfirmsExactAmount() {
    when(provider.process(any()))
        .thenAnswer(
            c -> {
              assertThat(tx.active).isFalse();
              assertThat(tx.commits).isEqualTo(1);
              assertThat(persisted.get().status()).isEqualTo(Payment.Status.PENDING);
              var operation = (PaymentProcessorPort.Operation) c.getArgument(0);
              assertThat(operation.amount()).isEqualTo(order.total());
              return new PaymentProcessorPort.Result(Payment.Status.SUCCEEDED, "ref");
            });
    var response = service.pay(order.id(), "key");
    assertThat(response.status()).isEqualTo("PAID");
    assertThat(persisted.get().status()).isEqualTo(Payment.Status.SUCCEEDED);
    verify(orders).confirmPayment(eq(order), eq(order.total()), any());
  }

  @Test
  void definitiveFailureLeavesOrderCreated() {
    when(provider.process(any()))
        .thenReturn(new PaymentProcessorPort.Result(Payment.Status.FAILED, "declined"));
    assertThat(service.pay(order.id(), "key").payment().status()).isEqualTo("FAILED");
    assertThat(order.status()).isEqualTo(Order.Status.CREATED);
    verify(orders, never()).confirmPayment(any(), any(), any());
  }

  @Test
  void providerExceptionPersistsUnknownRatherThanDefinitiveFailure() {
    when(provider.process(any())).thenThrow(new IllegalStateException("timeout"));
    var response = service.pay(order.id(), "key");
    assertThat(response.payment().status()).isEqualTo("UNKNOWN");
    assertThat(persisted.get().blocksCharge()).isTrue();
    verify(idem).completePendingPayments(eq(order.id()), eq(response));
    verify(orders, never()).confirmPayment(any(), any(), any());
  }

  @Test
  void cancelledOrderNeverInvokesProvider() {
    order.cancel(false, now);
    assertThatThrownBy(() -> service.pay(order.id(), "key")).isInstanceOf(BusinessException.class);
    verify(provider, never()).process(any());
    assertThat(persisted.get()).isNull();
  }

  @Test
  void recoveryNeverReplaysNonIdempotentProviderOperation() {
    var p = Payment.create(UUID.randomUUID(), order.id(), order.total(), now);
    var attempt = p.acquire(UUID.randomUUID(), "test", now);
    persisted.set(p);
    when(store.recoverable(any(), anyInt()))
        .thenReturn(
            List.of(
                new PaymentStore.Recoverable(
                    order.id(), attempt.id(), attempt.idempotencyReference())));
    when(provider.lookup(attempt.idempotencyReference())).thenReturn(Optional.empty());
    service.recover(Duration.ZERO, 1);
    assertThat(persisted.get().status()).isEqualTo(Payment.Status.UNKNOWN);
    verify(provider, never()).process(any());
  }
}
