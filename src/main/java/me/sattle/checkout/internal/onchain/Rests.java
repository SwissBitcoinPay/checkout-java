package me.sattle.checkout.internal.onchain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.sattle.checkout.NetworkException;
import me.sattle.checkout.PaymentStatus;
import me.sattle.checkout.internal.Constants;
import me.sattle.checkout.internal.Derive;
import me.sattle.checkout.internal.RandomBytes;
import me.sattle.checkout.internal.Urls;
import me.sattle.checkout.internal.Warn;
import me.sattle.checkout.internal.json.Json;
import me.sattle.checkout.internal.json.JsonArray;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.internal.lightning.Bolt11;
import me.sattle.checkout.internal.nostr.NostrEvent;
import me.sattle.checkout.internal.nostr.Quorum;
import me.sattle.checkout.internal.nostr.Records;
import me.sattle.checkout.internal.nostr.RelayPool;

/**
 * The rest of an on-chain underpayment (SPEC.md §31, "The rest of an underpayment"): when less than
 * the amount came on-chain in time, a payment check asks the merchant's Lightning address for an
 * invoice of the rest, declares it for the order's sale with {@code rest} (the address), and
 * publishes a rest record, which the pages of the order's checkout links read (they cannot read the
 * registry). From 1 000 sats, the rest is payable on-chain to the same address too; below, in
 * Lightning only.
 */
public final class Rests {
  private Rests() {}

  /**
   * A rest invoice: a declaration of the sale with {@code rest}, its invoice decoded.
   *
   * @param bolt11 the invoice
   * @param paymentHash its payment hash
   * @param verifyUrl its verify URL
   * @param sats its amount
   * @param createdAt its BOLT11 timestamp
   * @param expiresAt that + its expiry
   */
  public record RestInvoice(
      String bolt11,
      String paymentHash,
      String verifyUrl,
      long sats,
      long createdAt,
      long expiresAt) {}

  /** The order of the rest invoices: by date, then payment hash. */
  public static final Comparator<RestInvoice> REST_ORDER =
      Comparator.comparingLong(RestInvoice::createdAt).thenComparing(RestInvoice::paymentHash);

  /**
   * The {@code rest} of an open status (SPEC.md §31, "The status's rest"): null unless {@code
   * received} is above 0 before the invoice's end; else what is left, the latest rest invoice of
   * exactly that amount neither paid nor ended, and its QR code.
   *
   * @param sats the invoice's amount
   * @param expiresAt its end
   * @param address its deposit address
   * @param received what came in time
   * @param now Unix seconds
   * @param rests its rest invoices whose record is known on a majority
   * @param paid the payment hashes paid
   * @return the rest, or null
   */
  public static PaymentStatus.Rest restOf(
      long sats,
      long expiresAt,
      String address,
      long received,
      long now,
      List<RestInvoice> rests,
      Set<String> paid) {
    if (received <= 0 || received >= sats || now >= expiresAt) return null;
    long rest = sats - received;
    List<RestInvoice> sorted = new ArrayList<>(rests);
    sorted.sort(REST_ORDER.reversed());
    RestInvoice invoice = null;
    for (RestInvoice candidate : sorted) {
      if (candidate.sats() == rest
          && !paid.contains(candidate.paymentHash())
          && now < candidate.expiresAt()) {
        invoice = candidate;
        break;
      }
    }
    String bolt11 = invoice == null ? null : invoice.bolt11();
    String paymentUri =
        rest >= Constants.ONCHAIN_FLOOR_SATS
            ? Addresses.paymentUri(address, rest, bolt11)
            : bolt11 == null ? null : "lightning:" + bolt11;
    return new PaymentStatus.Rest(Long.toString(rest), bolt11, paymentUri);
  }

  /**
   * What a check asks for: an invoice of {@code sats}, lasting {@code expirySeconds}.
   *
   * @param sats the rest
   * @param expirySeconds its expiry
   */
  public record RestAsk(long sats, long expirySeconds) {}

