package me.sattle.checkout;

/**
 * A majority of the relays answered, and none holds the integration's channel, or only one of a
 * newer format than this version of the package reads ({@link #isNewerFormat()}: update it).
 */
public final class ChannelNotFoundException extends SattlemeException {
  private static final long serialVersionUID = 1L;

  /** Whether a channel of a newer format was found. */
  private final boolean newerFormat;

  /**
   * The channel was not found.
   *
   * @param newerFormat whether only a channel of a newer format was found
   */
  public ChannelNotFoundException(boolean newerFormat) {
    super(
        "channel_not_found",
        newerFormat
            ? "The integration's channel is of a newer format: update me.sattle:checkout"
            : "The integration's channel was not found on its relays",
        !newerFormat,
        null);
    this.newerFormat = newerFormat;
  }

  /**
   * Whether only a channel of a newer format was found: retrying does not help, updating does.
   *
   * @return true for a newer format
   */
  public boolean isNewerFormat() {
    return newerFormat;
  }
}
