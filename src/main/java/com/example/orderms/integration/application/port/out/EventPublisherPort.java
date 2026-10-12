package com.example.orderms.integration.application.port.out;

import java.util.UUID;

public interface EventPublisherPort {
  void publish(UUID eventId, String payload);
}
