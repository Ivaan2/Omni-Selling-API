package com.example.orderms.shared.application;

import java.util.function.Supplier;

public interface TransactionBoundary {
  <T> T execute(Supplier<T> work);

  /** Consistent read snapshot; adapters may use a dedicated read-only transaction. */
  default <T> T read(Supplier<T> work) {
    return execute(work);
  }
}
