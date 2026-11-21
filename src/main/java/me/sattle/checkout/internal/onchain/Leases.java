package me.sattle.checkout.internal.onchain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import me.sattle.checkout.internal.Checkout;
import me.sattle.checkout.internal.Constants;
import me.sattle.checkout.internal.Derive;
import me.sattle.checkout.internal.Hex;
import me.sattle.checkout.internal.RandomBytes;
import me.sattle.checkout.internal.Tasks;
import me.sattle.checkout.internal.Urls;
import me.sattle.checkout.internal.Warn;
import me.sattle.checkout.internal.crypto.Nip44;
import me.sattle.checkout.internal.json.Json;
import me.sattle.checkout.internal.json.JsonArray;
import me.sattle.checkout.internal.json.JsonNull;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.internal.lightning.Bolt11;
import me.sattle.checkout.internal.nostr.Events;
import me.sattle.checkout.internal.nostr.NostrEvent;
import me.sattle.checkout.internal.nostr.Records;
import me.sattle.checkout.internal.nostr.RelayPool;

/**
 * Leases (SPEC.md §31, "Leases"): an order holds a deposit address of the lot for the time of its
 * invoice with a record in the merchant's registry, signed by the order's sale, at the address's
 * registry address. Outside, what anyone holding the registry key reads (when the lease ends, or
 * its withdrawal); inside, sealed to the merchant's reading key as a declaration's, the order's
 * terms. Like a declaration, a lease stays: a payment of its address in its time is the order's for
 * good.
 */
public final class Leases {
  private Leases() {}

  /**
   * Where the leases of {@code address} are in the registry: HMAC(registry key, {@code onchain/v1/}
   * + address).
   *
   * @param registryKey the registry key
   * @param address the deposit address
   * @return its registry address (hex)
   */
  public static String leaseAddress(byte[] registryKey, String address) {
    return Derive.leaseAddress(registryKey, address);
  }

  /**
   * What a lease holds of its order (the lease's JSON, inside).
   *
   * @param address the deposit address
   * @param sats the amount
   * @param invoice the order's Lightning invoice, null for an order paid on-chain only
   * @param verifyUrl its verify URL, or null
   * @param createdAt the Invoice's date
   * @param expiresAt the Invoice's end
   * @param price the declared price
   * @param note the label
   * @param tolerance the order's tolerance (basis points), 0 for none (absent from the lease)
   * @param confirmed whether it is marked confirmed; null when absent (not confirmed either)
   */
  public record Terms(
      String address,
      long sats,
      String invoice,
      String verifyUrl,
      long createdAt,
      long expiresAt,
      Checkout.DeclaredPrice price,
      String note,
      int tolerance,
      Boolean confirmed) {
    /**
     * The same terms, marked.
     *
     * @param address the address
     * @param confirmed whether it is confirmed
     * @return the terms
     */
    public Terms with(String address, boolean confirmed) {
      return new Terms(
          address, sats, invoice, verifyUrl, createdAt, expiresAt, price, note, tolerance,
          confirmed);
    }
  }

  /**
   * Whether a lease's terms make it confirmed: {@code confirmed} true, nothing else.
   *
   * @param terms the terms
   * @return true when confirmed
   */
  public static boolean isConfirmedLease(Terms terms) {
    return Boolean.TRUE.equals(terms.confirmed());
  }

  /**
   * The lease of {@code terms.address} for {@code sale}, signed at {@code createdAt}; it ends at
   * expiresAt + 120. Its tolerance follows {@code taker} when it is above 0, absent otherwise.
   *
   * @param sale the sale
   * @param terms the terms
   * @param createdAt Unix seconds
   * @param random the CSPRNG
   * @return the event
   */
  public static NostrEvent leaseEvent(
      Derive.Sale sale, Terms terms, long createdAt, RandomBytes random) {
    JsonObject inside =
        new JsonObject()
            .put("version", Constants.LEASE_VERSION)
            .put("address", terms.address())
            .put("sats", terms.sats())
            .put("invoice", terms.invoice())
            .put("verifyUrl", terms.verifyUrl())
            .put("createdAt", terms.createdAt())
            .put("expiresAt", terms.expiresAt())
            .put(
                "sale",
                new JsonObject()
                    .put("key", Hex.encode(sale.key))
                    .put("fiat", terms.price().toJson())
                    .put("note", terms.note()))
            .put("taker", sale.taker);
    if (terms.tolerance() > 0) inside.put("tolerance", terms.tolerance());
    if (terms.confirmed() != null) inside.put("confirmed", (boolean) terms.confirmed());
    JsonObject outside =
        new JsonObject()
            .put("version", Constants.LEASE_VERSION)
            .put("end", terms.expiresAt() + Constants.GRACE_SECONDS)
            .put("sealed", Nip44.encrypt(Json.write(inside), sale.innerKey, random.next(32)));
    return sealSaleRecord(sale, terms.address(), outside, createdAt, random);
  }

