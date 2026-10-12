package com.example.orderms.payment.infrastructure.provider;

import com.example.orderms.config.AppProperties;
import com.example.orderms.payment.application.port.out.PaymentProcessorPort;
import com.example.orderms.payment.domain.Payment;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class FakePaymentAdapter implements PaymentProcessorPort {
  private final JdbcTemplate jdbc;
  private final AppProperties config;
  private final Clock clock;

  public FakePaymentAdapter(JdbcTemplate jdbc, AppProperties config, Clock clock) {
    this.jdbc = jdbc;
    this.config = config;
    this.clock = clock;
    if (!"fake".equals(config.payment().adapter()))
      throw new IllegalArgumentException("Only fake payment adapter is implemented");
  }

  public Result process(Operation op) {
    requireOutsideTransaction();
    jdbc.update(
        "insert into"
            + " simulated_provider_operations(idempotency_reference,amount,currency,outcome,provider_reference,created_at)"
            + " values (?,?,?,?,?,?) on conflict(idempotency_reference) do nothing",
        op.idempotencyReference(),
        op.amount().amount(),
        op.amount().currency(),
        config.payment().fakeOutcome().name(),
        "fake-" + op.idempotencyReference(),
        Timestamp.from(clock.instant()));
    var amount =
        jdbc.queryForObject(
            "select amount from simulated_provider_operations where idempotency_reference=?",
            java.math.BigDecimal.class,
            op.idempotencyReference());
    if (amount == null || amount.compareTo(op.amount().amount()) != 0)
      throw new IllegalStateException("Provider key amount mismatch");
    return read(op.idempotencyReference()).orElseThrow();
  }

  public Optional<Result> lookup(String reference) {
    requireOutsideTransaction();
    // Explicit simulator-only reconciliation configuration; no public outcome controls.
    if (config.payment().fakeLookupOutcome() != Payment.Status.UNKNOWN)
      jdbc.update(
          "update simulated_provider_operations set outcome=? where idempotency_reference=? and"
              + " outcome='UNKNOWN'",
          config.payment().fakeLookupOutcome().name(),
          reference);
    return read(reference);
  }

  private Optional<Result> read(String reference) {
    return jdbc
        .query(
            "select outcome,provider_reference from simulated_provider_operations where"
                + " idempotency_reference=?",
            (rs, n) -> new Result(Payment.Status.valueOf(rs.getString(1)), rs.getString(2)),
            reference)
        .stream()
        .findFirst();
  }

  private void requireOutsideTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Provider I/O must be outside transactions");
  }

  public boolean supportsIdempotentReplay() {
    return true;
  }

  public String provider() {
    return "fake";
  }
}
