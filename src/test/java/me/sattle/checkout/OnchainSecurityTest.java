package me.sattle.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import me.sattle.checkout.internal.Checkout;
import me.sattle.checkout.internal.Constants;
import me.sattle.checkout.internal.Derive;
import me.sattle.checkout.internal.RandomBytes;
import me.sattle.checkout.internal.Text;
import me.sattle.checkout.internal.crypto.Curve;
import me.sattle.checkout.internal.crypto.Hashes;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.internal.nostr.Events;
import me.sattle.checkout.internal.nostr.Keep;
import me.sattle.checkout.internal.nostr.NostrEvent;
import me.sattle.checkout.internal.nostr.Records;
import me.sattle.checkout.internal.nostr.RelayPool;
import me.sattle.checkout.internal.onchain.Leases;
import me.sattle.checkout.support.Chain;
import me.sattle.checkout.support.FixedRandom;
import me.sattle.checkout.support.Js;
import me.sattle.checkout.support.LocalRelay;
import me.sattle.checkout.support.TestMerchant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Attacks on on-chain payments (S1 to S6; SPEC.md §13, §21, §31), end to end against local relays
 * and fakes of the explorers. Each test states what must hold. As checkout-node's on-chain security
 * tests.
 */
class OnchainSecurityTest {
  private static final List<String> LOT =
      List.of(Chain.depositAddress(1), Chain.depositAddress(2), Chain.depositAddress(3));
  private static final DateTimeFormatter ISO =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private TestMerchant merchant;

  private SattlemeClient client() {
    return client(1000);
  }

  private SattlemeClient client(long relayTimeoutMs) {
    return merchant.clientBuilder().relayTimeoutMs(relayTimeoutMs).build();
  }

  private Derive.Sale saleOf(String ref) {
    return Derive.Sale.of(
        merchant.integrationKey, new Derive.Registry(merchant.registryKey, merchant.reader), ref);
  }

  /** The lease of {@code address} by the sale of {@code ref}, from {@code createdAt}. */
  private NostrEvent leaseBy(String ref, String address, long createdAt, RandomBytes random) {
    return Leases.leaseEvent(
        saleOf(ref),
        new Leases.Terms(
            address,
            1000,
            null,
            null,
            createdAt,
            createdAt + 900,
            new Checkout.DeclaredPrice(1000, 0, "SAT"),
            ref,
            0,
            null),
        createdAt,
        random);
  }

  private NostrEvent leaseBy(String ref, String address) {
    return leaseBy(ref, address, merchant.now, new RandomBytes(new SecureRandom()));
  }

  /** A record anybody writes at a registry address (it is public): a throwaway key signs it. */
  private static NostrEvent junk(String d, long createdAt, int seed, String content) {
    return Events.sign(
        30078,
        createdAt,
        List.of(List.of("d", d)),
        content,
        Hashes.sha256(Text.utf8("throwaway " + seed)),
        new RandomBytes(new SecureRandom()));
  }

  /**
   * A junk record dated beyond 2^53, signed as JavaScript serializes its date (the package's own
   * signer refuses such a date).
   */
  private static JsonObject junkBeyondSafeIntegers(String d, long createdAt, int seed) {
    byte[] secret = Hashes.sha256(Text.utf8("throwaway " + seed));
    String pubkey = Curve.publicKeyOf(secret);
    List<List<String>> tags = List.of(List.of("d", d));
    String id = Events.id(pubkey, createdAt, 30078, tags, "x");
    String sig =
        me.sattle.checkout.internal.Hex.encode(
            Curve.sign(me.sattle.checkout.internal.Hex.decode(id), secret, new byte[32]));
    return Js.obj(
        "id",
        id,
        "pubkey",
        pubkey,
        "created_at",
        createdAt,
        "kind",
        30078,
        "tags",
        tags,
        "content",
        "x",
        "sig",
        sig);
  }

  private static NostrEvent junk(String d, long createdAt, int seed) {
    return junk(d, createdAt, seed, "x");
  }

