package com.example.orderms.shared.infrastructure;

import com.example.orderms.shared.application.BusinessMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class MicrometerBusinessMetrics implements BusinessMetrics {
  private final MeterRegistry registry;

  public MicrometerBusinessMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public void increment(String name) {
    if (org.springframework.transaction.support.TransactionSynchronizationManager
        .isSynchronizationActive()) {
      org.springframework.transaction.support.TransactionSynchronizationManager
          .registerSynchronization(
              new org.springframework.transaction.support.TransactionSynchronization() {
                public void afterCommit() {
                  registry.counter(name).increment();
                }
              });
    } else registry.counter(name).increment();
  }
}
