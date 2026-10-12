package com.example.orderms.catalog.infrastructure.web;

import com.example.orderms.catalog.application.CatalogService;
import io.swagger.v3.oas.annotations.Operation;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class CatalogController {
  private final CatalogService catalog;

  public CatalogController(CatalogService catalog) {
    this.catalog = catalog;
  }

  public record ProductResponse(
      UUID id,
      String name,
      String description,
      BigDecimal price,
      String currency,
      boolean available) {}

  public record CatalogResponse(List<ProductResponse> products) {}

  @Operation(
      summary = "List non-deleted catalog products",
      description = "Unavailable products are visible but cannot be purchased.")
  @GetMapping("/api/v1/products")
  public CatalogResponse list() {
    return new CatalogResponse(
        catalog.list().stream()
            .map(
                p ->
                    new ProductResponse(
                        p.id(),
                        p.name(),
                        p.description(),
                        p.price().amount(),
                        p.price().currency(),
                        p.available()))
            .toList());
  }

  @Operation(
      summary = "Soft-delete product",
      security = @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "adminBasic"))
  @PatchMapping("/internal/api/v1/products/{productId}")
  public org.springframework.http.ResponseEntity<Void> delete(@PathVariable UUID productId) {
    catalog.softDelete(productId);
    return org.springframework.http.ResponseEntity.ok().build();
  }
}
