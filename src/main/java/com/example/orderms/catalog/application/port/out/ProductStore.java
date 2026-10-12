package com.example.orderms.catalog.application.port.out;

import com.example.orderms.catalog.domain.Product;
import java.util.*;

public interface ProductStore {
  List<Product> active();

  Optional<Product> find(UUID id);

  /** Hold a shared database lock until the current order transaction commits. */
  Optional<Product> findForPurchase(UUID id);

  void save(Product product);
}
