package me.sattle.checkout;

/**
 * No usable price: a source answered something that is not a price, a price too old or too far
 * ahead, or a custom source failed ({@link #getCause()}). Retry later, or with another source.
 */
public final class RateException extends SattlemeException {
  private static final long serialVersionUID = 1L;

  /**
   * No usable price.
   *
   * @param message why
   */
  public RateException(String message) {
    this(message, null);
  }

  /**
   * No usable price, for a cause.
   *
   * @param message why
   * @param cause the failure, or null
   */
  public RateException(String message, Throwable cause) {
    super("rate", message, true, cause);
  }
}
