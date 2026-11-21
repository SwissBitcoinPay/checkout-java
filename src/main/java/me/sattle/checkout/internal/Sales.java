package me.sattle.checkout.internal;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.sattle.checkout.DeclaredInvoice;
import me.sattle.checkout.Fiat;
import me.sattle.checkout.NetworkException;
import me.sattle.checkout.PaymentStatus;
import me.sattle.checkout.internal.json.Json;
import me.sattle.checkout.internal.json.JsonArray;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.internal.lightning.Bolt11;
import me.sattle.checkout.internal.nostr.Events;
import me.sattle.checkout.internal.nostr.NostrEvent;
import me.sattle.checkout.internal.nostr.Quorum;
import me.sattle.checkout.internal.nostr.Records;
import me.sattle.checkout.internal.nostr.RelayPool;
import me.sattle.checkout.internal.onchain.Addresses;
import me.sattle.checkout.internal.onchain.Leases;
import me.sattle.checkout.internal.onchain.Rests;

/**
 * The invoices declared for a sale, read back from the merchant's registry (SPEC.md §21): its
 * declarations and its leases of deposit addresses. What {@code createInvoice} checks before a new
 * invoice, and {@code findInvoices} gives.
 */
public final class Sales {
  private Sales() {}

  /**
   * A declared invoice, with its signed declaration (null for an order paid on-chain only) and
   * lease (null without an address): published again when the invoice may still be paid.
   *
   * @param invoice the invoice
   * @param event its declaration, or null
   * @param lease its lease, or null
   */
  public record SaleDeclaration(DeclaredInvoice invoice, NostrEvent event, NostrEvent lease) {}

  /**
   * What the registry holds of a sale.
   *
   * @param invoices the order's invoices, oldest first
   * @param rests the rest invoices of each invoice with an address, by {@link #restKeyOf}: by date
   *     then payment hash
   * @param unsureRests the declarations of rest invoices fewer than a majority of the merchant's
   *     relays sent, by payment hash: published again on a majority before they are used
   * @param leases the sale's leases still held (not withdrawn), by address
   * @param sentBy how many relays sent each event, by id
   */
  public record SaleRecords(
      List<SaleDeclaration> invoices,
      Map<String, List<Rests.RestInvoice>> rests,
      Map<String, NostrEvent> unsureRests,
      Map<String, Leases.OpenedLease> leases,
      Map<String, Integer> sentBy) {}

