package com.example.orderms.fulfilment.infrastructure.persistence;

import com.example.orderms.fulfilment.application.port.out.FulfilmentStore;
import com.example.orderms.fulfilment.domain.Fulfilment;
import jakarta.persistence.EntityManager;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JpaFulfilmentStore implements FulfilmentStore {
  private final EntityManager em;
  private final JdbcTemplate jdbc;
  private final com.example.orderms.shared.application.BusinessMetrics metrics;

  public JpaFulfilmentStore(
      EntityManager em,
      JdbcTemplate jdbc,
      com.example.orderms.shared.application.BusinessMetrics metrics) {
    this.em = em;
    this.jdbc = jdbc;
    this.metrics = metrics;
  }

  public Optional<Fulfilment> findByOrder(UUID orderId) {
    return em.createQuery(
            "select f from FulfilmentEntity f where f.orderId=:id", FulfilmentEntity.class)
        .setParameter("id", orderId)
        .getResultStream()
        .findFirst()
        .map(
            e ->
                new Fulfilment(
                    e.id,
                    e.orderId,
                    Fulfilment.Status.valueOf(e.status),
                    e.createdAt,
                    e.updatedAt,
                    e.version));
  }

  public void createIfAbsent(Fulfilment f) {
    int inserted =
        jdbc.update(
            "insert into fulfilments(id,order_id,status,created_at,updated_at,version) values"
                + " (?,?,'PENDING',?,?,0) on conflict(order_id) do nothing",
            f.id(),
            f.orderId(),
            java.sql.Timestamp.from(f.createdAt()),
            java.sql.Timestamp.from(f.updatedAt()));
    if (inserted == 1) metrics.increment("fulfilments.initialized");
  }
}
