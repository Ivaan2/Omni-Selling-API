package com.example.orderms.integration.infrastructure.outbox;

import com.example.orderms.integration.application.port.out.*;
import com.example.orderms.integration.domain.IntegrationEvent;
import com.example.orderms.shared.application.*;
import java.time.Duration;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class PostgresDeliveryStore implements DeliveryStore {
  private final JdbcTemplate jdbc;
  private final TransactionBoundary tx;
  private final JsonMapper json;
  private final IdGenerator ids;

  public PostgresDeliveryStore(
      JdbcTemplate jdbc, TransactionBoundary tx, JsonMapper json, IdGenerator ids) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.json = json;
    this.ids = ids;
  }

  public List<Claimed> claim(int batch, Duration lease) {
    return tx.execute(
        () -> {
          var token = ids.next();
          return jdbc.query(
              """
              WITH eligible AS (
               SELECT event_id,destination FROM outbox_deliveries
               WHERE (status='PENDING' AND next_retry_at<=CURRENT_TIMESTAMP)
                  OR (status='PROCESSING' AND locked_until<=CURRENT_TIMESTAMP)
               ORDER BY created_at,event_id,destination
               LIMIT ? FOR UPDATE SKIP LOCKED
              ), claimed AS (
               UPDATE outbox_deliveries d SET status='PROCESSING',claim_token=?,locked_until=CURRENT_TIMESTAMP + (? * interval '1 millisecond')
               FROM eligible e WHERE d.event_id=e.event_id AND d.destination=e.destination
               RETURNING d.event_id,d.destination,d.claim_token,d.retry_count
              ) SELECT c.*,e.payload::text FROM claimed c JOIN outbox_events e ON e.event_id=c.event_id
              """,
              (rs, n) ->
                  new Claimed(
                      rs.getObject(1, UUID.class),
                      rs.getString(2),
                      rs.getObject(3, UUID.class),
                      rs.getInt(4),
                      json.readValue(rs.getString(5), IntegrationEvent.class),
                      rs.getString(5)),
              batch,
              token,
              lease.toMillis());
        });
  }

  public boolean acknowledge(Claimed d) {
    return tx.execute(() -> ack(d) == 1);
  }

  private int ack(Claimed d) {
    return jdbc.update(
        "update outbox_deliveries set"
            + " status='DELIVERED',delivered_at=CURRENT_TIMESTAMP,locked_until=null,claim_token=null,last_error=null"
            + " where event_id=? and destination=? and status='PROCESSING' and claim_token=? and"
            + " locked_until>clock_timestamp()",
        d.eventId(),
        d.destination(),
        d.claimToken());
  }

  public void fail(Claimed d, String error, Duration delay, int maxRetries) {
    tx.execute(
        () -> {
          jdbc.update(
              "update outbox_deliveries set status=case when retry_count+1>=? then 'EXHAUSTED' else"
                  + " 'PENDING' end,retry_count=retry_count+1,next_retry_at=CURRENT_TIMESTAMP+(? *"
                  + " interval '1 millisecond'),locked_until=null,claim_token=null,last_error=?"
                  + " where event_id=? and destination=? and status='PROCESSING' and claim_token=?"
                  + " and locked_until>clock_timestamp()",
              maxRetries,
              delay.toMillis(),
              error,
              d.eventId(),
              d.destination(),
              d.claimToken());
          return null;
        });
  }

  public boolean consumeAndAcknowledge(Claimed d, InternalEventConsumer consumer) {
    return tx.execute(
        () -> {
          var live =
              jdbc.query(
                  "select event_id from outbox_deliveries where event_id=? and destination=? and"
                      + " status='PROCESSING' and claim_token=? and locked_until>clock_timestamp()"
                      + " for update",
                  (rs, n) -> rs.getObject(1, UUID.class),
                  d.eventId(),
                  d.destination(),
                  d.claimToken());
          if (live.isEmpty()) return false;
          consumer.consume(d.event());
          if (ack(d) != 1) throw new IllegalStateException("Internal acknowledgement lost lease");
          return true;
        });
  }
}