  /**
   * Whether a check whose status gave {@code rest} asks for an invoice of it (SPEC.md §31, "Asking
   * for a rest invoice"), and which: none when it has one, with less than 60 s left, or when the
   * sale's lease of the address is not the invoice's.
   *
   * @param rest the status's rest, or null
   * @param sats the invoice's amount
   * @param expiresAt its end
   * @param now Unix seconds
   * @param leaseSats the sale's lease's sats, or null without a lease
   * @param leaseExpiresAt the sale's lease's end, or null without a lease
   * @return what to ask, or null
   */
  public static RestAsk restToAsk(
      PaymentStatus.Rest rest,
      long sats,
      long expiresAt,
      long now,
      Long leaseSats,
      Long leaseExpiresAt) {
    if (rest == null || rest.bolt11() != null) return null;
    if (now + Constants.MIN_REST_SECONDS > expiresAt) return null;
    if (leaseSats == null
        || leaseSats != sats
        || leaseExpiresAt == null
        || leaseExpiresAt != expiresAt) {
      return null;
    }
    return new RestAsk(Long.parseLong(rest.sats()), expiresAt - now);
  }

  /**
   * Whether {@code sats} is within the Lightning address's sendable sats.
   *
   * @param sats the amount
   * @param min the least
   * @param max the most
   * @return true when within
   */
  public static boolean sendable(long sats, long min, long max) {
    return sats >= min && sats <= max;
  }

  /**
   * The rest record of an invoice (SPEC.md §31, "Rest records"): its invoice and verify URL, sealed
   * with the rest encryption key at the rest record address of its payment hash, signed by the rest
   * secret at {@code createdAt}, dropped by the relays a week after the end of the order's invoice.
   *
   * @param keys the rest keys
   * @param bolt11 the rest invoice
   * @param verifyUrl its verify URL
   * @param paymentHash its payment hash
   * @param expiresAt the order's invoice's end
   * @param createdAt Unix seconds
   * @param random the CSPRNG
   * @return the event
   */
  public static NostrEvent restRecordEvent(
      Derive.RestKeys keys,
      String bolt11,
      String verifyUrl,
      String paymentHash,
      long expiresAt,
      long createdAt,
      RandomBytes random) {
    return Records.seal(
        Constants.REST_RECORD_TYPE,
        new JsonObject()
            .put("version", Constants.REST_VERSION)
            .put("invoice", bolt11)
            .put("verifyUrl", verifyUrl),
        keys.secretKey(),
        keys.encryptionKey(),
        Derive.restRecordAddress(keys.key(), paymentHash),
        createdAt,
        List.of(List.of("expiration", Long.toString(expiresAt + Constants.CHECKOUT_LIFE_SECONDS))),
        random);
  }

  /**
   * A rest record, opened as the page reads it: its invoice, decoded.
   *
   * @param invoice the rest invoice; null for one of a newer format
   * @param event its event
   */
  public record OpenedRest(RestInvoice invoice, NostrEvent event) {
    /**
     * Whether it is of a newer format, which the page leaves aside.
     *
     * @return true for a newer format
     */
    public boolean isNewer() {
      return invoice == null;
    }
  }

  /**
   * What a relay sent, if it is a rest record of the rest author and rest key of a checkout link of
   * an invoice of {@code sats}, {@code createdAt} and {@code expiresAt} (SPEC.md §31, "Reading a
   * rest record"). An event at the link's acceptance record address is no rest record.
   *
   * @param value what a relay sent
   * @param keys the rest keys (the secret not needed)
   * @param sats the link's sats
   * @param createdAt the link's date
   * @param expiresAt the link's end
   * @return the record, or null
   */
  public static OpenedRest openRestRecord(
      JsonValue value, Derive.RestKeys keys, long sats, long createdAt, long expiresAt) {
    String d = Records.dTagOf(value);
    if (d == null || d.equals(Derive.acceptanceRecordAddress(keys.key()))) return null;
    Records.Opened<RestInvoice> opened =
        Records.open(
            value,
            Constants.REST_RECORD_TYPE,
            keys.encryptionKey(),
            d,
            keys.author(),
            (v, event) -> {
              JsonObject object = Json.object(v);
              if (object == null) return null;
              if (Json.isNumberAbove(object.get("version"), Constants.REST_VERSION)) {
                throw new Records.NewerFormat();
              }
              String invoice = Json.string(object.get("invoice"));
              if (!Json.isNumber(object.get("version"), Constants.REST_VERSION)
                  || invoice == null) {
                return null;
              }
              String verifyUrl = Json.string(object.get("verifyUrl"));
              if (verifyUrl == null || Urls.parseHttpsUrl(verifyUrl) == null) return null;
              Bolt11 decoded = Bolt11.decode(invoice);
              if (decoded == null
                  || !decoded.network().equals("bc")
                  || decoded.wholeSats() == null) {
                return null;
              }
              long restSats = decoded.wholeSats();
              long end = decoded.expiresAt();
              if (restSats >= sats
                  || decoded.timestamp() < createdAt - Constants.CLOCK_TOLERANCE_SECONDS
                  || end > expiresAt + Constants.CLOCK_TOLERANCE_SECONDS) {
                return null;
              }
              if (!d.equals(Derive.restRecordAddress(keys.key(), decoded.paymentHash())))
                return null;
              return new RestInvoice(
                  invoice, decoded.paymentHash(), verifyUrl, restSats, decoded.timestamp(), end);
            });
    if (opened == null) return null;
    return new OpenedRest(opened.value(), opened.event());
  }

