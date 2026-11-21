package me.sattle.checkout;

/**
 * Not an integration's secret key ({@code sattleme_ck_…}): another prefix, a damaged key, an old
 * link holding its parts. The message never holds the key.
 */
public final class InvalidKeyException extends SattlemeException {
  private static final long serialVersionUID = 1L;

  /** Not a sattle.me integration key. */
  public InvalidKeyException() {
    this("Not a sattle.me integration key");
  }

  /**
   * Not a sattle.me integration key, saying why.
   *
   * @param message why, never the key
   */
  public InvalidKeyException(String message) {
    super("invalid_key", message, false, null);
  }
}
