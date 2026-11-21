package me.sattle.checkout.internal.onchain;

import me.sattle.checkout.internal.Constants;
import me.sattle.checkout.internal.json.Json;
import me.sattle.checkout.internal.json.JsonValue;

/**
 * The merchant's tolerance of on-chain underpayments (SPEC.md §31, "The merchant's tolerance"): the
 * share of an order's amount its payment in time may miss and still pay it, in basis points. The
 * channel gives it, an order's lease keeps the one of its creation. It is never written where the
 * shop's page or the customer reads (the Invoice, the PaymentStatus, a checkout link's record, an
 * error, a log): a customer who knew it could pay that much less on purpose.
 */
public final class Tolerance {
  private Tolerance() {}

  /**
   * A tolerance as a channel or a lease writes it.
   *
   * @param value the JSON value, or null when absent
   * @return an integer from 0 to 1000, else 0
   */
  public static int toleranceOf(JsonValue value) {
    if (!Json.isInteger(value)) return 0;
    double number = Json.number(value);
    return number >= 0 && number <= Constants.MAX_TOLERANCE ? (int) number : 0;
  }

  /**
   * The least accepted of {@code sats} with {@code tolerance}: {@code sats − floor(sats × T /
   * 10000)}, on integers.
   *
   * @param sats the amount
   * @param tolerance basis points
   * @return the least accepted
   */
  public static long leastAccepted(long sats, int tolerance) {
    int checked = tolerance >= 0 && tolerance <= Constants.MAX_TOLERANCE ? tolerance : 0;
    // sats × 1000 at most 2.1 × 10^18: within a long.
    return sats - (sats * checked) / Constants.TOLERANCE_SCALE;
  }

  /**
   * What is due on-chain of an invoice of {@code sats} with {@code tolerance}, its rest invoices
   * having paid {@code paidByRests}, with the {@code received} of the merchant's acceptance that
   * counts, {@code merchantAccepted} (null without one: {@link MerchantAcceptances}) (SPEC.md §31,
   * "The on-chain payment", step 4): nothing (0) when they paid {@code sats} or more; else max(1,
   * min(L, M) − R), L the least accepted, M the merchant's acceptance's {@code received} ({@code
   * sats} without one), R what they paid. R is taken once from the lesser: M counts the rest
   * invoices paid when the merchant accepted, as L counts every payment. At least 1 sat: something
   * must count on-chain for the tolerance or the merchant's acceptance to apply.
   *
   * @param sats the amount
   * @param tolerance basis points
   * @param paidByRests what the rest invoices paid
   * @param merchantAccepted the merchant's acceptance's {@code received}, or null
   * @return what is due on-chain
   */
  public static long dueOnchain(long sats, int tolerance, long paidByRests, Long merchantAccepted) {
    if (paidByRests >= sats) return 0;
    long least = leastAccepted(sats, tolerance);
    long accepted = merchantAccepted != null && merchantAccepted < least ? merchantAccepted : least;
    long due = accepted - paidByRests;
    return due > 0 ? due : 1;
  }
}
