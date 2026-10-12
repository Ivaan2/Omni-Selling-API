package com.example.orderms.fulfilment.application;

import com.example.orderms.fulfilment.application.port.out.FulfilmentStore;
import com.example.orderms.fulfilment.domain.Fulfilment;
import com.example.orderms.shared.application.*;
import java.time.Clock;
import java.util.UUID;

public final class FulfilmentService {
  private final FulfilmentStore store;
  private final IdGenerator ids;
  private final Clock clock;

  public FulfilmentService(FulfilmentStore store, IdGenerator ids, Clock clock) {
    this.store = store;
    this.ids = ids;
    this.clock = clock;
  }

  public void initialize(UUID orderId) {
    if (store.findByOrder(orderId).isEmpty())
      store.createIfAbsent(Fulfilment.pending(ids.next(), orderId, clock.instant()));
  }
}