  /**
   * What the merchant's relays hold of an invoice's rest records.
   *
   * @param held the payment hashes of the rest records a majority of the relays sent
   * @param newer the record addresses ({@code d} tags) where a relay sent a rest record of a newer
   *     format, its event checked: it holds its address, never written over (SPEC.md §12)
   */
  public record RestRecords(Set<String> held, Set<String> newer) {}

  /**
   * The rest records of {@code keys} on the merchant's relays, as the page would read them: those a
   * majority sent, and the addresses records of a newer format hold (logged). Read with a key of
   * this read alone, not paged.
   *
   * @param pool the call's relays
   * @param keys the rest keys
   * @param sats the invoice's sats
   * @param createdAt the least date (a bound of the page's read)
   * @param expiresAt the invoice's end
   * @param relays the merchant's relays
   * @param random the CSPRNG
   * @param logger where warnings go
   * @return the records, or null when fewer than a majority answered
   * @throws InterruptedException when the call is cancelled
   */
  public static RestRecords readRestRecords(
      RelayPool pool,
      Derive.RestKeys keys,
      long sats,
      long createdAt,
      long expiresAt,
      List<String> relays,
      RandomBytes random,
      Warn logger)
      throws InterruptedException {
    int needed = RelayPool.majority(relays.size());
    JsonObject filter =
        new JsonObject()
            .put("kinds", new JsonArray().add(Constants.RECORD_KIND))
            .put("authors", JsonArray.ofStrings(List.of(keys.author())));
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
    if (!read.outcome().answered()) return null;
    Map<String, List<Object>> answers = read.events();
    Map<String, Integer> sentBy = new LinkedHashMap<>();
    Set<String> newer = new HashSet<>();
    for (List<Object> events : answers.values()) {
      Set<String> hashes = new HashSet<>();
      for (Object value : events) {
        OpenedRest opened = openRestRecord((JsonValue) value, keys, sats, createdAt, expiresAt);
        if (opened == null) continue;
        if (opened.isNewer()) newer.add(opened.event().tag("d"));
        else hashes.add(opened.invoice().paymentHash());
      }
      for (String hash : hashes) sentBy.merge(hash, 1, Integer::sum);
    }
    if (!newer.isEmpty()) {
      logger.warn(
          Warn.PREFIX
              + "a record of an invoice of the rest has a newer format than this version reads;"
              + " update the package (it is never written over)");
    }
    Set<String> held = new HashSet<>();
    for (Map.Entry<String, Integer> entry : sentBy.entrySet()) {
      if (entry.getValue() >= needed) held.add(entry.getKey());
    }
    return new RestRecords(held, newer);
  }

  /**
   * Publishes a rest record on the merchant's relays, signed and authenticated with the rest
   * secret, waiting until a majority took it.
   *
   * @param pool the call's relays
   * @param event the record
   * @param keys the rest keys
   * @param relays the merchant's relays
   * @throws InterruptedException when the call is cancelled
   * @throws NetworkException when fewer than a majority took it
   */
  public static void publishRestRecord(
      RelayPool pool, NostrEvent event, Derive.RestKeys keys, List<String> relays)
      throws InterruptedException {
    Quorum.publish(
        pool,
        relays,
        event,
        keys.secretKey(),
        "Fewer than a majority of the merchant's relays took the record of an invoice of the rest");
  }
}
