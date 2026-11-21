package me.sattle.checkout;

/**
 * An invoice, a pay request or a payment answer failed the checks: another network, amount,
 * merchant, duration or date, a verify answer about another invoice, a preimage that does not
 * match. Never show such an invoice.
 */
public final class InvalidInvoiceException extends SattlemeException {
  private static final long serialVersionUID = 1L;

  /**
   * A check failed.
   *
   * @param message which
   */
  public InvalidInvoiceException(String message) {
    this(message, null);
  }

  /**
   * A check failed, for a cause.
   *
   * @param message which
   * @param cause the failure, or null
   */
  public InvalidInvoiceException(String message, Throwable cause) {
    super("invalid_invoice", message, false, cause);
  }
}
