package me.sattle.checkout;

/**
 * A relay, the Lightning address's server, a rate source or the explorer (mempool.space, or the
 * merchant's Bitcoin node with its fallback) did not answer usably (in time), or fewer than a
 * majority of the relays answered or took a record. Retry later.
 */
public final class NetworkException extends SattlemeException {
  private static final long serialVersionUID = 1L;

  /**
   * No usable answer.
   *
   * @param message what did not answer
   */
  public NetworkException(String message) {
    this(message, null);
  }

  /**
   * No usable answer, for a cause.
   *
   * @param message what did not answer
   * @param cause the failure, or null
   */
  public NetworkException(String message, Throwable cause) {
    super("network", message, true, cause);
  }
}
