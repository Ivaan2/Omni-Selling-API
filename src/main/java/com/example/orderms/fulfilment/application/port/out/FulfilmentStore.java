package com.example.orderms.fulfilment.application.port.out;

import com.example.orderms.fulfilment.domain.Fulfilment;
import java.util.*;

public interface FulfilmentStore {
  Optional<Fulfilment> findByOrder(UUID orderId);

  void createIfAbsent(Fulfilment fulfilment);
}