  /** Every relay of the key takes {@code event}. */
  private void everywhere(NostrEvent event) {
    for (String url : merchant.keyRelays) merchant.net.get(url).take(event);
  }

  private List<LocalRelay> relays() {
    return merchant.keyRelays.stream().map(url -> merchant.net.get(url)).toList();
  }

  private static LocalRelay.Behaviour behaviour(Function<NostrEvent, String> refuse) {
    LocalRelay.Behaviour behaviour = new LocalRelay.Behaviour();
    behaviour.refuseEvent = refuse;
    return behaviour;
  }

  private static LocalRelay.Behaviour delayed(long ms) {
    LocalRelay.Behaviour behaviour = new LocalRelay.Behaviour();
    behaviour.queryDelayMs = ms;
    return behaviour;
  }

  /** What an order's createInvoice handed out at the address: null for none, or a failure. */
  private static String handedOut(Object outcome) {
    if (outcome instanceof InvoicePendingException pending) {
      return pending.getInvoice().onchainAddress();
    }
    if (outcome instanceof Invoice invoice) return invoice.onchainAddress();
    return null;
  }

  private Object attempt(SattlemeClient sattleme, Order order) throws InterruptedException {
    try {
      return sattleme.createInvoice(order);
    } catch (SattlemeException e) {
      return e;
    }
  }

  private static Order sats(String ref, long sats) {
    return Order.builder(ref).sats(sats).build();
  }

  @BeforeEach
  void start() {
    merchant = new TestMerchant().start();
    merchant.publishChannel(merchant.channelValue(100, Map.of("relays", merchant.keyRelays)), 100);
  }

  @AfterEach
  void stop() {
    merchant.stop();
  }

  // S5's floods and S4's last one sign hundreds of records, then read them from every relay:
  // seconds each alone, more on a busy machine (several builds at once, a slow runner). Time enough
  // for that (60 s), and for each page (4 s): what they test is what the read decides, not how fast
  // it is.
  @Nested
  class S5APagedReadOfTheLeasesMissesALeaseEveryRelayHolds {
    private String d() {
      return Leases.leaseAddress(merchant.registryKey, LOT.get(0));
    }

    @BeforeEach
    void lot() {
      merchant.publishLot(List.of(LOT.get(0)), 91);
    }

    private void cap(int cap) {
      for (LocalRelay relay : relays()) {
        LocalRelay.Behaviour behaviour = new LocalRelay.Behaviour();
        behaviour.cap = cap;
        relay.behaviour = behaviour;
      }
    }

    @Test
    void controlAnotherOrdersLeaseKeepsTheAddress() throws Exception {
      everywhere(leaseBy("HOLDER", LOT.get(0)));
      assertNull(client().createInvoice(sats("S5-0", 49_000)).onchainAddress());
    }

    @Test
    @Timeout(60)
    void tiesAPageOfRecordsOfTheLeasesVerySecondWithLowerIds() throws Exception {
      cap(500);
      // At equal created_at, the lowest id first (NIP-01; strfry: the last stored first).
      NostrEvent lease;
      int seed = 0;
      do {
        lease = leaseBy("HOLDER", LOT.get(0), merchant.now, FixedRandom.of("S5/" + seed++));
      } while (lease.id().compareTo("c") < 0);
      everywhere(lease);
      for (int i = 0, made = 0; made < 500; i += 1) {
        NostrEvent record = junk(d(), lease.createdAt(), i);
        if (record.id().compareTo(lease.id()) < 0) {
          everywhere(record);
          made += 1;
        }
      }
      assertNull(client(4000).createInvoice(sats("S5-1", 49_000)).onchainAddress());
    }