  /**
   * The invoices of {@code sale} on {@code relays}, oldest first: read page after page as soon as a
   * majority of the relays answered (or on every relay).
   *
   * @param pool the call's relays
   * @param sale the sale
   * @param relays the merchant's relays
   * @param logger where warnings go
   * @param everyRelay whether the read waits for every relay
   * @return what the registry holds of the sale
   * @throws InterruptedException when the call is cancelled
   * @throws NetworkException when fewer than a majority answered
   */
  public static SaleRecords readSaleDeclarations(
      RelayPool pool, Derive.Sale sale, List<String> relays, Warn logger, boolean everyRelay)
      throws InterruptedException {
    int needed = RelayPool.majority(relays.size());
    JsonObject filter =
        new JsonObject()
            .put("kinds", new JsonArray().add(Constants.RECORD_KIND))
            .put("authors", JsonArray.ofStrings(List.of(sale.author)));
    RelayPool.Answers read =
        Quorum.query(
            pool,
            relays,
            filter,
            sale.secretKey,
            true,
            // Its declarations and its records at registry addresses: sealed with the outer key of
            // their address and of the sale.
            Records.recordsThatOpen((d, author) ->
                author.equals(sale.author) && d != null
                    ? Derive.outerKey(sale.registry.key(), d, author)
                    : null),
            everyRelay);
    if (!read.outcome().answered()) {
      throw new NetworkException("Fewer than a majority of the merchant's relays answered");
    }
    Map<String, List<Object>> answers = read.events();
    Map<String, Declarations.Opened> declarations = new LinkedHashMap<>();
    List<Leases.SaleRecord> records = new ArrayList<>();
    Set<String> checked = new HashSet<>();
    Map<String, Integer> sentBy = new HashMap<>();
    boolean newer = false;
    for (List<JsonValue> events : Records.asValues(answers).values()) {
      // A relay counts for an event when it sent a copy whose id is its hash: it holds that event.
      Set<String> ids = new HashSet<>();
      for (JsonValue value : events) {
        NostrEvent event = Events.withValidId(value);
        if (event != null) ids.add(event.id());
      }
      for (String id : ids) sentBy.merge(id, 1, Integer::sum);
      for (JsonValue value : events) {
        JsonObject object = Json.object(value);
        String id = object == null ? null : Json.string(object.get("id"));
        if (id != null && checked.contains(id)) continue;
        Declarations.Opened declaration;
        Leases.SaleRecord lease = null;
        try {
          declaration = Declarations.openDeclaration(value, sale);
          if (declaration == null) lease = Leases.openSaleLease(value, sale);
        } catch (Records.NewerFormat e) {
          newer = true;
          // Authentic (its signature checked before its format), at a registry address: the newest
          // there, it leaves no lease of the sale.
          String d = Records.dTagOf(value);
          NostrEvent event = Events.withValidId(value);
          if (d != null && event != null) {
            checked.add(event.id());
            records.add(new Leases.SaleNewer(event, d));
          }
          continue;
        }
        if (declaration != null) {
          checked.add(declaration.event().id());
          declarations.putIfAbsent(declaration.decoded().paymentHash(), declaration);
        } else if (lease != null) {
          checked.add(lease.event().id());
          records.add(lease);
        }
      }
    }
    if (newer) {
      logger.warn(
          Warn.PREFIX
              + "a declaration or a lease of this order has a newer format than this version reads;"
              + " update the package");
    }
    List<Leases.SaleRecord> newest = newestRecords(records);
    Map<String, List<Rests.RestInvoice>> rests = new LinkedHashMap<>();
    Map<String, NostrEvent> unsureRests = new LinkedHashMap<>();
    List<Declarations.Opened> invoices = new ArrayList<>();
    for (Declarations.Opened declaration : declarations.values()) {
      if (declaration.rest() == null) {
        invoices.add(declaration);
        continue;
      }
      if (sentBy.getOrDefault(declaration.event().id(), 0) < needed) {
        unsureRests.put(declaration.decoded().paymentHash(), declaration.event());
      }
      rests
          .computeIfAbsent(
              restKeyOf(declaration.rest().address(), declaration.rest().expiresAt()),
              key -> new ArrayList<>())
          .add(restInvoiceOf(declaration));
    }
    for (List<Rests.RestInvoice> list : rests.values()) list.sort(Rests.REST_ORDER);
    Map<String, Leases.OpenedLease> leases = new LinkedHashMap<>();
    for (Leases.SaleRecord record : newest) {
      if (record instanceof Leases.SaleLease held) {
        leases.put(held.lease().terms().address(), held.lease());
      }
    }
    return new SaleRecords(
        invoicesOf(sale.ref, invoices, newest), rests, unsureRests, leases, sentBy);
  }

  /**
   * A rest invoice of a declaration with {@code rest} (opened: its invoice decodes, in whole sats).
   *
   * @param declaration the declaration
   * @return the rest invoice
   */
  public static Rests.RestInvoice restInvoiceOf(Declarations.Opened declaration) {
    Bolt11 decoded = declaration.decoded();
    return new Rests.RestInvoice(
        declaration.invoice(),
        decoded.paymentHash(),
        declaration.verifyUrl(),
        decoded.wholeSats(),
        decoded.timestamp(),
        decoded.expiresAt());
  }

  /**
   * Of the sale's records at registry addresses, the newest at each (the highest {@code
   * created_at}, then the lowest id): its lease there, or its withdrawal, which leaves none.
   *
   * @param records the records
   * @return the newest at each address
   */
  public static List<Leases.SaleRecord> newestRecords(List<Leases.SaleRecord> records) {
    Map<String, Leases.SaleRecord> newest = new LinkedHashMap<>();
    for (Leases.SaleRecord record : records) {
      Leases.SaleRecord known = newest.get(record.d());
      if (known == null
          || Records.replaces(
              record.event().createdAt(),
              record.event().id(),
              known.event().createdAt(),
              known.event().id())) {
        newest.put(record.d(), record);
      }
    }
    return new ArrayList<>(newest.values());
  }

