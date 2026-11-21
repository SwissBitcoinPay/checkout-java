package me.sattle.checkout;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import me.sattle.checkout.internal.Checkout;
import me.sattle.checkout.internal.Constants;
import me.sattle.checkout.internal.Derive;
import me.sattle.checkout.internal.Hex;
import me.sattle.checkout.internal.RandomBytes;
import me.sattle.checkout.internal.Text;
import me.sattle.checkout.internal.crypto.Nip44;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.internal.nostr.Events;
import me.sattle.checkout.internal.nostr.NostrEvent;
import me.sattle.checkout.internal.nostr.Records;
import me.sattle.checkout.internal.onchain.Explorer;
import me.sattle.checkout.internal.onchain.Leases;
import me.sattle.checkout.internal.onchain.Witnesses;
import me.sattle.checkout.support.Chain;
import me.sattle.checkout.support.Js;
import me.sattle.checkout.support.Lightning;
import me.sattle.checkout.support.LocalRelay;
import me.sattle.checkout.support.Once;
import me.sattle.checkout.support.RelayNet;
import me.sattle.checkout.support.TestMerchant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * On-chain payments (SPEC.md §31), end to end against local relays and fakes of breez.tips, of the
 * explorers (REST and WebSocket) and of BlockCypher: the methods of an order by its amount, a lot
 * read and an address leased, two servers leasing one address, a paid address skipped, the
 * witnesses written and read, the statuses of both parts, an order paid on-chain found again,
 * waitForPayment woken by the explorer's WebSocket. As checkout-node's on-chain tests.
 */
class OnchainTest {
  private static final String ORIGIN = "https://pay.example";
  private static final List<String> LOT =
      List.of(Chain.depositAddress(1), Chain.depositAddress(2), Chain.depositAddress(3));
  private static final DateTimeFormatter ISO =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private TestMerchant merchant;

  private SattlemeClient client() {
    return merchant.clientBuilder().relayTimeoutMs(1000).build();
  }

  @BeforeEach
  void start() {
    merchant = new TestMerchant().start();
    merchant.publishChannel(
        merchant.channelValue(
            100, Map.of("relays", merchant.keyRelays, "checkout", Js.obj("origin", ORIGIN))),
        100);
    merchant.publishLot(LOT, 90);
  }

  @AfterEach
  void stop() {
    merchant.stop();
  }

