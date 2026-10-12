package com.example.orderms.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.orderms.integration.application.*;
import com.example.orderms.integration.application.port.out.*;
import com.example.orderms.ordering.application.*;
import com.example.orderms.ordering.application.port.out.*;
import com.example.orderms.payment.application.*;
import com.example.orderms.payment.application.port.out.*;
import com.example.orderms.payment.domain.Payment;
import com.example.orderms.payment.infrastructure.provider.FakePaymentAdapter;
import com.example.orderms.shared.application.*;
import com.example.orderms.shared.domain.*;
import com.sun.net.httpserver.HttpServer;
import jakarta.persistence.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(
    properties = {
      "app.admin.password=test-only-admin",
      "app.outbox.enabled=false",
      "app.outbox.batch-size=20",
      "app.outbox.lease=3m"
    })
@AutoConfigureMockMvc
class OrderManagementIT {
  @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.6");
  static final AtomicBoolean receiverFails = new AtomicBoolean();
  static final List<String> received = new CopyOnWriteArrayList<>();
  static final HttpServer receiver = createReceiver();

  static HttpServer createReceiver() {
    try {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/events",
          exchange -> {
            received.add(
                new String(
                    exchange.getRequestBody().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(receiverFails.get() ? 503 : 204, -1);
            exchange.close();
          });
      server.start();
      return server;
    } catch (java.io.IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
    r.add(
        "app.outbox.receiver-url",
        () -> "http://127.0.0.1:" + receiver.getAddress().getPort() + "/events");
  }

  @Autowired OrderService orders;
  @Autowired PaymentService payments;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionBoundary tx;
  @Autowired OrderStore orderStore;
  @Autowired PaymentStore paymentStore;
  @Autowired DeliveryStore deliveries;
  @Autowired InternalEventConsumer internal;
  @Autowired OutboxDispatcher dispatcher;
  @Autowired MockMvc mvc;
  @Autowired JsonMapper json;
  @Autowired EntityManager em;
  @MockitoSpyBean FakePaymentAdapter provider;
  static final UUID PRODUCT = UUID.fromString("00000000-0000-4000-8000-000000000001");

  @BeforeEach
  void reset() {
    org.mockito.Mockito.reset(provider);
    jdbc.execute(
        "truncate table"
            + " outbox_deliveries,outbox_events,idempotency_records,fulfilments,payment_attempts,payments,order_items,orders,simulated_provider_operations");
    jdbc.update(
        "update products set deleted_at=null,version=0,price=25.00,available=true where id=?",
        PRODUCT);
    receiverFails.set(false);
    received.clear();
  }

  @AfterAll
  static void stopReceiver() {
    receiver.stop(0);
  }

  OrderView create(String key) {
    return orders.create(
        new CreateOrderCommand("customer", List.of(new CreateOrderCommand.Line(PRODUCT, 2)), key));
  }

  long count(String table) {
    return jdbc.queryForObject("select count(*) from " + table, Long.class);
  }

  @Test
  void migrationsJpaUuidAndPrecision() {
    var o = create("schema");
    assertThat(o.id().version()).isEqualTo(7);
    assertThat(o.totalAmount()).isEqualByComparingTo("50.00");
    assertThat(count("databasechangelog")).isEqualTo(3);
    assertThat(count("order_items")).isEqualTo(1);
    assertThat(count("outbox_events")).isEqualTo(1);
    assertThat(count("outbox_deliveries")).isEqualTo(1);
    assertThat(orders.get(o.id()).items().getFirst().productName()).isEqualTo("Wireless Mouse");
  }

  @Test
  void creationReplayHasOriginalSnapshotAndConflictsWithChangedContent() {
    var first = create("same");
    payments.pay(first.id(), "pay");
    var replay = create("same");
    assertThat(replay).isEqualTo(first);
    assertThat(count("orders")).isEqualTo(1);
    assertThatThrownBy(
            () ->
                orders.create(
                    new CreateOrderCommand(
                        "customer", List.of(new CreateOrderCommand.Line(PRODUCT, 1)), "same")))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("different content");
  }

  @Test
  void concurrentDuplicateCreationIsAtomic() throws Exception {
    var results = parallel(() -> create("duplicate"), () -> create("duplicate"));
    assertThat(results.get(0).id()).isEqualTo(results.get(1).id());
    assertThat(count("orders")).isEqualTo(1);
    assertThat(count("outbox_events")).isEqualTo(1);
  }

  @Test
  void rollbackIncludesBusinessStateAndEvent() {
    assertThatThrownBy(
            () ->
                tx.execute(
                    () -> {
                      create("rollback");
                      throw new IllegalStateException("abort");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(count("orders")).isZero();
    assertThat(count("outbox_events")).isZero();
    assertThat(count("idempotency_records")).isZero();
  }

  @Test
  void failureWritingOutboxRollsBackOrder() {
    jdbc.execute(
        "alter table outbox_events add constraint temporary_fail check(event_type <>"
            + " 'OrderCreated')");
    try {
      assertThatThrownBy(() -> create("event-failure"))
          .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
      assertThat(count("orders")).isZero();
      assertThat(count("order_items")).isZero();
      assertThat(count("idempotency_records")).isZero();
    } finally {
      jdbc.execute("alter table outbox_events drop constraint temporary_fail");
    }
  }

  @Test
  void constraintsRejectInvalidQuantityAndSecondActiveAttempt() {
    var o = create("constraints");
    var payment = payments.pay(o.id(), "pay");
    assertThatThrownBy(
            () -> jdbc.update("update order_items set quantity=0 where order_id=?", o.id()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    var p = payment.payment().id();
    jdbc.update(
        "insert into"
            + " payment_attempts(id,payment_id,provider,idempotency_reference,status,created_at,updated_at)"
            + " values (?,?,'fake',?,'UNKNOWN',now(),now())",
        UUID.randomUUID(),
        p,
        UUID.randomUUID().toString());
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into"
                        + " payment_attempts(id,payment_id,provider,idempotency_reference,status,created_at,updated_at)"
                        + " values (?,?,'fake',?,'PENDING',now(),now())",
                    UUID.randomUUID(),
                    p,
                    UUID.randomUUID().toString()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void staleOrderVersionIsRejected() {
    var o = create("version");
    var stale = tx.execute(() -> orderStore.find(o.id(), false).orElseThrow());
    orders.cancel(o.id());
    assertThatThrownBy(() -> tx.execute(() -> orderStore.save(stale)))
        .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
  }

  @Test
  void stalePaymentVersionIsRejected() {
    var o = create("payment-version");
    payments.pay(o.id(), "pay");
    var stale = tx.execute(() -> paymentStore.findByOrder(o.id()).orElseThrow());
    jdbc.update("update payments set version=version+1 where order_id=?", o.id());
    assertThatThrownBy(() -> tx.execute(() -> paymentStore.save(stale)))
        .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
  }

  @Test
  void jpaFulfilmentVersionProtectsState() {
    var o = create("fulfilment-version");
    payments.pay(o.id(), "pay");
    dispatcher.dispatch();
    assertThatThrownBy(
            () ->
                tx.execute(
                    () -> {
                      var entity =
                          em.createQuery("select f from FulfilmentEntity f").getSingleResult();
                      jdbc.update(
                          "update fulfilments set version=version+1 where order_id=?", o.id());
                      em.lock(entity, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
                      em.flush();
                      return null;
                    }))
        .isInstanceOf(org.springframework.orm.ObjectOptimisticLockingFailureException.class);
  }

  @Test
  void paymentReplayAndPermanentConstraintsSurviveRequestExpiry() {
    var o = create("paid");
    var first = payments.pay(o.id(), "key");
    assertThat(payments.pay(o.id(), "key")).isEqualTo(first);
    jdbc.update("update idempotency_records set expires_at=now()-interval '1 second'");
    assertThat(payments.pay(o.id(), "new-key").status()).isEqualTo("PAID");
    assertThat(count("simulated_provider_operations")).isEqualTo(1);
    assertThat(count("payment_attempts")).isEqualTo(1);
    assertThatThrownBy(() -> orders.cancel(o.id())).isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> orders.softDelete(o.id())).isInstanceOf(BusinessException.class);
  }

  @Test
  void twoPaymentKeysCannotAcquireTwoProviderOperations() throws Exception {
    var o = create("race");
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(
            call -> {
              entered.countDown();
              assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
              return call.callRealMethod();
            })
        .when(provider)
        .process(any());
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(() -> payments.pay(o.id(), "first"));
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      try {
        assertThatThrownBy(() -> payments.pay(o.id(), "second"))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> payments.pay(o.id(), "first"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("processing");
        assertThatThrownBy(() -> orders.cancel(o.id())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> orders.softDelete(o.id())).isInstanceOf(BusinessException.class);
      } finally {
        release.countDown();
      }
      assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo("PAID");
    }
    assertThat(count("simulated_provider_operations")).isEqualTo(1);
    assertThat(count("payment_attempts")).isEqualTo(1);
    assertThat(orders.get(o.id()).payment().status()).isEqualTo("SUCCEEDED");
  }

  @Test
  void concurrentPaymentAndCancellationHaveOneValidOutcome() throws Exception {
    var o = create("cancel-race");
    var outcomes =
        parallel(
            () -> outcome(() -> payments.pay(o.id(), "pay")),
            () -> outcome(() -> orders.cancel(o.id())));
    assertThat(outcomes).containsExactlyInAnyOrder(true, false);
    var order = orders.get(o.id());
    if (order.status().equals("PAID")) {
      assertThat(order.payment().status()).isEqualTo("SUCCEEDED");
      assertThat(count("simulated_provider_operations")).isEqualTo(1);
    } else {
      assertThat(order.status()).isEqualTo("CANCELLED");
      assertThat(count("simulated_provider_operations")).isZero();
    }
  }

  @Test
  void concurrentPaymentAndDeletionNeverPaysDeletedOrder() throws Exception {
    var o = create("delete-race");
    var outcomes =
        parallel(
            () -> outcome(() -> payments.pay(o.id(), "pay")),
            () ->
                outcome(
                    () -> {
                      orders.softDelete(o.id());
                      return null;
                    }));
    assertThat(outcomes).containsExactlyInAnyOrder(true, false);
    var state = jdbc.queryForMap("select status,deleted_at from orders where id=?", o.id());
    assertThat(state.get("status").equals("PAID") && state.get("deleted_at") != null).isFalse();
  }

  boolean outcome(Callable<?> work) {
    try {
      work.call();
      return true;
    } catch (BusinessException e) {
      return false;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void unknownChargeBlocksNewKeysAndCanReconcile() {
    var o = create("unknown");
    doAnswer(
            call -> {
              var op = (PaymentProcessorPort.Operation) call.getArgument(0);
              jdbc.update(
                  "insert into simulated_provider_operations values (?,?,?,'UNKNOWN',?,now())",
                  op.idempotencyReference(),
                  op.amount().amount(),
                  op.amount().currency(),
                  "fake-" + op.idempotencyReference());
              return new PaymentProcessorPort.Result(Payment.Status.UNKNOWN, null);
            })
        .when(provider)
        .process(any());
    var response = payments.pay(o.id(), "unknown-pay");
    assertThat(response.status()).isEqualTo("CREATED");
    assertThat(response.payment().status()).isEqualTo("UNKNOWN");
    assertThatThrownBy(() -> payments.pay(o.id(), "distinct"))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> orders.cancel(o.id())).isInstanceOf(BusinessException.class);
    jdbc.update("update simulated_provider_operations set outcome='SUCCEEDED'");
    payments.recover(Duration.ZERO, 10);
    assertThat(orders.get(o.id()).status()).isEqualTo("PAID");
    assertThat(count("payment_attempts")).isEqualTo(1);
    assertThat(payments.pay(o.id(), "unknown-pay").payment().status()).isEqualTo("UNKNOWN");
  }

  @Test
  void definitiveFailureAllowsAnotherDistinctAttempt() {
    var o = create("failed");
    doReturn(new PaymentProcessorPort.Result(Payment.Status.FAILED, "declined"))
        .when(provider)
        .process(any());
    assertThat(payments.pay(o.id(), "failed-key").payment().status()).isEqualTo("FAILED");
    org.mockito.Mockito.reset(provider);
    assertThat(payments.pay(o.id(), "another-key").status()).isEqualTo("PAID");
    assertThat(count("payment_attempts")).isEqualTo(2);
  }

  @Test
  void providerExceptionIsAmbiguousNotFailed() {
    var o = create("timeout");
    doThrow(new IllegalStateException("transport timeout")).when(provider).process(any());
    assertThat(payments.pay(o.id(), "pay").payment().status()).isEqualTo("UNKNOWN");
    assertThatThrownBy(() -> orders.softDelete(o.id())).isInstanceOf(BusinessException.class);
  }

  @Test
  void restartRecoveryUsesDurableAttemptAndStableProviderReference() {
    var o = create("restart");
    // Simulate interruption after provider success but before local confirmation.
    doAnswer(
            call -> {
              call.callRealMethod();
              throw new SimulatedCrash();
            })
        .when(provider)
        .process(any());
    assertThatThrownBy(() -> payments.pay(o.id(), "crash-key")).isInstanceOf(SimulatedCrash.class);
    assertThat(jdbc.queryForObject("select status from payment_attempts", String.class))
        .isEqualTo("PENDING");
    assertThat(count("simulated_provider_operations")).isEqualTo(1);
    var restartedProvider = new FakePaymentAdapter(jdbc, config, Clock.systemUTC());
    var restartedService =
        new PaymentService(
            paymentStore, restartedProvider, orders, idem, tx, ids, Clock.systemUTC(), metrics);
    restartedService.recover(Duration.ZERO, 10);
    assertThat(orders.get(o.id()).status()).isEqualTo("PAID");
    assertThat(count("simulated_provider_operations")).isEqualTo(1);
    assertThat(count("outbox_events")).isEqualTo(2);
    assertThat(payments.pay(o.id(), "crash-key").status()).isEqualTo("PAID");
  }

  @Autowired com.example.orderms.config.AppProperties config;
  @Autowired IdempotencyStore idem;
  @Autowired IdGenerator ids;
  @Autowired BusinessMetrics metrics;

  static class SimulatedCrash extends Error {}

  @Test
  void productDeletionPreservesHistoryAndPreventsNewOrders() throws Exception {
    var o = create("historical");
    mvc.perform(
            patch("/internal/api/v1/products/" + PRODUCT)
                .with(httpBasic("admin", "test-only-admin")))
        .andExpect(status().isOk());
    mvc.perform(
            patch("/internal/api/v1/products/" + PRODUCT)
                .with(httpBasic("admin", "test-only-admin")))
        .andExpect(status().isOk());
    assertThat(orders.get(o.id()).items().getFirst().unitPrice()).isEqualByComparingTo("25");
    assertThatThrownBy(() -> create("new-order")).isInstanceOf(BusinessException.class);
    mvc.perform(get("/api/v1/products"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.products.length()").value(2));
  }

  @Test
  void orderDeletionIsIdempotentAndPubliclyHidden() {
    var o = create("deleted");
    orders.softDelete(o.id());
    orders.softDelete(o.id());
    assertThat(count("orders")).isEqualTo(1);
    assertThat(count("order_items")).isEqualTo(1);
    assertThat(count("outbox_events")).isEqualTo(2);
    assertThatThrownBy(() -> orders.get(o.id())).isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> payments.pay(o.id(), "pay")).isInstanceOf(BusinessException.class);
  }

  @Test
  void deliveryOutageDoesNotFailOrdersOrInternalFulfilment() {
    receiverFails.set(true);
    var o = create("outage");
    payments.pay(o.id(), "pay");
    dispatcher.dispatch();
    assertThat(orders.get(o.id()).fulfilment().status()).isEqualTo("PENDING");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_deliveries where destination='HTTP' and"
                    + " status='PENDING'",
                Long.class))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select status from outbox_deliveries where destination='FULFILMENT'",
                String.class))
        .isEqualTo("DELIVERED");
  }

  @Test
  void dispatcherRetriesStableEventsAndPositiveAcknowledgement() {
    receiverFails.set(true);
    var o = create("retry");
    dispatcher.dispatch();
    assertThat(jdbc.queryForObject("select retry_count from outbox_deliveries", Integer.class))
        .isEqualTo(1);
    var payload = received.getFirst();
    receiverFails.set(false);
    jdbc.update("update outbox_deliveries set next_retry_at=now()-interval '1 second'");
    dispatcher.dispatch();
    assertThat(received).containsExactly(payload, payload);
    assertThat(jdbc.queryForObject("select status from outbox_deliveries", String.class))
        .isEqualTo("DELIVERED");
    assertThat(json.readTree(payload).get("aggregateId").asText()).isEqualTo(o.id().toString());
  }

  @Test
  void exhaustedDeliveryRemainsVisibleAndRecoverable() {
    create("exhausted");
    var d = deliveries.claim(1, Duration.ofSeconds(10)).getFirst();
    deliveries.fail(d, "receiver unavailable", Duration.ZERO, 1);
    assertThat(jdbc.queryForObject("select status from outbox_deliveries", String.class))
        .isEqualTo("EXHAUSTED");
    assertThat(deliveries.claim(1, Duration.ofSeconds(10))).isEmpty();
    jdbc.update(
        "update outbox_deliveries set status='PENDING',retry_count=0,next_retry_at=now() where"
            + " status='EXHAUSTED'");
    dispatcher.dispatch();
    assertThat(jdbc.queryForObject("select status from outbox_deliveries", String.class))
        .isEqualTo("DELIVERED");
  }

  @Test
  void duplicatePaidDeliveryCreatesOnlyOneFulfilment() {
    var o = create("fulfilment");
    payments.pay(o.id(), "pay");
    dispatcher.dispatch();
    jdbc.update(
        "update outbox_deliveries set status='PENDING',next_retry_at=now(),delivered_at=null where"
            + " destination='FULFILMENT'");
    dispatcher.dispatch();
    assertThat(count("fulfilments")).isEqualTo(1);
    assertThat(orders.get(o.id()).fulfilment().status()).isEqualTo("PENDING");
  }

  @Test
  void concurrentOutboxWorkersNeverOwnSameLiveLease() throws Exception {
    for (int i = 0; i < 6; i++) create("worker-" + i);
    var batches =
        parallel(
            () -> deliveries.claim(3, Duration.ofSeconds(10)),
            () -> deliveries.claim(3, Duration.ofSeconds(10)));
    assertThat(batches.get(0)).hasSize(3);
    assertThat(batches.get(1)).hasSize(3);
    assertThat(
            batches.stream()
                .flatMap(List::stream)
                .map(DeliveryStore.Claimed::eventId)
                .distinct()
                .count())
        .isEqualTo(6);
    assertThat(deliveries.claim(10, Duration.ofSeconds(10))).isEmpty();
  }

  @Test
  void expiredLeaseIsRecoverableAndStaleWorkerCannotAcknowledgeOrFail() {
    create("lease");
    var stale = deliveries.claim(1, Duration.ofSeconds(10)).getFirst();
    jdbc.update("update outbox_deliveries set locked_until=now()-interval '1 second'");
    var current = deliveries.claim(1, Duration.ofSeconds(10)).getFirst();
    assertThat(current.claimToken()).isNotEqualTo(stale.claimToken());
    assertThat(deliveries.acknowledge(stale)).isFalse();
    deliveries.fail(stale, "stale", Duration.ZERO, 1);
    assertThat(jdbc.queryForObject("select claim_token from outbox_deliveries", UUID.class))
        .isEqualTo(current.claimToken());
    assertThat(deliveries.acknowledge(current)).isTrue();
  }

  @Test
  void internalConsumerAndAcknowledgementRollbackTogether() {
    var o = create("internal-rollback");
    payments.pay(o.id(), "pay");
    var delivery =
        deliveries.claim(10, Duration.ofSeconds(30)).stream()
            .filter(d -> d.destination().equals("FULFILMENT"))
            .findFirst()
            .orElseThrow();
    assertThatThrownBy(
            () ->
                deliveries.consumeAndAcknowledge(
                    delivery,
                    event -> {
                      internal.consume(event);
                      throw new IllegalStateException("crash before ack");
                    }))
        .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
        .hasMessageContaining("crash before ack");
    assertThat(count("fulfilments")).isZero();
    assertThat(
            jdbc.queryForObject(
                "select status from outbox_deliveries where destination='FULFILMENT'",
                String.class))
        .isEqualTo("PROCESSING");
    assertThat(deliveries.consumeAndAcknowledge(delivery, internal)).isTrue();
    assertThat(count("fulfilments")).isEqualTo(1);
  }

  @Test
  void eventSnapshotMatchesTransitionAndDoesNotChangeLater() {
    var o = create("snapshot");
    var original = jdbc.queryForObject("select payload::text from outbox_events", String.class);
    orders.cancel(o.id());
    assertThat(
            jdbc.queryForObject(
                "select payload::text from outbox_events where event_type='OrderCreated'",
                String.class))
        .isEqualTo(original);
    var cancelled =
        json.readTree(
            jdbc.queryForObject(
                "select payload::text from outbox_events where event_type='OrderCancelled'",
                String.class));
    assertThat(cancelled.get("data").get("status").asText()).isEqualTo("CANCELLED");
    assertThat(cancelled.get("aggregateVersion").asLong()).isEqualTo(orders.get(o.id()).version());
  }

  @Test
  void apiValidationProblemDetailsAndIdempotency() throws Exception {
    String body =
        "{\"customerId\":\"customer\",\"items\":[{\"productId\":\""
            + PRODUCT
            + "\",\"quantity\":2}]}";
    mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    var result =
        mvc.perform(
                post("/api/v1/orders")
                    .header("Idempotency-Key", "api")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andExpect(header().exists("X-Correlation-ID"))
            .andReturn();
    var id = json.readTree(result.getResponse().getContentAsString()).get("id").asText();
    mvc.perform(
            post("/api/v1/orders")
                .header("Idempotency-Key", "api")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("2}", "3}")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409));
    mvc.perform(
            post("/api/v1/orders")
                .header("Idempotency-Key", "invalid")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("2}", "0}")))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/v1/orders/" + id + "/pay").header("Idempotency-Key", "api-pay"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PAID"));
    mvc.perform(post("/api/v1/orders/" + id + "/cancel")).andExpect(status().isConflict());
  }

  @Test
  void administrativeAuthorizationAndOpenApi() throws Exception {
    var o = create("security");
    mvc.perform(patch("/internal/api/v1/orders/" + o.id())).andExpect(status().isUnauthorized());
    mvc.perform(patch("/internal/api/v1/orders/" + o.id()).with(user("operator").roles("USER")))
        .andExpect(status().isForbidden());
    mvc.perform(
            patch("/internal/api/v1/orders/" + o.id()).with(httpBasic("admin", "test-only-admin")))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/orders/" + o.id())).andExpect(status().isNotFound());
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/api/v1/orders/{orderId}/pay'].post").exists());
    mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
  }

  @Test
  void storedEventCannotBeMutated() {
    create("immutable");
    assertThatThrownBy(() -> jdbc.update("update outbox_events set event_type='OrderDeleted'"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThat(jdbc.queryForObject("select event_type from outbox_events", String.class))
        .isEqualTo("OrderCreated");
  }

  @Test
  void publicPaymentRejectsClientAmountAndPriceInput() throws Exception {
    var o = create("no-client-prices");
    mvc.perform(
            post("/api/v1/orders/" + o.id() + "/pay")
                .header("Idempotency-Key", "body")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":1}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/orders")
                .header("Idempotency-Key", "price")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"customerId\":\"customer\",\"items\":[{\"productId\":\""
                        + PRODUCT
                        + "\",\"quantity\":1,\"unitPrice\":1}]}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/orders")
                .header("Idempotency-Key", "fractional-quantity")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"customerId\":\"customer\",\"items\":[{\"productId\":\""
                        + PRODUCT
                        + "\",\"quantity\":1.5}]}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/orders")
                .header("Idempotency-Key", "null-item")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"customer\",\"items\":[null]}"))
        .andExpect(status().isBadRequest());
    assertThat(count("simulated_provider_operations")).isZero();
  }

  @Test
  void openApiHasSevenOperationsExamplesAndRequiredPaymentKey() throws Exception {
    var result = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
    var spec = json.readTree(result.getResponse().getContentAsString());
    assertThat(spec.get("paths").size()).isEqualTo(7);
    var pay = spec.path("paths").path("/api/v1/orders/{orderId}/pay").path("post");
    assertThat(pay.has("requestBody")).isFalse();
    boolean keyRequired = false;
    for (var parameter : pay.path("parameters"))
      if (parameter.path("name").asText().equals("Idempotency-Key"))
        keyRequired = parameter.path("required").asBoolean();
    assertThat(keyRequired).isTrue();
    assertThat(
            pay.path("responses")
                .path("200")
                .path("content")
                .path("application/json")
                .has("example"))
        .isTrue();
  }

  @Autowired com.example.orderms.catalog.application.CatalogService catalog;

  @Test
  void productDeletionCannotInvalidateAnInFlightPurchaseSnapshot() throws Exception {
    var resolved = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var deleting = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var creation =
          pool.submit(
              () ->
                  tx.execute(
                      () -> {
                        catalog.resolve(PRODUCT);
                        resolved.countDown();
                        try {
                          if (!release.await(5, TimeUnit.SECONDS))
                            throw new IllegalStateException("test release timeout");
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(e);
                        }
                        return create("catalog-delete-race");
                      }));
      assertThat(resolved.await(5, TimeUnit.SECONDS)).isTrue();
      var deletion =
          pool.submit(
              () -> {
                deleting.countDown();
                catalog.softDelete(PRODUCT);
              });
      assertThat(deleting.await(5, TimeUnit.SECONDS)).isTrue();
      try {
        assertThatThrownBy(() -> deletion.get(150, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
      } finally {
        release.countDown();
      }
      var order = creation.get(5, TimeUnit.SECONDS);
      deletion.get(5, TimeUnit.SECONDS);
      assertThat(orders.get(order.id()).items().getFirst().unitPrice())
          .isEqualByComparingTo("25.00");
      assertThatThrownBy(() -> create("after-delete")).isInstanceOf(BusinessException.class);
    }
  }

  @Autowired CatalogPort catalogPort;
  @Autowired OrderEvents orderEvents;
  @Autowired PaymentGuard paymentGuard;
  @Autowired OrderSummaries summaries;

  @Test
  void orderReadDoesNotMixPrePaymentOrderWithPostPaymentSummary() throws Exception {
    var created = create("read-snapshot");
    var loaded = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    OrderStore interleaved =
        new OrderStore() {
          public Optional<com.example.orderms.ordering.domain.Order> find(UUID id, boolean lock) {
            var result = orderStore.find(id, lock);
            loaded.countDown();
            try {
              if (!resume.await(5, TimeUnit.SECONDS))
                throw new IllegalStateException("test timeout");
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(e);
            }
            return result;
          }

          public com.example.orderms.ordering.domain.Order save(
              com.example.orderms.ordering.domain.Order order) {
            return orderStore.save(order);
          }
        };
    var reader =
        new OrderService(
            interleaved,
            catalogPort,
            idem,
            orderEvents,
            paymentGuard,
            summaries,
            tx,
            ids,
            Clock.systemUTC(),
            metrics);
    try (var pool = Executors.newSingleThreadExecutor()) {
      var reading = pool.submit(() -> reader.get(created.id()));
      assertThat(loaded.await(5, TimeUnit.SECONDS)).isTrue();
      try {
        assertThat(payments.pay(created.id(), "pay").status()).isEqualTo("PAID");
      } finally {
        resume.countDown();
      }
      var snapshot = reading.get(5, TimeUnit.SECONDS);
      assertThat(snapshot.status()).isEqualTo("CREATED");
      assertThat(snapshot.payment()).isNull();
      assertThat(orders.get(created.id()).payment().status()).isEqualTo("SUCCEEDED");
    }
  }

  private <T> List<T> parallel(Callable<T> a, Callable<T> b) throws Exception {
    var barrier = new CyclicBarrier(2);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var f1 =
          pool.submit(
              () -> {
                barrier.await(5, TimeUnit.SECONDS);
                return a.call();
              });
      var f2 =
          pool.submit(
              () -> {
                barrier.await(5, TimeUnit.SECONDS);
                return b.call();
              });
      return List.of(f1.get(15, TimeUnit.SECONDS), f2.get(15, TimeUnit.SECONDS));
    }
  }
}
