package com.example.orderms.ordering.infrastructure.web;

import com.example.orderms.ordering.application.*;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class OrderController {
  private final OrderService orders;

  public OrderController(OrderService orders) {
    this.orders = orders;
  }

  public record ItemRequest(@NotNull UUID productId, @Positive @Max(1000000) int quantity) {}

  public record CreateRequest(
      @NotBlank @Size(max = 128) @Schema(example = "customer-123") String customerId,
      @NotEmpty @Size(max = 100) List<@NotNull @Valid ItemRequest> items) {}

  @Operation(
      summary = "Create an order",
      description =
          "Mandatory idempotency. Item order does not affect canonical hashing. Duplicate product"
              + " IDs are rejected.")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "201",
      description = "Created, including idempotent replay; Location identifies the order")
  @PostMapping("/api/v1/orders")
  public ResponseEntity<OrderView> create(
      @Parameter(description = "Client-generated key, 1–200 characters", required = true)
          @RequestHeader("Idempotency-Key")
          String key,
      @Valid @RequestBody CreateRequest request) {
    com.example.orderms.shared.infrastructure.web.IdempotencyHeader.validate(key);
    var command =
        new CreateOrderCommand(
            request.customerId(),
            request.items().stream()
                .map(i -> new CreateOrderCommand.Line(i.productId(), i.quantity()))
                .toList(),
            key);
    var view = orders.create(command);
    return ResponseEntity.created(URI.create("/api/v1/orders/" + view.id())).body(view);
  }

  @Operation(
      summary = "Retrieve a visible order",
      description = "Payment and eventual fulfilment summaries are included when present.")
  @GetMapping("/api/v1/orders/{orderId}")
  public OrderView get(@PathVariable UUID orderId) {
    return orders.get(orderId);
  }

  @Operation(
      summary = "Cancel an unpaid order",
      description = "Only CREATED orders without pending or unknown payment attempts are eligible.")
  @PostMapping("/api/v1/orders/{orderId}/cancel")
  public OrderView cancel(@PathVariable UUID orderId) {
    return orders.cancel(orderId);
  }

  @Operation(
      summary = "Soft-delete an eligible order",
      description = "CREATED and CANCELLED only; repeated deletion is idempotent.",
      security = @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "adminBasic"))
  @PatchMapping("/internal/api/v1/orders/{orderId}")
  public ResponseEntity<Void> delete(@PathVariable UUID orderId) {
    orders.softDelete(orderId);
    return ResponseEntity.ok().build();
  }
}