  /**
   * Once each relay received as many events (a publication ends on a majority, the last relay's
   * copy may be on its way), what the first relay received.
   */
  private List<NostrEvent> received() throws InterruptedException {
    merchant.net.settle();
    long deadline = System.nanoTime() + 2_000_000_000L;
    while (merchant.keyRelays.stream()
                .map(url -> merchant.net.get(url).received.size())
                .distinct()
                .count()
            > 1
        && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    return merchant.net.get(merchant.keyRelays.get(0)).received;
  }

  /** The records the first relay holds now. */
  private List<JsonValue> held() throws InterruptedException {
    received();
    return merchant.net.get(merchant.keyRelays.get(0)).events;
  }

  /** A lease or a withdrawal the relays received, with its whole event. */
  record LeaseSeen(Leases.LeaseRecord record, NostrEvent event) {}

  /** The leases and withdrawals of {@code address} the relays received, opened by the merchant. */
  private List<LeaseSeen> leasesOf(String address, List<NostrEvent> received) {
    String d = Leases.leaseAddress(merchant.registryKey, address);
    List<LeaseSeen> found = new ArrayList<>();
    for (NostrEvent event : received) {
      Leases.LeaseRecord record = Leases.openLeaseRecord(event.toJson(), merchant.registryKey, d);
      if (record != null) found.add(new LeaseSeen(record, event));
    }
    return found;
  }

  private List<LeaseSeen> leasesOf(String address) throws InterruptedException {
    return leasesOf(address, received());
  }

  private static List<String> kinds(List<LeaseSeen> leases) {
    return leases.stream()
        .map(lease -> lease.record().outside().kind().name().toLowerCase(java.util.Locale.ROOT))
        .toList();
  }

  /** The inside of a lease, as the merchant's app opens it (registry key and reading key). */
  private JsonObject openAsMerchant(NostrEvent event) {
    String d = event.tag("d");
    JsonValue outside =
        Js.parse(
            DeclarationTest.decrypt(
                event.content(), Derive.outerKey(merchant.registryKey, d, event.pubkey())));
    assertEquals("registry-onchain", Js.text(outside, "t"));
    JsonObject inside =
        (JsonObject)
            Js.parse(
                DeclarationTest.decrypt(
                    Js.text(Js.get(outside, "v"), "sealed"),
                    Nip44.conversationKey(merchant.readerSecret, event.pubkey())));
    JsonObject merged = new JsonObject().put("end", Js.get(Js.get(outside, "v"), "end"));
    for (Map.Entry<String, JsonValue> entry : inside.entries()) {
      merged.put(entry.getKey(), entry.getValue());
    }
    return merged;
  }

  /** Whether {@code event} is a lease's mark at {@code d}: the lease again, confirmed. */
  private boolean isMark(NostrEvent event, String d) {
    if (!d.equals(event.tag("d"))) return false;
    Leases.LeaseRecord record = Leases.openLeaseRecord(event.toJson(), merchant.registryKey, d);
    return record != null
        && record.outside().kind() == Leases.Kind.LEASE
        && Js.of(true).equals(openAsMerchant(event).get("confirmed"));
  }

  /** A witness the relays hold, and when it was seen. */
  record SeenWitness(Witnesses.Witness witness, long seenAt) {}

  /** The witnesses the relays hold, as the merchant's app reads them (by the seen author). */
  private List<SeenWitness> witnesses() throws InterruptedException {
    Derive.SeenKeys seen = Derive.SeenKeys.of(merchant.integrationKey);
    List<SeenWitness> found = new ArrayList<>();
    for (JsonValue event : held()) {
      Witnesses.Opened opened = Witnesses.openWitness(event, seen);
      if (opened != null && !opened.isNewer()) {
        found.add(new SeenWitness(opened.witness(), opened.event().createdAt()));
      }
    }
    return found;
  }

  private Derive.Sale saleOf(String ref) {
    return Derive.Sale.of(
        merchant.integrationKey, new Derive.Registry(merchant.registryKey, merchant.reader), ref);
  }

  private static Order sats(String ref, long sats) {
    return Order.builder(ref).sats(sats).build();
  }

  private static void until(BooleanSupplier condition) throws InterruptedException {
    for (int i = 0; i < 300 && !condition.getAsBoolean(); i += 1) Thread.sleep(10);
    assertTrue(condition.getAsBoolean());
  }

  @Nested
  class TheMethodsOfAnOrderByItsAmount {
    @Test
    void leasesAnAddressFrom1000SatsBeforeTheInvoiceIsDeclared() throws Exception {
      Invoice invoice =
          client().createInvoice(Order.builder("ORD-1").sats(49_000).note("Order 1").build());
      assertTrue(LOT.contains(invoice.onchainAddress()));
      String address = invoice.onchainAddress();
      assertTrue(invoice.bolt11().startsWith("lnbc"));
      assertEquals(
          "bitcoin:" + address + "?amount=0.00049&lightning=" + invoice.bolt11(),
          invoice.paymentUri());
      // The explorer was asked whether the address was paid already.
      assertTrue(
          merchant.chain.asked.contains(Constants.MEMPOOL_URL + "/address/" + address + "/txs"));
      // The lease first, then its mark once confirmed, then the declaration: an address is never
      // shown before its lease is confirmed.
      Derive.Sale sale = saleOf("ORD-1");
      List<NostrEvent> received = received();
      String d = Leases.leaseAddress(merchant.registryKey, address);
      assertEquals(d, received.get(0).tag("d"));
      assertEquals(3, received.size());
      assertTrue(received.stream().allMatch(event -> event.pubkey().equals(sale.author)));
      assertTrue(received.get(1).createdAt() > received.get(0).createdAt());
      assertEquals(Js.of(false), openAsMerchant(received.get(0)).get("confirmed"));
      assertEquals(
          Js.obj(
              "end",
              invoice.expiresAt() + 120,
              "version",
              1,
              "address",
              address,
              "sats",
              49000,
              "invoice",
              invoice.bolt11(),
              "verifyUrl",
              invoice.verifyUrl(),
              "createdAt",
              invoice.createdAt(),
              "expiresAt",
              invoice.expiresAt(),
              "sale",
              Js.obj(
                  "key",
                  Hex.encode(sale.key),
                  "fiat",
                  Js.obj("minor", 49000, "digits", 0, "currency", "SAT"),
                  "note",
                  "Order 1"),
              "taker",
              sale.taker,
              "confirmed",
              true),
          openAsMerchant(received.get(1)));
    }

    @Test
    void isLightningOnlyBelow1000SatsTheLotIsNotEvenRead() throws Exception {
      Invoice invoice = client().createInvoice(sats("ORD-2", 999));
      assertNull(invoice.onchainAddress());
      assertEquals("lightning:" + invoice.bolt11(), invoice.paymentUri());
      assertEquals(List.of(), merchant.chain.asked);
    }

    @Test
    void isOnchainOnlyAboveTheLightningAddressesMaximum() throws Exception {
      merchant.pay = Js.with(merchant.pay, "maxSendable", 4_000_000_000L);
      Invoice invoice =
          client()
              .createInvoice(Order.builder("ORD-3").sats("150000000").durationSeconds(600).build());
      assertNull(invoice.bolt11());
      assertNull(invoice.paymentHash());
      assertNull(invoice.verifyUrl());
      assertEquals("150000000", invoice.sats());
      assertEquals(merchant.now, invoice.createdAt());
      assertEquals(merchant.now + 600, invoice.expiresAt());
      assertEquals("bitcoin:" + invoice.onchainAddress() + "?amount=1.5", invoice.paymentUri());
      // No invoice asked, nothing declared: the lease alone, then its mark.
      assertEquals(0, merchant.invoices.size());
      List<NostrEvent> received = received();
      assertEquals(2, received.size());
      JsonObject mark = openAsMerchant(received.get(1));
      assertEquals(Js.of(true), mark.get("confirmed"));
      assertEquals(Js.of(null), mark.get("invoice"));
      assertEquals(Js.of(null), mark.get("verifyUrl"));
      assertEquals(merchant.now, Js.integer(mark, "createdAt"));
      assertEquals(merchant.now + 600, Js.integer(mark, "expiresAt"));
      assertEquals(merchant.now + 720, Js.integer(mark, "end"));
      assertEquals(
          new PaymentStatus.Open(invoice.expiresAt(), "0", null), client().getStatus(invoice));
    }

    @Test
    void isLightningOnlyWithoutALotAndFailsOnchainOnly() throws Exception {
      for (String relay : merchant.keyRelays) merchant.net.get(relay).events.clear();
      merchant.publishChannel(
          merchant.channelValue(100, Map.of("relays", merchant.keyRelays)), 100);
      SattlemeClient sattleme = client();
      assertEquals(0, sattleme.merchant().onchainAddresses());
      assertNull(sattleme.createInvoice(sats("ORD-4", 49_000)).onchainAddress());
      merchant.pay = Js.with(merchant.pay, "maxSendable", 1_000_000);
      assertThrows(
          OnchainUnavailableException.class, () -> sattleme.createInvoice(sats("ORD-5", 49_000)));
      // Nothing published for that order.
      assertEquals(1, merchant.invoices.size());
    }

    @Test
    void isLightningOnlyWhenTheExplorerDoesNotAnswerAndNetworkErrorOnchainOnly() throws Exception {
      merchant.chain.down.add("explorer");
      SattlemeClient sattleme = client();
      assertNull(sattleme.createInvoice(sats("ORD-6", 49_000)).onchainAddress());
      merchant.pay = Js.with(merchant.pay, "maxSendable", 1_000_000);
      assertThrows(NetworkException.class, () -> sattleme.createInvoice(sats("ORD-7", 49_000)));
    }

    @Test
    void refusesMoreThan21MillionBitcoins() {
      InvalidOrderException error =
          assertThrows(
              InvalidOrderException.class,
              () ->
                  client().createInvoice(Order.builder("ORD-8").sats("2100000000000001").build()));
      assertEquals("sats", error.getParam());
    }

    @Test
    void countsTheLotsAddressesInMerchant() throws Exception {
      assertEquals(3, client().merchant().onchainAddresses());
    }
  }

  @Nested
  class LeasingAnAddress {
    @Test
    void asksNoPageOfTheLeasesOnceTheReadOfTheNamedAddressesFailed() throws Exception {
      // SPEC.md §13, §4: the leases of the lot's addresses (a complete read, page after page) and
      // the witnesses naming them are read at once. Here the second fails (two relays of three
      // never answer it: network, at its 1 s) while the first still pages, a page each 300 ms
      // through 2 100 records: once createInvoice failed, the relays are asked nothing more.
      merchant.publishLot(List.of(LOT.get(0)), 91);
      String d = Leases.leaseAddress(merchant.registryKey, LOT.get(0));
      for (int r = 0; r < merchant.keyRelays.size(); r += 1) {
        LocalRelay relay = merchant.net.get(merchant.keyRelays.get(r));
        for (int i = 0; i < 2100; i += 1) {
          String hex = Integer.toHexString(i);
          relay.store(
              Js.obj(
                  "id",
                  "0".repeat(64 - hex.length()) + hex,
                  "pubkey",
                  "ab".repeat(32),
                  "created_at",
                  merchant.now - i,
                  "kind",
                  30078,
                  "tags",
                  List.of(List.of("d", d)),
                  "content",
                  "",
                  "sig",
                  "00".repeat(64)));
        }
        LocalRelay.Behaviour slow = new LocalRelay.Behaviour();
        slow.queryDelayMs = 300;
        if (r < 2) slow.muteQuery = filter -> filter.has("#o");
        relay.behaviour = slow;
      }
      SattlemeClient sattleme = client();
      assertThrows(NetworkException.class, () -> sattleme.createInvoice(sats("L-20", 49_000)));
      List<Integer> asked = new ArrayList<>();
      for (String url : merchant.keyRelays) {
        LocalRelay relay = merchant.net.get(url);
        // The read of the leases was paging when the call failed.
        assertTrue(relay.queries.stream().anyMatch(filter -> filter.has("until")), url);
        asked.add(relay.queries.size());
      }
      Thread.sleep(1200);
      for (int r = 0; r < merchant.keyRelays.size(); r += 1) {
        LocalRelay relay = merchant.net.get(merchant.keyRelays.get(r));
        assertEquals(asked.get(r).intValue(), relay.queries.size(), merchant.keyRelays.get(r));
      }
    }

    @Test
    void neverLeasesAnAddressAnotherOrderHoldsUntilItsLeaseEnded() throws Exception {
      merchant.publishLot(List.of(LOT.get(0)), 91);
      SattlemeClient sattleme = client();
      Invoice first =
          sattleme.createInvoice(Order.builder("L-1").sats(49_000).durationSeconds(60).build());
      assertEquals(LOT.get(0), first.onchainAddress());
      assertNull(sattleme.createInvoice(sats("L-2", 49_000)).onchainAddress());
      // Its lease ends 120 s after the invoice's end; another server's clock may be 300 s behind.
      merchant.now = first.expiresAt() + 120 + 300;
      assertNull(sattleme.createInvoice(sats("L-3", 49_000)).onchainAddress());
      merchant.now += 1;
      assertEquals(LOT.get(0), sattleme.createInvoice(sats("L-4", 49_000)).onchainAddress());
    }

    @Test
    void skipsAnAddressPaidAlreadyAndPublishesItsWitnessNone() throws Exception {
      JsonObject paid =
          Chain.payment(
              Chain.txid("old"),
              LOT.get(0),
              20_000,
              List.of(Chain.txid("coin of " + Chain.txid("old")) + ":0"),
              960_000L);
      merchant.chain.add(LOT.get(0), paid);
      merchant.publishLot(List.of(LOT.get(0), LOT.get(1)), 91);
      Invoice invoice = client().createInvoice(sats("L-5", 49_000));
      assertEquals(LOT.get(1), invoice.onchainAddress());
      // Whichever was tried first: the paid one is never leased.
      assertEquals(List.of(), leasesOf(LOT.get(0)));
      merchant.publishLot(List.of(LOT.get(0)), 92);
      merchant.pay = Js.with(merchant.pay, "maxSendable", 1_000_000);
      assertThrows(
          OnchainUnavailableException.class, () -> client().createInvoice(sats("L-6", 49_000)));
      String txid = Js.text(paid, "txid");
      assertEquals(
          List.of(
              new SeenWitness(
                  new Witnesses.Witness(
                      LOT.get(0),
                      txid,
                      null,
                      20_000,
                      List.of(Chain.txid("coin of " + txid) + ":0"),
                      false,
                      null),
                  merchant.now)),
          witnesses());
    }

    @Test
    void withdrawsItsLeaseUnconfirmedWhenAnotherServerLeasedTheAddressAtOnce() throws Exception {
      merchant.publishLot(List.of(LOT.get(0)), 91);
      String d = Leases.leaseAddress(merchant.registryKey, LOT.get(0));
      Derive.Sale other = saleOf("ANOTHER-SERVER");
      NostrEvent competing =
          Leases.leaseEvent(
              other,
              new Leases.Terms(
                  LOT.get(0),
                  1000,
                  null,
                  null,
                  merchant.now,
                  merchant.now + 900,
                  new Checkout.DeclaredPrice(1000, 0, "SAT"),
                  "Another order",
                  0,
                  null),
              merchant.now,
              new RandomBytes(new SecureRandom()));
      Once raced = new Once();
      // Another server's lease of the same address reaches every relay right after this one's.
      for (String url : merchant.keyRelays) {
        LocalRelay.Behaviour behaviour = new LocalRelay.Behaviour();
        behaviour.onEvent =
            (event, relay) -> {
              if (!d.equals(event.tag("d")) || event.pubkey().equals(other.author)) return;
              raced.run(() -> {
                for (String target : merchant.keyRelays) {
                  merchant.net.get(target).take(competing);
                }
              });
            };
        merchant.net.get(url).behaviour = behaviour;
      }
      Invoice invoice = client().createInvoice(sats("L-7", 49_000));
      // It saw the other lease after its own was on a majority: not confirmed, Lightning only.
      // Its lease, unmarked, is withdrawn: it gives the invoice no address.
      assertNull(invoice.onchainAddress());
      List<LeaseSeen> records =
          leasesOf(LOT.get(0)).stream()
              .filter(seen -> !seen.event().pubkey().equals(other.author))
              .toList();
      assertEquals(List.of("lease", "withdrawn"), kinds(records));
      assertEquals(Js.of(false), openAsMerchant(records.get(0).event()).get("confirmed"));
      assertTrue(records.get(1).event().createdAt() > records.get(0).event().createdAt());
      DeclaredInvoice found = client().findInvoices("L-7").get(0);
      assertEquals(invoice.bolt11(), found.bolt11());
      assertNull(found.onchainAddress());
    }

    @Test
    void leavesItsLeaseConfirmedWhenTheDeclarationFailsItHoldsTheAddress() throws Exception {
      merchant.publishLot(List.of(LOT.get(0)), 91);
      String d = Leases.leaseAddress(merchant.registryKey, LOT.get(0));
      for (String url : merchant.keyRelays.subList(0, 2)) {
        LocalRelay.Behaviour behaviour = new LocalRelay.Behaviour();
        // Leases and withdrawals are taken, declarations refused.
        behaviour.refuseEvent = event -> d.equals(event.tag("d")) ? null : "blocked: no";
        merchant.net.get(url).behaviour = behaviour;
      }
      assertThrows(NetworkException.class, () -> client().createInvoice(sats("L-8", 49_000)));
      // The lease and its mark: never withdrawn.
      assertEquals(List.of("lease", "lease"), kinds(leasesOf(LOT.get(0))));
      for (String url : merchant.keyRelays) {
        merchant.net.get(url).behaviour = new LocalRelay.Behaviour();
      }
      // It holds the address until its end: another order gets none.
      assertNull(client().createInvoice(sats("L-9", 49_000)).onchainAddress());
    }

    @Test
    void leavesItsLeaseConfirmedWhenItsMarkFailsItHoldsTheAddress() throws Exception {
      merchant.publishLot(List.of(LOT.get(0)), 91);
      String d = Leases.leaseAddress(merchant.registryKey, LOT.get(0));
      for (String url : merchant.keyRelays.subList(0, 2)) {
        LocalRelay.Behaviour behaviour = new LocalRelay.Behaviour();
        // The lease is taken, its mark refused: confirmed, never marked on a majority.
        behaviour.refuseEvent = event -> isMark(event, d) ? "blocked: no" : null;
        merchant.net.get(url).behaviour = behaviour;
      }
      assertThrows(NetworkException.class, () -> client().createInvoice(sats("L-11", 49_000)));
      // The lease, then its mark (refused here): never withdrawn, nothing declared.
      List<NostrEvent> sent = received();
      List<LeaseSeen> leases = leasesOf(LOT.get(0), sent);
      assertEquals(List.of("lease", "lease"), kinds(leases));
      assertEquals(Js.of(false), openAsMerchant(leases.get(0).event()).get("confirmed"));
      assertEquals(Js.of(true), openAsMerchant(leases.get(1).event()).get("confirmed"));
      assertEquals(2, sent.size());
      for (String url : merchant.keyRelays) {
        merchant.net.get(url).behaviour = new LocalRelay.Behaviour();
      }
      // It holds the address until its end: another order gets none.
      assertNull(client().createInvoice(sats("L-12", 49_000)).onchainAddress());
    }

    @Test
    void leavesItsLeaseConfirmedWhenItsCheckoutLinkFailsItHoldsTheAddress() throws Exception {
      merchant.publishLot(List.of(LOT.get(0)), 91);
      for (String url : merchant.keyRelays.subList(0, 2)) {
        LocalRelay.Behaviour behaviour = new LocalRelay.Behaviour();
        // The lease and its mark are taken, the checkout link (the one record with an expiration
        // here) refused.
        behaviour.refuseEvent =
            event ->
                event.tags().stream().anyMatch(tag -> tag.get(0).equals("expiration"))
                    ? "blocked: no"
                    : null;
        merchant.net.get(url).behaviour = behaviour;
      }
      assertThrows(
          NetworkException.class,
          () -> client().createInvoice(Order.builder("L-13").sats(49_000).checkout(true).build()));
      // The lease, its mark, then the link (refused here): never withdrawn, nothing declared.
      List<NostrEvent> sent = received();
      List<LeaseSeen> leases = leasesOf(LOT.get(0), sent);
      assertEquals(List.of("lease", "lease"), kinds(leases));
      assertEquals(Js.of(true), openAsMerchant(leases.get(1).event()).get("confirmed"));
      assertEquals(3, sent.size());
      for (String url : merchant.keyRelays) {
        merchant.net.get(url).behaviour = new LocalRelay.Behaviour();
      }
      // It holds the address until its end: another order gets none.
      assertNull(client().createInvoice(sats("L-14", 49_000)).onchainAddress());
    }

    /** An invoice of {@code made}'s amount and hash, padded with {@code words} words more. */
    private String sized(TestMerchant.MadeInvoice made, int words) {
      Lightning.Spec spec = new Lightning.Spec();
      spec.amountMsat = made.amountMsat();
      spec.paymentHash = made.paymentHash();
      spec.descriptionHash = Lightning.metadataHash(TestMerchant.METADATA);
      spec.timestamp = merchant.now;
      spec.expirySeconds = 900L;
      for (int i = 0; i < 39; i += 1) {
        spec.extra.add(new Object[] {31, new ArrayList<>(Collections.nCopies(1023, 0))});
      }
      spec.extra.add(new Object[] {31, new ArrayList<>(Collections.nCopies(words, 0))});
      return Lightning.makeInvoice(spec);
    }

    @Test
    void refusesAnInvoiceItsLeaseCannotHoldBeforeAnythingIsAskedOrPublished() throws Exception {
      merchant.publishLot(List.of(LOT.get(0)), 91);
      // The lease seals the invoice twice, as the declaration does, with some 150 bytes more: an
      // invoice of 40 550 characters fits a declaration (its inside within NIP-44's padded 40 960
      // bytes) and not a lease (padded to 49 152 bytes, too large once sealed again).
      merchant.tamper =
          made ->
              new TestMerchant.MadeInvoice(
                  sized(made, 40_550 - sized(made, 0).length()),
                  made.verify(),
                  made.paymentHash(),
                  made.preimage(),
                  made.amountMsat(),
                  made.expiry(),
                  made.comment());
      merchant.net.settle();
      List<Integer> sent = new ArrayList<>();
      for (String url : merchant.keyRelays) sent.add(merchant.net.get(url).received.size());
      InvalidInvoiceException error =
          assertThrows(
              InvalidInvoiceException.class, () -> client().createInvoice(sats("L-14", 49_000)));
      assertTrue(error.getMessage().contains("lease of a deposit address"), error.getMessage());
      assertEquals(40_550, merchant.invoices.values().iterator().next().pr().length());
      // Nothing published (no lease, no declaration), the explorer never asked.
      merchant.net.settle();
      for (int i = 0; i < merchant.keyRelays.size(); i += 1) {
        assertEquals(
            sent.get(i).intValue(), merchant.net.get(merchant.keyRelays.get(i)).received.size());
      }
      assertEquals(
          List.of(),
          merchant.http.urls().stream().filter(url -> url.contains("/address/")).toList());
    }

    @Test
    void publishesNothingButItsWithdrawalWhenTheLeaseDoesNotReachAMajority() throws Exception {
      merchant.publishLot(List.of(LOT.get(0)), 91);
      for (String url : merchant.keyRelays.subList(0, 2)) {
        LocalRelay.Behaviour behaviour = new LocalRelay.Behaviour();
        behaviour.refuse = "blocked: no";
        merchant.net.get(url).behaviour = behaviour;
      }
      assertThrows(NetworkException.class, () -> client().createInvoice(sats("L-10", 49_000)));
      // The lease, then its withdrawal: no mark, no declaration, no link.
      merchant.net.settle();
      List<NostrEvent> third = merchant.net.get(merchant.keyRelays.get(2)).received;
      assertEquals(2, third.size());
      assertEquals(List.of("lease", "withdrawn"), kinds(leasesOf(LOT.get(0), third)));
    }

    /** Random bytes that count the draws made while a lease's event is built. */
    private static final class LeaseDraws extends SecureRandom {
      private static final long serialVersionUID = 1L;
      final java.util.concurrent.atomic.AtomicInteger count =
          new java.util.concurrent.atomic.AtomicInteger();

      @Override
      public synchronized void nextBytes(byte[] bytes) {
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
          if (frame.getClassName().equals(Leases.class.getName())
              && frame.getMethodName().equals("leaseEvent")) {
            count.incrementAndGet();
            break;
          }
        }
        super.nextBytes(bytes);
      }
    }

    /** The inside of each lease the relays received, as the merchant's app opens it, as text. */
    private List<String> leaseInsides() throws InterruptedException {
      List<String> insides = new ArrayList<>();
      for (NostrEvent event : received()) {
        String d = event.tag("d");
        if (d == null) continue;
        JsonValue outside;
        try {
          outside =
              Js.parse(
                  Nip44.decrypt(
                      event.content(), Derive.outerKey(merchant.registryKey, d, event.pubkey())));
        } catch (Nip44.DecryptException | Nip44.UnknownVersionException | RuntimeException e) {
          continue;
        }
        if (!"registry-onchain".equals(Js.text(outside, "t"))) continue;
        String sealed = Js.text(Js.get(outside, "v"), "sealed");
        if (sealed == null) continue;
        insides.add(
            DeclarationTest.decrypt(
                sealed, Nip44.conversationKey(merchant.readerSecret, event.pubkey())));
      }
      return insides;
    }

    @Test
    void measuresTheLeaseAsFirstPublishedUnconfirmedItsRandomBytesLeftAlone() throws Exception {
      // SPEC.md §17 step 6 (checkout-spec 1c88066): the lease is measured as first published,
      // "confirmed": false, the longer of its forms, with a deposit address's 62 characters. Its
      // inside is padded to 40 960 bytes up to 40 960 bytes, to 49 152 beyond, too large once
      // sealed again: an invoice whose lease's inside is 40 960 bytes confirmed and 40 961
      // unconfirmed is invalid_invoice before anything is asked or published, and one character
      // less is leased. The size check draws none of the client's random bytes.
      merchant.publishLot(List.of(LOT.get(0), LOT.get(1), LOT.get(2)), 91);
      int[] words = {0};
      merchant.tamper =
          made ->
              new TestMerchant.MadeInvoice(
                  sized(made, words[0]),
                  made.verify(),
                  made.paymentHash(),
                  made.preimage(),
                  made.amountMsat(),
                  made.expiry(),
                  made.comment());
      // The sizes of a lease, first published and marked confirmed, of an invoice of 0 words more.
      merchant.net.settle();
      int before = leaseInsides().size();
      assertNotNull(client().createInvoice(sats("L-21", 49_000)).onchainAddress());
      List<String> measured = leaseInsides().subList(before, leaseInsides().size());
      assertEquals(2, measured.size(), measured.toString());
      String unconfirmed = measured.get(0);
      String confirmed = measured.get(1);
      assertEquals(Js.of(false), Js.get(Js.parse(unconfirmed), "confirmed"));
      assertEquals(Js.of(true), Js.get(Js.parse(confirmed), "confirmed"));
      int length = Text.utf8Length(confirmed);
      assertEquals(length + 1, Text.utf8Length(unconfirmed));
      assertTrue(length < 40_960, length + " bytes");
      // At the boundary: 40 960 bytes confirmed, 40 961 unconfirmed.
      words[0] = 40_960 - length;
      merchant.net.settle();
      List<Integer> sent = new ArrayList<>();
      for (String url : merchant.keyRelays) sent.add(merchant.net.get(url).received.size());
      int asked = merchant.http.urls().size();
      LeaseDraws draws = new LeaseDraws();
      SattlemeClient refusing = merchant.clientBuilder().relayTimeoutMs(1000).random(draws).build();
      InvalidInvoiceException error =
          assertThrows(
              InvalidInvoiceException.class, () -> refusing.createInvoice(sats("L-22", 49_000)));
      assertTrue(error.getMessage().contains("lease of a deposit address"), error.getMessage());
      assertEquals(0, draws.count.get());
      merchant.net.settle();
      for (int i = 0; i < merchant.keyRelays.size(); i += 1) {
        assertEquals(
            sent.get(i).intValue(), merchant.net.get(merchant.keyRelays.get(i)).received.size());
      }
      // Its pay request and invoice asked; no explorer, nothing else.
      assertEquals(
          List.of(),
          merchant.http.urls().subList(asked, merchant.http.urls().size()).stream()
              .filter(url -> !url.startsWith("https://breez.tips/"))
              .toList());
      // One character less: leased, its first lease's inside exactly 40 960 bytes.
      words[0] -= 1;
      before = leaseInsides().size();
      assertNotNull(client().createInvoice(sats("L-23", 49_000)).onchainAddress());
      assertEquals(40_960, Text.utf8Length(leaseInsides().get(before)));
    }
  }

