package com.example.orderms.ordering.application;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

public final class RequestHash {
  private RequestHash() {}

  public static String of(String canonical) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String field(String value) {
    return value.length() + ":" + value;
  }
}
