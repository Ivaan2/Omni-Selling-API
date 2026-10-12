package com.example.orderms.catalog.application;

import com.example.orderms.catalog.application.port.out.ProductStore;
import com.example.orderms.catalog.domain.Product;
import com.example.orderms.shared.application.TransactionBoundary;
import com.example.orderms.shared.domain.BusinessException;
import java.time.Clock;
import java.util.*;

public final class CatalogService {
  private final ProductStore store;
  private final TransactionBoundary tx;
  private final Clock clock;

  public CatalogService(ProductStore store, TransactionBoundary tx, Clock clock) {
    this.store = store;
    this.tx = tx;
    this.clock = clock;
  }

  public List<Product> list() {
    return tx.execute(store::active);
  }

  public Product resolve(UUID id) {
    var product =
        store.findForPurchase(id).orElseThrow(() -> BusinessException.missing("Product not found"));
    product.requirePurchasable();
    return product;
  }

  public void softDelete(UUID id) {
    tx.execute(
        () -> {
          var product =
              store.find(id).orElseThrow(() -> BusinessException.missing("Product not found"));
          if (product.softDelete(clock.instant())) store.save(product);
          return null;
        });
  }
}