  /**
   * The withdrawal of {@code sale}'s lease of {@code address}, which replaces it (same author and
   * {@code d} tag): its {@code created_at} is strictly after the lease's.
   *
   * @param sale the sale
   * @param address the address
   * @param createdAt Unix seconds
   * @param random the CSPRNG
   * @return the event
   */
  public static NostrEvent withdrawalEvent(
      Derive.Sale sale, String address, long createdAt, RandomBytes random) {
    return sealSaleRecord(
        sale,
        address,
        new JsonObject().put("version", Constants.LEASE_VERSION).put("withdrawn", true),
        createdAt,
        random);
  }

  private static NostrEvent sealSaleRecord(
      Derive.Sale sale, String address, JsonObject value, long createdAt, RandomBytes random) {
    String d = leaseAddress(sale.registry.key(), address);
    return Records.seal(
        Constants.LEASE_RECORD_TYPE,
        value,
        sale.secretKey,
        Derive.outerKey(sale.registry.key(), d, sale.author),
        d,
        createdAt,
        List.of(),
        random);
  }

  /** The kinds of a record's outside. */
  public enum Kind {
    /** A lease, until its end. */
    LEASE,
    /** A withdrawal: it holds nothing. */
    WITHDRAWN,
    /** Authentic, but of a newer format: an unreadable lease is never written over. */
    NEWER
  }

  /**
   * A record's outside, as anyone holding the registry key reads it.
   *
   * @param kind a lease, a withdrawal, or of a newer format
   * @param end a lease's end (0 otherwise)
   * @param sealed a lease's inside, sealed (null otherwise)
   */
  public record Outside(Kind kind, long end, String sealed) {}

  /**
   * What a read of leases keeps of a record's outside: whether, and until when, it holds.
   *
   * @param kind a lease, a withdrawal, or of a newer format
   * @param end a lease's end (0 otherwise)
   */
  public record Hold(Kind kind, long end) {}

  /**
   * What a read of leases keeps of a record's event, its id checked: what the walk of its address
   * and a lease's confirmation need, and its signature (SPEC.md §13, Limits).
   *
   * @param id its id
   * @param pubkey its author
   * @param createdAt its date
   * @param sig its signature
   */
  public record LeaseEvent(String id, String pubkey, long createdAt, String sig) {}

  /**
   * A record of the registry at a deposit address's registry address, by any author: its id
   * checked, its signature not yet (SPEC.md §31, "Opening a lease"). {@link #signed()} checks it,
   * once: whether a copy of the event a relay sent carries a signature that checks out, so that a
   * relay's copy with a damaged signature never hides the valid one another relay sent.
   */
  public static final class LeaseRecord {
    private final LeaseEvent event;
    private final String d;
    private final Hold outside;
    private final BooleanSupplier signed;
    private final Consumer<String> addCopy;

    /**
     * A record.
     *
     * @param event its event
     * @param d its registry address
     * @param outside what its outside holds
     * @param signed whether one of its copies' signatures checks out
     * @param addCopy takes the signature of another relay's copy of it
     */
    public LeaseRecord(
        LeaseEvent event,
        String d,
        Hold outside,
        BooleanSupplier signed,
        Consumer<String> addCopy) {
      this.event = event;
      this.d = d;
      this.outside = outside;
      this.signed = signed;
      this.addCopy = addCopy;
    }

    /**
     * Its event.
     *
     * @return what is kept of it
     */
    public LeaseEvent event() {
      return event;
    }

    /**
     * Its registry address.
     *
     * @return the {@code d} tag
     */
    public String d() {
      return d;
    }

    /**
     * What its outside holds.
     *
     * @return the hold
     */
    public Hold outside() {
      return outside;
    }

    /**
     * Whether a copy of it carries a signature that checks out (checked once, when first asked).
     *
     * @return true when signed
     */
    public boolean signed() {
      return signed.getAsBoolean();
    }

