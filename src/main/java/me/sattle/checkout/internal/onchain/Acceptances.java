package me.sattle.checkout.internal.onchain;

import java.util.List;
import java.util.Map;
import me.sattle.checkout.NetworkException;
import me.sattle.checkout.PaymentStatus;
import me.sattle.checkout.internal.Constants;
import me.sattle.checkout.internal.Derive;
import me.sattle.checkout.internal.RandomBytes;
import me.sattle.checkout.internal.Warn;
import me.sattle.checkout.internal.json.Json;
import me.sattle.checkout.internal.json.JsonArray;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.internal.nostr.NostrEvent;
import me.sattle.checkout.internal.nostr.Quorum;
import me.sattle.checkout.internal.nostr.Records;
import me.sattle.checkout.internal.nostr.RelayPool;

/**
 * Acceptance records (SPEC.md §31, "Acceptance records"): the page of a checkout link cannot know
 * the merchant's tolerance nor read the merchant's acceptance ({@link MerchantAcceptances}), so an
 * order either accepted would read underpaid there, its customer asked for the rest and never sent
 * back to the shop. A check whose status is paid (or being confirmed) with less than the amount
 * publishes what it received then, signed by the rest secret, for the pages of the order's checkout
 * links. Never the tolerance itself.
 */
public final class Acceptances {
  private Acceptances() {}

  /**
   * What a check whose status is {@code status} must have the pages of the order's checkout links
   * know (SPEC.md §31, "Publishing it"): the status's {@code received}, when the order's tolerance
   * or the merchant's acceptance accepted less than its amount ({@code unconfirmed}, or {@code
   * paid} with transactions) before the links' life ends.
   *
   * @param status the status
   * @param sats the invoice's amount
   * @param expiresAt its end
   * @param now Unix seconds
   * @return what to publish, or null
   */
  public static Long acceptanceToPublish(
      PaymentStatus status, long sats, long expiresAt, long now) {
    if (now >= expiresAt + Constants.CHECKOUT_LIFE_SECONDS) return null;
    String received;
    if (status instanceof PaymentStatus.Unconfirmed unconfirmed) {
      received = unconfirmed.received();
    } else if (status instanceof PaymentStatus.PaidOnchain paid && !paid.txids().isEmpty()) {
      received = paid.received();
    } else if (status instanceof PaymentStatus.PaidMixed paid && !paid.txids().isEmpty()) {
      received = paid.received();
    } else {
      return null;
    }
    long value = Long.parseLong(received);
    return value < sats ? value : null;
  }

  /**
   * The acceptance record of the invoice of {@code keys}: {@code received}, sealed with the rest
   * encryption key at its acceptance record address, signed by the rest secret at {@code
   * createdAt}, dropped by the relays a week after the invoice's end.
   *
   * @param keys the invoice's rest keys
   * @param received what was received
   * @param expiresAt the invoice's end
   * @param createdAt Unix seconds
   * @param random the CSPRNG
   * @return the event
   */
  public static NostrEvent acceptanceRecordEvent(
      Derive.RestKeys keys, long received, long expiresAt, long createdAt, RandomBytes random) {
    return Records.seal(
        Constants.ACCEPTANCE_RECORD_TYPE,
        new JsonObject().put("version", Constants.ACCEPTANCE_VERSION).put("received", received),
        keys.secretKey(),
        keys.encryptionKey(),
        Derive.acceptanceRecordAddress(keys.key()),
        createdAt,
        List.of(List.of("expiration", Long.toString(expiresAt + Constants.CHECKOUT_LIFE_SECONDS))),
        random);
  }

  /**
   * What a relay sent, opened as an acceptance record of {@code keys} for an invoice of {@code
   * sats} (SPEC.md §31, "Reading an acceptance record"): its {@code received}, from 1, below {@code
   * sats}.
   *
   * @param value what a relay sent
   * @param keys the rest keys (the secret not needed)
   * @param sats the invoice's amount
   * @return the record (its value null for a newer format), or null when it is not one
   */
  public static Records.Opened<Long> openAcceptanceRecord(
      JsonValue value, Derive.RestKeys keys, long sats) {
    String address = Derive.acceptanceRecordAddress(keys.key());
    if (!address.equals(Records.dTagOf(value))) return null;
    return Records.open(
        value,
        Constants.ACCEPTANCE_RECORD_TYPE,
        keys.encryptionKey(),
        address,
        keys.author(),
        (v, event) -> {
          JsonObject object = Json.object(v);
          if (object == null) return null;
          if (Json.isNumberAbove(object.get("version"), Constants.ACCEPTANCE_VERSION)) {
            throw new Records.NewerFormat();
          }
          if (!Json.isNumber(object.get("version"), Constants.ACCEPTANCE_VERSION)) return null;
          Long received = Json.safeInteger(object.get("received"));
          if (received == null || received < 1) return null;
          return received < sats ? received : null;
        });
  }

