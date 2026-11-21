package me.sattle.checkout;

import java.math.BigInteger;
import me.sattle.checkout.internal.Model;
import me.sattle.checkout.internal.Prices;

/**
 * A price in a currency (SPEC.md §3): {@code minor} units with {@code digits} decimals ({@code
 * "2450"}, 2, {@code "CHF"} is 24.50 CHF).
 *
 * @param amount the price as written, with exactly the currency's decimals: {@code "24.50"}
 * @param currency ISO 4217, upper case
 * @param minor the price in the currency's minor units, a string of digits: {@code "2450"}
 * @param digits the currency's decimals: 2
 */
public record Fiat(String amount, String currency, String minor, int digits)
    implements java.io.Serializable {
  /**
   * A price in a currency, checked (SPEC.md §25): a currency of {@link Currencies} (upper case), an
   * amount of digits with at most the currency's decimals (trailing zeros aside), nothing around
   * it; never a float, never rounded.
   *
   * @param amount the amount: {@code "24.50"}
   * @param currency the currency: {@code "CHF"}
   * @return the price
   * @throws InvalidOrderException ({@code fiat}) when it is not one
   */
  public static Fiat of(String amount, String currency) {
    return Prices.parseFiat(amount, currency);
  }

  /**
   * The sats of this price at {@code price}, the price of one bitcoin in its currency as an exact
   * decimal string, rounded up to the next whole sat (SPEC.md §25): 24.50 CHF at {@code "69012"} is
   * 35 502 sats (the exact quotient is 35 501.07…).
   *
   * @param price the price of one bitcoin: {@code "69012.5"}, {@code "8e-8"}
   * @return the sats
   * @throws InvalidOrderException ({@code fiat}) when this is no price: {@code minor} not 1 to 16
   *     digits of 1 to 2^53 − 1 minor units, or {@code digits} not 0 to 8 (a Fiat made by hand, or
   *     read from a stored invoice, which no rule checks there)
   * @throws RateException when {@code price} is not a decimal above 0
   */
  public BigInteger toSats(String price) {
    BigInteger units = Prices.minorUnitsOf(minor, digits);
    Prices.Decimal decimal = Prices.parseDecimal(price);
    if (decimal == null) throw new RateException("A price must be a decimal string above 0");
    return Prices.minorToSats(units, digits, decimal);
  }

  /**
   * The JSON of SPEC.md §3: {@code {"amount": "24.50", "currency": "CHF", "minor": "2450",
   * "digits": 2}}.
   *
   * @return its JSON text
   */
  public String toJson() {
    return Model.write(Model.fiat(this));
  }
}