  @Nested
  class ThePaymentOfAnInvoiceWithAnAddress {
    private SattlemeClient sattleme;
    private Invoice invoice;
    private String address;

    @BeforeEach
    void create() throws Exception {
      merchant.publishLot(List.of(LOT.get(0)), 91);
      sattleme = client();
      invoice =
          sattleme.createInvoice(Order.builder("P-1").sats(49_000).durationSeconds(600).build());
      address = invoice.onchainAddress();
    }

    private String sale() {
      return saleOf("P-1").author;
    }

    private JsonObject pay(String seed, long sats) {
      JsonObject tx = Chain.payment(Chain.txid(seed), address, sats);
      merchant.chain.add(address, tx);
      return tx;
    }

    @Test
    void isUnconfirmedOnceEnoughIsSentItsWitnessPublishedThenPaidAtThreeConfirmations()
        throws Exception {
      assertEquals(
          new PaymentStatus.Open(invoice.expiresAt(), "0", null), sattleme.getStatus(invoice));
      JsonObject tx = pay("pay", 49_000);
      String txid = Js.text(tx, "txid");
      assertEquals(
          new PaymentStatus.Unconfirmed("49000", 0, List.of(txid)), sattleme.getStatus(invoice));
      // Seen while the lease runs: the witness of the order's sale, which the merchant's app
      // reads.
      List<SeenWitness> seen = witnesses();
      assertEquals(1, seen.size());
      Witnesses.Witness witness = seen.get(0).witness();
      assertEquals(address, witness.address());
      assertEquals(txid, witness.txid());
      assertEquals(sale(), witness.sale());
      assertEquals(49_000, witness.sats());
      assertEquals(false, witness.replacement());
      merchant.chain.confirm(address, txid, merchant.chain.height - 1);
      assertEquals(
          2,
          assertInstanceOf(PaymentStatus.Unconfirmed.class, sattleme.getStatus(invoice))
              .confirmations());
      merchant.chain.confirm(address, txid, merchant.chain.height - 2);
      // 3 confirmations on the explorer read: paid, on what it alone shows.
      assertEquals(
          new PaymentStatus.PaidOnchain("49000", List.of(txid)), sattleme.getStatus(invoice));
      for (String asked : merchant.chain.asked) {
        assertTrue(
            asked.startsWith(Constants.MEMPOOL_URL + "/")
                || asked.startsWith(Constants.BLOCKCYPHER_URL + "/"),
            asked);
      }
      // Long after the invoice's end: the witness says it came in time.
      merchant.now = invoice.expiresAt() + 3600;
      assertEquals(
          new PaymentStatus.PaidOnchain("49000", List.of(txid)), sattleme.getStatus(invoice));
      // One witness: a check that finds it adds nothing.
      assertEquals(1, witnesses().size());
      assertEquals(List.of(), merchant.chain.blockcypherAsked());
    }

