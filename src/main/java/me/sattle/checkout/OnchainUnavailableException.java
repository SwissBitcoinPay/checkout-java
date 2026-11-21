package me.sattle.checkout;

/**
 * The order can only be paid on-chain (its amount is outside the Lightning address's bounds), and
 * no deposit address of the integration's lot could be leased. Retry later: the merchant's app
 * fills the lot again whenever it is opened.
 */
public final class OnchainUnavailableException extends SattlemeException {
  private static final long serialVersionUID = 1L;

  /** No address could be leased. */
  public OnchainUnavailableException() {
    super(
        "onchain_unavailable",
        "No deposit address of the integration's lot could be leased: try again later",
        true,
        null);
  }
}
