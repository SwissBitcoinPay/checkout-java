package me.sattle.checkout.internal.onchain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import me.sattle.checkout.NetworkException;
import me.sattle.checkout.internal.Constants;
import me.sattle.checkout.internal.Derive;
import me.sattle.checkout.internal.Hex;
import me.sattle.checkout.internal.RandomBytes;
import me.sattle.checkout.internal.Text;
import me.sattle.checkout.internal.Warn;
import me.sattle.checkout.internal.crypto.Hashes;
import me.sattle.checkout.internal.json.Json;
import me.sattle.checkout.internal.json.JsonArray;
import me.sattle.checkout.internal.json.JsonBoolean;
import me.sattle.checkout.internal.json.JsonNull;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.internal.nostr.Events;
import me.sattle.checkout.internal.nostr.NostrEvent;
import me.sattle.checkout.internal.nostr.Quorum;
import me.sattle.checkout.internal.nostr.Records;
import me.sattle.checkout.internal.nostr.RelayPool;

/**
 * Witnesses (SPEC.md §31, "Witnesses"): the integration records each transaction it sees paying an
 * address of its lot, and when: during the lease of an order (that order's sale), or at no lease's
 * time ({@code none}). So that every reader, the merchant's app included, tells the same order
 * paid. Only the integration writes them (signed by its seen secret, sealed with its channel's
 * key); the merchant's app reads them to attribute deposits and to take paid addresses out of the
 * lot.
 */
public final class Witnesses {
  private Witnesses() {}

  private static String mac(byte[] key, String text) {
    return Hex.encode(Hashes.hmacSha256(key, Text.utf8(text)));
  }

  /**
   * Where the witness of {@code txid} paying {@code address} for {@code sale} is.
   *
   * @param addressKey the seen address key
   * @param address the address
   * @param txid the transaction's id
   * @param sale the sale author, or null for none
   * @return the witness address (hex)
   */
  public static String witnessAddress(byte[] addressKey, String address, String txid, String sale) {
    return mac(addressKey, address + "/" + txid + "/" + (sale == null ? Constants.NO_SALE : sale));
  }

  /**
   * The {@code o} tag of the witnesses of {@code address} for {@code sale}: how a check reads them.
   *
   * @param addressKey the seen address key
   * @param address the address
   * @param sale the sale author, or null for none
   * @return the order tag (hex)
   */
  public static String orderTag(byte[] addressKey, String address, String sale) {
    return mac(addressKey, "order/" + address + "/" + (sale == null ? Constants.NO_SALE : sale));
  }

  /**
   * The address tag of {@code address}: a witness's second {@code o} tag, which finds every witness
   * of the address, whatever its sale.
   *
   * @param addressKey the seen address key
   * @param address the address
   * @return the address tag (hex)
   */
  public static String addressTag(byte[] addressKey, String address) {
    return mac(addressKey, "address/" + address);
  }

  private static final Pattern COIN = Pattern.compile("[0-9a-f]{64}:(?:0|[1-9][0-9]{0,9})");

  /**
   * Whether {@code value} is a coin as a witness writes it: {@code <txid>:<vout>}, the vout a u32
   * in decimal without leading zeros.
   *
   * @param value a text, or null
   * @return true when it is one
   */
  public static boolean isCoin(String value) {
    return value != null
        && COIN.matcher(value).matches()
        && Long.parseLong(value.substring(65)) <= 4_294_967_295L;
  }

  /**
   * What a witness says.
   *
   * @param address the address
   * @param txid the transaction
   * @param sale the sale author, or null: at no lease's time
   * @param sats what {@code txid} pays {@code address}
   * @param inputs the coins {@code txid} spends, its first 100, in order
   * @param replacement held for the sale only as a replacement of payments in time
   * @param expiresAt the {@code expiresAt} of the invoice whose lease {@code txid} came in (or
   *     stands for); null for none
   */
  public record Witness(
      String address,
      String txid,
      String sale,
      long sats,
      List<String> inputs,
      boolean replacement,
      Long expiresAt) {
    /**
     * A witness.
     *
     * @param address the address
     * @param txid the transaction
     * @param sale the sale author, or null
     * @param sats the sats
     * @param inputs its coins
     * @param replacement whether it is a replacement's
     * @param expiresAt the invoice's end, or null
     */
    public Witness {
      inputs = List.copyOf(inputs);
    }
  }

