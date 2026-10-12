package com.example.orderms.config;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.*;

@Configuration
public class OpenApiConfiguration {
  @Bean
  OpenAPI api() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Order Management API")
                .version("1.0")
                .description(
                    "Demo customer IDs are unverified. Request idempotency defaults to 24h;"
                        + " permanent payment constraints survive expiry. External delivery is at"
                        + " least once."))
        .components(
            new Components()
                .addSecuritySchemes(
                    "adminBasic",
                    new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")));
  }

  @Bean
  OpenApiCustomizer errorResponses() {
    return api ->
        api.getPaths()
            .values()
            .forEach(
                path ->
                    path.readOperations()
                        .forEach(
                            op -> {
                              for (int status :
                                  new int[] {400, 401, 403, 404, 409, 422, 500, 503}) {
                                if ((status == 401 || status == 403) && op.getSecurity() == null)
                                  continue;
                                op.getResponses()
                                    .addApiResponse(
                                        Integer.toString(status),
                                        new ApiResponse()
                                            .description(
                                                switch (status) {
                                                  case 400 ->
                                                      "Malformed request, validation or"
                                                          + " missing/invalid idempotency header";
                                                  case 401 ->
                                                      "Administrative authentication required";
                                                  case 403 -> "Administrative authority required";
                                                  case 404 -> "Resource missing or order deleted";
                                                  case 409 ->
                                                      "State, concurrency or idempotency conflict;"
                                                          + " processing requests may retry with"
                                                          + " the same key";
                                                  case 422 ->
                                                      "Commercial or domain invariant violation";
                                                  case 503 -> "Persistence temporarily unavailable";
                                                  default -> "Unexpected server error";
                                                })
                                            .content(
                                                new Content()
                                                    .addMediaType(
                                                        "application/problem+json",
                                                        new MediaType()
                                                            .schema(new Schema<>().type("object"))
                                                            .example(
                                                                java.util.Map.of(
                                                                    "type",
                                                                    "about:blank",
                                                                    "status",
                                                                    status,
                                                                    "title",
                                                                    "Request failed",
                                                                    "detail",
                                                                    "See documented error"
                                                                        + " mapping")))));
                              }
                            }));
  }

  @Bean
  OpenApiCustomizer examples(tools.jackson.databind.json.JsonMapper json) {
    return api -> {
      var orderExample =
          json.readValue(
              """
              {"id":"01950000-0000-7000-8000-000000000001","customerId":"customer-123","status":"CREATED","items":[{"productId":"00000000-0000-4000-8000-000000000001","productName":"Wireless Mouse","quantity":2,"unitPrice":25.00,"currency":"EUR"}],"totalAmount":50.00,"currency":"EUR","createdAt":"2026-10-12T16:30:00Z","version":0,"payment":null,"fulfilment":null}
              """,
              java.util.Map.class);
      api.getPaths()
          .forEach(
              (path, item) ->
                  item.readOperations()
                      .forEach(
                          op -> {
                            op.getResponses()
                                .forEach(
                                    (status, response) -> {
                                      if (status.startsWith("2") && response.getContent() != null) {
                                        var media =
                                            response.getContent().values().iterator().next();
                                        Object example = orderExample;
                                        if (path.equals("/api/v1/products"))
                                          example =
                                              java.util.Map.of(
                                                  "products",
                                                  java.util.List.of(
                                                      java.util.Map.of(
                                                          "id",
                                                          "00000000-0000-4000-8000-000000000001",
                                                          "name",
                                                          "Wireless Mouse",
                                                          "description",
                                                          "Compact wireless mouse",
                                                          "price",
                                                          new java.math.BigDecimal("25.00"),
                                                          "currency",
                                                          "EUR",
                                                          "available",
                                                          true)));
                                        else if (path.endsWith("/pay")) {
                                          var paid = new java.util.LinkedHashMap<>(orderExample);
                                          paid.put("status", "PAID");
                                          paid.put("version", 2);
                                          paid.put(
                                              "payment",
                                              java.util.Map.of(
                                                  "id",
                                                  "01950000-0000-7000-8000-000000000002",
                                                  "status",
                                                  "SUCCEEDED",
                                                  "amount",
                                                  new java.math.BigDecimal("50.00"),
                                                  "currency",
                                                  "EUR"));
                                          example = paid;
                                        } else if (path.endsWith("/cancel")) {
                                          var cancelled =
                                              new java.util.LinkedHashMap<>(orderExample);
                                          cancelled.put("status", "CANCELLED");
                                          cancelled.put("version", 1);
                                          example = cancelled;
                                        }
                                        media.setExample(example);
                                        response.setContent(
                                            new Content().addMediaType("application/json", media));
                                      }
                                    });
                            if (path.startsWith("/internal/"))
                              op.getResponses()
                                  .get("200")
                                  .setDescription(
                                      "Soft deletion complete, or already deleted; empty response"
                                          + " body");
                            if (path.endsWith("/pay")) op.setRequestBody(null);
                          }));
      var create = api.getPaths().get("/api/v1/orders").getPost();
      create
          .getRequestBody()
          .getContent()
          .values()
          .forEach(
              media ->
                  media.setExample(
                      java.util.Map.of(
                          "customerId",
                          "customer-123",
                          "items",
                          java.util.List.of(
                              java.util.Map.of(
                                  "productId",
                                  "00000000-0000-4000-8000-000000000001",
                                  "quantity",
                                  2)))));
      create
          .getResponses()
          .get("201")
          .addHeaderObject(
              "Location",
              new io.swagger.v3.oas.models.headers.Header()
                  .description("Relative URI of the order")
                  .schema(new Schema<>().type("string"))
                  .example("/api/v1/orders/01950000-0000-7000-8000-000000000001"));
    };
  }
}
