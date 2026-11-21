package me.sattle.checkout;

import java.util.function.Consumer;

/**
 * How {@link SattlemeClient#waitForPayment} waits (SPEC.md §23): between two checks, and who hears
 * each status. Nothing is checked until the call ({@link InvalidOptionException}).
 */
public final class WaitOptions {
  private final Long intervalMs;
  private final Consumer<PaymentStatus> onStatus;

  private WaitOptions(Builder builder) {
    this.intervalMs = builder.intervalMs;
    this.onStatus = builder.onStatus;
  }

  /**
   * The defaults: a check every 2 s, no listener.
   *
   * @return the options
   */
  public static WaitOptions defaults() {
    return builder().build();
  }

  /**
   * New options.
   *
   * @return their builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Between two checks, in milliseconds.
   *
   * @return the interval as given, or null for the default (2000)
   */
  public Long intervalMs() {
    return intervalMs;
  }

  /**
   * Who hears each status.
   *
   * @return the listener, or null
   */
  public Consumer<PaymentStatus> onStatus() {
    return onStatus;
  }

  @Override
  public String toString() {
    return "WaitOptions[intervalMs=" + intervalMs + "]";
  }

  /** Builds {@link WaitOptions}. */
  public static final class Builder {
    private Long intervalMs;
    private Consumer<PaymentStatus> onStatus;

    private Builder() {}

    /**
     * Between two checks of the Lightning part: 250 to 60 000 ms, 2000 when not given.
     *
     * @param intervalMs the interval
     * @return this builder
     */
    public Builder intervalMs(long intervalMs) {
      this.intervalMs = intervalMs;
      return this;
    }

    /**
     * Hears each status, on the thread that waits. What it throws ends the wait.
     *
     * @param onStatus the listener
     * @return this builder
     */
    public Builder onStatus(Consumer<PaymentStatus> onStatus) {
      this.onStatus = onStatus;
      return this;
    }

    /**
     * The options.
     *
     * @return the options, unchecked
     */
    public WaitOptions build() {
      return new WaitOptions(this);
    }
  }
}
