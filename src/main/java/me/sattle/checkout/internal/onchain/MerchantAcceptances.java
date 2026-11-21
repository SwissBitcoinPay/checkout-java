package me.sattle.checkout.internal.onchain;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import me.sattle.checkout.NetworkException;
import me.sattle.checkout.internal.Constants;
import me.sattle.checkout.internal.Derive;
import me.sattle.checkout.internal.RandomBytes;
import me.sattle.checkout.internal.Tasks;
import me.sattle.checkout.internal.json.Json;
import me.sattle.checkout.internal.json.JsonArray;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.internal.nostr.NostrEvent;
import me.sattle.checkout.internal.nostr.Records;
import me.sattle.checkout.internal.nostr.RelayPool;

/**
 * The merchant's acceptance (SPEC.md §31, "The merchant's acceptance"): the merchant may accept by
 * hand, in its app, what an order underpaid on-chain received as paying it in full. The app
 * publishes a record of the team (the integration key opens it but cannot make one) holding what
 * the order had received then; a check judges the payment against that amount when it is less than
 * the least the order's tolerance accepts ({@link Tolerance#dueOnchain}). The majority of the
 * merchant's relays holds the truth: a check reads it on every relay and counts a record only when
 * a majority of the relays sent it. A check never publishes one, nor publishes one again: the pages
 * of the order's checkout links learn of it only through an acceptance record ({@link
 * Acceptances}).
 */
public final class MerchantAcceptances {
  private MerchantAcceptances() {}

  /**
   * What a check opens the merchant's acceptance of an invoice with.
   *
   * @param team the team's public key (hex): its only author
   * @param encryptionKey the channel's encryption key: its NIP-44 conversation key
   * @param address its {@code d} tag ({@link Derive#merchantAcceptanceAddress})
   */
  public record Keys(String team, byte[] encryptionKey, String address) {
    /**
     * The keys of the merchant's acceptance of the invoice of the sale {@code saleAuthor} with the
     * deposit address {@code address} ending at {@code expiresAt}, for the integration {@code
     * integrationKey} of {@code team}.
     *
     * @param integrationKey the integration key
     * @param team the team's public key (hex)
     * @param saleAuthor the order's sale author (hex)
     * @param address the invoice's deposit address
     * @param expiresAt the invoice's end
     * @return the keys
     */
    public static Keys of(
        byte[] integrationKey, String team, String saleAuthor, String address, long expiresAt) {
      return new Keys(
          team,
          Derive.channelEncryptionKey(integrationKey),
          Derive.merchantAcceptanceAddress(integrationKey, saleAuthor, address, expiresAt));
    }

    @Override
    public String toString() {
      return "Keys[team=" + team + ", address=" + address + "]";
    }
  }

  /** What a merchant's acceptance a relay sent is, opened. */
  public enum Status {
    /** Valid: what the order had received when the merchant accepted it. */
    VALID,
    /**
     * Of a newer format: warned of; the newest a majority sent, it leaves the order without one.
     */
    NEWER,
    /**
     * Of a newer format, dated after the lease's end: set aside as any record dated so, warned of.
     */
    NEWER_SET_ASIDE
  }

  /**
   * A merchant's acceptance a relay sent, opened ({@link #openMerchantAcceptance}).
   *
   * @param status valid, or of a newer format
   * @param event its event, checked
   * @param received what the order had received when the merchant accepted it; 0 for a newer format
   */
  public record Opened(Status status, NostrEvent event, long received) {}

  /**
   * What a relay sent, read as the merchant's acceptance of the invoice of {@code sats} ending at
   * {@code expiresAt} (SPEC.md §31, "Reading it"): a record of type {@code
   * integration-onchain-accepted}, author the team, sealed with the channel's key, at the address
   * of {@code keys}, that checks out (its id, its signature); valid when its value is an object
   * whose {@code version} is 1 and whose {@code received} is a safe integer from 1, below {@code
   * sats} (more fields ignored); of a newer format when its {@code version} is above 1 or its
   * content of a newer NIP-44 version. Null for anything else, and for a record dated after the
   * lease's end ({@code expiresAt} + 120), which is set aside whatever its format: one of a newer
   * format is still told, for the warning ({@link Status#NEWER_SET_ASIDE}). Its {@code created_at}
   * is never compared with now.
   *
   * @param value what a relay sent
   * @param keys the keys of the invoice's merchant's acceptance
   * @param sats the invoice's amount
   * @param expiresAt the invoice's end
   * @return the record opened, or null
   */
  public static Opened openMerchantAcceptance(
      JsonValue value, Keys keys, long sats, long expiresAt) {
    Records.Opened<Long> opened =
        Records.open(
            value,
            Constants.MERCHANT_ACCEPTANCE_RECORD_TYPE,
            keys.encryptionKey(),
            keys.address(),
            keys.team(),
            (v, event) -> {
              JsonObject object = Json.object(v);
              if (object == null) return null;
              if (Json.isNumberAbove(
                  object.get("version"), Constants.MERCHANT_ACCEPTANCE_VERSION)) {
                throw new Records.NewerFormat();
              }
              if (!Json.isNumber(object.get("version"), Constants.MERCHANT_ACCEPTANCE_VERSION)) {
                return null;
              }
              Long received = Json.safeInteger(object.get("received"));
              if (received == null || received < 1) return null;
              return received < sats ? received : null;
            });
    if (opened == null) return null;
    // After the lease's end: set aside, of a newer format or not (SPEC.md §31, "Reading it").
    boolean late = opened.event().createdAt() > expiresAt + Constants.GRACE_SECONDS;
    if (opened.isNewer()) {
      return new Opened(late ? Status.NEWER_SET_ASIDE : Status.NEWER, opened.event(), 0);
    }
    return late ? null : new Opened(Status.VALID, opened.event(), opened.value());
  }

