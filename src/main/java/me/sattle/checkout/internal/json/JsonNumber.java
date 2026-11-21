package me.sattle.checkout.internal.json;

/**
 * A JSON number: an IEEE 754 double, whatever its spelling ({@code 500}, {@code 500.0} and {@code
 * 5e2} are one number).
 *
 * @param value the double
 */
public record JsonNumber(double value) implements JsonValue {
  /** 2^53 − 1: the largest integer every JSON reader holds exactly. */
  public static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

  /**
   * Whether it is an integer ({@code Number.isInteger}): finite and whole, of any size.
   *
   * @return true for an integer
   */
  public boolean isInteger() {
    return Double.isFinite(value) && Math.floor(value) == value;
  }

  /**
   * Whether it is a safe integer ({@code Number.isSafeInteger}): whole, at most 2^53 − 1 either
   * way.
   *
   * @return true for a safe integer
   */
  public boolean isSafeInteger() {
    return isInteger() && Math.abs(value) <= MAX_SAFE_INTEGER;
  }
}
