package me.sattle.checkout.internal;

import java.math.BigInteger;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.sattle.checkout.Currencies;
import me.sattle.checkout.Fiat;
import me.sattle.checkout.InvalidOrderException;
import me.sattle.checkout.internal.json.EcmaNumbers;

/**
 * Amounts in a currency, converted to sats at the rate of the source the integrator chooses
 * (SPEC.md §25). Amounts are exact integers end to end: the price of one bitcoin is read as the
 * exact decimal its source wrote, and the sats are rounded up to the next whole sat, as the
 * merchant's point of sale does: the merchant never receives less than the price.
 */
public final class Prices {
  private Prices() {}

  private static final BigInteger SATS_PER_BTC = BigInteger.valueOf(Constants.SATS_PER_BTC);

  /**
   * An exact positive decimal: {@code units} × 10^-{@code scale}, in its shortest writing.
   *
   * @param units its digits
   * @param scale its decimals
   */
  public record Decimal(BigInteger units, int scale) {}

  private static final Pattern DECIMAL =
      Pattern.compile("([0-9]+)(?:\\.([0-9]+))?(?:[eE]([+-]?[0-9]+))?");

  private static final Pattern AMOUNT = Pattern.compile("([0-9]{1,15})(?:\\.([0-9]{1,15}))?");

  /**
   * A price as an exact decimal (SPEC.md §25): digits, an optional fraction and an optional
   * exponent ({@code "69025.1084416666694295"}, {@code "8e-8"}), 64 characters at most, an exponent
   * of 64 at most either way, nothing around it.
   *
   * @param value the price as a source wrote it, or null
   * @return its value, or null unless it is a strictly positive decimal
   */
  public static Decimal parseDecimal(String value) {
    if (value == null || value.length() > Constants.MAX_PRICE_LENGTH) return null;
    Matcher match = DECIMAL.matcher(value);
    if (!match.matches()) return null;
    String fraction = match.group(2) == null ? "" : match.group(2);
    String exponentText = match.group(3) == null ? "0" : match.group(3);
    // Up to 62 digits: read as a BigInteger, then bounded.
    BigInteger exponentValue =
        new BigInteger(exponentText.startsWith("+") ? exponentText.substring(1) : exponentText);
    if (exponentValue.abs().compareTo(BigInteger.valueOf(Constants.MAX_PRICE_EXPONENT)) > 0) {
      return null;
    }
    long exponent = exponentValue.longValue();
    BigInteger units = new BigInteger(match.group(1) + fraction);
    long scale = fraction.length() - exponent;
    if (scale < 0) {
      units = units.multiply(BigInteger.TEN.pow((int) -scale));
      scale = 0;
    }
    // The shortest exact writing: 69012.50 is 69012.5.
    while (scale > 0 && units.signum() != 0 && units.mod(BigInteger.TEN).signum() == 0) {
      units = units.divide(BigInteger.TEN);
      scale -= 1;
    }
    return units.signum() > 0 ? new Decimal(units, (int) scale) : null;
  }

  /**
   * The text of a JSON number a source answered: the shortest decimal that reads back as the same
   * IEEE 754 double (ECMAScript's {@code Number::toString}).
   *
   * @param value the number
   * @return its text, or null when it is not finite
   */
  public static String numberText(double value) {
    return Double.isFinite(value) ? EcmaNumbers.format(value) : null;
  }

  /**
   * The plain decimal writing of an exact decimal: {@code 69012.5}, {@code 0.00000008}.
   *
   * @param units its digits
   * @param scale its decimals
   * @return its writing
   */
  public static String formatDecimal(BigInteger units, int scale) {
    if (scale == 0) return units.toString();
    StringBuilder digits = new StringBuilder(units.toString());
    while (digits.length() < scale + 1) digits.insert(0, '0');
    int point = digits.length() - scale;
    return digits.substring(0, point) + "." + digits.substring(point);
  }

  /**
   * The plain decimal writing of an exact decimal.
   *
   * @param decimal the decimal
   * @return its writing
   */
  public static String formatDecimal(Decimal decimal) {
    return formatDecimal(decimal.units(), decimal.scale());
  }