  /** Newest first. */
  private static final Comparator<Leases.OpenedLease> NEWEST_LEASE =
      Comparator.comparingLong((Leases.OpenedLease lease) -> -lease.event().createdAt())
          .thenComparing(lease -> lease.event().id());

  /**
   * The sale's invoices, oldest first (by date, then payment hash, none first, then address), from
   * its declarations (one per payment hash; one with {@code rest} gives none) and its newest record
   * at each registry address. Only a confirmed lease gives an address.
   *
   * @param ref the order's reference
   * @param declarations the declarations
   * @param newest the newest record of the sale at each registry address
   * @return the invoices
   */
  public static List<SaleDeclaration> invoicesOf(
      String ref, List<Declarations.Opened> declarations, List<Leases.SaleRecord> newest) {
    List<Leases.OpenedLease> confirmed = new ArrayList<>();
    for (Leases.SaleRecord record : newest) {
      if (record instanceof Leases.SaleLease lease
          && Leases.isConfirmedLease(lease.lease().terms())) {
        confirmed.add(lease.lease());
      }
    }
    List<SaleDeclaration> found = new ArrayList<>();
    for (Declarations.Opened declaration : declarations) {
      // A rest invoice's: no invoice of the order.
      if (declaration.rest() != null) continue;
      Bolt11 decoded = declaration.decoded();
      String invoice = Text.asciiLower(declaration.invoice());
      List<Leases.OpenedLease> naming = new ArrayList<>();
      for (Leases.OpenedLease lease : confirmed) {
        String leased = lease.terms().invoice();
        if (leased != null && Text.asciiLower(leased).equals(invoice)) naming.add(lease);
      }
      naming.sort(NEWEST_LEASE);
      Leases.OpenedLease lease = naming.isEmpty() ? null : naming.get(0);
      long sats = decoded.wholeSats();
      String onchainAddress = lease == null ? null : lease.terms().address();
      found.add(
          new SaleDeclaration(
              new DeclaredInvoice(
                  ref,
                  declaration.invoice(),
                  decoded.paymentHash(),
                  declaration.verifyUrl(),
                  onchainAddress,
                  Addresses.paymentUri(onchainAddress, sats, declaration.invoice()),
                  Long.toString(sats),
                  fiatOf(declaration.price()),
                  declaration.note(),
                  decoded.timestamp(),
                  decoded.expiresAt(),
                  declaration.declaredAt()),
              declaration.event(),
              lease == null ? null : lease.event()));
    }
    for (Leases.OpenedLease lease : confirmed) {
      Leases.Terms terms = lease.terms();
      if (terms.invoice() != null) continue;
      found.add(
          new SaleDeclaration(
              new DeclaredInvoice(
                  ref,
                  null,
                  null,
                  null,
                  terms.address(),
                  Addresses.paymentUri(terms.address(), terms.sats(), null),
                  Long.toString(terms.sats()),
                  fiatOf(terms.price()),
                  terms.note(),
                  terms.createdAt(),
                  terms.expiresAt(),
                  // Its mark's date.
                  lease.event().createdAt()),
              null,
              lease.event()));
    }
    found.sort(
        Comparator.comparingLong((SaleDeclaration item) -> item.invoice().createdAt())
            .thenComparing(item -> nullToEmpty(item.invoice().paymentHash()))
            .thenComparing(item -> nullToEmpty(item.invoice().onchainAddress())));
    return found;
  }

  private static String nullToEmpty(String text) {
    return text == null ? "" : text;
  }

  /**
   * Step 1 of SPEC.md §21: the indices of the invoices (oldest first) that may still be paid at
   * {@code now}, until their end and its grace period.
   *
   * @param invoices the invoices
   * @param now Unix seconds
   * @return their indices
   */
  public static List<Integer> mayStillBePaid(List<DeclaredInvoice> invoices, long now) {
    List<Integer> indices = new ArrayList<>();
    for (int index = 0; index < invoices.size(); index += 1) {
      if (now < invoices.get(index).expiresAt() + Constants.GRACE_SECONDS) indices.add(index);
    }
    return indices;
  }

