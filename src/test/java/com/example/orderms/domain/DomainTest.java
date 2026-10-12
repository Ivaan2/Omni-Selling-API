package com.example.orderms.domain;

import static org.assertj.core.api.Assertions.*;

import com.example.orderms.catalog.domain.*;
import com.example.orderms.fulfilment.domain.*;
import com.example.orderms.ordering.domain.*;
import com.example.orderms.payment.domain.*;
import com.example.orderms.shared.domain.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class DomainTest {
  private final Instant now = Instant.parse("2026-01-01T00:00:00Z");

  private Money eur(String amount) {
    return new Money(new BigDecimal(amount), "EUR");
  }

  private OrderItem item(int qty) {
    return new OrderItem(UUID.randomUUID(), UUID.randomUUID(), "Snapshot", qty, eur("25.00"));
  }

  private Order order() {
    return Order.create(UUID.randomUUID(), "customer", List.of(item(2)), now);
  }

  @Test
  void orderRequiresItems() {
    assertThatThrownBy(() -> Order.create(UUID.randomUUID(), "c", List.of(), now))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void quantitiesMustBePositive() {
    for (int qty : new int[] {0, -1})
      assertThatThrownBy(() -> item(qty)).isInstanceOf(BusinessException.class);
  }

  @Test
  void moneyRejectsPrecisionLossAndCurrencyMismatch() {
    assertThatThrownBy(() -> eur("1.001")).isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> new Money(BigDecimal.ONE, "USD"))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> eur("-1")).isInstanceOf(BusinessException.class);
  }

  @Test
  void totalIsCalculatedFromImmutableSnapshots() {
    var o = order();
    assertThat(o.total()).isEqualTo(eur("50"));
    assertThatThrownBy(() -> o.items().clear()).isInstanceOf(UnsupportedOperationException.class);
    assertThat(o.items().getFirst().unitPrice()).isEqualTo(eur("25"));
  }

  @Test
  void rejectsIncorrectPersistedTotal() {
    assertThatThrownBy(
            () ->
                new Order(
                    UUID.randomUUID(),
                    "c",
                    List.of(item(1)),
                    Order.Status.CREATED,
                    eur("1"),
                    now,
                    now,
                    null,
                    0))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void duplicateProductsRejected() {
    var i = item(1);
    assertThatThrownBy(() -> Order.create(UUID.randomUUID(), "c", List.of(i, i), now))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void createdCanBecomePaidButCannotThenCancelOrDelete() {
    var o = order();
    o.markPaid(o.total(), now);
    assertThat(o.status()).isEqualTo(Order.Status.PAID);
    assertThatThrownBy(() -> o.cancel(false, now)).isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> o.softDelete(false, now)).isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> o.markPaid(o.total(), now)).isInstanceOf(BusinessException.class);
  }

  @Test
  void createdCanCancelAndCancelledCanDelete() {
    var o = order();
    o.cancel(false, now);
    assertThat(o.status()).isEqualTo(Order.Status.CANCELLED);
    assertThat(o.softDelete(false, now)).isTrue();
    assertThat(o.softDelete(false, now)).isFalse();
  }

  @Test
  void mismatchedPaymentRejected() {
    var o = order();
    assertThatThrownBy(() -> o.markPaid(eur("1"), now)).isInstanceOf(BusinessException.class);
  }

  @Test
  void activeOrUncertainChargeBlocksCancelAndDeletion() {
    var o = order();
    assertThatThrownBy(() -> o.cancel(true, now)).isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> o.softDelete(true, now)).isInstanceOf(BusinessException.class);
  }

  @Test
  void deletedOrderRejectsPublicOperations() {
    var o = order();
    o.softDelete(false, now);
    assertThatThrownBy(o::requirePayable).isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> o.cancel(false, now)).isInstanceOf(BusinessException.class);
  }

  private Payment payment() {
    return Payment.create(UUID.randomUUID(), UUID.randomUUID(), eur("50"), now);
  }

  @Test
  void paymentSucceedsOnlyOnce() {
    var p = payment();
    var a = p.acquire(UUID.randomUUID(), "fake", now);
    p.resolve(a.id(), Payment.Status.SUCCEEDED, "provider", now);
    assertThat(p.status()).isEqualTo(Payment.Status.SUCCEEDED);
    assertThatThrownBy(() -> p.acquire(UUID.randomUUID(), "fake", now))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> p.resolve(a.id(), Payment.Status.SUCCEEDED, "provider", now))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void failedAttemptPermitsAnotherAndPreservesHistory() {
    var p = payment();
    var a = p.acquire(UUID.randomUUID(), "fake", now);
    p.resolve(a.id(), Payment.Status.FAILED, "declined", now);
    var b = p.acquire(UUID.randomUUID(), "fake", now.plusSeconds(1));
    assertThat(p.attempts()).hasSize(2);
    assertThat(p.attempts().getFirst().status()).isEqualTo(Payment.Status.FAILED);
    assertThat(b.id()).isNotEqualTo(a.id());
  }

  @Test
  void pendingAndUnknownBlockNewChargesUntilReconciled() {
    var p = payment();
    var a = p.acquire(UUID.randomUUID(), "fake", now);
    assertThatThrownBy(() -> p.acquire(UUID.randomUUID(), "fake", now))
        .isInstanceOf(BusinessException.class);
    p.resolve(a.id(), Payment.Status.UNKNOWN, null, now);
    assertThat(p.blocksCharge()).isTrue();
    assertThatThrownBy(() -> p.cancel(now)).isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> p.acquire(UUID.randomUUID(), "fake", now))
        .isInstanceOf(BusinessException.class);
    p.resolve(a.id(), Payment.Status.SUCCEEDED, "confirmed", now);
    assertThat(p.status()).isEqualTo(Payment.Status.SUCCEEDED);
  }

  @Test
  void fulfilmentOnlyAdvancesOneStep() {
    var f = Fulfilment.pending(UUID.randomUUID(), UUID.randomUUID(), now);
    assertThatThrownBy(() -> f.advanceTo(Fulfilment.Status.DELIVERED, now))
        .isInstanceOf(BusinessException.class);
    for (var next :
        List.of(
            Fulfilment.Status.PROCESSING, Fulfilment.Status.DELIVERED, Fulfilment.Status.FULFILLED))
      f.advanceTo(next, now);
    assertThatThrownBy(() -> f.advanceTo(Fulfilment.Status.PROCESSING, now))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void productDeletionDoesNotChangeSnapshot() {
    var p =
        new Product(UUID.randomUUID(), "Mouse", "Description", eur("25"), true, now, now, null, 0);
    var i = new OrderItem(UUID.randomUUID(), p.id(), p.name(), 2, p.price());
    p.softDelete(now);
    assertThatThrownBy(p::requirePurchasable).isInstanceOf(BusinessException.class);
    assertThat(i.subtotal()).isEqualTo(eur("50"));
    assertThat(p.softDelete(now)).isFalse();
  }

  @Test
  void unavailableProductsRejected() {
    var p = new Product(UUID.randomUUID(), "Mouse", "", eur("25"), false, now, now, null, 0);
    assertThatThrownBy(p::requirePurchasable).isInstanceOf(BusinessException.class);
  }
}