  /**
   * The signed event of a witness, {@code created_at} when it was seen.
   *
   * @param seen the seen keys
   * @param witness the witness
   * @param createdAt Unix seconds
   * @param random the CSPRNG
   * @return the event
   */
  public static NostrEvent witnessEvent(
      Derive.SeenKeys seen, Witness witness, long createdAt, RandomBytes random) {
    List<String> inputs = witness.inputs();
    JsonObject value =
        new JsonObject()
            .put("version", Constants.WITNESS_VERSION)
            .put("address", witness.address())
            .put("txid", witness.txid())
            .put("sale", witness.sale())
            .put("sats", witness.sats())
            .put(
                "inputs",
                JsonArray.ofStrings(
                    inputs.subList(0, Math.min(inputs.size(), Constants.MAX_WITNESS_INPUTS))))
            .put("replacement", witness.replacement())
            .put("expiresAt", witness.sale() == null ? null : witness.expiresAt());
    return Records.seal(
        Constants.WITNESS_RECORD_TYPE,
        value,
        seen.secretKey(),
        seen.encryptionKey(),
        witnessAddress(seen.addressKey(), witness.address(), witness.txid(), witness.sale()),
        createdAt,
        List.of(
            List.of(
                Constants.ORDER_TAG,
                orderTag(seen.addressKey(), witness.address(), witness.sale())),
            List.of(Constants.ORDER_TAG, addressTag(seen.addressKey(), witness.address()))),
        random);
  }

  private static boolean hasOrderTag(NostrEvent event, String tag) {
    for (List<String> item : event.tags()) {
      if (item.size() >= 2 && item.get(0).equals(Constants.ORDER_TAG) && item.get(1).equals(tag)) {
        return true;
      }
    }
    return false;
  }

  /** A witness's value, checked against its event's tags; NewerFormat for a newer version. */
  private static Witness parseWitness(JsonValue value, NostrEvent event, byte[] addressKey) {
    JsonObject v = Json.object(value);
    if (v == null) return null;
    if (Json.isNumberAbove(v.get("version"), Constants.WITNESS_VERSION)) {
      throw new Records.NewerFormat();
    }
    if (!Json.isNumber(v.get("version"), Constants.WITNESS_VERSION)) return null;
    String address = Json.string(v.get("address"));
    String txid = Json.string(v.get("txid"));
    if (!Addresses.isDepositAddress(address) || !Text.isHex32(txid)) return null;
    JsonValue saleValue = v.get("sale");
    String sale = null;
    if (saleValue != JsonNull.NULL) {
      sale = Json.string(saleValue);
      if (!Text.isHex32(sale)) return null;
    }
    Long sats = Json.safeInteger(v.get("sats"));
    if (sats == null || sats < 1 || sats > Constants.MAX_SATS) return null;
    JsonArray inputsValue = Json.array(v.get("inputs"));
    if (inputsValue == null || inputsValue.size() > Constants.MAX_WITNESS_INPUTS) return null;
    List<String> inputs = new ArrayList<>();
    Set<String> distinct = new HashSet<>();
    for (JsonValue input : inputsValue.items()) {
      String coin = Json.string(input);
      if (!isCoin(coin) || !distinct.add(coin)) return null;
      inputs.add(coin);
    }
    JsonValue replacementValue = v.get("replacement");
    if (!(replacementValue instanceof JsonBoolean)) return null;
    boolean replacement = replacementValue == JsonBoolean.TRUE;
    if (sale == null && replacement) return null;
    // Null for none, the invoice's end for a sale; absent, it is no witness.
    JsonValue expiresValue = v.get("expiresAt");
    Long expiresAt = null;
    if (sale == null) {
      if (expiresValue != JsonNull.NULL) return null;
    } else {
      expiresAt = Json.safeInteger(expiresValue);
      if (expiresAt == null) return null;
    }
    if (!witnessAddress(addressKey, address, txid, sale).equals(event.tag("d"))
        || !hasOrderTag(event, orderTag(addressKey, address, sale))
        || !hasOrderTag(event, addressTag(addressKey, address))) {
      return null;
    }
    return new Witness(address, txid, sale, sats, inputs, replacement, expiresAt);
  }