  /**
   * Whether the pages already know {@code received} (SPEC.md §31, "Publishing it"): a majority of
   * the merchant's relays each sent a valid acceptance record whose {@code received}, the newest it
   * sent, is at most {@code received}. True when a relay sent one of a newer format, never written
   * over. {@link NetworkException} when fewer than a majority answered: what the others hold (of a
   * newer format, perhaps) cannot be told, so nothing is published; the next check reads again.
   *
   * @param pool the call's relays
   * @param keys the invoice's rest keys
   * @param sats the invoice's amount
   * @param received what was received
   * @param relays the merchant's relays
   * @param random the CSPRNG
   * @param logger where the warning of a record of a newer format goes
   * @return true when known
   * @throws InterruptedException when the call is cancelled
   * @throws NetworkException when fewer than a majority of the relays answered
   */
  public static boolean acceptanceKnown(
      RelayPool pool,
      Derive.RestKeys keys,
      long sats,
      long received,
      List<String> relays,
      RandomBytes random,
      Warn logger)
      throws InterruptedException {
    int needed = RelayPool.majority(relays.size());
    JsonObject filter =
        new JsonObject()
            .put("kinds", new JsonArray().add(Constants.RECORD_KIND))
            .put("authors", JsonArray.ofStrings(List.of(keys.author())))
            .put("#d", JsonArray.ofStrings(List.of(Derive.acceptanceRecordAddress(keys.key()))));
    RelayPool.Answers read =
        Quorum.query(
            pool,
            relays,
            filter,
            random.next(32),
            false,
            Records.recordsThatOpen(
                (d, author) -> author.equals(keys.author()) ? keys.encryptionKey() : null),
            false);
    Map<String, List<Object>> answers = read.events();
    int holders = 0;
    for (Map.Entry<String, List<JsonValue>> relay : Records.asValues(answers).entrySet()) {
      Records.Newest<Long> found =
          Records.newest(
              Map.of(relay.getKey(), relay.getValue()),
              value -> openAcceptanceRecord(value, keys, sats));
      if (found.newestUnreadable() != null) {
        logger.warn(
            Warn.PREFIX
                + "the acceptance record of a payment has a newer format than this version reads;"
                + " update the package (it is never written over)");
        return true;
      }
      if (found.newest() != null && found.newest().value() <= received) holders += 1;
    }
    // Fewer than a majority answered: such a read decides nothing (SPEC.md §13), and what the
    // others hold is never written over unseen (§12).
    if (!read.outcome().answered()) {
      throw new NetworkException("Fewer than a majority of the merchant's relays answered");
    }
    return holders >= needed;
  }

  /**
   * When the order's tolerance or the merchant's acceptance accepted less than its amount, has the
   * pages of its checkout links know it (SPEC.md §31, "Acceptance records", "Publishing it"): the
   * record of what {@code status} received, unless the relays know it already ({@link
   * #acceptanceKnown}); either failure (fewer than a majority answering the read, or taking the
   * record) logged as a warning, never the check's error: the next check reads and publishes again.
   *
   * @param pool the call's relays
   * @param status the invoice's status, as the check found it
   * @param sats the invoice's amount
   * @param expiresAt the invoice's end
   * @param keys the invoice's rest keys, made when needed
   * @param relays the merchant's relays
   * @param now Unix seconds
   * @param random the CSPRNG
   * @param logger where warnings go
   * @throws InterruptedException when the call is cancelled
   */
  public static void publishAcceptance(
      RelayPool pool,
      PaymentStatus status,
      long sats,
      long expiresAt,
      java.util.function.Supplier<Derive.RestKeys> keys,
      List<String> relays,
      java.util.function.LongSupplier now,
      RandomBytes random,
      Warn logger)
      throws InterruptedException {
    Long received = acceptanceToPublish(status, sats, expiresAt, now.getAsLong());
    if (received == null) return;
    Derive.RestKeys restKeys = keys.get();
    try {
      if (acceptanceKnown(pool, restKeys, sats, received, relays, random, logger)) return;
      publishAcceptanceRecord(
          pool,
          acceptanceRecordEvent(restKeys, received, expiresAt, now.getAsLong(), random),
          restKeys,
          relays);
    } catch (NetworkException e) {
      logger.warn(
          Warn.PREFIX
              + "the acceptance record of a payment was not published ("
              + e.getMessage()
              + "); published again at the next check");
    }
  }

  /**
   * Publishes an acceptance record on the merchant's relays, signed and authenticated with the rest
   * secret, waiting until a majority took it.
   *
   * @param pool the call's relays
   * @param event the record
   * @param keys the rest keys
   * @param relays the merchant's relays
   * @throws InterruptedException when the call is cancelled
   * @throws NetworkException when fewer than a majority took it
   */
  public static void publishAcceptanceRecord(
      RelayPool pool, NostrEvent event, Derive.RestKeys keys, List<String> relays)
      throws InterruptedException {
    Quorum.publish(
        pool,
        relays,
        event,
        keys.secretKey(),
        "Fewer than a majority of the merchant's relays took the acceptance record of a payment");
  }
}