  /**
   * A price given as {@code {"amount": "24.50", "currency": "CHF"}}, in integer minor units of its
   * currency (SPEC.md §25): a currency of the table (upper case), an amount of digits with at most
   * the currency's decimals (trailing zeros aside), nothing around it; never a float, never
   * rounded.
   *
   * @param amount the amount
   * @param currency the currency
   * @return the Fiat
   * @throws InvalidOrderException ({@code fiat}) when it is not one
   */
  public static Fiat parseFiat(String amount, String currency) {
    OptionalInt digits = Currencies.currencyDigits(currency);
    if (currency == null || digits.isEmpty()) {
      throw new InvalidOrderException("fiat", "currency: an ISO 4217 code in upper case, as CHF");
    }
    if (amount == null) {
      throw new InvalidOrderException(
          "fiat", "amount: a string of digits, as \"24.50\" (never a float)");
    }
    Matcher match = AMOUNT.matcher(amount);
    if (!match.matches()) throw new InvalidOrderException("fiat", "amount: digits, as \"24.50\"");
    String fraction = match.group(2) == null ? "" : match.group(2);
    int end = fraction.length();
    while (end > 0 && fraction.charAt(end - 1) == '0') end -= 1;
    fraction = fraction.substring(0, end);
    int places = digits.getAsInt();
    if (fraction.length() > places) {
      throw new InvalidOrderException(
          "fiat", "amount: " + currency + " has " + places + " decimals at most");
    }
    StringBuilder padded = new StringBuilder(fraction);
    while (padded.length() < places) padded.append('0');
    BigInteger minor =
        new BigInteger(match.group(1))
            .multiply(BigInteger.TEN.pow(places))
            .add(padded.length() == 0 ? BigInteger.ZERO : new BigInteger(padded.toString()));
    if (minor.signum() < 1 || minor.compareTo(BigInteger.valueOf(Constants.MAX_MINOR_UNITS)) > 0) {
      throw new InvalidOrderException("fiat", "amount: more than 0, and not that large");
    }
    return new Fiat(formatDecimal(minor, places), currency, minor.toString(), places);
  }

  private static final Pattern MINOR = Pattern.compile("[0-9]{1,16}");

  /**
   * The minor units of a price as a Fiat holds them, checked: {@code minor} 1 to 16 digits of 1 to
   * 2^53 − 1 units, {@code digits} 0 to 8, as an order's price and a declaration's (SPEC.md §25).
   *
   * @param minor the minor units, as written
   * @param digits the currency's decimals
   * @return the units
   * @throws InvalidOrderException ({@code fiat}) when they are not a price's
   */
  public static BigInteger minorUnitsOf(String minor, int digits) {
    if (minor == null
        || !MINOR.matcher(minor).matches()
        || digits < 0
        || digits > Constants.MAX_DECLARED_DIGITS) {
      throw new InvalidOrderException(
          "fiat", "not a price: minor units of 1 to 16 digits, and 0 to 8 decimals");
    }
    BigInteger units = new BigInteger(minor);
    if (units.signum() < 1 || units.compareTo(BigInteger.valueOf(Constants.MAX_MINOR_UNITS)) > 0) {
      throw new InvalidOrderException("fiat", "not a price: 1 to 2^53 - 1 minor units");
    }
    return units;
  }

  /**
   * Sats for {@code minor} units of a currency with {@code digits} decimals at {@code price} (one
   * bitcoin in that currency), rounded up to the next whole sat, on integers: ⌈minor × 10^8 ×
   * 10^scale / (units × 10^digits)⌉.
   *
   * @param minor the minor units
   * @param digits the currency's decimals
   * @param price the price of one bitcoin
   * @return the sats
   */
  public static BigInteger minorToSats(BigInteger minor, int digits, Decimal price) {
    BigInteger dividend = minor.multiply(SATS_PER_BTC).multiply(BigInteger.TEN.pow(price.scale()));
    BigInteger divisor = price.units().multiply(BigInteger.TEN.pow(digits));
    return dividend.add(divisor).subtract(BigInteger.ONE).divide(divisor);
  }
}