    /**
     * Takes the signature of another relay's copy of it: one more to try.
     *
     * @param sig the signature
     */
    public void addCopy(String sig) {
      addCopy.accept(sig);
    }

    @Override
    public String toString() {
      return "LeaseRecord[" + event.id() + "]";
    }
  }

  /**
   * {@code event}'s record at {@code d} with its outside, its signature checked when first asked.
   *
   * @param event the event
   * @param d its registry address
   * @param outside what it holds
   * @return the record
   */
  public static LeaseRecord leaseRecordOf(LeaseEvent event, String d, Hold outside) {
    List<String> signatures = new ArrayList<>();
    signatures.add(event.sig());
    Boolean[] signed = {null};
    return new LeaseRecord(
        event,
        d,
        outside,
        () -> {
          if (signed[0] == null) {
            boolean any = false;
            for (String sig : signatures) {
              if (Events.signatureChecks(event.id(), event.pubkey(), sig)) {
                any = true;
                break;
              }
            }
            signed[0] = any;
          }
          return signed[0];
        },
        sig -> {
          // The same event (its id is its hash) with another signature: one more to try.
          if (signatures.contains(sig)) return;
          signatures.add(sig);
          if (Boolean.FALSE.equals(signed[0])) signed[0] = null;
        });
  }

  /**
   * What a relay sent, opened as a record of type {@code registry-onchain} at the registry address
   * {@code d}, with the outer key of {@code d} and of the event's own author: its id first; a
   * content of a newer NIP-44 version is no record here (anybody can write one at {@code d}); its
   * signature left to {@link #isLeased}.
   *
   * @param value what a relay sent
   * @param registryKey the registry key
   * @param d the registry address
   * @return the record, or null when it is not one
   */
  public static LeaseRecord openLeaseRecord(JsonValue value, byte[] registryKey, String d) {
    OpenedOutside opened = openLeaseOutside(value, registryKey, d);
    if (opened == null) return null;
    Outside outside = opened.outside();
    return leaseRecordOf(opened.event(), d, new Hold(outside.kind(), outside.end()));
  }

  /**
   * A record's event and its whole outside.
   *
   * @param event what a read keeps of its event
   * @param outside its outside
   */
  public record OpenedOutside(LeaseEvent event, Outside outside) {}

  /** A lease's outside; NewerFormat for a newer version. */
  private static Outside parseOutside(JsonValue value) {
    JsonObject v = Json.object(value);
    if (v == null) return null;
    if (Json.isNumberAbove(v.get("version"), Constants.LEASE_VERSION))
      throw new Records.NewerFormat();
    if (!Json.isNumber(v.get("version"), Constants.LEASE_VERSION)) return null;
    if (Json.isTrue(v.get("withdrawn"))) return new Outside(Kind.WITHDRAWN, 0, null);
    Long end = Json.safeInteger(v.get("end"));
    String sealed = Json.string(v.get("sealed"));
    if (end == null || sealed == null) return null;
    return new Outside(Kind.LEASE, end, sealed);
  }

  /**
   * {@link #openLeaseRecord}'s opening, with the whole outside (its {@code sealed} included).
   *
   * @param value what a relay sent
   * @param registryKey the registry key
   * @param d the registry address
   * @return the event and its outside, or null
   */
  public static OpenedOutside openLeaseOutside(JsonValue value, byte[] registryKey, String d) {
    NostrEvent event = Events.withValidId(value);
    if (event == null
        || event.kind() != Constants.RECORD_KIND
        || !d.equals(Records.dTagOf(value))) {
      return null;
    }
    String plaintext;
    try {
      plaintext = Nip44.decrypt(event.content(), Derive.outerKey(registryKey, d, event.pubkey()));
    } catch (Nip44.UnknownVersionException | Nip44.DecryptException e) {
      // A content of a newer NIP-44 version included: only the outer key seals a newer lease.
      return null;
    }
    JsonObject envelope = Json.object(Json.parseOrNull(plaintext));
    if (envelope == null
        || !envelope.has("v")
        || !Constants.LEASE_RECORD_TYPE.equals(Json.string(envelope.get("t")))) {
      return null;
    }
    Outside outside;
    try {
      outside = parseOutside(envelope.get("v"));
    } catch (Records.NewerFormat e) {
      outside = new Outside(Kind.NEWER, 0, null);
    }
    if (outside == null) return null;
    return new OpenedOutside(
        new LeaseEvent(event.id(), event.pubkey(), event.createdAt(), event.sig()), outside);
  }

