package com.example.orderms.integration.infrastructure.publisher;

import com.example.orderms.config.AppProperties;
import com.example.orderms.integration.application.port.out.EventPublisherPort;
import java.net.URI;
import java.net.http.*;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class HttpEventPublisher implements EventPublisherPort {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(HttpEventPublisher.class);
  private final tools.jackson.databind.json.JsonMapper json;
  private final HttpClient client;
  private final AppProperties config;

  public HttpEventPublisher(AppProperties config, tools.jackson.databind.json.JsonMapper json) {
    this.config = config;
    this.json = json;
    if (!config.outbox().receiverUrl().isBlank()) {
      var uri = URI.create(config.outbox().receiverUrl());
      if ((!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme()))
          || uri.getHost() == null
          || uri.getUserInfo() != null)
        throw new IllegalArgumentException(
            "Receiver must be an HTTP(S) URL without embedded credentials");
    }
    client =
        HttpClient.newBuilder()
            .connectTimeout(config.outbox().timeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  public void publish(UUID eventId, String payload) {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("HTTP delivery must be outside transaction");
    if (config.outbox().receiverUrl().isBlank())
      throw new IllegalStateException("Receiver is disabled");
    var correlation = json.readTree(payload).path("data").path("correlationId").asString("");
    var request =
        HttpRequest.newBuilder(URI.create(config.outbox().receiverUrl()))
            .timeout(config.outbox().timeout())
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", eventId.toString())
            .header("X-Correlation-ID", correlation.isBlank() ? eventId.toString() : correlation)
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();
    try {
      var response = client.send(request, HttpResponse.BodyHandlers.discarding());
      if (response.statusCode() < 200 || response.statusCode() >= 300)
        throw new IllegalStateException("Receiver did not acknowledge event");
      log.info("Event {} acknowledged; correlation {}", eventId, correlation);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Delivery interrupted", e);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Delivery transport failed", e);
    }
  }
}