    @Test
    void isOpenWhileUnderpaidThenExpiredWithWhatWasReceived() throws Exception {
      merchant.chain.add(
          address,
          Chain.payment(
              Chain.txid("part"),
              address,
              20_000,
              List.of(Chain.txid("coin of " + Chain.txid("part")) + ":0"),
              969_990L));
      // Its rest, asked in Lightning too (RestTest).
      PaymentStatus.Open open =
          assertInstanceOf(PaymentStatus.Open.class, sattleme.getStatus(invoice));
      assertEquals(invoice.expiresAt(), open.expiresAt());
      assertEquals("20000", open.received());
      assertEquals("29000", open.rest().sats());
      merchant.now = invoice.expiresAt() + 121;
      assertEquals(new PaymentStatus.Expired("20000"), sattleme.getStatus(invoice));
    }

    @Test
    void isExpiredForAPaymentNoCheckSawReceivedLateByBlockCyphersTime() throws Exception {
      merchant.now = invoice.expiresAt() + 3600;
      JsonObject tx = pay("late", 49_000);
      String txid = Js.text(tx, "txid");
      merchant.chain.received.put(
          txid, ISO.format(Instant.ofEpochSecond(invoice.expiresAt() + 121)));
      assertEquals(new PaymentStatus.Expired("0"), sattleme.getStatus(invoice));
      assertEquals(
          List.of(Constants.BLOCKCYPHER_URL + "/txs/" + txid + "?limit=1&includeHex=false"),
          merchant.chain.blockcypherAsked());
      // Of no order: a witness none, so that the merchant's app takes the address out of the lot.
      List<SeenWitness> seen = witnesses();
      assertEquals(1, seen.size());
      assertEquals(txid, seen.get(0).witness().txid());
      assertNull(seen.get(0).witness().sale());
    }