  /** Whether {@code record} holds its address at {@code t}. */
  private static boolean holds(LeaseRecord record, long t) {
    Hold hold = record.outside();
    return hold.kind() == Kind.NEWER
        || (hold.kind() == Kind.LEASE && hold.end() >= t - Constants.CLOCK_TOLERANCE_SECONDS);
  }

  /** Newest first: a later {@code created_at}, then the lowest id. */
  private static final Comparator<LeaseRecord> NEWEST_FIRST =
      Comparator.comparingLong((LeaseRecord record) -> -record.event().createdAt())
          .thenComparing(record -> record.event().id());

  /**
   * Whether the records of one address lease it at {@code t} (SPEC.md §31, "Leases"): of each
   * author, its records from the newest: one that holds the address leases it; one that holds
   * nothing ends the author's records, unless an older one of them holds and its signature does not
   * check out. Every author is walked.
   *
   * @param records the address's records
   * @param t Unix seconds
   * @return true when leased
   */
  public static boolean isLeased(List<LeaseRecord> records, long t) {
    Map<String, List<LeaseRecord>> byAuthor = new LinkedHashMap<>();
    for (LeaseRecord record : records) {
      byAuthor.computeIfAbsent(record.event().pubkey(), author -> new ArrayList<>()).add(record);
    }
    boolean leased = false;
    for (List<LeaseRecord> list : byAuthor.values()) {
      List<LeaseRecord> walk = new ArrayList<>(list);
      walk.sort(NEWEST_FIRST);
      for (int index = 0; index < walk.size(); index += 1) {
        LeaseRecord record = walk.get(index);
        if (holds(record, t)) {
          leased = true;
          break;
        }
        boolean olderHolds = false;
        for (LeaseRecord older : walk.subList(index + 1, walk.size())) {
          if (holds(older, t)) {
            olderHolds = true;
            break;
          }
        }
        if (!olderHolds) break;
        if (record.signed()) break;
      }
    }
    return leased;
  }

  /**
   * Whether the lease {@code own} (an event of the sale) is confirmed by {@code records}, a
   * complete read of its address's records (SPEC.md §31, "Leasing an address", step 3.3): it is
   * among them, no record of another author holds the address at {@code t}, and {@code own} is the
   * newest record of its author there.
   *
   * @param records the address's records
   * @param own the lease
   * @param t Unix seconds
   * @return true when confirmed
   */
  public static boolean isConfirmed(List<LeaseRecord> records, LeaseEvent own, long t) {
    boolean found = false;
    for (LeaseRecord record : records) found |= record.event().id().equals(own.id());
    if (!found) return false;
    List<LeaseRecord> others = new ArrayList<>();
    for (LeaseRecord record : records) {
      if (!record.event().pubkey().equals(own.pubkey())) others.add(record);
    }
    if (isLeased(others, t)) return false;
    for (LeaseRecord record : records) {
      LeaseEvent event = record.event();
      if (event.pubkey().equals(own.pubkey())
          && !event.id().equals(own.id())
          && Records.replaces(event.createdAt(), event.id(), own.createdAt(), own.id())) {
        return false;
      }
    }
    return true;
  }

  /**
   * The records of the registry at the registry addresses of deposit addresses.
   *
   * @param records by address
   * @param answered whether a majority of the relays answered
   * @param complete whether a majority of the relays read them completely
   */
  public record LeaseRead(
      Map<String, List<LeaseRecord>> records, boolean answered, boolean complete) {}

