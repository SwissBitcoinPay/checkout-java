package me.sattle.checkout;

/**
 * A parameter of the order, or a stored invoice, is invalid (found before any request): {@link
 * #getParam()} names it.
 */
public final class InvalidOrderException extends SattlemeException {
  private static final long serialVersionUID = 1L;

  /** The parameter: {@code "ref"}, {@code "sats"}, {@code "fiat"}… */
  private final String param;

  /**
   * An invalid parameter of an order, or an invalid stored invoice.
   *
   * @param param the parameter's name
   * @param message what it must be
   */
  public InvalidOrderException(String param, String message) {
    super("invalid_order", param + ": " + message, false, null);
    this.param = param;
  }

  /**
   * The invalid parameter.
   *
   * @return {@code "order"}, {@code "ref"}, {@code "sats"}, {@code "fiat"}, {@code "rateSource"},
   *     {@code "durationSeconds"}, {@code "note"}, {@code "checkout"}, {@code "redirectUrl"} or
   *     {@code "invoice"}
   */
  public String getParam() {
    return param;
  }
}
