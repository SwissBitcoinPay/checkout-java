package me.sattle.checkout;

/** None of the rate sources has a price in the currency ({@link #getCurrency()}). */
public final class UnsupportedCurrencyException extends SattlemeException {
  private static final long serialVersionUID = 1L;

  /** The currency without a price. */
  private final String currency;

  /**
   * The source {@code source} has no price in {@code currency}: what a custom rate source throws.
   *
   * @param currency the currency (ISO 4217)
   * @param source the source's name, for the message
   */
  public UnsupportedCurrencyException(String currency, String source) {
    this(currency, source, source + " has no price in " + currency);
  }

  /**
   * No price in {@code currency}, with a message.
   *
   * @param currency the currency (ISO 4217)
   * @param source the source's name
   * @param message the message
   */
  public UnsupportedCurrencyException(String currency, String source, String message) {
    super("unsupported_currency", message, false, null);
    this.currency = currency;
  }

  /**
   * The currency.
   *
   * @return its ISO 4217 code
   */
  public String getCurrency() {
    return currency;
  }
}