    @Test
    @Timeout(60)
    void unusableDatesAPageOfRecordsDatedBeyond2To53() throws Exception {
      cap(500);
      everywhere(leaseBy("HOLDER", LOT.get(0)));
      for (int i = 0; i < 500; i += 1) {
        JsonObject record = junkBeyondSafeIntegers(d(), (1L << 53) + 2L * (i + 1), i);
        // As a relay that keeps any date stores it (strfry refuses one more than 900 s ahead).
        for (LocalRelay relay : relays()) relay.store(record);
      }
      assertNull(client(4000).createInvoice(sats("S5-2", 49_000)).onchainAddress());
    }

    @Test
    @Timeout(60)
    void volumeMoreThan20PagesOfRecordsNewerThanTheLease() throws Exception {
      cap(100);
      everywhere(
          leaseBy("HOLDER", LOT.get(0), merchant.now - 600, new RandomBytes(new SecureRandom())));
      for (int i = 0; i < 2001; i += 1) everywhere(junk(d(), merchant.now - 599 + (i % 500), i));
      assertNull(client(4000).createInvoice(sats("S5-3", 49_000)).onchainAddress());
    }

    @Test
    @Timeout(60)
    void orderARelayThatAnswersAFilterWithoutALimitOldestFirst() throws Exception {
      for (LocalRelay relay : relays()) {
        LocalRelay.Behaviour behaviour = new LocalRelay.Behaviour();
        behaviour.oldestFirstWithoutLimit = true;
        relay.behaviour = behaviour;
      }
      everywhere(leaseBy("HOLDER", LOT.get(0)));
      for (int i = 0; i < 1000; i += 1) everywhere(junk(d(), merchant.now - 7000 + i, i));
      assertNull(client(4000).createInvoice(sats("S5-4", 49_000)).onchainAddress());
    }
  }

  @Nested
  class S4RecordsOfANewerFormatSignedByAnyKeyHoldEveryAddress {
    @Test
    void anEmptyContentAtEachRegistryAddressTakesNothingAway() throws Exception {
      merchant.publishLot(LOT, 91);
      for (int i = 0; i < LOT.size(); i += 1) {
        everywhere(
            junk(
                Leases.leaseAddress(merchant.registryKey, LOT.get(i)),
                merchant.now,
                10_000 + i,
                ""));
      }
      assertNotNull(client().createInvoice(sats("S4-1", 49_000)).onchainAddress());
    }

    @Test
    void aLeaseOfANewerFormatSealedWithTheRegistryKeyHoldsItsAddressAndIsWarnedOf()
        throws Exception {
      merchant.publishLot(List.of(LOT.get(0)), 91);
      String d = Leases.leaseAddress(merchant.registryKey, LOT.get(0));
      byte[] secret = Hashes.sha256(Text.utf8("a sale of a newer version"));
      NostrEvent newer =
          Records.seal(
              Constants.LEASE_RECORD_TYPE,
              Js.obj("version", 2),
              secret,
              Derive.outerKey(merchant.registryKey, d, Curve.publicKeyOf(secret)),
              d,
              merchant.now - 60,
              List.of(),
              new RandomBytes(new SecureRandom()));
      everywhere(newer);
      Invoice invoice = client().createInvoice(sats("S4-3", 49_000));
      assertNull(invoice.onchainAddress());
      assertTrue(
          merchant.warnings.stream()
              .anyMatch(
                  warning -> warning.contains("a lease of a deposit address has a newer format")),
          merchant.warnings.toString());
    }

    @Test
    @Timeout(60)
    void eachSuchRecordCostsNoSignatureCheck() throws Exception {
      merchant.publishLot(List.of(LOT.get(0)), 91);
      String d = Leases.leaseAddress(merchant.registryKey, LOT.get(0));
      for (int i = 0; i < 1000; i += 1) everywhere(junk(d, merchant.now - i, 20_000 + i, "#"));
      long before = Curve.verifications();
      client(4000).createInvoice(sats("S4-2", 49_000));
      // The channel, the lot, the order's own records: a handful. Never one per junk record.
      assertTrue(Curve.verifications() - before < 50, (Curve.verifications() - before) + "");
    }
  }