  /**
   * A witness a relay sent, opened.
   *
   * @param event its event, checked
   * @param witness what it says; null when it is of a newer format (what it names cannot be read,
   *     its {@code d} tag can)
   * @param d its {@code d} tag
   */
  public record Opened(NostrEvent event, Witness witness, String d) {
    /**
     * Whether it is of a newer format.
     *
     * @return true for a newer format
     */
    public boolean isNewer() {
      return witness == null;
    }
  }

  /**
   * What a relay sent, if it is a witness of this integration.
   *
   * @param value what a relay sent
   * @param seen the seen keys
   * @return the witness, or null
   */
  public static Opened openWitness(JsonValue value, Derive.SeenKeys seen) {
    String d = Records.dTagOf(value);
    if (d == null) return null;
    Records.Opened<Witness> opened =
        Records.open(
            value,
            Constants.WITNESS_RECORD_TYPE,
            seen.encryptionKey(),
            d,
            seen.author(),
            (v, event) -> parseWitness(v, event, seen.addressKey()));
    if (opened == null) return null;
    return new Opened(opened.event(), opened.value(), d);
  }

  /**
   * A witness of the order's sale, as a check uses it.
   *
   * @param sats what it says the transaction pays
   * @param inputs the coins recorded
   * @param replacement whether it is a replacement's
   * @param seenAt its {@code created_at}: when it was seen
   */
  public record SaleWitness(long sats, List<String> inputs, boolean replacement, long seenAt) {}

  /**
   * The witnesses of an invoice: of this invoice (its sale and its {@code expiresAt}), of other
   * invoices (another sale, or another {@code expiresAt} of its sale), of none, and of a newer
   * format.
   *
   * @param sale of this invoice, by txid
   * @param others of other invoices, by txid: what they name counts for nothing here
   * @param none the txids a witness none names
   * @param newer the witness addresses ({@code d} tags) of the witnesses of a newer format
   */
  public record OrderWitnesses(
      Map<String, SaleWitness> sale,
      Map<String, SaleWitness> others,
      Set<String> none,
      Set<String> newer) {
    /**
     * No witness at all.
     *
     * @return empty witnesses
     */
    public static OrderWitnesses empty() {
      return new OrderWitnesses(
          new LinkedHashMap<>(),
          new LinkedHashMap<>(),
          new LinkedHashSet<>(),
          new LinkedHashSet<>());
    }
  }

  /**
   * The {@code o} tags of the read of an invoice's witnesses: of the sale, of none, of the address.
   */
  private static List<String> readTags(Derive.SeenKeys seen, String address, String saleAuthor) {
    return List.of(
        orderTag(seen.addressKey(), address, saleAuthor),
        orderTag(seen.addressKey(), address, null),
        addressTag(seen.addressKey(), address));
  }

  /**
   * The witnesses of the invoice with the address {@code address} and the end {@code expiresAt},
   * for the sale {@code saleAuthor}: read on the merchant's relays by their order tags (of the sale
   * and of none) and the address tag (every sale's), as soon as a majority answered (or on every
   * relay), with a key of this read alone.
   *
   * @param pool the call's relays
   * @param seen the seen keys
   * @param address the address
   * @param saleAuthor the sale author
   * @param expiresAt the invoice's end
   * @param relays the merchant's relays
   * @param random the CSPRNG
   * @param everyRelay whether the read waits for every relay
   * @param logger where the warning of a witness of a newer format goes
   * @return the witnesses
   * @throws InterruptedException when the call is cancelled
   * @throws NetworkException when fewer than a majority answered
   */
  public static OrderWitnesses readOrderWitnesses(
      RelayPool pool,
      Derive.SeenKeys seen,
      String address,
      String saleAuthor,
      long expiresAt,
      List<String> relays,
      RandomBytes random,
      boolean everyRelay,
      Warn logger)
      throws InterruptedException {
    JsonObject filter =
        new JsonObject()
            .put("kinds", new JsonArray().add(Constants.RECORD_KIND))
            .put("authors", JsonArray.ofStrings(List.of(seen.author())))
            .put("#o", JsonArray.ofStrings(readTags(seen, address, saleAuthor)));
    RelayPool.Answers read =
        Quorum.query(
            pool,
            relays,
            filter,
            random.next(32),
            false,
            Records.recordsThatOpen(
                (d, author) -> author.equals(seen.author()) ? seen.encryptionKey() : null),
            everyRelay);
    if (!read.outcome().answered()) {
      throw new NetworkException("Fewer than a majority of the merchant's relays answered");
    }
    Map<String, List<Object>> answers = read.events();
    List<JsonValue> events = new ArrayList<>();
    for (List<Object> list : answers.values()) {
      for (Object value : list) events.add((JsonValue) value);
    }
    OrderWitnesses witnesses = orderWitnessesOf(events, seen, address, saleAuthor, expiresAt);
    if (!witnesses.newer().isEmpty()) {
      logger.warn(
          Warn.PREFIX
              + "a witness of an on-chain payment has a newer format than this version reads;"
              + " update the package (it is never written over)");
    }
    return witnesses;
  }

