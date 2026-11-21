package me.sattle.checkout.internal.json;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Numbers written as ECMAScript's {@code Number.prototype.toString} writes them (the shortest
 * decimal that reads back as the same double; on a tie, the closest; then the even one), the
 * spelling {@code JSON.stringify} gives and SPEC.md §25 reads a price in. The platform's own {@code
 * Double.toString} is not used: its spelling differs, and before Java 19 it was not always the
 * shortest.
 */
public final class EcmaNumbers {
  private EcmaNumbers() {}

  /** The digits and the exponent of a positive finite double: value = 0.digits × 10^point. */
  private record Shortest(String digits, int point) {}

  /**
   * {@code value} as {@code Number.prototype.toString()} writes it.
   *
   * @param value any double
   * @return its ECMAScript spelling ({@code "NaN"} and {@code "Infinity"} included)
   */
  public static String format(double value) {
    if (Double.isNaN(value)) return "NaN";
    if (value == 0) return "0";
    if (Double.isInfinite(value)) return value > 0 ? "Infinity" : "-Infinity";
    if (value < 0) return "-" + format(-value);
    if (value < 0x1p53 && Math.floor(value) == value) return Long.toString((long) value);
    Shortest shortest = shortest(value);
    String s = shortest.digits();
    int k = s.length();
    int n = shortest.point();
    if (k <= n && n <= 21) return s + "0".repeat(n - k);
    if (0 < n && n <= 21) return s.substring(0, n) + "." + s.substring(n);
    if (-6 < n && n <= 0) return "0." + "0".repeat(-n) + s;
    int e = n - 1;
    String exponent = (e >= 0 ? "+" : "-") + Math.abs(e);
    return k == 1 ? s + "e" + exponent : s.charAt(0) + "." + s.substring(1) + "e" + exponent;
  }

  /** The fewest digits that read back as {@code value} (positive, finite); of two, the closest. */
  private static Shortest shortest(double value) {
    BigDecimal exact = new BigDecimal(value);
    for (int precision = 1; precision <= 17; precision += 1) {
      BigDecimal below = exact.round(new MathContext(precision, RoundingMode.FLOOR));
      BigDecimal above = exact.round(new MathContext(precision, RoundingMode.CEILING));
      boolean belowReads = readsBack(below, value);
      boolean aboveReads = readsBack(above, value);
      BigDecimal chosen;
      if (belowReads && aboveReads) {
        int closer = exact.subtract(below).compareTo(above.subtract(exact));
        chosen =
            closer < 0
                ? below
                : closer > 0
                    ? above
                    : exact.round(new MathContext(precision, RoundingMode.HALF_EVEN));
      } else if (belowReads) {
        chosen = below;
      } else if (aboveReads) {
        chosen = above;
      } else {
        continue;
      }
      BigDecimal stripped = chosen.stripTrailingZeros();
      BigInteger unscaled = stripped.unscaledValue();
      String digits = unscaled.toString();
      return new Shortest(digits, digits.length() - stripped.scale());
    }
    // 17 significant digits always read back (IEEE 754): never reached.
    throw new IllegalStateException("No decimal reads back as " + value);
  }

  private static boolean readsBack(BigDecimal candidate, double value) {
    return candidate.signum() > 0 && Double.parseDouble(candidate.toString()) == value;
  }
}