  @Nested
  class S1AnEarlierInvoiceWhoseLeaseWasNeverConfirmedIsHandedOutAgain {
    @Test
    void anOrderPaidOnchainOnlyWhoseLeaseReachedOneRelay() throws Exception {
      merchant.publishLot(List.of(LOT.get(0)), 91);
      merchant.pay = Js.with(merchant.pay, "maxSendable", 1_000_000);
      List<LocalRelay> relays = relays();
      LocalRelay r1 = relays.get(0);
      LocalRelay r2 = relays.get(1);
      LocalRelay r3 = relays.get(2);
      String author = saleOf("S1-R").author;
      long start = merchant.now;
      // r1 takes the order's lease and nothing after it (its withdrawal is lost); r2 and r3
      // refuse.
      r1.behaviour =
          behaviour(e -> e.pubkey().equals(author) && e.createdAt() > start ? "error: down" : null);
      r2.behaviour = behaviour(e -> e.pubkey().equals(author) ? "error: down" : null);
      r3.behaviour = behaviour(e -> e.pubkey().equals(author) ? "error: down" : null);
      assertThrows(NetworkException.class, () -> client().createInvoice(sats("S1-R", 49_000)));

      // Another order while r1 is slow: r2 and r3, which never got that lease, answer first.
      r1.behaviour = delayed(500);
      r2.behaviour = new LocalRelay.Behaviour();
      r3.behaviour = new LocalRelay.Behaviour();
      Object x = attempt(client(), sats("S1-X", 49_000));
      boolean xHolds = x instanceof Invoice invoice && LOT.get(0).equals(invoice.onchainAddress());

      // The first order again while r2 is slow: r1, which holds its lease, answers.
      r1.behaviour = new LocalRelay.Behaviour();
      r2.behaviour = delayed(500);
      Object again = attempt(client(), sats("S1-R", 49_000));
      // Never both: the address handed out to this order while the other holds it.
      assertFalse(xHolds && LOT.get(0).equals(handedOut(again)));
    }

    @Test
    void anInvoiceWithLightningWhoseDeclarationFailedItsWithdrawalOnOneRelay() throws Exception {
      merchant.publishLot(List.of(LOT.get(0)), 91);
      List<LocalRelay> relays = relays();
      LocalRelay r1 = relays.get(0);
      LocalRelay r2 = relays.get(1);
      LocalRelay r3 = relays.get(2);
      String author = saleOf("S1-L").author;
      String d = Leases.leaseAddress(merchant.registryKey, LOT.get(0));
      long start = merchant.now;
      // r1: the lease and the declaration, not the withdrawal. r2: the lease and the withdrawal,
      // not the declaration. r3: the lease only.
      r1.behaviour =
          behaviour(e ->
              e.pubkey().equals(author) && d.equals(e.tag("d")) && e.createdAt() > start
                  ? "error: down"
                  : null);
      r2.behaviour =
          behaviour(e -> e.pubkey().equals(author) && !d.equals(e.tag("d")) ? "error: down" : null);
      r3.behaviour =
          behaviour(e ->
              e.pubkey().equals(author) && (!d.equals(e.tag("d")) || e.createdAt() > start)
                  ? "error: down"
                  : null);
      assertThrows(NetworkException.class, () -> client().createInvoice(sats("S1-L", 49_000)));

      // Another order while r1 is slow: r2's withdrawal frees the address.
      r1.behaviour = delayed(500);
      r2.behaviour = new LocalRelay.Behaviour();
      r3.behaviour = new LocalRelay.Behaviour();
      Invoice x = client().createInvoice(sats("S1-X", 49_000));
      boolean xHolds = LOT.get(0).equals(x.onchainAddress());

      // The first order again while r2 is slow.
      r1.behaviour = new LocalRelay.Behaviour();
      r2.behaviour = delayed(500);
      Object again = attempt(client(), sats("S1-L", 49_000));
      assertFalse(xHolds && LOT.get(0).equals(handedOut(again)));
    }
  }

