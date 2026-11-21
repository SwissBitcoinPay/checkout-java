package me.sattle.checkout.internal.onchain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import me.sattle.checkout.NetworkException;
import me.sattle.checkout.PaymentStatus;
import me.sattle.checkout.internal.Constants;
import me.sattle.checkout.internal.Http;
import me.sattle.checkout.internal.Lnurl;
import me.sattle.checkout.internal.Tasks;
import me.sattle.checkout.internal.Warn;

/**
 * The payment of an invoice (SPEC.md §31, "The on-chain payment"): its on-chain part, from the
 * explorer and the witnesses of its order, its rest part (the Lightning invoices of the rest of an
 * underpayment), and how they combine with its Lightning part.
 */
public final class Payments {
  private Payments() {}

  /** The on-chain part of an invoice with an address. */
  public sealed interface OnchainPart permits Underpaid, Unconfirmed, Paid {
    /**
     * What counts of what came in time.
     *
     * @return the sats
     */
    long received();
  }

  /**
   * Less than the amount came in time: final once the lease ended and nothing is undecided.
   *
   * @param received what counts
   * @param isFinal whether it is final
   */
  public record Underpaid(long received, boolean isFinal) implements OnchainPart {}

  /**
   * Enough came in time, not 3 confirmations deep on the explorer read yet.
   *
   * @param received what counts
   * @param confirmations the least confirmations of its transactions
   * @param txids the order's transactions
   */
  public record Unconfirmed(long received, long confirmations, List<String> txids)
      implements OnchainPart {}

  /**
   * 3 confirmations on the explorer read (no transaction: the rest invoices paid it).
   *
   * @param received what counts
   * @param txids the order's transactions
   */
  public record Paid(long received, List<String> txids) implements OnchainPart {}

  /**
   * A value that comes later, waited for.
   *
   * @param <T> the value
   */
  @FunctionalInterface
  public interface Wait<T> {
    /**
     * The value.
     *
     * @return it
     * @throws InterruptedException when the call is cancelled
     */
    T get() throws InterruptedException;
  }

  /** Reads the witnesses of the order. */
  @FunctionalInterface
  public interface WitnessReader {
    /**
     * The witnesses.
     *
     * @param everyRelay whether the read waits for every relay
     * @return the witnesses
     * @throws InterruptedException when the call is cancelled
     * @throws NetworkException when fewer than a majority answered
     */
    Witnesses.OrderWitnesses read(boolean everyRelay) throws InterruptedException;
  }

  /**
   * What the on-chain part of an invoice needs.
   *
   * @param address the deposit address
   * @param sats the amount
   * @param expiresAt the invoice's end
   * @param sale the order's sale author (hex)
   * @param clock Unix seconds: read once the explorer answered (a transaction listed then was
   *     broadcast by then)
   * @param http the HTTP client (BlockCypher)
   * @param explorer the explorer of the call: the address's transactions and the chain's height are
   *     one read of it, both of one explorer
   * @param readWitnesses the witnesses of the order
   * @param witnessAddress the witness address of a txid for the sale, or for none (null)
   * @param publish publishes a witness on a majority of the merchant's relays
   * @param logger where warnings go
   * @param paidByRests what the invoice's rest invoices paid (checked at the same time)
   * @param tolerance the order's tolerance (read with the sale's records at the same time)
   * @param readMerchantAcceptance starts the read of the merchant's acceptance of the invoice
   *     ({@link MerchantAcceptances}) on every relay, read at the same time as the witnesses when
   *     some transaction pays the address (a {@link NetworkException} when fewer than a majority
   *     answered); null: none
   */
  public record OnchainCheck(
      String address,
      long sats,
      long expiresAt,
      String sale,
      LongSupplier clock,
      Http http,
      CallExplorer explorer,
      WitnessReader readWitnesses,
      BinaryOperator<String> witnessAddress,
      Function<Witnesses.Witness, CompletableFuture<Void>> publish,
      Warn logger,
      Wait<Long> paidByRests,
      Wait<Integer> tolerance,
      Supplier<CompletableFuture<MerchantAcceptances.Accepted>> readMerchantAcceptance) {}

