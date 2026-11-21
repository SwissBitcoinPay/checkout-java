package me.sattle.checkout;

/**
 * An option of the client or of a call is invalid (a programming error, found before any request):
 * {@link #getParam()} names it.
 */
public final class InvalidOptionException extends SattlemeException {
  private static final long serialVersionUID = 1L;

  /** The option: {@code "rateSource"}, {@code "relayTimeout"}, {@code "interval"}… */
  private final String param;

  /**
   * An invalid option.
   *
   * @param param the option's name
   * @param message what it must be
   */
  public InvalidOptionException(String param, String message) {
    super("invalid_option", param + ": " + message, false, null);
    this.param = param;
  }

  /**
   * The invalid option.
   *
   * @return its name: {@code "options"}, {@code "rateSource"}, {@code "coingeckoApiKey"}, {@code
   *     "httpClient"}, {@code "webSocketFactory"}, {@code "relayTimeout"}, {@code "httpTimeout"},
   *     {@code "logger"}, {@code "clock"}, {@code "random"} or {@code "interval"}
   */
  public String getParam() {
    return param;
  }
}
