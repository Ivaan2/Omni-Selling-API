package com.example.orderms.config;

import com.example.orderms.catalog.application.*;
import com.example.orderms.catalog.application.port.out.*;
import com.example.orderms.fulfilment.application.*;
import com.example.orderms.fulfilment.application.port.out.*;
import com.example.orderms.ordering.application.*;
import com.example.orderms.ordering.application.port.out.*;
import com.example.orderms.payment.application.*;
import com.example.orderms.payment.application.port.out.*;
import com.example.orderms.shared.application.*;
import com.fasterxml.uuid.Generators;
import java.time.Clock;
import org.springframework.context.annotation.*;

@Configuration
public class CoreConfiguration {
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  IdGenerator idGenerator() {
    var generator = Generators.timeBasedEpochGenerator();
    return generator::generate;
  }

  @Bean
  CatalogService catalogService(ProductStore store, TransactionBoundary tx, Clock clock) {
    return new CatalogService(store, tx, clock);
  }

  @Bean
  OrderService orderService(
      OrderStore orders,
      CatalogPort catalog,
      IdempotencyStore idem,
      OrderEvents events,
      PaymentGuard guard,
      OrderSummaries summaries,
      TransactionBoundary tx,
      IdGenerator ids,
      Clock clock,
      BusinessMetrics metrics) {
    return new OrderService(
        orders, catalog, idem, events, guard, summaries, tx, ids, clock, metrics);
  }

  @Bean
  PaymentService paymentService(
      PaymentStore store,
      PaymentProcessorPort processor,
      OrderService orders,
      IdempotencyStore idem,
      TransactionBoundary tx,
      IdGenerator ids,
      Clock clock,
      BusinessMetrics metrics) {
    return new PaymentService(store, processor, orders, idem, tx, ids, clock, metrics);
  }

  @Bean
  FulfilmentService fulfilmentService(FulfilmentStore store, IdGenerator ids, Clock clock) {
    return new FulfilmentService(store, ids, clock);
  }
}
