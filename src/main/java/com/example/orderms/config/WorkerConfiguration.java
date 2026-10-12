package com.example.orderms.config;

import com.example.orderms.integration.application.OutboxDispatcher;
import com.example.orderms.integration.application.port.out.*;
import com.example.orderms.shared.application.BusinessMetrics;
import org.springframework.context.annotation.*;

@Configuration
public class WorkerConfiguration {
  @Bean
  OutboxDispatcher outboxDispatcher(
      DeliveryStore store,
      EventPublisherPort publisher,
      InternalEventConsumer consumer,
      AppProperties config,
      BusinessMetrics metrics) {
    var c = config.outbox();
    return new OutboxDispatcher(
        store,
        publisher,
        consumer,
        new OutboxDispatcher.Policy(
            c.batchSize(), c.lease(), c.maxRetries(), c.retryBase(), c.retryMax()),
        metrics);
  }
}