    @Test
    void judgesATransactionByTheClockOnceTheExplorerAnsweredNeverBeforeIt() throws Exception {
      // The check starts 5 s before the lease's end; the explorer answers 10 s later. Listed then,
      // the transaction was not seen in time: BlockCypher received it 3 s after the end.
      merchant.now = invoice.expiresAt() + 115;
      JsonObject tx = pay("listed after the end", 49_000);
      String txid = Js.text(tx, "txid");
      merchant.chain.received.put(
          txid, ISO.format(Instant.ofEpochSecond(invoice.expiresAt() + 123)));
      merchant.override =
          uri -> {
            if (uri.getPath().endsWith("/address/" + address + "/txs")) merchant.now += 10;
            return null;
          };
      // Its Lightning part, asked before, not final yet: open, with nothing received on-chain.
      assertEquals(
          new PaymentStatus.Open(invoice.expiresAt(), "0", null), sattleme.getStatus(invoice));
      List<SeenWitness> seen = witnesses();
      assertEquals(1, seen.size());
      assertEquals(txid, seen.get(0).witness().txid());
      assertNull(seen.get(0).witness().sale());
    }

    @Test
    void neverWritesAWitnessNoneOverOneOfANewerFormatAndWarns() throws Exception {
      merchant.now = invoice.expiresAt() + 3600;
      JsonObject tx = pay("late, its witness newer", 49_000);
      String txid = Js.text(tx, "txid");
      merchant.chain.received.put(
          txid, ISO.format(Instant.ofEpochSecond(invoice.expiresAt() + 121)));
      // A newer version's witness none of it, on every relay: what it holds cannot be read here.
      Derive.SeenKeys seen = Derive.SeenKeys.of(merchant.integrationKey);
      NostrEvent newer =
          Records.seal(
              Constants.WITNESS_RECORD_TYPE,
              Js.obj("version", 2),
              seen.secretKey(),
              seen.encryptionKey(),
              Witnesses.witnessAddress(seen.addressKey(), address, txid, null),
              merchant.now - 60,
              List.of(
                  List.of("o", Witnesses.orderTag(seen.addressKey(), address, null)),
                  List.of("o", Witnesses.addressTag(seen.addressKey(), address))),
              new RandomBytes(new SecureRandom()));
      for (String url : merchant.keyRelays) merchant.net.get(url).take(newer);
      merchant.net.settle();
      List<Integer> sent =
          merchant.keyRelays.stream().map(url -> merchant.net.get(url).received.size()).toList();
      assertEquals(new PaymentStatus.Expired("0"), sattleme.getStatus(invoice));
      merchant.net.settle();
      for (int i = 0; i < merchant.keyRelays.size(); i += 1) {
        List<NostrEvent> received = merchant.net.get(merchant.keyRelays.get(i)).received;
        assertEquals(
            List.of(),
            received.subList(sent.get(i), received.size()).stream()
                .filter(event -> event.pubkey().equals(seen.author()))
                .toList(),
            merchant.keyRelays.get(i));
      }
      assertTrue(
          merchant.warnings.stream()
              .anyMatch(warning ->
                  warning.contains("a witness of an on-chain payment has a newer format")),
          merchant.warnings.toString());
    }

    @Test
    void countsAPaymentNoCheckSawThatBlockCypherReceivedInTime() throws Exception {
      merchant.now = invoice.expiresAt() + 3600;
      JsonObject tx = pay("in time", 49_000);
      String txid = Js.text(tx, "txid");
      merchant.chain.received.put(
          txid, ISO.format(Instant.ofEpochSecond(invoice.expiresAt() + 100)));
      PaymentStatus.Unconfirmed status =
          assertInstanceOf(PaymentStatus.Unconfirmed.class, sattleme.getStatus(invoice));
      assertEquals("49000", status.received());
      List<SeenWitness> seen = witnesses();
      assertEquals(1, seen.size());
      assertEquals(txid, seen.get(0).witness().txid());
      assertEquals(sale(), seen.get(0).witness().sale());
      assertEquals(false, seen.get(0).witness().replacement());
      // Its witness spares the next check the question.
      sattleme.getStatus(invoice);
      assertEquals(1, merchant.chain.blockcypherAsked().size());
    }

    @Test
    void countsAFeeBumpAfterTheLeasesEndOfAPaymentSeenInTime() throws Exception {
      JsonObject first = pay("first", 49_000);
      sattleme.getStatus(invoice);
      merchant.now = invoice.expiresAt() + 3600;
      merchant.chain.drop(address, Js.text(first, "txid"));
      List<String> coins = new ArrayList<>();
      for (JsonValue input : Js.list(first, "vin")) {
        coins.add(Js.text(input, "txid") + ":" + Js.integer(input, "vout"));
      }
      coins.add(Chain.txid("fee") + ":1");
      JsonObject bump = Chain.payment(Chain.txid("bump"), address, 49_000, coins, null);
      merchant.chain.add(address, bump);
      String txid = Js.text(bump, "txid");
      PaymentStatus.Unconfirmed status =
          assertInstanceOf(PaymentStatus.Unconfirmed.class, sattleme.getStatus(invoice));
      assertEquals("49000", status.received());
      assertEquals(List.of(txid), status.txids());
      assertTrue(
          witnesses().stream()
              .anyMatch(seen ->
                  seen.witness().txid().equals(txid)
                      && sale().equals(seen.witness().sale())
                      && seen.witness().replacement()));
      // Nothing to ask BlockCypher: all of it stands for the payment it replaces.
      assertEquals(List.of(), merchant.chain.blockcypherAsked());
    }

    @Test
    void failsWithNetworkErrorWhenTheExplorerDoesNotAnswerUnlessLightningWasPaid()
        throws Exception {
      merchant.chain.down.add("explorer");
      assertThrows(NetworkException.class, () -> sattleme.getStatus(invoice));
      merchant.payInvoice(invoice.paymentHash());
      assertInstanceOf(PaymentStatus.PaidLightning.class, sattleme.getStatus(invoice));
    }

    @Test
    void leavesNoRequestRunningOnceItReturnedWhenOneOfTheExplorersAnswersFails() throws Exception {
      // SPEC.md §4, §13: nothing a call started runs on once it returned, but a publication's
      // relays past its majority. The address's transactions fail at once while the chain's
      // height, asked with them, hangs: the check fails (network), the height's request is
      // aborted then, not left to its 20 s. The transactions fail once the height was asked.
      java.util.concurrent.CountDownLatch never = new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.CountDownLatch heightAsked = new java.util.concurrent.CountDownLatch(1);
      String height = Constants.MEMPOOL_URL + "/blocks/tip/height";
      String txs = Constants.MEMPOOL_URL + "/address/" + address + "/txs";
      me.sattle.checkout.support.FakeHttp http =
          new me.sattle.checkout.support.FakeHttp(uri -> {
            if (uri.toString().equals(height)) {
              heightAsked.countDown();
              never.await(30, TimeUnit.SECONDS);
            }
            if (uri.toString().equals(txs)) {
              assertTrue(heightAsked.await(10, TimeUnit.SECONDS));
              return me.sattle.checkout.support.FakeHttp.Answer.text(503, "down");
            }
            return merchant.answer(uri);
          });
      try {
        SattlemeClient hanging =
            merchant.clientBuilder().httpClient(http).httpTimeoutMs(20_000).build();
        long started = System.nanoTime();
        assertThrows(NetworkException.class, () -> hanging.getStatus(invoice));
        assertTrue(System.nanoTime() - started < 10_000_000_000L);
        List<me.sattle.checkout.support.FakeHttp.Sent> asked =
            http.sent.stream().filter(sent -> sent.uri().toString().equals(height)).toList();
        assertEquals(1, asked.size());
        for (me.sattle.checkout.support.FakeHttp.Sent sent : http.sent) {
          assertTrue(sent.result().isDone(), sent.uri() + " still running");
        }
        assertTrue(asked.get(0).result().isCancelled());
      } finally {
        never.countDown();
        http.shutdown();
      }
    }

