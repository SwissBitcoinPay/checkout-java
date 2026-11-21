package me.sattle.checkout.internal.json;

/** Text that is not JSON (RFC 8259). */
public final class JsonException extends Exception {
  private static final long serialVersionUID = 1L;

  /**
   * Not JSON.
   *
   * @param message where
   */
  public JsonException(String message) {
    super(message);
  }
}