  private enum Kind {
    IN_TIME,
    REPLACEMENT,
    LATE,
    UNDECIDED,
    ANOTHERS
  }

  private record Paying(Explorer.Tx tx, long pays) {}

  /** Step 1's read of the explorer: the address's transactions and the chain's height. */
  private record Step(List<Explorer.Tx> txs, long height) {}

  private static long add(long a, long b) {
    try {
      return Math.addExact(a, b);
    } catch (ArithmeticException e) {
      throw new NetworkException("The explorer's transactions add up to more than can be", e);
    }
  }

  /**
   * The on-chain part of an invoice, checked at {@code now} (SPEC.md §31, "The on-chain payment"):
   * each transaction paying its address in time, a replacement of payments in time, late, or
   * undecided; what counts of them; and, once enough counts, their confirmations on the explorer
   * read: paid at 3, on what that explorer alone shows. Publishes the witnesses it finds (of the
   * sale on a majority, else {@code network}; of none for the merchant's app only, a failure
   * logged). What is due on-chain is the least the order's tolerance accepts of the amount (the
   * amount without tolerance), or the {@code received} of the merchant's acceptance a majority of
   * the relays sent when less, less what its rest invoices paid, a sat at least ({@link
   * Tolerance#dueOnchain}): when they pay the amount, the on-chain part is paid without
   * transactions.
   *
   * @param check what it needs
   * @return the on-chain part
   * @throws InterruptedException when the call is cancelled
   * @throws NetworkException when the explorer, BlockCypher or the relays fail
   */
  public static OnchainPart checkOnchainPart(OnchainCheck check) throws InterruptedException {
    String address = check.address();
    String sale = check.sale();
    Http http = check.http();
    long end = check.expiresAt() + Constants.GRACE_SECONDS;
    // One read, of one explorer: a transaction's confirmations compare its block's height with
    // that explorer's height. Either failing, both are asked of the next explorer (SPEC.md §31).
    Step step =
        check
            .explorer()
            .read(source -> {
              CompletableFuture<List<Explorer.Tx>> txsAnswer =
                  Explorer.addressTransactions(http, source, address);
              CompletableFuture<Long> heightAnswer = Explorer.chainHeight(http, source);
              return Tasks.map(
                  Tasks.all(List.of(txsAnswer, heightAnswer)),
                  ignored -> new Step(txsAnswer.join(), heightAnswer.join()));
            });
    List<Explorer.Tx> txs = step.txs();
    long height = step.height();
    // The time of the check: once the explorer listed what pays the address (step 3.4: a
    // transaction listed now was broadcast by now), never a time read before.
    long now = check.clock().getAsLong();
    List<Paying> paying = new ArrayList<>();
    Set<String> listed = new LinkedHashSet<>();
    for (Explorer.Tx tx : txs) {
      long pays = Explorer.paysTo(tx, address);
      if (pays > 0 && !listed.contains(tx.txid())) {
        paying.add(new Paying(tx, pays));
        listed.add(tx.txid());
      }
    }
    // The witnesses, and the merchant's acceptance with them (on every relay), when some
    // transaction pays the address.
    Witnesses.OrderWitnesses witnesses = Witnesses.OrderWitnesses.empty();
    MerchantAcceptances.Accepted accepted = MerchantAcceptances.Accepted.NONE;
    if (!paying.isEmpty()) {
      CompletableFuture<MerchantAcceptances.Accepted> acceptance =
          check.readMerchantAcceptance() == null
              ? CompletableFuture.completedFuture(MerchantAcceptances.Accepted.NONE)
              : check.readMerchantAcceptance().get();
      try {
        witnesses = check.readWitnesses().read(false);
        accepted = Tasks.await(acceptance);
      } finally {
        // Read, or no longer needed (the witnesses' read failed): its relays asked nothing more.
        acceptance.cancel(true);
      }
    }
    if (accepted.newer()) {
      check
          .logger()
          .warn(
              Warn.PREFIX
                  + "a merchant's acceptance of this order has a newer format than this version"
                  + " reads; update the package");
    }

    // The parts: from the witnesses of this invoice and of other invoices (in the order they were
    // seen), then the transactions no such witness names (their order unknown): a replacement of
    // another invoice's payment stands for it.
    Function<Witnesses.OrderWitnesses, Map<String, List<Replacements.Part>>> partsOf =
        read -> {
          List<Replacements.PaymentTx> list = new ArrayList<>();
          for (Map<String, Witnesses.SaleWitness> map : List.of(read.sale(), read.others())) {
            for (Map.Entry<String, Witnesses.SaleWitness> entry : map.entrySet()) {
              Witnesses.SaleWitness witness = entry.getValue();
              list.add(
                  new Replacements.PaymentTx(
                      entry.getKey(),
                      witness.sats(),
                      witness.inputs(),
                      witness.seenAt(),
                      listed.contains(entry.getKey())));
            }
          }
          for (Paying p : paying) {
            String txid = p.tx().txid();
            if (read.sale().containsKey(txid) || read.others().containsKey(txid)) continue;
            list.add(new Replacements.PaymentTx(txid, p.pays(), p.tx().coins(), null, true));
          }
          return Replacements.paymentParts(list);
        };
    Map<String, List<Replacements.Part>> parts = partsOf.apply(witnesses);

    // A witness of a newer format that names no transaction listed may be of a payment replaced.
    boolean newerElsewhere = false;
    for (String d : witnesses.newer()) {
      boolean names = false;
      for (Paying p : paying) {
        String txid = p.tx().txid();
        if (d.equals(check.witnessAddress().apply(txid, sale))
            || d.equals(check.witnessAddress().apply(txid, null))) {
          names = true;
          break;
        }
      }
      if (!names) {
        newerElsewhere = true;
        break;
      }
    }

    Map<String, Kind> kinds = new LinkedHashMap<>();
    List<Witnesses.Witness> ofSale = new ArrayList<>();
    List<Witnesses.Witness> ofNone = new ArrayList<>();
    for (Paying p : paying) {
      Explorer.Tx tx = p.tx();
      String txid = tx.txid();
      // Another invoice's: a transaction counts for one invoice at most.
      if (anothers(witnesses, parts, txid)) {
        kinds.put(txid, Kind.ANOTHERS);
        continue;
      }
      Witnesses.SaleWitness witness = witnesses.sale().get(txid);
      if (witness != null && !witness.replacement()) {
        kinds.put(txid, Kind.IN_TIME);
        continue;
      }
      if (witnesses.newer().contains(check.witnessAddress().apply(txid, sale))) {
        kinds.put(txid, Kind.UNDECIDED);
        continue;
      }
      if (now <= end) {
        // Listed now, it was broadcast by now: while the lease runs.
        kinds.put(txid, Kind.IN_TIME);
        ofSale.add(witnessOf(check, tx, p.pays(), true, false));
        continue;
      }
      if (witness != null) {
        // An earlier check found it a replacement, and BlockCypher's time of it late.
        kinds.put(txid, Kind.REPLACEMENT);
        continue;
      }
      // No check saw it in time.
      List<Replacements.Part> own = parts.get(txid);
      boolean replaces = false;
      boolean ofItself = false;
      for (Replacements.Part part : own) {
        if (part.root().equals(txid)) ofItself = true;
        else replaces = true;
      }
      if (ofItself) {
        Long time = Tasks.await(Explorer.receivedAt(http, txid));
        if (time != null && time <= end * 1000) {
          kinds.put(txid, Kind.IN_TIME);
          ofSale.add(witnessOf(check, tx, p.pays(), true, false));
          continue;
        }
      }
      if (newerElsewhere) {
        kinds.put(txid, Kind.UNDECIDED);
      } else if (replaces) {
        kinds.put(txid, Kind.REPLACEMENT);
        ofSale.add(witnessOf(check, tx, p.pays(), true, true));
      } else {
        // Its payment reaches the merchant's wallet, but no order: a witness none tells the
        // merchant's app, unless one names it already, or one of a newer format holds its witness
        // address (it names it too; a record of a newer format is never written over).
        kinds.put(txid, Kind.LATE);
        if (!witnesses.none().contains(txid)
            && !witnesses.newer().contains(check.witnessAddress().apply(txid, null))) {
          ofNone.add(witnessOf(check, tx, p.pays(), false, false));
        }
      }
    }
    List<CompletableFuture<?>> publications = new ArrayList<>();
    for (Witnesses.Witness witness : ofSale) publications.add(check.publish().apply(witness));
    for (Witnesses.Witness witness : ofNone) {
      publications.add(
          check
              .publish()
              .apply(witness)
              .handle((ignored, error) -> {
                if (error == null) return CompletableFuture.<Void>completedFuture(null);
                Throwable cause = Tasks.unwrap(error);
                if (!(cause instanceof NetworkException)) {
                  return CompletableFuture.<Void>failedFuture(cause);
                }
                check
                    .logger()
                    .warn(
                        Warn.PREFIX
                            + "the witness of a late payment was not published: "
                            + cause.getMessage());
                return CompletableFuture.<Void>completedFuture(null);
              })
              .thenCompose(future -> future));
    }
    Tasks.await(Tasks.all(publications));
    // Its witnesses on a majority, read again on every relay: of two checks that publish witnesses
    // of one transaction at once, the later reader sees both, whatever one relay hides.
    if (!ofSale.isEmpty()) {
      Witnesses.OrderWitnesses again = check.readWitnesses().read(true);
      Map<String, List<Replacements.Part>> partsAgain = partsOf.apply(again);
      for (Paying p : paying) {
        if (anothers(again, partsAgain, p.tx().txid())) kinds.put(p.tx().txid(), Kind.ANOTHERS);
      }
    }

    Map<String, Long> counted = new LinkedHashMap<>();
    for (Paying p : paying) {
      Kind kind = kinds.get(p.tx().txid());
      if (kind == Kind.UNDECIDED || kind == Kind.ANOTHERS) continue;
      long sum = 0;
      for (Replacements.Part part : parts.get(p.tx().txid())) {
        if (inTime(part.root(), listed, kinds, witnesses)) sum = add(sum, part.sats());
      }
      if (sum > 0) counted.put(p.tx().txid(), sum);
    }
    long received = 0;
    for (long sum : counted.values()) received = add(received, sum);
    long paidByRests = check.paidByRests() == null ? 0 : check.paidByRests().get();
    int tolerance = check.tolerance() == null ? 0 : check.tolerance().get();
    // Nothing due when the rest invoices paid it all; else the least the order's tolerance accepts,
    // or what the merchant accepted when less, less what they paid, at least a sat.
    long due = Tolerance.dueOnchain(check.sats(), tolerance, paidByRests, accepted.received());
    if (due == 0) return new Paid(received, List.of());
    if (received < due) {
      return new Underpaid(received, now > end && !kinds.containsValue(Kind.UNDECIDED));
    }
    List<String> txids = new ArrayList<>();
    long confirmations = Long.MAX_VALUE;
    for (Paying p : paying) {
      if (!counted.containsKey(p.tx().txid())) continue;
      txids.add(p.tx().txid());
      confirmations = Math.min(confirmations, Explorer.confirmationsOf(p.tx(), height));
    }
    if (confirmations >= Constants.CONFIRMATIONS) return new Paid(received, List.copyOf(txids));
    return new Unconfirmed(received, confirmations, List.copyOf(txids));
  }