  /**
   * The witnesses of an invoice from what the relays sent to its read: of each witness address, the
   * newest event that opens (one of a newer format, its event checked, is kept as such). A witness
   * that holds none of the read's {@code o} tags, or whose {@code address} is not {@code address},
   * is ignored, of a newer format or not.
   *
   * @param events what the relays sent
   * @param seen the seen keys
   * @param address the address
   * @param saleAuthor the sale author
   * @param expiresAt the invoice's end
   * @return the witnesses
   */
  public static OrderWitnesses orderWitnessesOf(
      Iterable<JsonValue> events,
      Derive.SeenKeys seen,
      String address,
      String saleAuthor,
      long expiresAt) {
    Set<String> tags = new HashSet<>(readTags(seen, address, saleAuthor));
    Map<String, Opened> newest = new LinkedHashMap<>();
    Set<String> checked = new HashSet<>();
    for (JsonValue value : events) {
      JsonObject object = Json.object(value);
      String id = object == null ? null : Json.string(object.get("id"));
      if (id != null && checked.contains(id)) continue;
      Opened opened = openWitness(value, seen);
      if (opened == null) continue;
      checked.add(opened.event().id());
      boolean read = false;
      for (List<String> tag : opened.event().tags()) {
        if (tag.size() >= 2
            && tag.get(0).equals(Constants.ORDER_TAG)
            && tags.contains(tag.get(1))) {
          read = true;
          break;
        }
      }
      if (!read) continue;
      if (!opened.isNewer() && !opened.witness().address().equals(address)) continue;
      String d = opened.event().tag("d");
      Opened known = newest.get(d);
      if (known == null
          || Records.replaces(
              opened.event().createdAt(),
              opened.event().id(),
              known.event().createdAt(),
              known.event().id())) {
        newest.put(d, opened);
      }
    }
    OrderWitnesses witnesses = OrderWitnesses.empty();
    for (Map.Entry<String, Opened> entry : newest.entrySet()) {
      Opened opened = entry.getValue();
      if (opened.isNewer()) {
        witnesses.newer().add(entry.getKey());
        continue;
      }
      Witness witness = opened.witness();
      SaleWitness of =
          new SaleWitness(
              witness.sats(), witness.inputs(), witness.replacement(), opened.event().createdAt());
      if (witness.sale() == null) {
        witnesses.none().add(witness.txid());
      } else if (witness.sale().equals(saleAuthor) && witness.expiresAt() == expiresAt) {
        witnesses.sale().put(witness.txid(), of);
      } else {
        witnesses.others().put(witness.txid(), of);
      }
    }
    return witnesses;
  }