    @Test
    void isPaidByItsLightningInvoiceWhateverFailureItsOnchainPartMeets() throws Exception {
      // SPEC.md §14 (checkout-spec 1c88066): a paid Lightning invoice wins over every failure of
      // the on-chain part, not only revoked: the channel not found, relays that do not answer,
      // an explorer that does not answer. Unpaid, the check fails with that error.
      List<String> relays = merchant.keyRelays;
      Map<String, List<JsonValue>> removed = new java.util.HashMap<>();
      Runnable noChannel =
          () -> {
            for (String url : relays) {
              List<JsonValue> events = merchant.net.get(url).events;
              List<JsonValue> team =
                  events.stream()
                      .filter(event -> merchant.team.equals(Js.text(event, "pubkey")))
                      .toList();
              removed.put(url, team);
              events.removeAll(team);
            }
          };
      Runnable channelBack =
          () -> {
            for (String url : relays) merchant.net.get(url).events.addAll(removed.get(url));
          };
      Runnable mute =
          () -> {
            for (String url : relays) merchant.net.get(url).behaviour.mute = true;
          };
      Runnable answering =
          () -> {
            for (String url : relays) merchant.net.get(url).behaviour.mute = false;
          };
      Runnable explorerDown = () -> merchant.chain.down.add("explorer");
      Runnable explorerUp = () -> merchant.chain.down.remove("explorer");
      record Failure(String name, Runnable start, Runnable end, Class<? extends Exception> error) {}
      List<Failure> failures =
          List.of(
              new Failure(
                  "channel_not_found", noChannel, channelBack, ChannelNotFoundException.class),
              new Failure("relays mute", mute, answering, NetworkException.class),
              new Failure("explorer down", explorerDown, explorerUp, NetworkException.class));
      for (Failure failure : failures) {
        failure.start().run();
        try {
          assertThrows(failure.error(), () -> sattleme.getStatus(invoice), failure.name());
        } finally {
          failure.end().run();
        }
      }
      merchant.payInvoice(invoice.paymentHash());
      PaymentStatus paid =
          new PaymentStatus.PaidLightning(merchant.invoices.get(invoice.paymentHash()).preimage());
      for (Failure failure : failures) {
        failure.start().run();
        try {
          assertEquals(paid, sattleme.getStatus(invoice), failure.name());
          assertEquals(
              paid,
              sattleme.waitForPayment(invoice, WaitOptions.builder().intervalMs(250).build()),
              failure.name());
        } finally {
          failure.end().run();
        }
      }
    }

    @Test
    void isRevokedOnceTheIntegrationIsRemovedUnlessItsLightningInvoiceIsPaid() throws Exception {
      // SPEC.md §14: every call that reads the channel is revoked, the on-chain part of a check
      // among them, its wait too; an invoice paid by its Lightning invoice is still found paid.
      pay("part", 20_000);
      assertEquals("open", sattleme.getStatus(invoice).state());
      merchant.publishChannel(Js.obj("version", 1, "status", "removed", "updatedAt", 200), 200);
      assertThrows(RevokedException.class, () -> sattleme.getStatus(invoice));
      assertThrows(
          RevokedException.class,
          () -> sattleme.waitForPayment(invoice, WaitOptions.builder().intervalMs(250).build()));
      assertThrows(RevokedException.class, () -> sattleme.findInvoices("P-1"));
      assertThrows(RevokedException.class, () -> sattleme.merchant());
      merchant.payInvoice(invoice.paymentHash());
      assertEquals(
          new PaymentStatus.PaidLightning(merchant.invoices.get(invoice.paymentHash()).preimage()),
          sattleme.getStatus(invoice));
      assertInstanceOf(
          PaymentStatus.PaidLightning.class,
          sattleme.waitForPayment(invoice, WaitOptions.builder().intervalMs(250).build()));
    }

    @Test
    void failsWithNetworkErrorWhenTheExplorerListsATransactionOfMoreThanEveryBitcoin()
        throws Exception {
      // Two outputs of 2^53 - 1 sats each to the address: each a safe integer (SPEC.md §31), all
      // together more than 2 100 000 000 000 000 sats, no transaction: the answer is a failure,
      // never a witness of such sats, never an error of the platform.
      long most = 9_007_199_254_740_991L;
      merchant.chain.add(
          address,
          Js.obj(
              "txid",
              Chain.txid("more than every bitcoin"),
              "vin",
              List.of(Js.obj("txid", Chain.txid("coin"), "vout", 0)),
              "vout",
              List.of(
                  Js.obj("value", most, "scriptpubkey_address", address),
                  Js.obj("value", most, "scriptpubkey_address", address)),
              "status",
              Js.obj("confirmed", false)));
      assertThrows(NetworkException.class, () -> sattleme.getStatus(invoice));
      assertEquals(List.of(), witnesses());
      merchant.payInvoice(invoice.paymentHash());
      assertInstanceOf(PaymentStatus.PaidLightning.class, sattleme.getStatus(invoice));
    }

    @Test
    void isFoundByTheOrdersNextInvoicePendingWhileConfirmedThenPaid() throws Exception {
      JsonObject tx = pay("order", 49_000);
      String txid = Js.text(tx, "txid");
      sattleme.getStatus(invoice);
      merchant.now = invoice.expiresAt() + 600;
      InvoicePendingException pending =
          assertThrows(
              InvoicePendingException.class, () -> sattleme.createInvoice(sats("P-1", 49_000)));
      assertEquals(invoice.bolt11(), pending.getInvoice().bolt11());
      assertEquals(address, pending.getInvoice().onchainAddress());
      merchant.chain.confirm(address, txid, merchant.chain.height - 5);
      OrderPaidException paid =
          assertThrows(OrderPaidException.class, () -> sattleme.createInvoice(sats("P-1", 49_000)));
      assertNull(paid.getPreimage());
      assertEquals(new PaymentStatus.PaidOnchain("49000", List.of(txid)), paid.getStatus());
    }

    @Test
    void isFoundByFindInvoicesWithItsAddressAnOrderPaidOnchainOnlyToo() throws Exception {
      merchant.pay = Js.with(merchant.pay, "maxSendable", 1_000_000);
      merchant.publishLot(List.of(LOT.get(1)), 92);
      Invoice onchainOnly =
          sattleme.createInvoice(Order.builder("P-2").sats(2_000_000).note("Big").build());
      DeclaredInvoice found = sattleme.findInvoices("P-1").get(0);
      assertEquals(
          new DeclaredInvoice(
              "P-1",
              invoice.bolt11(),
              invoice.paymentHash(),
              invoice.verifyUrl(),
              address,
              invoice.paymentUri(),
              "49000",
              null,
              "P-1",
              invoice.createdAt(),
              invoice.expiresAt(),
              merchant.now),
          found);
      assertEquals(
          List.of(
              new DeclaredInvoice(
                  "P-2",
                  null,
                  null,
                  null,
                  onchainOnly.onchainAddress(),
                  onchainOnly.paymentUri(),
                  "2000000",
                  null,
                  "Big",
                  onchainOnly.createdAt(),
                  onchainOnly.expiresAt(),
                  // Its confirmed lease: marked a second after the lease.
                  onchainOnly.createdAt() + 1)),
          sattleme.findInvoices("P-2"));
      // Pending: its declaration and its lease published again, nothing else made.
      int before = merchant.invoices.size();
      assertThrows(
          InvoicePendingException.class, () -> sattleme.createInvoice(sats("P-2", 2_000_000)));
      assertEquals(before, merchant.invoices.size());
    }

    @Test
    void refusesAStoredInvoiceWithAnAddressButNoReference() {
      InvoiceToCheck noRef =
          InvoiceToCheck.of(
              null,
              invoice.bolt11(),
              invoice.paymentHash(),
              invoice.verifyUrl(),
              invoice.onchainAddress(),
              invoice.sats(),
              invoice.expiresAt());
      assertThrows(InvalidOrderException.class, () -> sattleme.getStatus(noRef));
      JsonObject json = (JsonObject) Js.parse(invoice.toJson());
      JsonObject withoutRef = new JsonObject();
      for (Map.Entry<String, JsonValue> entry : json.entries()) {
        if (!entry.getKey().equals("ref")) withoutRef.put(entry.getKey(), entry.getValue());
      }
      assertThrows(
          InvalidOrderException.class,
          () -> sattleme.getStatus(InvoiceToCheck.fromJson(Js.write(withoutRef))));
      InvoiceToCheck notAnAddress =
          InvoiceToCheck.of(
              invoice.ref(),
              invoice.bolt11(),
              invoice.paymentHash(),
              invoice.verifyUrl(),
              "bc1qnotanaddress",
              invoice.sats(),
              invoice.expiresAt());
      assertThrows(InvalidOrderException.class, () -> sattleme.getStatus(notAnAddress));
    }

