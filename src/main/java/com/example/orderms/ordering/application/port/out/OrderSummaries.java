package com.example.orderms.ordering.application.port.out;

import com.example.orderms.ordering.application.OrderView;
import java.util.UUID;

public interface OrderSummaries {
  OrderView.PaymentSummary payment(UUID orderId);

  OrderView.FulfilmentSummary fulfilment(UUID orderId);
}
