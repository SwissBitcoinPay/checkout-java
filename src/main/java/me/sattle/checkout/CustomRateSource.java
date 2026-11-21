package me.sattle.checkout;

/**
 * A rate source of the integrator (SPEC.md §26): CoinGecko Pro, a self-hosted mempool, several
 * sources checked against each other… Give it with {@link RateSource#custom}.
 *
 * <p>It runs on a thread of the call, which is interrupted when the call is cancelled: a source
 * that blocks should stop then (throwing {@link InterruptedException}, or anything else: the call
 * fails with its cancellation either way).
 */
@FunctionalInterface
public interface CustomRateSource {
  /**
   * The price of one bitcoin in {@code currency}.
   *
   * @param currency ISO 4217, upper case
   * @return the price, as an exact decimal string (and when it was set, when known)
   * @throws UnsupportedCurrencyException when it has no price in that currency
   * @throws SattlemeException an error of this package goes through as it is
   * @throws Exception any other failure becomes a {@link RateException} (its cause)
   */
  RateQuote price(String currency) throws Exception;
}
