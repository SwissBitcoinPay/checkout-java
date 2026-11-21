package me.sattle.checkout;

import java.util.List;
import me.sattle.checkout.internal.Model;

/**
 * Where the payment of an invoice is (SPEC.md §3, §22, §31). {@code received} is what the order
 * received in time, in sats: on-chain to its address, and in Lightning by the invoices of the rest
 * of an on-chain underpayment; {@code "0"} without an address. Below the amount in {@link
 * Unconfirmed} and {@link Paid} (on-chain or mixed) when the merchant's tolerance of on-chain
 * underpayments, or the merchant's acceptance of what came (in its app), accepted it: the order is
 * paid all the same.
 */
public sealed interface PaymentStatus extends java.io.Serializable
    permits PaymentStatus.Open,
        PaymentStatus.Unconfirmed,
        PaymentStatus.Paid,
        PaymentStatus.Expired {

  /**
   * The state: {@code "open"}, {@code "unconfirmed"}, {@code "paid"} or {@code "expired"}.
   *
   * @return the state
   */
  String state();

  /**
   * Whether it is final: paid, or expired for good.
   *
   * @return true for {@link Paid} and {@link Expired}
   */
  default boolean isFinal() {
    return this instanceof Paid || this instanceof Expired;
  }

  /**
   * The JSON of SPEC.md §3: {@code {"state": "paid", "method": "lightning", "preimage": …}}…
   *
   * @return its JSON text
   */
  default String toJson() {
    return Model.write(Model.status(this));
  }

  /**
   * Not paid (yet): it may still be, until {@code expiresAt}, and is reported up to 120 s later.
   * {@code received} above 0: an on-chain underpayment, whose {@code rest} is still payable until
   * {@code expiresAt}.
   *
   * @param expiresAt Unix seconds: the invoice's end
   * @param received what came in time, in sats (a string of digits)
   * @param rest what is left to pay, and how; null unless {@code received} is above 0 before the
   *     invoice's end
   */
  record Open(long expiresAt, String received, Rest rest) implements PaymentStatus {
    @Override
    public String state() {
      return "open";
    }
  }

  /**
   * Enough was received in time (the amount, or less within the merchant's tolerance or down to
   * what the merchant accepted), on-chain by the transactions {@code txids} (and by invoices of the
   * rest), not deep enough yet (3 confirmations): not paid yet, it may still fail (a transaction
   * replaced or dropped).
   *
   * @param received what came in time, in sats
   * @param confirmations the least confirmations of its transactions
   * @param txids its transactions
   */
  record Unconfirmed(String received, int confirmations, List<String> txids)
      implements PaymentStatus {
    /**
     * Being confirmed.
     *
     * @param received what came in time, in sats
     * @param confirmations the least confirmations of its transactions
     * @param txids its transactions
     * @throws NullPointerException when {@code txids} is null or holds a null ({@link List#copyOf})
     */
    public Unconfirmed {
      txids = List.copyOf(txids);
    }

    @Override
    public String state() {
      return "unconfirmed";
    }
  }

  /** Paid: in Lightning, on-chain, or both (mixed). Never charge the order again. */
  sealed interface Paid extends PaymentStatus
      permits PaymentStatus.PaidLightning, PaymentStatus.PaidOnchain, PaymentStatus.PaidMixed {
    @Override
    default String state() {
      return "paid";
    }

    /**
     * How it was paid.
     *
     * @return {@code "lightning"}, {@code "onchain"} or {@code "mixed"}
     */
    String method();
  }

  /**
   * Paid in Lightning: {@code preimage} is the proof of payment, to keep with the order.
   *
   * @param preimage the preimage of the payment hash, lowercase hex
   */
  record PaidLightning(String preimage) implements Paid {
    @Override
    public String method() {
      return "lightning";
    }
  }

  /**
   * Paid on-chain by the transactions {@code txids} (3 confirmations on the explorer read:
   * mempool.space, or the merchant's Bitcoin node).
   *
   * @param received what came in time, in sats
   * @param txids its transactions
   */
  record PaidOnchain(String received, List<String> txids) implements Paid {
    /**
     * Paid on-chain.
     *
     * @param received what came in time, in sats
     * @param txids its transactions
     * @throws NullPointerException when {@code txids} is null or holds a null ({@link List#copyOf})
     */
    public PaidOnchain {
      txids = List.copyOf(txids);
    }

    @Override
    public String method() {
      return "onchain";
    }
  }

  /**
   * Paid on-chain by {@code txids} (3 confirmations on the explorer read; none when the invoices of
   * the rest alone pay it) and in Lightning by invoices of the rest of that underpayment, whose
   * {@code preimages} are the proofs of those payments, to keep with the order.
   *
   * @param received what came in time, in sats
   * @param txids its transactions
   * @param preimages the preimages of the rest invoices paid, in their order
   */
  record PaidMixed(String received, List<String> txids, List<String> preimages) implements Paid {
    /**
     * Paid on-chain and in Lightning.
     *
     * @param received what came in time, in sats
     * @param txids its transactions
     * @param preimages the preimages of the rest invoices paid
     * @throws NullPointerException when a list is null or holds a null ({@link List#copyOf})
     */
    public PaidMixed {
      txids = List.copyOf(txids);
      preimages = List.copyOf(preimages);
    }

    @Override
    public String method() {
      return "mixed";
    }
  }

  /**
   * Never paid, for good: the order may get a new invoice. {@code received} above 0: the merchant
   * received that much, less than the amount (settle it with the customer). One exception, the
   * merchant's acceptance (SPEC.md §3, §27): when a relay that holds it fails at a check after the
   * lease's end, that check may give {@code expired}, and a later one, which the relay answers,
   * {@code unconfirmed}, then {@code paid}; an integrator that has cancelled the order then settles
   * it by hand.
   *
   * @param received what came in time, in sats
   */
  record Expired(String received) implements PaymentStatus {
    @Override
    public String state() {
      return "expired";
    }
  }

  /**
   * What is left to pay of an invoice underpaid on-chain (SPEC.md §3, "rest").
   *
   * @param sats the invoice's sats less what was received: all that is left, whatever the
   *     merchant's tolerance or acceptance
   * @param bolt11 a Lightning invoice of exactly that, declared for the order; null while there is
   *     none
   * @param paymentUri what to show as one QR code: {@code bitcoin:…} from 1 000 sats, else {@code
   *     lightning:…}; null with neither
   */
  record Rest(String sats, String bolt11, String paymentUri) implements java.io.Serializable {}
}
