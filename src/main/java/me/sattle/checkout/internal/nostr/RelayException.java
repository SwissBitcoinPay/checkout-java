package me.sattle.checkout.internal.nostr;

/** A relay's failure: no connection, no answer in time, a refusal. Never a secret in it. */
public final class RelayException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** The reason a relay refused an event ({@code OK} false), or null. */
  private final String reason;

  /**
   * A failure.
   *
   * @param message what failed
   */
  public RelayException(String message) {
    this(message, null);
  }

  /**
   * A refusal.
   *
   * @param message what failed
   * @param reason the relay's reason
   */
  public RelayException(String message, String reason) {
    super(message, null, false, false);
    this.reason = reason;
  }

  /**
   * The relay's reason, for a refusal.
   *
   * @return it, or null
   */
  public String reason() {
    return reason;
  }
}