  @Nested
  class S2ATransactionEvictedThenBroadcastAgainDuringALaterLeasePaysTwoOrders {
    @Test
    void theFirstOrdersWitnessNamesIt() throws Exception {
      merchant.publishLot(List.of(LOT.get(0)), 91);
      SattlemeClient sattleme = client();
      Invoice p =
          sattleme.createInvoice(Order.builder("S2-P").sats(49_000).durationSeconds(600).build());
      String address = p.onchainAddress();
      // Paid with a very low fee, seen while P's lease runs: P's witness.
      JsonObject t = Chain.payment(Chain.txid("S2 low fee"), address, 49_000);
      String txid = Js.text(t, "txid");
      merchant.chain.add(address, t);
      assertEquals("unconfirmed", sattleme.getStatus(p).state());
      // Evicted from the mempools: the explorer lists nothing for the address.
      merchant.chain.drop(address, txid);
      merchant.now = p.expiresAt() + 120 + 301;
      assertEquals(new PaymentStatus.Expired("0"), sattleme.getStatus(p));

      Invoice n =
          sattleme.createInvoice(Order.builder("S2-N").sats(49_000).durationSeconds(600).build());
      // The payer's wallet broadcasts T again during N's lease; it is mined.
      merchant.chain.add(address, t);
      if (address.equals(n.onchainAddress())) sattleme.getStatus(n);
      merchant.chain.confirm(address, txid, merchant.chain.height - 5);
      // The payer asks for P again: the shop checks P's earlier invoice.
      Object pAgain = attempt(sattleme, sats("S2-P", 49_000));
      boolean nPaid =
          address.equals(n.onchainAddress()) && sattleme.getStatus(n).state().equals("paid");
      boolean pPaid = pAgain instanceof OrderPaidException;
      // One transaction, one order.
      assertFalse(nPaid && pPaid);
    }

    @Test
    void noCheckSawItWhileTheFirstLeaseRanBlockCyphersTime() throws Exception {
      merchant.publishLot(List.of(LOT.get(0)), 91);
      SattlemeClient sattleme = client();
      Invoice p =
          sattleme.createInvoice(Order.builder("S2-Q").sats(49_000).durationSeconds(600).build());
      String address = p.onchainAddress();
      JsonObject t = Chain.payment(Chain.txid("S2 unseen"), address, 49_000);
      String txid = Js.text(t, "txid");
      // BlockCypher received it while P's lease ran; no check of P saw it; it is evicted.
      merchant.chain.received.put(txid, ISO.format(Instant.ofEpochSecond(p.createdAt() + 10)));
      merchant.now = p.expiresAt() + 120 + 301;
      assertEquals(new PaymentStatus.Expired("0"), sattleme.getStatus(p));

      Invoice n =
          sattleme.createInvoice(Order.builder("S2-M").sats(49_000).durationSeconds(600).build());
      assertEquals(address, n.onchainAddress());
      merchant.chain.add(address, t);
      PaymentStatus.Unconfirmed status =
          assertInstanceOf(PaymentStatus.Unconfirmed.class, sattleme.getStatus(n));
      assertEquals("49000", status.received());
      merchant.chain.confirm(address, txid, merchant.chain.height - 5);
      Object pAgain = attempt(sattleme, sats("S2-Q", 49_000));
      boolean nPaid = sattleme.getStatus(n).state().equals("paid");
      boolean pPaid = pAgain instanceof OrderPaidException;
      assertFalse(nPaid && pPaid);
    }
  }

