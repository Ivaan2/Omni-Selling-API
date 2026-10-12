package com.example.orderms.integration.infrastructure.consumer;

import com.example.orderms.fulfilment.application.FulfilmentService;
import com.example.orderms.integration.application.port.out.InternalEventConsumer;
import com.example.orderms.integration.domain.IntegrationEvent;
import org.springframework.stereotype.Component;

@Component
public class OrderPaidConsumer implements InternalEventConsumer {
  private final FulfilmentService fulfilment;

  public OrderPaidConsumer(FulfilmentService fulfilment) {
    this.fulfilment = fulfilment;
  }

  public void consume(IntegrationEvent e) {
    if (!"OrderPaid".equals(e.eventType())
        || e.eventVersion() != 1
        || !"PAID".equals(e.data().status())
        || !e.aggregateId().equals(e.data().orderId()))
      throw new IllegalArgumentException("Unsupported fulfilment event");
    fulfilment.initialize(e.data().orderId());
  }
}