  /** Another invoice's: a witness of another invoice names it, or the root of one of its parts. */
  private static boolean anothers(
      Witnesses.OrderWitnesses read, Map<String, List<Replacements.Part>> parts, String txid) {
    if (read.others().containsKey(txid)) return true;
    for (Replacements.Part part : parts.get(txid)) {
      if (read.others().containsKey(part.root())) return true;
    }
    return false;
  }

  /** Whether a part of {@code root} counts: its root came in time. */
  private static boolean inTime(
      String root,
      Set<String> listed,
      Map<String, Kind> kinds,
      Witnesses.OrderWitnesses witnesses) {
    if (listed.contains(root)) return kinds.get(root) == Kind.IN_TIME;
    Witnesses.SaleWitness witness = witnesses.sale().get(root);
    return witness != null && !witness.replacement();
  }

  private static Witnesses.Witness witnessOf(
      OnchainCheck check, Explorer.Tx tx, long pays, boolean forSale, boolean replacement) {
    List<String> coins = tx.coins();
    return new Witnesses.Witness(
        check.address(),
        tx.txid(),
        forSale ? check.sale() : null,
        pays,
        coins.subList(0, Math.min(coins.size(), Constants.MAX_WITNESS_INPUTS)),
        replacement,
        forSale ? check.expiresAt() : null);
  }

