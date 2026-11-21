package me.sattle.checkout;

import me.sattle.checkout.internal.Model;

/**
 * The rate an amount in a currency was converted at (SPEC.md §3): for the audit, and to answer a
 * customer.
 *
 * @param source {@code "coingecko"}, {@code "mempool"}, {@code "opennode"}, {@code "coinbase"},
 *     {@code "kraken"} or {@code "custom"}
 * @param currency ISO 4217
 * @param price the price of one bitcoin in {@code currency}, a plain decimal in its shortest
 *     writing: {@code "69012.5"}
 * @param fetchedAt Unix seconds: when the package read it
 * @param time Unix seconds: when the source says the price was set, null when it does not say
 */
public record RateInfo(String source, String currency, String price, long fetchedAt, Long time)
    implements java.io.Serializable {
  /**
   * The JSON of SPEC.md §3.
   *
   * @return its JSON text
   */
  public String toJson() {
    return Model.write(Model.rateInfo(this));
  }
}
