package com.example.orderms.integration.application.port.out;

import com.example.orderms.integration.domain.IntegrationEvent;

public interface InternalEventConsumer {
  void consume(IntegrationEvent event);
}
