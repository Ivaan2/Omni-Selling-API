package com.example.orderms.integration.infrastructure;

import io.micrometer.core.instrument.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class OutboxMetrics {
  public OutboxMetrics(MeterRegistry registry, JdbcTemplate jdbc) {
    Gauge.builder(
            "outbox.deliveries.pending",
            jdbc,
            j ->
                count(
                    j,
                    "select count(*) from outbox_deliveries where status in"
                        + " ('PENDING','PROCESSING')"))
        .register(registry);
    Gauge.builder(
            "outbox.deliveries.exhausted",
            jdbc,
            j -> count(j, "select count(*) from outbox_deliveries where status='EXHAUSTED'"))
        .register(registry);
    Gauge.builder(
            "outbox.delivery.lag.seconds",
            jdbc,
            j ->
                count(
                    j,
                    "select coalesce(extract(epoch from (CURRENT_TIMESTAMP-min(created_at))),0)"
                        + " from outbox_deliveries where status in ('PENDING','PROCESSING')"))
        .register(registry);
  }

  private static double count(JdbcTemplate jdbc, String sql) {
    var value = jdbc.queryForObject(sql, Double.class);
    return value == null ? 0 : value;
  }
}
