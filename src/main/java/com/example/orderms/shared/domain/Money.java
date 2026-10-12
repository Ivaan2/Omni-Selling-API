package com.example.orderms.shared.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public record Money(BigDecimal amount, String currency) {
  public Money {
    Objects.requireNonNull(amount, "amount");
    if (!"EUR".equals(currency)) throw BusinessException.invalid("Only EUR is supported");
    try {
      amount = amount.setScale(2, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException e) {
      throw BusinessException.invalid("Money must have at most two decimal places");
    }
    if (amount.signum() < 0 || amount.precision() > 19)
      throw BusinessException.invalid("Invalid monetary amount");
  }

  public Money add(Money other) {
    if (!currency.equals(other.currency)) throw BusinessException.invalid("Currency mismatch");
    return new Money(amount.add(other.amount), currency);
  }

  public Money multiply(int quantity) {
    if (quantity <= 0) throw BusinessException.invalid("Quantity must be positive");
    return new Money(amount.multiply(BigDecimal.valueOf(quantity)), currency);
  }
}