  /**
   * Of the invoices that may still be paid, whether a majority of the merchant's {@code relays}
   * sent the last record of each in the read on every relay (SPEC.md §21, step 1): the one shown.
   *
   * @param invoices the invoices that may still be paid
   * @param sentBy how many relays sent each event, by id; null when fewer than a majority answered
   *     that read
   * @param relays how many relays the merchant's list holds
   * @return whether each is held, in order; null when the read was not answered
   */
  public static List<Boolean> heldBy(
      List<SaleDeclaration> invoices, Map<String, Integer> sentBy, int relays) {
    if (sentBy == null) return null;
    int needed = RelayPool.majority(relays);
    List<Boolean> held = new ArrayList<>();
    for (SaleDeclaration invoice : invoices) {
      held.add(sentBy.getOrDefault(lastRecordOf(invoice).id(), 0) >= needed);
    }
    return held;
  }

  /**
   * An invoice's last record: its declaration, or for an invoice paid on-chain only its lease's
   * event (its mark).
   *
   * @param invoice the invoice
   * @return its last record
   */
  public static NostrEvent lastRecordOf(SaleDeclaration invoice) {
    return invoice.event() != null ? invoice.event() : invoice.lease();
  }

  /** What the check of the invoices that may still be paid gives (SPEC.md §21, step 1). */
  public sealed interface Outcome permits PaidOutcome, FailedOutcome, Unread, HandOut {}

  /**
   * The first paid.
   *
   * @param index its index
   */
  public record PaidOutcome(int index) implements Outcome {}

  /**
   * The first that failed.
   *
   * @param index its index
   */
  public record FailedOutcome(int index) implements Outcome {}

  /** The read on every relay was not answered by a majority. */
  public record Unread() implements Outcome {}

  /**
   * The one handed out.
   *
   * @param index its index
   */
  public record HandOut(int index) implements Outcome {}

  /**
   * Of the invoices that may still be paid (oldest first, one or more), from the payment check of
   * each and, of several, whether a majority of the relays sent its last record ({@code held}; null
   * when fewer than a majority answered that read).
   *
   * @param statuses the outcome of each check
   * @param held whether each is held by a majority, or null
   * @return the outcome
   */
  public static Outcome pendingOutcome(
      List<Tasks.Settled<PaymentStatus>> statuses, List<Boolean> held) {
    for (int index = 0; index < statuses.size(); index += 1) {
      Tasks.Settled<PaymentStatus> status = statuses.get(index);
      if (status.ok() && status.value() instanceof PaymentStatus.Paid)
        return new PaidOutcome(index);
    }
    for (int index = 0; index < statuses.size(); index += 1) {
      if (!statuses.get(index).ok()) return new FailedOutcome(index);
    }
    if (statuses.size() == 1) return new HandOut(0);
    if (held == null) return new Unread();
    List<Integer> shown = new ArrayList<>();
    for (int index = 0; index < held.size(); index += 1) {
      if (Boolean.TRUE.equals(held.get(index))) shown.add(index);
    }
    if (shown.size() == 1) return new HandOut(shown.get(0));
    for (int index = statuses.size() - 1; index >= 0; index -= 1) {
      PaymentStatus status = statuses.get(index).value();
      if (status instanceof PaymentStatus.Unconfirmed
          || (status instanceof PaymentStatus.Open open
              && new BigInteger(open.received()).signum() > 0)) {
        return new HandOut(index);
      }
    }
    return new HandOut(statuses.size() - 1);
  }

  /**
   * The key, in {@link SaleRecords#rests()}, of the invoice with {@code address} and the end {@code
   * expiresAt}.
   *
   * @param address its deposit address
   * @param expiresAt its end
   * @return the key
   */
  public static String restKeyOf(String address, long expiresAt) {
    return address + "/" + expiresAt;
  }

  /**
   * The price of a declared sale as a Fiat: null for a sale in sats.
   *
   * @param price the declared price
   * @return the Fiat, or null
   */
  public static Fiat fiatOf(Checkout.DeclaredPrice price) {
    if (price.currency().equals(Constants.SATS_CURRENCY)) return null;
    return new Fiat(
        Prices.formatDecimal(BigInteger.valueOf(price.minor()), price.digits()),
        price.currency(),
        Long.toString(price.minor()),
        price.digits());
  }
}