    @Test
    void readsAnInvoiceWithoutAnAddressFieldAsLightningOnly() throws Exception {
      JsonObject json = (JsonObject) Js.parse(invoice.toJson());
      JsonObject stored = new JsonObject();
      for (Map.Entry<String, JsonValue> entry : json.entries()) {
        if (!entry.getKey().equals("onchainAddress") && !entry.getKey().equals("paymentUri")) {
          stored.put(entry.getKey(), entry.getValue());
        }
      }
      merchant.chain.down.add("explorer");
      PaymentStatus.Open open =
          assertInstanceOf(
              PaymentStatus.Open.class,
              sattleme.getStatus(InvoiceToCheck.fromJson(Js.write(stored))));
      assertEquals("0", open.received());
    }
  }

  @Nested
  class WaitForPaymentOfAnInvoiceWithAnAddress {
    @Test
    void checksTheOnchainPartWhenTheExplorersWebSocketSaysSomethingChanged() throws Exception {
      RelayNet.ExplorerSocket socket = merchant.startExplorerSocket();
      merchant.publishLot(List.of(LOT.get(0)), 91);
      SattlemeClient sattleme = client();
      Invoice invoice = sattleme.createInvoice(sats("W-1", 49_000));
      String address = invoice.onchainAddress();
      List<PaymentStatus> statuses = new CopyOnWriteArrayList<>();
      CompletableFuture<PaymentStatus> waiting =
          sattleme.waitForPaymentAsync(
              invoice,
              // Lightning checked once a minute: what moves here is the socket.
              WaitOptions.builder().intervalMs(60_000).onStatus(statuses::add).build());
      until(() -> socket.messages.size() == 2 && !statuses.isEmpty());
      assertEquals(
          List.of(
              Js.obj("action", "want", "data", List.of("blocks")),
              Js.obj("track-address", address)),
          socket.messages);
      assertEquals("open", statuses.get(statuses.size() - 1).state());

      JsonObject tx = Chain.payment(Chain.txid("watched"), address, 49_000);
      merchant.chain.add(address, tx);
      socket.push(Js.obj("address-transactions", List.of(tx)));
      until(() -> statuses.get(statuses.size() - 1).state().equals("unconfirmed"));

      merchant.chain.confirm(address, Js.text(tx, "txid"), merchant.chain.height - 2);
      socket.push(Js.obj("block", Js.obj("height", merchant.chain.height)));
      assertEquals(
          new PaymentStatus.PaidOnchain("49000", List.of(Js.text(tx, "txid"))),
          waiting.get(20, TimeUnit.SECONDS));
      // Closed once done.
      until(() -> socket.clients() == 0);
    }

    /**
     * The test's clock, but held, once armed, at the next reading on a thread other than {@code
     * waiter}'s (a check waitForPayment started): a step that does not heed an interrupt.
     */
    final class HoldingClock extends java.time.Clock {
      volatile Thread waiter;
      volatile boolean armed;
      volatile boolean holding;
      volatile long resumedAt;
      final java.util.concurrent.CountDownLatch release =
          new java.util.concurrent.CountDownLatch(1);

      @Override
      public java.time.ZoneId getZone() {
        return ZoneOffset.UTC;
      }

      @Override
      public java.time.Clock withZone(java.time.ZoneId zone) {
        return this;
      }

      @Override
      public Instant instant() {
        boolean hold;
        synchronized (this) {
          hold = armed && Thread.currentThread() != waiter;
          if (hold) armed = false;
        }
        if (hold) {
          holding = true;
          boolean interrupted = false;
          while (release.getCount() > 0) {
            try {
              release.await();
            } catch (InterruptedException e) {
              interrupted = true;
            }
          }
          resumedAt = System.nanoTime();
          if (interrupted) Thread.currentThread().interrupt();
        }
        return Instant.ofEpochSecond(merchant.now);
      }
    }

    @Test
    void leavesNothingRunningOnceItReturnedACheckItStartedHasEnded() throws Exception {
      RelayNet.ExplorerSocket socket = merchant.startExplorerSocket();
      merchant.publishLot(List.of(LOT.get(0)), 91);
      HoldingClock clock = new HoldingClock();
      SattlemeClient sattleme = merchant.clientBuilder().relayTimeoutMs(1000).clock(clock).build();
      Invoice invoice = sattleme.createInvoice(sats("W-4", 49_000));
      List<PaymentStatus> statuses = new CopyOnWriteArrayList<>();
      CompletableFuture<PaymentStatus> last = new CompletableFuture<>();
      long[] returnedAt = {0};
      List<Integer> sent = new CopyOnWriteArrayList<>();
      int[] asked = {0};
      Thread waiter =
          new Thread(() -> {
            try {
              PaymentStatus status =
                  sattleme.waitForPayment(
                      invoice,
                      WaitOptions.builder().intervalMs(250).onStatus(statuses::add).build());
              returnedAt[0] = System.nanoTime();
              // What the relays and breez.tips got when it returned.
              for (String url : merchant.keyRelays) {
                LocalRelay relay = merchant.net.get(url);
                sent.add(relay.received.size() + relay.queries.size());
              }
              asked[0] = merchant.http.calls.size();
              last.complete(status);
            } catch (Throwable e) {
              last.completeExceptionally(e);
            }
          });
      clock.waiter = waiter;
      try {
        waiter.start();
        until(() -> socket.messages.size() == 2 && !statuses.isEmpty());
        // A block: a full check starts, and is held at its first step.
        clock.armed = true;
        socket.push(Js.obj("block", Js.obj("height", merchant.chain.height)));
        until(() -> clock.holding);
        // Meanwhile the Lightning invoice is paid: the wait returns, the check stopped; its held
        // step goes on a second later.
        merchant.payInvoice(invoice.paymentHash());
        me.sattle.checkout.internal.Tasks.schedule(1000, clock.release::countDown);
        assertInstanceOf(PaymentStatus.PaidLightning.class, last.get(10, TimeUnit.SECONDS));
      } finally {
        clock.release.countDown();
      }
      // When it returned, the check it started had ended: its held step was over.
      assertTrue(
          clock.resumedAt != 0 && clock.resumedAt <= returnedAt[0],
          "returned before the check it started ended");
      // And nothing asked nor published since: the check stopped, nothing of it runs on.
      Thread.sleep(1000);
      for (int i = 0; i < merchant.keyRelays.size(); i += 1) {
        LocalRelay relay = merchant.net.get(merchant.keyRelays.get(i));
        assertEquals(sent.get(i).intValue(), relay.received.size() + relay.queries.size());
      }
      assertEquals(asked[0], merchant.http.calls.size());
    }

    /** The merchant's fake HTTP client, but the explorer's transactions held while {@code gate}. */
    final class HoldingHttp extends java.net.http.HttpClient {
      volatile CompletableFuture<Void> gate;
      final java.util.concurrent.atomic.AtomicInteger held =
          new java.util.concurrent.atomic.AtomicInteger();

      @Override
      public java.util.Optional<java.net.CookieHandler> cookieHandler() {
        return java.util.Optional.empty();
      }

      @Override
      public java.util.Optional<java.time.Duration> connectTimeout() {
        return java.util.Optional.empty();
      }

      @Override
      public Redirect followRedirects() {
        return Redirect.NEVER;
      }

      @Override
      public java.util.Optional<java.net.ProxySelector> proxy() {
        return java.util.Optional.empty();
      }

      @Override
      public javax.net.ssl.SSLContext sslContext() {
        return merchant.http.sslContext();
      }

      @Override
      public javax.net.ssl.SSLParameters sslParameters() {
        return merchant.http.sslParameters();
      }

      @Override
      public java.util.Optional<java.net.Authenticator> authenticator() {
        return java.util.Optional.empty();
      }

      @Override
      public Version version() {
        return Version.HTTP_1_1;
      }

      @Override
      public java.util.Optional<java.util.concurrent.Executor> executor() {
        return java.util.Optional.empty();
      }

      @Override
      public <T> java.net.http.HttpResponse<T> send(
          java.net.http.HttpRequest request, java.net.http.HttpResponse.BodyHandler<T> handler) {
        throw new UnsupportedOperationException();
      }

      @Override
      public <T> CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
          java.net.http.HttpRequest request, java.net.http.HttpResponse.BodyHandler<T> handler) {
        CompletableFuture<Void> waiting = gate;
        if (waiting != null && request.uri().toString().endsWith("/txs")) {
          held.incrementAndGet();
          return waiting.thenCompose(ignored -> merchant.http.sendAsync(request, handler));
        }
        return merchant.http.sendAsync(request, handler);
      }

