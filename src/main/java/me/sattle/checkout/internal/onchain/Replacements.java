package me.sattle.checkout.internal.onchain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replacements (SPEC.md §31, "Replacements"): a payer whose transaction is slow to confirm may
 * broadcast another one in its place (RBF), which spends a coin of the same. A replacement keeps
 * the time of the payment it replaces, for at most what that payment paid the address: what it pays
 * more counts at its own time, as any other payment would.
 */
public final class Replacements {
  private Replacements() {}

  /**
   * A transaction paying the address, as the parts are made of it.
   *
   * @param txid its id
   * @param sats what it pays the address
   * @param inputs the coins it spends ({@code <txid>:<vout>}), or those recorded of it
   * @param order when it was seen (a witness's {@code created_at}); null: unknown, after every
   *     known one
   * @param listed whether the explorer lists it now
   */
  public record PaymentTx(
      String txid, long sats, List<String> inputs, Long order, boolean listed) {}

  /**
   * A part of a listed transaction: sats it pays as its {@code root} (itself, for a part of its
   * own).
   *
   * @param sats the sats
   * @param root the root's txid
   */
  public record Part(long sats, String root) {}

  private static final Comparator<PaymentTx> ORDER =
      (a, b) -> {
        if (a.order() == null ? b.order() != null : !a.order().equals(b.order())) {
          if (a.order() == null) return 1;
          if (b.order() == null) return -1;
          return Long.compare(a.order(), b.order());
        }
        return a.txid().compareTo(b.txid());
      };

  /**
   * The parts of each listed transaction, by txid: a txid given twice is taken once (its first
   * entry); the transactions in order (unknown last, then by txid); the roots of each are those
   * before it that spend one of its coins (their roots, put together), else itself; each root gives
   * at most its sats, in that order, then what a transaction still pays is a part of its own.
   *
   * @param txs the transactions
   * @return the parts of each listed one
   */
  public static Map<String, List<Part>> paymentParts(List<PaymentTx> txs) {
    Map<String, PaymentTx> unique = new LinkedHashMap<>();
    for (PaymentTx tx : txs) unique.putIfAbsent(tx.txid(), tx);
    List<PaymentTx> sorted = new ArrayList<>(unique.values());
    sorted.sort(ORDER);
    Map<String, Integer> position = new HashMap<>();
    for (int i = 0; i < sorted.size(); i += 1) position.put(sorted.get(i).txid(), i);
    Map<String, List<String>> roots = new HashMap<>();
    for (int index = 0; index < sorted.size(); index += 1) {
      PaymentTx tx = sorted.get(index);
      Set<String> coins = new HashSet<>(tx.inputs());
      List<PaymentTx> before = new ArrayList<>();
      for (PaymentTx other : sorted.subList(0, index)) {
        for (String coin : other.inputs()) {
          if (coins.contains(coin)) {
            before.add(other);
            break;
          }
        }
      }
      if (before.isEmpty()) {
        roots.put(tx.txid(), List.of(tx.txid()));
        continue;
      }
      Set<String> together = new LinkedHashSet<>();
      for (PaymentTx other : before) together.addAll(roots.get(other.txid()));
      List<String> list = new ArrayList<>(together);
      list.sort(Comparator.comparingInt(position::get));
      roots.put(tx.txid(), list);
    }
    Map<String, Long> toGive = new HashMap<>();
    for (PaymentTx tx : sorted) toGive.put(tx.txid(), tx.sats());
    Map<String, List<Part>> parts = new LinkedHashMap<>();
    for (PaymentTx tx : sorted) {
      if (!tx.listed()) continue;
      List<String> own = roots.get(tx.txid());
      long owed = tx.sats();
      List<Part> list = new ArrayList<>();
      if (own.size() == 1 && own.get(0).equals(tx.txid())) {
        // A payment of its own: all its sats, which it no longer has to give.
        toGive.put(tx.txid(), 0L);
      } else {
        for (String root : own) {
          long left = toGive.get(root);
          long take = Math.min(owed, left);
          if (take > 0) {
            list.add(new Part(take, root));
            toGive.put(root, left - take);
            owed -= take;
          }
        }
      }
      if (owed > 0) list.add(new Part(owed, tx.txid()));
      parts.put(tx.txid(), List.copyOf(list));
    }
    return parts;
  }
}
