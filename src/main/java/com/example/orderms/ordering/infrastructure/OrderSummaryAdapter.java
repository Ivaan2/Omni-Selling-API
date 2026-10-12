package com.example.orderms.ordering.infrastructure;

import com.example.orderms.fulfilment.application.port.out.FulfilmentStore;
import com.example.orderms.ordering.application.*;
import com.example.orderms.ordering.application.port.out.OrderSummaries;
import com.example.orderms.payment.application.port.out.PaymentStore;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class OrderSummaryAdapter implements OrderSummaries {
  private final PaymentStore payments;
  private final FulfilmentStore fulfilments;

  public OrderSummaryAdapter(PaymentStore payments, FulfilmentStore fulfilments) {
    this.payments = payments;
    this.fulfilments = fulfilments;
  }

  public OrderView.PaymentSummary payment(UUID id) {
    return payments
        .findByOrder(id)
        .map(
            p ->
                new OrderView.PaymentSummary(
                    p.id(), p.status().name(), p.amount().amount(), p.amount().currency()))
        .orElse(null);
  }

  public OrderView.FulfilmentSummary fulfilment(UUID id) {
    return fulfilments
        .findByOrder(id)
        .map(f -> new OrderView.FulfilmentSummary(f.id(), f.status().name()))
        .orElse(null);
  }
}