  /**
   * The rest part of an invoice: its rest invoices, and the Lightning part of each, in order.
   *
   * @param invoices the rest invoices
   * @param results the Lightning part of each
   * @param error the sale's records could not be read (whether a rest invoice was paid cannot be
   *     told), or null
   * @param unrecorded the payment hashes of the rest invoices whose rest record is not known on a
   *     majority: never the status's {@code bolt11}; null for none
   */
  public record RestPart(
      List<Rests.RestInvoice> invoices,
      List<Tasks.Settled<Lnurl.LightningPart>> results,
      RuntimeException error,
      Set<String> unrecorded) {
    /**
     * A rest part.
     *
     * @param invoices the rest invoices
     * @param results the Lightning part of each
     * @param error the read's error, or null
     * @param unrecorded the rest invoices not known on a majority, or null
     */
    public RestPart {
      invoices = List.copyOf(invoices);
      results = List.copyOf(results);
    }

    /**
     * A rest part read.
     *
     * @param invoices the rest invoices
     * @param results the Lightning part of each
     * @return the part
     */
    public static RestPart of(
        List<Rests.RestInvoice> invoices, List<Tasks.Settled<Lnurl.LightningPart>> results) {
      return new RestPart(invoices, results, null, null);
    }

    /**
     * The rest part of a check whose read of the sale's records failed.
     *
     * @param error the read's error
     * @return the part
     */
    public static RestPart failed(RuntimeException error) {
      return new RestPart(List.of(), List.of(), error, null);
    }

    /**
     * The same part, with the rest invoices not known on a majority.
     *
     * @param unrecorded their payment hashes
     * @return the part
     */
    public RestPart withUnrecorded(Set<String> unrecorded) {
      return new RestPart(invoices, results, error, unrecorded);
    }
  }

