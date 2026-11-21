package me.sattle.checkout;

/**
 * A checkout link was asked, and the merchant's app gives no web app for it (its channel names
 * none). Make the invoice without {@code checkout}, and show it on the shop's page.
 */
public final class CheckoutUnavailableException extends SattlemeException {
  private static final long serialVersionUID = 1L;

  /** No web app for checkout links. */
  public CheckoutUnavailableException() {
    super(
        "checkout_unavailable",
        "The merchant's app gives no web app for checkout links: show the invoice on the shop's"
            + " page",
        false,
        null);
  }
}
