package com.example.orderms.ordering.infrastructure.persistence;

import com.example.orderms.config.AppProperties;
import com.example.orderms.ordering.application.*;
import com.example.orderms.ordering.application.port.out.IdempotencyStore;
import com.example.orderms.shared.application.IdGenerator;
import com.example.orderms.shared.domain.BusinessException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class PostgresIdempotencyStore implements IdempotencyStore {
  private final JdbcTemplate jdbc;
  private final JsonMapper json;
  private final Clock clock;
  private final IdGenerator ids;
  private final AppProperties config;

  private record Stored(UUID id, String hash, String state, String response) {}

  public PostgresIdempotencyStore(
      JdbcTemplate jdbc, JsonMapper json, Clock clock, IdGenerator ids, AppProperties config) {
    this.jdbc = jdbc;
    this.json = json;
    this.clock = clock;
    this.ids = ids;
    this.config = config;
  }

  public Claim acquire(String customer, String operation, String key, String hash) {
    if (key == null || key.isBlank() || key.length() > 200)
      throw BusinessException.invalid("Idempotency-Key must contain 1 to 200 characters");
    // Delete only completed expired records; unresolved operations never expire away.
    jdbc.update(
        "delete from idempotency_records where customer_scope=? and operation=? and"
            + " idempotency_key=? and state='COMPLETED' and expires_at<=?",
        customer,
        operation,
        key,
        Timestamp.from(clock.instant()));
    var id = ids.next();
    int inserted =
        jdbc.update(
            "insert into"
                + " idempotency_records(id,customer_scope,operation,idempotency_key,request_hash,state,created_at,expires_at)"
                + " values (?,?,?,?,?,'PROCESSING',?,?) on"
                + " conflict(customer_scope,operation,idempotency_key) do nothing",
            id,
            customer,
            operation,
            key,
            hash,
            Timestamp.from(clock.instant()),
            Timestamp.from(clock.instant().plus(config.idempotencyRetention())));
    if (inserted == 1) return new Claim(id, null);
    var existing =
        jdbc.queryForObject(
            "select id,request_hash,state,response::text from idempotency_records where"
                + " customer_scope=? and operation=? and idempotency_key=?",
            (rs, n) ->
                new Stored(
                    rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)),
            customer,
            operation,
            key);
    if (existing == null) throw new IllegalStateException("Idempotency record disappeared");
    if (!hash.equals(existing.hash()))
      throw BusinessException.conflict("Idempotency key was already used with different content");
    if (!"COMPLETED".equals(existing.state()))
      throw BusinessException.conflict(
          "Request is processing; retry with the same key after a delay");
    return new Claim(existing.id(), json.readValue(existing.response(), OrderView.class));
  }

  public void associate(UUID id, UUID resource) {
    jdbc.update(
        "update idempotency_records set resource_id=? where id=? and state='PROCESSING'",
        resource,
        id);
  }

  public void complete(UUID id, UUID resource, OrderView response) {
    jdbc.update(
        "update idempotency_records set state='COMPLETED',resource_id=?,response=cast(? as jsonb)"
            + " where id=? and state='PROCESSING'",
        resource,
        json.writeValueAsString(response),
        id);
  }

  public void completePendingPayments(UUID orderId, OrderView response) {
    jdbc.update(
        "update idempotency_records set state='COMPLETED',response=cast(? as jsonb) where"
            + " resource_id=? and operation=? and state='PROCESSING'",
        json.writeValueAsString(response),
        orderId,
        "pay-order:" + orderId);
  }
}
