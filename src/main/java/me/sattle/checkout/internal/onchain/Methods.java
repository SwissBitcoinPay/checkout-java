package me.sattle.checkout.internal.onchain;

import me.sattle.checkout.InvalidOrderException;
import me.sattle.checkout.OnchainUnavailableException;
import me.sattle.checkout.SattlemeException;
import me.sattle.checkout.internal.Constants;

/**
 * The methods of an order by its amount (SPEC.md §31, "Methods by amount"): Lightning when the
 * amount is within the Lightning address's sendable sats, on-chain from 1 000 sats when an address
 * can be leased. Nothing is repaired: an amount is never split between the two, nor moved into the
 * sendable sats.
 */
public final class Methods {
  private Methods() {}

  /**
   * The methods of an order.
   *
   * @param lightning whether it is paid in Lightning
   * @param onchain whether it is paid on-chain
   */
  public record Of(boolean lightning, boolean onchain) {}

  /**
   * The methods {@code sats} may be paid with, before an address is leased.
   *
   * @param sats the amount
   * @param min the least sendable sats
   * @param max the most sendable sats
   * @param param {@code sats} or {@code fiat}, for the error
   * @return the methods
   * @throws InvalidOrderException below 1 000 sats and outside the sendable sats
   */
  public static Of paymentMethods(long sats, long min, long max, String param) {
    boolean lightning = sats >= min && sats <= max;
    boolean onchain = sats >= Constants.ONCHAIN_FLOOR_SATS;
    if (!lightning && !onchain) {
      throw new InvalidOrderException(
          param, sats + " sats is outside the merchant's bounds (" + min + " to " + max + " sats)");
    }
    return new Of(lightning, onchain);
  }

  /** What leasing an address gave. */
  public sealed interface LeaseOutcome permits Leased, NoAddress, ExplorerFailed {}

  /**
   * An address was leased.
   *
   * @param address the address
   * @param event its lease, marked
   */
  public record Leased(String address, me.sattle.checkout.internal.nostr.NostrEvent event)
      implements LeaseOutcome {}

  /** No address could be leased. */
  public record NoAddress() implements LeaseOutcome {}

  /**
   * The explorer did not answer.
   *
   * @param error its error
   */
  public record ExplorerFailed(SattlemeException error) implements LeaseOutcome {}

  /**
   * The methods once leasing an address gave {@code outcome}: without an address, Lightning only;
   * an order that can only be paid on-chain then fails.
   *
   * @param methods the methods by amount
   * @param outcome what leasing gave
   * @return the methods
   * @throws OnchainUnavailableException when no address could be leased for an order paid on-chain
   *     only
   * @throws SattlemeException the explorer's error, for an order paid on-chain only
   */
  public static Of methodsWith(Of methods, LeaseOutcome outcome) {
    if (!methods.onchain() || outcome instanceof Leased) return methods;
    if (!methods.lightning()) {
      throw outcome instanceof ExplorerFailed
          ? ((ExplorerFailed) outcome).error()
          : new OnchainUnavailableException();
    }
    return new Of(true, false);
  }
}