  /**
   * What the rest invoices paid.
   *
   * @param paid the sats they paid (each payment hash once)
   * @param preimages their preimages, in order
   * @param hashes the payment hashes paid
   * @param isFinal whether each of them is final
   * @param error the first error, or null
   */
  public record RestSummary(
      long paid,
      List<String> preimages,
      Set<String> hashes,
      boolean isFinal,
      RuntimeException error) {}

  /**
   * What the rest invoices of {@code rests} paid (each payment hash once; one that failed counts as
   * not paid), their preimages in order (by date, then payment hash), whether each is final, and
   * the first error.
   *
   * @param rests the rest part, or null
   * @return its summary
   */
  public static RestSummary restSummary(RestPart rests) {
    long paid = 0;
    List<String> preimages = new ArrayList<>();
    Set<String> hashes = new HashSet<>();
    boolean isFinal = true;
    RuntimeException error = null;
    if (rests != null && rests.error() != null) {
      isFinal = false;
      error = rests.error();
    }
    if (rests != null) {
      List<Integer> order = new ArrayList<>();
      for (int i = 0; i < rests.invoices().size(); i += 1) order.add(i);
      order.sort(
          (a, b) -> Rests.REST_ORDER.compare(rests.invoices().get(a), rests.invoices().get(b)));
      for (int index : order) {
        Rests.RestInvoice invoice = rests.invoices().get(index);
        Tasks.Settled<Lnurl.LightningPart> result = rests.results().get(index);
        if (!result.ok()) {
          isFinal = false;
          if (error == null) error = result.error();
          continue;
        }
        if (result.value().isPaid()) {
          if (!hashes.add(invoice.paymentHash())) continue;
          paid = Math.addExact(paid, invoice.sats());
          preimages.add(result.value().preimage());
        } else if (result.value().state() == Lnurl.LightningPart.State.OPEN) {
          isFinal = false;
        }
      }
    }
    return new RestSummary(paid, List.copyOf(preimages), Set.copyOf(hashes), isFinal, error);
  }

