package com.example.orderms.ordering.application.port.out;

import com.example.orderms.ordering.application.OrderView;
import com.example.orderms.ordering.domain.Order;

public interface OrderEvents {
  void append(String type, Order order, OrderView.PaymentSummary payment);
}
