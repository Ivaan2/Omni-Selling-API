package com.example.orderms.shared.infrastructure.web;

public final class IdempotencyHeader {
  private IdempotencyHeader() {}

  public static void validate(String key) {
    if (key == null || key.isBlank() || key.length() > 200)
      throw new IllegalArgumentException("Idempotency-Key must contain 1 to 200 characters");
  }
}
