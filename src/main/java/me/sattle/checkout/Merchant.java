package me.sattle.checkout;

import me.sattle.checkout.internal.Model;

/**
 * The merchant, as the integration's channel, Lightning address and lot give it (SPEC.md §3). Never
 * the merchant's Lightning address: the app never shows it to the integrator.
 *
 * @param businessName the business's name
 * @param minSats the smallest invoice the Lightning address makes, in sats (a string of digits)
 * @param maxSats the largest, in sats (a string of digits)
 * @param onchainAddresses the deposit addresses of the integration's lot, leased or not: 0 takes no
 *     on-chain payment
 */
public record Merchant(String businessName, String minSats, String maxSats, int onchainAddresses)
    implements java.io.Serializable {
  /**
   * The JSON of SPEC.md §3.
   *
   * @return its JSON text
   */
  public String toJson() {
    return Model.write(Model.merchant(this));
  }
}
