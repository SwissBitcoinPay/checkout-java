package me.sattle.checkout;

/**
 * The order still has an invoice that may be paid (not expired, in the 120 s after its end, or
 * being confirmed on-chain): show that one ({@link #getInvoice()}) instead of making another. One
 * order, one invoice at a time. When the order asked for a checkout link and that invoice may still
 * be paid, {@link #getCheckoutUrl()} is a new link to it, else null.
 */
public final class InvoicePendingException extends SattlemeException {
  private static final long serialVersionUID = 1L;

  /** The pending invoice, as the merchant's registry holds it. */
  private final DeclaredInvoice invoice;

  /** A new checkout link to it, or null. */
  private final String checkoutUrl;

  /**
   * An invoice of the order may still be paid.
   *
   * @param invoice that invoice
   * @param checkoutUrl a new checkout link to it, or null
   */
  public InvoicePendingException(DeclaredInvoice invoice, String checkoutUrl) {
    super("invoice_pending", "This order still has an invoice that may be paid", false, null);
    this.invoice = invoice;
    this.checkoutUrl = checkoutUrl;
  }

  /**
   * The pending invoice: show it.
   *
   * @return the registry's copy of it
   */
  public DeclaredInvoice getInvoice() {
    return invoice;
  }

  /**
   * A new checkout link to the pending invoice, when the order asked for one and that invoice may
   * still be paid.
   *
   * @return its URL, or null
   */
  public String getCheckoutUrl() {
    return checkoutUrl;
  }
}
