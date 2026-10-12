package com.example.orderms.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.orderms.ordering.application.*;
import com.example.orderms.ordering.application.port.out.*;
import com.example.orderms.ordering.domain.Order;
import com.example.orderms.shared.application.*;
import com.example.orderms.shared.domain.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;

class OrderServiceTest {
  OrderStore store = mock(OrderStore.class);
  CatalogPort catalog = mock(CatalogPort.class);
  IdempotencyStore idem = mock(IdempotencyStore.class);
  OrderEvents events = mock(OrderEvents.class);
  PaymentGuard guard = mock(PaymentGuard.class);
  OrderSummaries summaries = mock(OrderSummaries.class);
  BusinessMetrics metrics = mock(BusinessMetrics.class);
  TransactionBoundary tx =
      new TransactionBoundary() {
        public <T> T execute(Supplier<T> work) {
          return work.get();
        }
      };
  OrderService service =
      new OrderService(
          store,
          catalog,
          idem,
          events,
          guard,
          summaries,
          tx,
          UUID::randomUUID,
          Clock.systemUTC(),
          metrics);

  @Test
  void pricesAreResolvedFromCatalogAndEventContainsPersistedOrder() {
    var product = UUID.randomUUID();
    var claimId = UUID.randomUUID();
    when(idem.acquire(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(new IdempotencyStore.Claim(claimId, null));
    when(catalog.resolve(product))
        .thenReturn(
            new CatalogPort.ProductSnapshot(
                product, "Mouse", new Money(new BigDecimal("25"), "EUR")));
    when(store.save(any())).thenAnswer(i -> i.getArgument(0));
    var response =
        service.create(
            new CreateOrderCommand(
                "customer", List.of(new CreateOrderCommand.Line(product, 2)), "key"));
    assertThat(response.totalAmount()).isEqualByComparingTo("50");
    verify(events)
        .append(
            eq("OrderCreated"),
            argThat(o -> o.total().amount().compareTo(new BigDecimal("50")) == 0),
            isNull());
    verify(idem).complete(claimId, response.id(), response);
  }

  @Test
  void replayDoesNotResolveProductsOrCreateAnotherOrder() {
    var snapshot =
        new OrderView(
            UUID.randomUUID(),
            "c",
            "CREATED",
            List.of(),
            BigDecimal.ONE,
            "EUR",
            Instant.now(),
            0,
            null,
            null);
    when(idem.acquire(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(new IdempotencyStore.Claim(UUID.randomUUID(), snapshot));
    assertThat(service.create(new CreateOrderCommand("c", List.of(), "key"))).isSameAs(snapshot);
    verifyNoInteractions(catalog, events, store);
  }

  @Test
  void paymentGuardPreventsCancellationAndDeletion() {
    var item =
        new com.example.orderms.ordering.domain.OrderItem(
            UUID.randomUUID(), UUID.randomUUID(), "Mouse", 1, new Money(BigDecimal.ONE, "EUR"));
    var order = Order.create(UUID.randomUUID(), "c", List.of(item), Instant.now());
    when(store.find(order.id(), true)).thenReturn(Optional.of(order));
    when(guard.blocksChange(order.id())).thenReturn(true);
    assertThatThrownBy(() -> service.cancel(order.id())).isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.softDelete(order.id())).isInstanceOf(BusinessException.class);
    verifyNoInteractions(events);
  }

  @Test
  void canonicalHashIgnoresItemOrderButPreservesContent() {
    var a = new CreateOrderCommand.Line(UUID.randomUUID(), 1);
    var b = new CreateOrderCommand.Line(UUID.randomUUID(), 2);
    assertThat(new CreateOrderCommand("c", List.of(a, b), "k").canonicalHash())
        .isEqualTo(new CreateOrderCommand("c", List.of(b, a), "another").canonicalHash());
    assertThat(new CreateOrderCommand("c", List.of(a, b), "k").canonicalHash())
        .isNotEqualTo(new CreateOrderCommand("other", List.of(a, b), "k").canonicalHash());
  }
}
