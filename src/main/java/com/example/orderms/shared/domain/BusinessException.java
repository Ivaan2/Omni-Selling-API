package com.example.orderms.shared.domain;

public final class BusinessException extends RuntimeException {
  public enum Kind {
    INVALID,
    NOT_FOUND,
    CONFLICT
  }

  private final Kind kind;

  public BusinessException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }

  public static BusinessException conflict(String message) {
    return new BusinessException(Kind.CONFLICT, message);
  }

  public static BusinessException invalid(String message) {
    return new BusinessException(Kind.INVALID, message);
  }

  public static BusinessException missing(String message) {
    return new BusinessException(Kind.NOT_FOUND, message);
  }
}
