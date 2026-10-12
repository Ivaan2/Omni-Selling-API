package com.example.orderms.integration.application;

import com.example.orderms.integration.application.port.out.*;
import com.example.orderms.shared.application.BusinessMetrics;
import java.time.Duration;

public final class OutboxDispatcher {
  public record Policy(
      int batch, Duration lease, int maxRetries, Duration retryBase, Duration retryMax) {}

  private final DeliveryStore store;
  private final EventPublisherPort publisher;
  private final InternalEventConsumer consumer;
  private final Policy policy;
  private final BusinessMetrics metrics;

  public OutboxDispatcher(
      DeliveryStore store,
      EventPublisherPort publisher,
      InternalEventConsumer consumer,
      Policy policy,
      BusinessMetrics metrics) {
    this.store = store;
    this.publisher = publisher;
    this.consumer = consumer;
    this.policy = policy;
    this.metrics = metrics;
  }

  public int dispatch() {
    var batch = store.claim(policy.batch(), policy.lease());
    for (var delivery : batch) {
      try {
        if ("FULFILMENT".equals(delivery.destination())) {
          store.consumeAndAcknowledge(delivery, consumer);
        } else {
          publisher.publish(delivery.eventId(), delivery.payload());
          store.acknowledge(delivery);
        }
      } catch (RuntimeException e) {
        metrics.increment("outbox.delivery.failures");
        long multiplier = 1L << Math.min(delivery.retryCount(), 20);
        var delay = policy.retryBase().multipliedBy(multiplier);
        if (delay.compareTo(policy.retryMax()) > 0) delay = policy.retryMax();
        // No response bodies, URLs, credentials or provider data in operational errors.
        store.fail(delivery, e.getClass().getSimpleName(), delay, policy.maxRetries());
        metrics.increment("outbox.delivery.retries");
      }
    }
    return batch.size();
  }
}
