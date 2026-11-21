package me.sattle.checkout;

/**
 * The order was already paid, with {@link #getInvoice()}: {@link #getStatus()} is its paid status,
 * {@link #getPreimage()} the proof of a payment of its own Lightning invoice (null when it was paid
 * otherwise: on-chain, or mixed). Never charge it again.
 */
public final class OrderPaidException extends SattlemeException {
  private static final long serialVersionUID = 1L;

  /** The invoice that was paid. */
  private final DeclaredInvoice invoice;

  /** Its paid status. */
  private final PaymentStatus.Paid status;

  /**
   * The order was paid.
   *
   * @param invoice the invoice that was paid
   * @param status its paid status
   */
  public OrderPaidException(DeclaredInvoice invoice, PaymentStatus.Paid status) {
    super("order_paid", "This order was already paid", false, null);
    this.invoice = invoice;
    this.status = status;
  }

  /**
   * The invoice that was paid.
   *
   * @return the registry's copy of it
   */
  public DeclaredInvoice getInvoice() {
    return invoice;
  }

  /**
   * Its paid status.
   *
   * @return paid in Lightning, on-chain or mixed
   */
  public PaymentStatus.Paid getStatus() {
    return status;
  }

  /**
   * The preimage of its Lightning invoice, the proof of payment.
   *
   * @return the preimage (lowercase hex), or null when it was paid otherwise than by its own
   *     Lightning invoice
   */
  public String getPreimage() {
    return status instanceof PaymentStatus.PaidLightning
        ? ((PaymentStatus.PaidLightning) status).preimage()
        : null;
  }
}
