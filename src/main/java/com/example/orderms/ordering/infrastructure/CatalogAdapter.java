package com.example.orderms.ordering.infrastructure;

import com.example.orderms.catalog.application.CatalogService;
import com.example.orderms.ordering.application.port.out.CatalogPort;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class CatalogAdapter implements CatalogPort {
  private final CatalogService catalog;

  public CatalogAdapter(CatalogService catalog) {
    this.catalog = catalog;
  }

  public ProductSnapshot resolve(UUID id) {
    var p = catalog.resolve(id);
    return new ProductSnapshot(p.id(), p.name(), p.price());
  }
}