  @Nested
  class S3TheWitnessOfAnEarlierInvoiceOfTheOrderCountsForALaterOne {
    @Test
    void anUnderpaymentOfAnExpiredInvoiceCountsAgainForTheNextOne() throws Exception {
      merchant.publishLot(List.of(LOT.get(0)), 91);
      // On-chain only: no rest invoice is asked.
      merchant.pay = Js.with(merchant.pay, "maxSendable", 1_000_000);
      SattlemeClient sattleme = client();
      Invoice first =
          sattleme.createInvoice(Order.builder("S3-R").sats(49_000).durationSeconds(600).build());
      String address = first.onchainAddress();
      JsonObject t = Chain.payment(Chain.txid("S3 part"), address, 30_000);
      merchant.chain.add(address, t);
      PaymentStatus.Open open =
          assertInstanceOf(PaymentStatus.Open.class, sattleme.getStatus(first));
      assertEquals("30000", open.received());
      merchant.now = first.expiresAt() + 121;
      // Underpaid for good: the merchant settles the 30 000 sats with the customer.
      assertEquals(new PaymentStatus.Expired("30000"), sattleme.getStatus(first));
      // T leaves the mempool (evicted).
      merchant.chain.drop(address, Js.text(t, "txid"));
      merchant.now = first.expiresAt() + 120 + 301;
      Object second =
          attempt(sattleme, Order.builder("S3-R").sats(49_000).durationSeconds(600).build());
      if (second instanceof SattlemeException) {
        // No address for it (the fix): nothing can count twice.
        assertInstanceOf(OnchainUnavailableException.class, second);
        return;
      }
      Invoice next = (Invoice) second;
      assertEquals(address, next.onchainAddress());
      // T broadcast again after the second invoice's end: late, whatever its witness.
      merchant.now = next.expiresAt() + 3600;
      merchant.chain.add(address, t);
      assertEquals(new PaymentStatus.Expired("0"), sattleme.getStatus(next));
    }

    @Test
    void aWitnessOfTheEarlierInvoiceMadeAfterTheLaterOneLeasedTheAddress() throws Exception {
      merchant.publishLot(List.of(LOT.get(0)), 91);
      merchant.pay = Js.with(merchant.pay, "maxSendable", 1_000_000);
      SattlemeClient sattleme = client();
      Invoice first =
          sattleme.createInvoice(Order.builder("S3-Q").sats(49_000).durationSeconds(600).build());
      String address = first.onchainAddress();
      JsonObject t = Chain.payment(Chain.txid("S3 unseen part"), address, 30_000);
      // Received by BlockCypher while the first lease ran; no check saw it; then evicted.
      merchant.chain.received.put(
          Js.text(t, "txid"), ISO.format(Instant.ofEpochSecond(first.createdAt() + 10)));
      // Beyond the reads of leases (7200 s), where the order's own records keep it off the
      // address.
      merchant.now = first.createdAt() + 7300;
      assertEquals(new PaymentStatus.Expired("0"), sattleme.getStatus(first));
      Invoice second =
          sattleme.createInvoice(Order.builder("S3-Q").sats(49_000).durationSeconds(600).build());
      assertEquals(address, second.onchainAddress());
      // T comes back after the second invoice's end; the first invoice is checked first.
      merchant.now = second.expiresAt() + 3600;
      merchant.chain.add(address, t);
      List<PaymentStatus> statuses = List.of(sattleme.getStatus(first), sattleme.getStatus(second));
      long counted =
          statuses.stream()
              .filter(status ->
                  (status instanceof PaymentStatus.Open open && open.received().equals("30000"))
                      || (status instanceof PaymentStatus.Expired expired
                          && expired.received().equals("30000")))
              .count();
      // T was received once: it counts for one invoice at most.
      assertTrue(counted <= 1, statuses.toString());
    }
  }

  @Test
  void s6AHostileRelayHidesALeaseTheConfirmationReadsOnEveryRelay() throws Exception {
    merchant.publishLot(List.of(LOT.get(0)), 91);
    List<LocalRelay> relays = relays();
    LocalRelay h = relays.get(1);
    LocalRelay r2 = relays.get(2);
    NostrEvent lease = leaseBy("S6-A", LOT.get(0));
    // r1 refused it.
    h.take(lease);
    r2.take(lease);
    LocalRelay.Behaviour hiding = new LocalRelay.Behaviour();
    hiding.hide = event -> lease.id().equals(Js.text(event, "id"));
    h.behaviour = hiding;
    // r2 answers its reads 1.5 s late, well within the timeout (4 s): H and r1 answer first,
    // whatever the load of the machine running the test.
    r2.behaviour = delayed(1500);
    Invoice invoice = client(4000).createInvoice(sats("S6-B", 49_000));
    assertNull(invoice.onchainAddress());
  }