  /**
   * The status of an invoice from its parts (SPEC.md §31): paid in Lightning, else paid by its rest
   * invoices alone, else paid on-chain (mixed when rest invoices paid part of it), else unconfirmed
   * on-chain, else an error of a part (the Lightning part's first, then the rest part's, then the
   * on-chain part's, thrown), else expired once every part is final, else open, with its rest.
   *
   * @param sats the invoice's amount
   * @param expiresAt its end
   * @param address its deposit address, or null
   * @param now Unix seconds
   * @param lightning the Lightning part, or null without a Lightning invoice
   * @param onchain the on-chain part, or null without an address
   * @param rests the rest part, or null
   * @return the status
   */
  public static PaymentStatus combineParts(
      long sats,
      long expiresAt,
      String address,
      long now,
      Tasks.Settled<Lnurl.LightningPart> lightning,
      Tasks.Settled<OnchainPart> onchain,
      RestPart rests) {
    if (lightning != null && lightning.ok() && lightning.value().isPaid()) {
      return new PaymentStatus.PaidLightning(lightning.value().preimage());
    }
    RestSummary summary = restSummary(rests);
    if (summary.paid() >= sats) {
      // What came on-chain in time counts too (0 when the on-chain part failed).
      long onchainReceived = onchain != null && onchain.ok() ? onchain.value().received() : 0;
      return new PaymentStatus.PaidMixed(
          Long.toString(onchainReceived + summary.paid()), List.of(), summary.preimages());
    }
    if (onchain != null && onchain.ok() && onchain.value() instanceof Paid paid) {
      String received = Long.toString(paid.received() + summary.paid());
      return summary.paid() > 0
          ? new PaymentStatus.PaidMixed(received, paid.txids(), summary.preimages())
          : new PaymentStatus.PaidOnchain(received, paid.txids());
    }
    if (onchain != null && onchain.ok() && onchain.value() instanceof Unconfirmed unconfirmed) {
      return new PaymentStatus.Unconfirmed(
          Long.toString(unconfirmed.received() + summary.paid()),
          (int) Math.min(unconfirmed.confirmations(), Integer.MAX_VALUE),
          unconfirmed.txids());
    }
    if (lightning != null && !lightning.ok()) throw lightning.error();
    if (summary.error() != null) throw summary.error();
    if (onchain != null && !onchain.ok()) throw onchain.error();
    Underpaid underpaid =
        onchain != null && onchain.value() instanceof Underpaid value ? value : null;
    long received = (underpaid == null ? 0 : underpaid.received()) + summary.paid();
    boolean lightningFinal =
        lightning == null || lightning.value().state() == Lnurl.LightningPart.State.EXPIRED;
    boolean onchainFinal = onchain == null || (underpaid != null && underpaid.isFinal());
    if (lightningFinal && onchainFinal && summary.isFinal()) {
      return new PaymentStatus.Expired(Long.toString(received));
    }
    PaymentStatus.Rest rest = null;
    if (address != null) {
      List<Rests.RestInvoice> recorded = new ArrayList<>();
      if (rests != null) {
        for (Rests.RestInvoice invoice : rests.invoices()) {
          if (rests.unrecorded() == null || !rests.unrecorded().contains(invoice.paymentHash())) {
            recorded.add(invoice);
          }
        }
      }
      rest = Rests.restOf(sats, expiresAt, address, received, now, recorded, summary.hashes());
    }
    return new PaymentStatus.Open(expiresAt, Long.toString(received), rest);
  }
}