  /**
   * The merchant's acceptance of an invoice, as a check counts it.
   *
   * @param received the {@code received} of the merchant's acceptance that counts; null without one
   * @param newer whether a relay sent one of a newer format, whatever its date and how many relays
   *     sent it
   */
  public record Accepted(Long received, boolean newer) {
    /** No merchant's acceptance, none of a newer format: what a check that reads none counts. */
    public static final Accepted NONE = new Accepted(null, false);
  }

  /**
   * The merchant's acceptance of the invoice of {@code sats} ending at {@code expiresAt} (SPEC.md
   * §31, "Counting it"), from what each relay of the merchant's list ({@code relayCount} relays)
   * sent to its read on every relay ({@code answers}, by relay): every copy opened ({@link
   * #openMerchantAcceptance}), once per id and signature; a relay sent a record when it sent a copy
   * of it that opens. Of the records valid or of a newer format that a majority of the list sent,
   * the newest (the highest {@code created_at}, then the lowest id, as relays keep them) decides: a
   * valid one is the merchant's acceptance, one of a newer format leaves the order without one. One
   * that fewer than a majority sent never counts, whichever relays sent it.
   *
   * @param answers what each relay sent, by relay
   * @param keys the keys of the invoice's merchant's acceptance
   * @param sats the invoice's amount
   * @param expiresAt the invoice's end
   * @param relayCount how many relays the merchant's list holds
   * @return the merchant's acceptance that counts, and whether one of a newer format was sent
   */
  public static Accepted merchantAcceptanceOf(
      Map<String, List<JsonValue>> answers, Keys keys, long sats, long expiresAt, int relayCount) {
    // Each copy opened once, by id and signature, whatever relays send it.
    Map<String, Opened> copies = new HashMap<>();
    // The records that count if enough relays sent them, by id, and how many relays sent each.
    Map<String, Opened> records = new LinkedHashMap<>();
    Map<String, Integer> sentBy = new HashMap<>();
    boolean newer = false;
    for (List<JsonValue> events : answers.values()) {
      Set<String> sent = new HashSet<>();
      for (JsonValue value : events) {
        Opened opened = openCopy(value, keys, sats, expiresAt, copies);
        if (opened == null) continue;
        if (opened.status() != Status.VALID) newer = true;
        if (opened.status() == Status.NEWER_SET_ASIDE) continue;
        String id = opened.event().id();
        // One relay counts once for a record, whatever copies of it it sent.
        if (!sent.add(id)) continue;
        records.putIfAbsent(id, opened);
        sentBy.merge(id, 1, Integer::sum);
      }
    }
    int needed = RelayPool.majority(relayCount);
    Opened newest = null;
    for (Opened opened : records.values()) {
      if (sentBy.get(opened.event().id()) < needed) continue;
      if (newest == null
          || Records.replaces(
              opened.event().createdAt(),
              opened.event().id(),
              newest.event().createdAt(),
              newest.event().id())) {
        newest = opened;
      }
    }
    Long received = newest != null && newest.status() == Status.VALID ? newest.received() : null;
    return new Accepted(received, newer);
  }

  /** A copy opened, once per id and signature ({@code copies}). */
  private static Opened openCopy(
      JsonValue value, Keys keys, long sats, long expiresAt, Map<String, Opened> copies) {
    JsonObject object = Json.object(value);
    String id = object == null ? null : Json.string(object.get("id"));
    String sig = object == null ? null : Json.string(object.get("sig"));
    if (id == null || sig == null) return openMerchantAcceptance(value, keys, sats, expiresAt);
    String copy = id + "/" + sig;
    if (!copies.containsKey(copy)) {
      copies.put(copy, openMerchantAcceptance(value, keys, sats, expiresAt));
    }
    return copies.get(copy);
  }

  /**
   * The merchant's acceptance of the invoice of {@code sats} ending at {@code expiresAt} ({@link
   * #merchantAcceptanceOf}), read on every relay of the merchant's {@code relays} (SPEC.md §13:
   * once every relay answered or failed, or one timeout after a majority answered), not paged, with
   * a key of this read alone, drawn at once. Publishes nothing.
   *
   * @param pool the call's relays
   * @param keys the keys of the invoice's merchant's acceptance
   * @param sats the invoice's amount
   * @param expiresAt the invoice's end
   * @param relays the merchant's relays
   * @param random the CSPRNG
   * @return completes with the merchant's acceptance; a {@link NetworkException} when fewer than a
   *     majority answered; cancelling it ends the read
   */
  public static CompletableFuture<Accepted> readMerchantAcceptance(
      RelayPool pool,
      Keys keys,
      long sats,
      long expiresAt,
      List<String> relays,
      RandomBytes random) {
    JsonObject filter =
        new JsonObject()
            .put("kinds", new JsonArray().add(Constants.RECORD_KIND))
            .put("authors", JsonArray.ofStrings(List.of(keys.team())))
            .put("#d", JsonArray.ofStrings(List.of(keys.address())));
    CompletableFuture<RelayPool.Answers> read =
        pool.queryRelays(
            relays,
            filter,
            random.next(32),
            RelayPool.majority(relays.size()),
            false,
            Records.recordsThatOpen((d, author) ->
                author.equals(keys.team()) && keys.address().equals(d)
                    ? keys.encryptionKey()
                    : null),
            true);
    return Tasks.map(
        read,
        answers -> {
          if (!answers.outcome().answered()) {
            throw new NetworkException("Fewer than a majority of the merchant's relays answered");
          }
          return merchantAcceptanceOf(
              Records.asValues(answers.events()), keys, sats, expiresAt, relays.size());
        });
  }
}
