package com.example.orderms.integration.infrastructure.outbox;

import com.example.orderms.config.AppProperties;
import com.example.orderms.integration.domain.IntegrationEvent;
import com.example.orderms.ordering.application.OrderView;
import com.example.orderms.ordering.application.port.out.OrderEvents;
import com.example.orderms.ordering.domain.Order;
import com.example.orderms.shared.application.IdGenerator;
import java.sql.Timestamp;
import java.time.Clock;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class PostgresOrderEvents implements OrderEvents {
  private final JdbcTemplate jdbc;
  private final JsonMapper json;
  private final IdGenerator ids;
  private final Clock clock;
  private final AppProperties config;

  public PostgresOrderEvents(
      JdbcTemplate jdbc, JsonMapper json, IdGenerator ids, Clock clock, AppProperties config) {
    this.jdbc = jdbc;
    this.json = json;
    this.ids = ids;
    this.clock = clock;
    this.config = config;
  }

  public void append(String type, Order order, OrderView.PaymentSummary payment) {
    var now = clock.instant();
    var id = ids.next();
    var p =
        payment == null
            ? null
            : new IntegrationEvent.Payment(
                payment.id(), payment.status(), payment.amount(), payment.currency());
    var data =
        new IntegrationEvent.Data(
            order.id(),
            order.customerId(),
            order.status().name(),
            order.total().amount(),
            order.total().currency(),
            order.items().stream()
                .map(
                    i ->
                        new IntegrationEvent.Item(
                            i.productId(),
                            i.productName(),
                            i.quantity(),
                            i.unitPrice().amount(),
                            i.unitPrice().currency()))
                .toList(),
            order.createdAt(),
            order.deletedAt(),
            p,
            MDC.get("correlationId"));
    var event = new IntegrationEvent(id, type, 1, order.id(), order.version(), now, data);
    jdbc.update(
        "insert into"
            + " outbox_events(event_id,aggregate_id,aggregate_type,aggregate_version,event_type,event_version,payload,occurred_at,created_at)"
            + " values (?,?,'Order',?,?,1,cast(? as jsonb),?,?)",
        id,
        order.id(),
        order.version(),
        type,
        json.writeValueAsString(event),
        Timestamp.from(now),
        Timestamp.from(now));
    if (!config.outbox().receiverUrl().isBlank()) delivery(id, "HTTP", now);
    if ("OrderPaid".equals(type)) delivery(id, "FULFILMENT", now);
  }

  private void delivery(java.util.UUID id, String destination, java.time.Instant now) {
    jdbc.update(
        "insert into outbox_deliveries(event_id,destination,status,next_retry_at,created_at) values"
            + " (?,?,'PENDING',?,?)",
        id,
        destination,
        Timestamp.from(now),
        Timestamp.from(now));
  }
}