  /**
   * The records of the registry at the registry addresses of {@code addresses}, by address: a
   * complete read on the merchant's relays, since 7200 s before {@code now}, with a key of this
   * read alone. Each relay's first copy of an event counts, its signature tried with the other
   * relays'.
   *
   * @param pool the call's relays
   * @param registryKey the registry key
   * @param addresses the addresses
   * @param relays the merchant's relays
   * @param now Unix seconds
   * @param random the CSPRNG
   * @param everyRelay whether the read waits for every relay
   * @param logger where the warning of a record of a newer format goes
   * @return the records
   * @throws InterruptedException when the call is cancelled
   */
  public static LeaseRead readLeases(
      RelayPool pool,
      byte[] registryKey,
      List<String> addresses,
      List<String> relays,
      long now,
      RandomBytes random,
      boolean everyRelay,
      Warn logger)
      throws InterruptedException {
    Map<String, String> byD = new LinkedHashMap<>();
    Map<String, List<LeaseRecord>> found = new LinkedHashMap<>();
    for (String address : addresses) {
      byD.put(leaseAddress(registryKey, address), address);
      found.put(address, new ArrayList<>());
    }
    if (addresses.isEmpty()) return new LeaseRead(found, true, true);
    int needed = RelayPool.majority(relays.size());
    JsonObject filter =
        new JsonObject()
            .put("kinds", new JsonArray().add(Constants.RECORD_KIND))
            .put("#d", JsonArray.ofStrings(new ArrayList<>(byD.keySet())))
            .put("since", now - Constants.LEASE_LOOKBACK_SECONDS);
    RelayPool.CompleteAnswers read =
        Tasks.await(
            pool.queryComplete(
                relays,
                filter,
                random.next(32),
                needed,
                value -> {
                  String d = Records.dTagOf(value);
                  return d != null && byD.containsKey(d)
                      ? openLeaseRecord(value, registryKey, d)
                      : null;
                },
                everyRelay));
    // Ids that checked out: another copy of one is the same event, its signature one more to try.
    Map<String, LeaseRecord> checked = new HashMap<>();
    for (List<Object> records : read.answers().values()) {
      // A relay's first copy of each id: at most one signature per relay to try.
      Set<String> ofRelay = new HashSet<>();
      for (Object value : records) {
        LeaseRecord record = (LeaseRecord) value;
        String id = record.event().id();
        if (!ofRelay.add(id)) continue;
        LeaseRecord known = checked.get(id);
        if (known != null) {
          known.addCopy(record.event().sig());
          continue;
        }
        checked.put(id, record);
        found.get(byD.get(record.d())).add(record);
      }
    }
    boolean newer = false;
    for (List<LeaseRecord> records : found.values()) {
      for (LeaseRecord record : records) newer |= record.outside().kind() == Kind.NEWER;
    }
    if (newer) {
      logger.warn(
          Warn.PREFIX
              + "a lease of a deposit address has a newer format than this version reads; update"
              + " the package (it holds its address, never written over)");
    }
    return new LeaseRead(found, read.outcome().answered(), read.outcome().complete());
  }

  /**
   * A lease of the sale, opened by the sale (or the merchant): its terms.
   *
   * @param event its event, checked
   * @param d its registry address
   * @param terms its terms
   * @param inside the lease's JSON, inside, as it was sealed
   */
  public record OpenedLease(NostrEvent event, String d, Terms terms, JsonObject inside) {}

  private static Checkout.DeclaredPrice parsePrice(JsonValue value) {
    JsonObject object = Json.object(value);
    if (object == null) return null;
    Long minor = Json.safeInteger(object.get("minor"));
    if (minor == null || minor < 0) return null;
    JsonValue digits = object.get("digits");
    Long places = Json.isInteger(digits) ? Json.safeInteger(digits) : null;
    if (places == null || places < 0 || places > Constants.MAX_DECLARED_DIGITS) return null;
    String currency = Json.string(object.get("currency"));
    if (currency == null || !currency.matches("[A-Z]{3}")) return null;
    return new Checkout.DeclaredPrice(minor, (int) (long) places, currency);
  }

  /**
   * The inside of a lease's outside, opened with the sale's inner key (SPEC.md §31, "Opening it as
   * the sale"): its terms (its tolerance read as 0 when it is absent or not one).
   *
   * @param outside the lease's outside
   * @param d its registry address
   * @param sale the sale
   * @return the terms and the inside, or null when they are not valid
   * @throws Records.NewerFormat for a newer version
   */
  public static OpenedLease openLeaseInside(Outside outside, String d, Derive.Sale sale) {
    return openInside(null, outside, d, sale);
  }

