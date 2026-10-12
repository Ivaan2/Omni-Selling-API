package com.example.orderms.ordering.application.port.out;

import com.example.orderms.ordering.domain.Order;
import java.util.*;

public interface OrderStore {
  Optional<Order> find(UUID id, boolean lock);

  Order save(Order order);
}