      @Override
      public <T> CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
          java.net.http.HttpRequest request,
          java.net.http.HttpResponse.BodyHandler<T> handler,
          java.net.http.HttpResponse.PushPromiseHandler<T> push) {
        return sendAsync(request, handler);
      }
    }

    @Test
    void leavesNothingRunningOnceItReturnedACheckHeldOnTheExplorerStops() throws Exception {
      RelayNet.ExplorerSocket socket = merchant.startExplorerSocket();
      merchant.publishLot(List.of(LOT.get(0)), 91);
      HoldingHttp http = new HoldingHttp();
      SattlemeClient sattleme =
          merchant.clientBuilder().httpClient(http).relayTimeoutMs(1000).build();
      Invoice invoice = sattleme.createInvoice(sats("W-5", 49_000));
      String address = invoice.onchainAddress();
      List<PaymentStatus> statuses = new CopyOnWriteArrayList<>();
      CompletableFuture<PaymentStatus> waiting =
          sattleme.waitForPaymentAsync(
              invoice, WaitOptions.builder().intervalMs(250).onStatus(statuses::add).build());
      until(() -> socket.messages.size() == 2 && !statuses.isEmpty());
      // A block: a full check starts, and waits for the explorer's answer.
      http.gate = new CompletableFuture<>();
      socket.push(Js.obj("block", Js.obj("height", merchant.chain.height)));
      until(() -> http.held.get() > 0);
      // Meanwhile the Lightning invoice is paid: the wait returns.
      merchant.payInvoice(invoice.paymentHash());
      assertInstanceOf(PaymentStatus.PaidLightning.class, waiting.get(10, TimeUnit.SECONDS));
      // Then the explorer answers, a part of the amount listed: the check it held would publish its
      // witness, ask an invoice of the rest and declare it. It stopped with the wait.
      merchant.net.settle();
      List<Integer> sent = new ArrayList<>();
      for (String url : merchant.keyRelays) sent.add(merchant.net.get(url).received.size());
      int made = merchant.invoices.size();
      merchant.chain.add(address, Chain.payment(Chain.txid("late part"), address, 10_000));
      http.gate.complete(null);
      Thread.sleep(1000);
      for (int i = 0; i < merchant.keyRelays.size(); i += 1) {
        assertEquals(
            sent.get(i).intValue(), merchant.net.get(merchant.keyRelays.get(i)).received.size());
      }
      assertEquals(made, merchant.invoices.size());
    }

    @Test
    void stopsWhenItsFutureIsCancelledItsSocketClosed() throws Exception {
      RelayNet.ExplorerSocket socket = merchant.startExplorerSocket();
      merchant.publishLot(List.of(LOT.get(0)), 91);
      SattlemeClient sattleme = client();
      Invoice invoice = sattleme.createInvoice(sats("W-2", 49_000));
      CompletableFuture<PaymentStatus> waiting =
          sattleme.waitForPaymentAsync(invoice, WaitOptions.defaults());
      until(() -> socket.clients() == 1);
      Thread.sleep(50);
      assertTrue(waiting.cancel(true));
      assertThrows(CancellationException.class, () -> waiting.get(5, TimeUnit.SECONDS));
      until(() -> socket.clients() == 0);
    }

    @Test
    void stopsWhenItsThreadIsInterrupted() throws Exception {
      merchant.publishLot(List.of(LOT.get(0)), 91);
      SattlemeClient sattleme = client();
      Invoice invoice = sattleme.createInvoice(sats("W-3", 49_000));
      List<Throwable> thrown = new CopyOnWriteArrayList<>();
      Thread thread =
          new Thread(() -> {
            try {
              sattleme.waitForPayment(invoice);
            } catch (Throwable e) {
              thrown.add(e);
            }
          });
      thread.start();
      Thread.sleep(50);
      thread.interrupt();
      thread.join(5000);
      assertEquals(1, thrown.size());
      assertInstanceOf(InterruptedException.class, thrown.get(0));
    }
  }

  @Test
  void aCheckoutLinkToAnInvoiceWithAnAddressIsARecordOfVersion2ThePageReadsWithoutTheInvoice()
      throws Exception {
    merchant.publishLot(List.of(LOT.get(0)), 91);
    Invoice invoice =
        client()
            .createInvoice(
                Order.builder("C-1")
                    .sats(49_000)
                    .checkout(true)
                    .redirectUrl("https://shop.example/thanks")
                    .build());
    String url = invoice.checkoutUrl();
    Checkout.Link link =
        assertInstanceOf(
            Checkout.Link.class, Checkout.parseFragment(url.substring(url.indexOf('#'))));
    String address = Derive.checkoutAddress(link.linkKey());
    NostrEvent event =
        held().stream()
            .map(Events::checked)
            .filter(e -> e != null && address.equals(e.tag("d")))
            .findFirst()
            .orElseThrow();
    JsonValue value =
        CheckoutVectorTest.openValue(
            event, Derive.checkoutEncryptionKey(link.linkKey()), "checkout");
    Derive.RestKeys rest =
        Derive.RestKeys.of(
            merchant.integrationKey, "C-1", invoice.onchainAddress(), invoice.expiresAt());
    assertEquals(
        Js.obj(
            "version",
            2,
            "businessName",
            "Sunrise Bakery",
            "invoice",
            invoice.bolt11(),
            "verifyUrl",
            invoice.verifyUrl(),
            "onchainAddress",
            invoice.onchainAddress(),
            "sats",
            49000,
            "createdAt",
            invoice.createdAt(),
            "expiresAt",
            invoice.expiresAt(),
            "fiat",
            null,
            "note",
            "C-1",
            "redirectUrl",
            "https://shop.example/thanks",
            // The rest records of this invoice of the order: written by the integration key alone.
            "restAuthor",
            rest.author(),
            "restKey",
            Hex.encode(rest.key())),
        value);
    Checkout.CheckoutRecord record = Checkout.parseValue(value);
    assertNotNull(record);
    assertEquals(invoice.onchainAddress(), record.onchainAddress());
    assertEquals(49_000, record.sats());
    assertEquals(invoice.expiresAt(), record.expiresAt());
    assertEquals(rest.author(), record.rest().author());
    assertArrayEquals(rest.key(), record.rest().key());
  }

  @Test
  void readsNoTransactionWhoseOutputsHoldMoreThanEveryBitcoin() {
    String address = LOT.get(0);
    JsonObject tx = Chain.payment(Chain.txid("all"), address, 1000);
    // Every bitcoin there will ever be, all outputs together (to the address or not), and no more.
    Explorer.Tx all =
        Explorer.parseTransaction(
            Js.with(
                tx,
                "vout",
                List.of(
                    Js.obj("value", 2_000_000_000_000_000L, "scriptpubkey_address", address),
                    Js.obj("value", 100_000_000_000_000L))));
    assertEquals(2_000_000_000_000_000L, Explorer.paysTo(all, address));
    assertNull(
        Explorer.parseTransaction(
            Js.with(
                tx,
                "vout",
                List.of(
                    Js.obj("value", 2_000_000_000_000_000L, "scriptpubkey_address", address),
                    Js.obj("value", 100_000_000_000_001L)))));
    // 2 000 outputs of 2^53 - 1 sats, each a safe integer, whose sum a long cannot hold: none.
    long most = 9_007_199_254_740_991L;
    List<Object> outputs = new ArrayList<>();
    for (int i = 0; i < 2000; i += 1) outputs.add(Js.obj("value", most));
    assertNull(Explorer.parseTransaction(Js.with(tx, "vout", outputs)));
  }

  @Test
  void readsBlockCyphersTimesAsItWritesThemTheFractionPastMillisecondsDroppedAndRefusesAnyElse() {
    assertEquals(
        utc(2026, 10, 10, 13, 5, 12, 345),
        Explorer.blockcypherMilliseconds("2026-10-10T13:05:12.345Z"));
    assertEquals(
        utc(2026, 10, 10, 13, 5, 12, 910),
        Explorer.blockcypherMilliseconds("2026-10-10T13:05:12.91Z"));
    assertEquals(
        utc(2026, 10, 10, 13, 5, 12, 0), Explorer.blockcypherMilliseconds("2026-10-10T13:05:12Z"));
    // Dropped, never rounded (SPEC.md §31, Explorers).
    assertEquals(
        utc(2026, 10, 10, 13, 5, 12, 345),
        Explorer.blockcypherMilliseconds("2026-10-10T13:05:12.345999999Z"));
    // Year 75, not 1975: before 1970, never a time in a lease.
    assertTrue(Explorer.blockcypherMilliseconds("0075-01-01T00:00:00Z") < 0);
    for (String text :
        List.of(
            "2026-02-30T00:00:00Z",
            "2026-13-01T00:00:00Z",
            "2026-10-10T24:00:00Z",
            "2026-12-31T23:59:60Z",
            "2026-10-10 13:05:12Z",
            "2026-10-10T13:05:12",
            "2026-10-10t13:05:12z",
            "2026-10-10T15:05:12+02:00",
            "2026-10-10T13:05:12.Z",
            "2026-10-10T13:05:12.0000000000Z")) {
      assertNull(Explorer.blockcypherMilliseconds(text), text);
    }
  }

  private static Long utc(int year, int month, int day, int hour, int minute, int second, int ms) {
    return LocalDateTime.of(year, month, day, hour, minute, second, ms * 1_000_000)
        .toInstant(ZoneOffset.UTC)
        .toEpochMilli();
  }
}