  /**
   * The addresses of {@code addresses} a witness names (SPEC.md §31, "Leasing an address", step 2):
   * read by their address tags, not paged, as soon as a majority answered, with a key of this read
   * alone. An event of the seen author that checks out with an address's tag as an {@code o} tag
   * names that address, one of a newer format included.
   *
   * @param pool the call's relays
   * @param seen the seen keys
   * @param addresses the lot's addresses
   * @param relays the merchant's relays
   * @param random the CSPRNG
   * @return the addresses named
   * @throws InterruptedException when the call is cancelled
   * @throws NetworkException when fewer than a majority answered
   */
  public static Set<String> readNamedAddresses(
      RelayPool pool,
      Derive.SeenKeys seen,
      List<String> addresses,
      List<String> relays,
      RandomBytes random)
      throws InterruptedException {
    Set<String> named = new HashSet<>();
    if (addresses.isEmpty()) return named;
    Map<String, String> byTag = new LinkedHashMap<>();
    for (String address : addresses) byTag.put(addressTag(seen.addressKey(), address), address);
    Map<String, Boolean> checksOut = new HashMap<>();
    JsonObject filter =
        new JsonObject()
            .put("kinds", new JsonArray().add(Constants.RECORD_KIND))
            .put("authors", JsonArray.ofStrings(List.of(seen.author())))
            .put("#o", JsonArray.ofStrings(new ArrayList<>(byTag.keySet())));
    RelayPool.Answers read =
        Quorum.query(
            pool,
            relays,
            filter,
            random.next(32),
            false,
            value -> {
              // Only an event that checks out names an address: nothing else is kept. A copy
              // that a relay sends again under the same id and signature is checked once.
              List<String> names =
                  addressesNamed(
                      value,
                      seen.author(),
                      byTag,
                      event ->
                          checksOut.computeIfAbsent(
                              event.id() + "/" + event.sig(),
                              copy ->
                                  Events.signatureChecks(event.id(), event.pubkey(), event.sig())));
              return names.isEmpty() ? null : names;
            },
            false);
    if (!read.outcome().answered()) {
      throw new NetworkException("Fewer than a majority of the merchant's relays answered");
    }
    for (List<Object> events : read.events().values()) {
      for (Object names : events) {
        for (Object address : (List<?>) names) named.add((String) address);
      }
    }
    return named;
  }

  /**
   * The addresses of {@code byTag} (by their address tags) that {@code value} names (SPEC.md §31,
   * "Leasing an address", step 3): when it is an event of {@code seenAuthor} that checks out
   * (SPEC.md §11), each address whose address tag it holds as an {@code o} tag, whatever its
   * content (one that does not open as a witness, or of a newer format, names it all the same);
   * none otherwise.
   *
   * @param value what a relay sent
   * @param seenAuthor the seen author
   * @param byTag the addresses, by their address tags
   * @return the addresses it names
   */
  public static List<String> addressesNamed(
      JsonValue value, String seenAuthor, Map<String, String> byTag) {
    return addressesNamed(
        value,
        seenAuthor,
        byTag,
        event -> Events.signatureChecks(event.id(), event.pubkey(), event.sig()));
  }

  private static List<String> addressesNamed(
      JsonValue value,
      String seenAuthor,
      Map<String, String> byTag,
      java.util.function.Predicate<NostrEvent> signed) {
    // The cheap checks first: its author, its tags; then its id, then its signature.
    List<String> tagged = taggedBy(value, seenAuthor, byTag);
    if (tagged.isEmpty()) return tagged;
    NostrEvent event = Events.withValidId(value);
    return event != null && signed.test(event) ? tagged : List.of();
  }

  /**
   * The addresses of {@code byTag} an event of {@code seenAuthor} tags (once each), checked or not.
   */
  private static List<String> taggedBy(
      JsonValue value, String seenAuthor, Map<String, String> byTag) {
    JsonObject event = Json.object(value);
    if (event == null || !seenAuthor.equals(Json.string(event.get("pubkey")))) return List.of();
    JsonArray tags = Json.array(event.get("tags"));
    if (tags == null) return List.of();
    Set<String> found = new LinkedHashSet<>();
    for (JsonValue tagValue : tags.items()) {
      JsonArray tag = Json.array(tagValue);
      if (tag == null || tag.size() < 2 || !Constants.ORDER_TAG.equals(Json.string(tag.get(0)))) {
        continue;
      }
      String address = byTag.get(Json.string(tag.get(1)));
      if (address != null) found.add(address);
    }
    return new ArrayList<>(found);
  }

  /**
   * Publishes a witness on the merchant's relays, signed and authenticated with the seen secret, at
   * once.
   *
   * @param pool the call's relays
   * @param seen the seen keys
   * @param witness the witness
   * @param relays the merchant's relays
   * @param createdAt Unix seconds
   * @param random the CSPRNG
   * @return completes once a majority took it; a {@link NetworkException} otherwise
   */
  public static CompletableFuture<Void> publishWitness(
      RelayPool pool,
      Derive.SeenKeys seen,
      Witness witness,
      List<String> relays,
      long createdAt,
      RandomBytes random) {
    NostrEvent event = witnessEvent(seen, witness, createdAt, random);
    return Quorum.publishAsync(
        pool,
        relays,
        event,
        seen.secretKey(),
        "Fewer than a majority of the merchant's relays took the witness of a payment");
  }
}