  private static OpenedLease openInside(
      NostrEvent event, Outside outside, String d, Derive.Sale sale) {
    String plaintext;
    try {
      plaintext = Nip44.decrypt(outside.sealed(), sale.innerKey);
    } catch (Nip44.UnknownVersionException | Nip44.DecryptException e) {
      return null;
    }
    JsonObject v = Json.object(Json.parseOrNull(plaintext));
    if (v == null) return null;
    if (Json.isNumberAbove(v.get("version"), Constants.LEASE_VERSION))
      throw new Records.NewerFormat();
    if (!Json.isNumber(v.get("version"), Constants.LEASE_VERSION)) return null;
    String address = Json.string(v.get("address"));
    if (!Addresses.isDepositAddress(address)
        || !leaseAddress(sale.registry.key(), address).equals(d)) {
      return null;
    }
    Long sats = Json.safeInteger(v.get("sats"));
    if (sats == null || sats < 1 || sats > Constants.MAX_SATS) return null;
    Long createdAt = Json.safeInteger(v.get("createdAt"));
    Long expiresAt = Json.safeInteger(v.get("expiresAt"));
    if (createdAt == null
        || expiresAt == null
        || createdAt >= expiresAt
        || outside.end() != expiresAt + Constants.GRACE_SECONDS) {
      return null;
    }
    JsonValue invoiceValue = v.get("invoice");
    JsonValue verifyValue = v.get("verifyUrl");
    String invoice = null;
    String verifyUrl = null;
    if (invoiceValue == JsonNull.NULL) {
      if (verifyValue != JsonNull.NULL) return null;
    } else {
      invoice = Json.string(invoiceValue);
      verifyUrl = Json.string(verifyValue);
      if (invoice == null || verifyUrl == null || Urls.parseHttpsUrl(verifyUrl) == null)
        return null;
      Bolt11 decoded = Bolt11.decode(invoice);
      if (decoded == null
          || !decoded.network().equals("bc")
          || decoded.amountMsat() == null
          || decoded.amountMsat() != sats * 1000
          || decoded.timestamp() != createdAt
          || decoded.expiresAt() != expiresAt) {
        return null;
      }
    }
    JsonObject saleValue = Json.object(v.get("sale"));
    if (saleValue == null) return null;
    String note = Json.string(saleValue.get("note"));
    if (!Hex.encode(sale.key).equals(Json.string(saleValue.get("key"))) || note == null)
      return null;
    Checkout.DeclaredPrice price = parsePrice(saleValue.get("fiat"));
    if (price == null) return null;
    Boolean confirmed = v.has("confirmed") ? Json.isTrue(v.get("confirmed")) : null;
    Terms terms =
        new Terms(
            address,
            sats,
            invoice,
            verifyUrl,
            createdAt,
            expiresAt,
            price,
            note,
            Tolerance.toleranceOf(v.get("tolerance")),
            confirmed);
    return new OpenedLease(event, d, terms, v);
  }

  /** A record of the sale at a registry address: its lease, its withdrawal, or newer. */
  public sealed interface SaleRecord permits SaleLease, SaleWithdrawal, SaleNewer {
    /**
     * Its event.
     *
     * @return the event
     */
    NostrEvent event();

    /**
     * Its registry address.
     *
     * @return the {@code d} tag
     */
    String d();
  }

  /**
   * A lease of the sale.
   *
   * @param lease the lease
   */
  public record SaleLease(OpenedLease lease) implements SaleRecord {
    @Override
    public NostrEvent event() {
      return lease.event();
    }

    @Override
    public String d() {
      return lease.d();
    }
  }

  /**
   * A withdrawal of the sale.
   *
   * @param event its event
   * @param d its registry address
   */
  public record SaleWithdrawal(NostrEvent event, String d) implements SaleRecord {}

  /**
   * A record of the sale of a newer format: authentic, unreadable; the newest at its address, it
   * leaves no lease there.
   *
   * @param event its event
   * @param d its registry address
   */
  public record SaleNewer(NostrEvent event, String d) implements SaleRecord {}

  /**
   * What a relay sent, if it is a lease (or a withdrawal) of {@code sale}: at a registry address,
   * signed by the sale, with valid terms inside.
   *
   * @param value what a relay sent
   * @param sale the sale
   * @return the record, or null
   * @throws Records.NewerFormat for one of a newer format
   */
  public static SaleRecord openSaleLease(JsonValue value, Derive.Sale sale) {
    String d = Records.dTagOf(value);
    JsonObject object = Json.object(value);
    if (d == null || object == null || !sale.author.equals(Json.string(object.get("pubkey")))) {
      return null;
    }
    Records.Opened<Outside> opened =
        Records.open(
            value,
            Constants.LEASE_RECORD_TYPE,
            Derive.outerKey(sale.registry.key(), d, sale.author),
            d,
            sale.author,
            (outside, event) -> parseOutside(outside));
    if (opened == null) return null;
    if (opened.isNewer() || opened.value().kind() == Kind.NEWER) throw new Records.NewerFormat();
    if (opened.value().kind() == Kind.WITHDRAWN) return new SaleWithdrawal(opened.event(), d);
    OpenedLease lease = openInside(opened.event(), opened.value(), d, sale);
    return lease == null ? null : new SaleLease(lease);
  }
}
