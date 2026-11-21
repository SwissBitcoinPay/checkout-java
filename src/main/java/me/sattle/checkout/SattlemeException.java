package me.sattle.checkout;

/**
 * What every error of this package is (SPEC.md §5): a stable {@link #getCode() code} for programs,
 * and whether the same call may succeed later, unchanged ({@link #isRetryable()}). Every error a
 * call raises is one of its subclasses, but for a cancelled call, which ends with the platform's
 * cancellation ({@link InterruptedException}, or a {@link
 * java.util.concurrent.CancellationException} of the future of an asynchronous call).
 *
 * <p>A message never holds a secret: neither the integration's key, nor a sale's key, nor any key
 * derived from them.
 */
public class SattlemeException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** The stable code: {@code "invalid_key"}, {@code "network"}… */
  private final String code;

  /** Whether the same call may succeed later, unchanged. */
  private final boolean retryable;

  /**
   * An error of this package.
   *
   * @param code the stable code of SPEC.md §5
   * @param message what failed, without a secret
   * @param retryable whether the same call may succeed later
   * @param cause what caused it, or null
   */
  protected SattlemeException(String code, String message, boolean retryable, Throwable cause) {
    super(message, cause);
    this.code = code;
    this.retryable = retryable;
  }

  /**
   * The stable code of the error (SPEC.md §5), for programs.
   *
   * @return {@code "invalid_key"}, {@code "invalid_option"}, {@code "invalid_order"}, {@code
   *     "revoked"}, {@code "channel_not_found"}, {@code "network"}, {@code "invalid_invoice"},
   *     {@code "invoice_pending"}, {@code "order_paid"}, {@code "unsupported_currency"}, {@code
   *     "rate"}, {@code "checkout_unavailable"} or {@code "onchain_unavailable"}
   */
  public final String getCode() {
    return code;
  }

  /**
   * Whether the same call may succeed later, unchanged (a relay or a service down).
   *
   * @return true when retrying may help
   */
  public final boolean isRetryable() {
    return retryable;
  }
}
