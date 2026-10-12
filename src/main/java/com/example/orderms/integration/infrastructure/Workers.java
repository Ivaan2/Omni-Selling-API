package com.example.orderms.integration.infrastructure;

import com.example.orderms.config.AppProperties;
import com.example.orderms.integration.application.OutboxDispatcher;
import com.example.orderms.payment.application.PaymentService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.*;
import org.springframework.context.event.*;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class Workers {
  private static final Logger log = LoggerFactory.getLogger(Workers.class);
  private final OutboxDispatcher dispatcher;
  private final PaymentService payments;
  private final AppProperties config;
  private final AtomicBoolean stopping = new AtomicBoolean();

  public Workers(OutboxDispatcher dispatcher, PaymentService payments, AppProperties config) {
    this.dispatcher = dispatcher;
    this.payments = payments;
    this.config = config;
  }

  @EventListener(ContextClosedEvent.class)
  public void stop() {
    stopping.set(true);
  }

  @Scheduled(fixedDelayString = "${app.outbox.poll-interval}")
  public void deliver() {
    if (stopping.get() || !config.outbox().enabled()) return;
    try {
      dispatcher.dispatch();
    } catch (RuntimeException e) {
      log.error("Outbox polling failed: {}", e.getClass().getSimpleName());
    }
  }

  @Scheduled(fixedDelayString = "${app.payment.recovery-interval}")
  public void recover() {
    if (stopping.get() || !config.outbox().enabled()) return;
    try {
      payments.recover(config.payment().recoveryAge(), config.outbox().batchSize());
    } catch (RuntimeException e) {
      log.error(
          "Payment recovery failed; persisted attempts remain recoverable: {}",
          e.getClass().getSimpleName());
    }
  }
}
