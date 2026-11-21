package me.sattle.checkout;

/**
 * A price of one bitcoin, as a {@link CustomRateSource} gives it (SPEC.md §26): an exact decimal
 * string, never a number, which may already have lost digits.
 *
 * @param price an exact decimal: {@code "69012.5"}, {@code "8e-8"}
 * @param time Unix seconds: when the source set it, null when it does not say (an undated price
 *     cannot be checked for its age)
 */
public record RateQuote(String price, Long time) {
  /**
   * An undated price.
   *
   * @param price an exact decimal
   * @return the quote
   */
  public static RateQuote of(String price) {
    return new RateQuote(price, null);
  }

  /**
   * A dated price.
   *
   * @param price an exact decimal
   * @param time Unix seconds: when the source set it
   * @return the quote
   */
  public static RateQuote of(String price, long time) {
    return new RateQuote(price, time);
  }
}
