package com.example.orderms.ordering.application.port.out;

import com.example.orderms.shared.domain.Money;
import java.util.UUID;

public interface CatalogPort {
  record ProductSnapshot(UUID id, String name, Money price) {}

  ProductSnapshot resolve(UUID id);
}
