package com.example.orderms.integration.application.port.out;

import com.example.orderms.integration.domain.IntegrationEvent;
import java.time.Duration;
import java.util.*;

public interface DeliveryStore {
  record Claimed(
      UUID eventId,
      String destination,
      UUID claimToken,
      int retryCount,
      IntegrationEvent event,
      String payload) {}

  List<Claimed> claim(int batch, Duration lease);

  boolean acknowledge(Claimed delivery);

  void fail(Claimed delivery, String error, Duration delay, int maxRetries);

  boolean consumeAndAcknowledge(Claimed delivery, InternalEventConsumer consumer);
}