  @Nested
  class CopiesOfARecordUnderIdsOfTheirOwnAReadKeepsNoneOfThemWhole {
    /** {@code event} with another tag and date, its id recomputed, its signature as it is. */
    private JsonObject copyOf(NostrEvent event, int index, String filler) {
      List<List<String>> tags = new ArrayList<>(event.tags());
      tags.add(List.of("x", filler));
      long createdAt = event.createdAt() - 1 - index;
      return new NostrEvent(
              Events.id(event.pubkey(), createdAt, event.kind(), tags, event.content()),
              event.pubkey(),
              createdAt,
              event.kind(),
              tags,
              event.content(),
              event.sig())
          .toJson();
    }

    @Test
    @Timeout(60)
    void aLeaseOfEachCopyOnlyItsIdAuthorDateAndSignatureAreKept() throws Exception {
      NostrEvent lease =
          leaseBy("OTHER", LOT.get(0), merchant.now - 60, new RandomBytes(new SecureRandom()));
      everywhere(lease);
      String filler = "y".repeat(50_000);
      // Relays that keep them all (anybody can write at a lease's address): read on any
      // majority.
      List<JsonObject> copies = new ArrayList<>();
      for (int i = 0; i < 200; i += 1) copies.add(copyOf(lease, i, filler));
      for (LocalRelay relay : relays()) for (JsonObject copy : copies) relay.store(copy);
      RelayPool pool =
          new RelayPool(
              merchant.net, 2000, new RandomBytes(new SecureRandom()), () -> merchant.now);
      try {
        Leases.LeaseRead read =
            Leases.readLeases(
                pool,
                merchant.registryKey,
                List.of(LOT.get(0)),
                merchant.keyRelays,
                merchant.now,
                FixedRandom.of("1"),
                false,
                merchant.warnings::add);
        List<Leases.LeaseRecord> records = read.records().get(LOT.get(0));
        // Every copy opens as the lease does (its id is its hash, its content decrypts).
        assertEquals(201, records.size());
        // Of each, only what Leases.LeaseEvent holds: its id, author, date and signature.
        long kept = 0;
        for (Leases.LeaseRecord record : records) {
          Leases.LeaseEvent event = record.event();
          kept += event.id().length() + event.pubkey().length() + event.sig().length() + 20;
        }
        assertTrue(kept < 201 * 400, kept + " characters");
        // Only the lease is signed: the walk still finds the address leased.
        assertEquals(
            List.of(lease.id()),
            records.stream()
                .filter(Leases.LeaseRecord::signed)
                .map(record -> record.event().id())
                .toList());
        assertTrue(Leases.isLeased(records, merchant.now));
      } finally {
        pool.close();
      }
    }

    @Test
    void aSignedRecordACopyIsNotKeptItsSignatureDoesNotCheckOut() {
      byte[] encryptionKey = Derive.channelEncryptionKey(merchant.integrationKey);
      Keep keep =
          Records.recordsThatOpen(
              (d, author) -> author.equals(merchant.team) ? encryptionKey : null);
      NostrEvent channel =
          merchant.channelEvent(merchant.channelValue(100), 100, merchant.teamSecret);
      JsonValue json = channel.toJson();
      // Its NIP-01 members, as sent.
      assertEquals(json, keep.keep(json));
      assertNull(keep.keep(copyOf(channel, 0, "z".repeat(1000))));
      assertNull(keep.keep(Js.with(json, "sig", "00".repeat(64))));
      // Another key's: not opened, not kept.
      assertNull(keep.keep(Js.with(json, "pubkey", "ab".repeat(32))));
    }
  }
}
