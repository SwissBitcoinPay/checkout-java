package me.sattle.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import me.sattle.checkout.internal.Checkout;
import me.sattle.checkout.internal.Constants;
import me.sattle.checkout.internal.Derive;
import me.sattle.checkout.internal.RandomBytes;
import me.sattle.checkout.internal.Text;
import me.sattle.checkout.internal.crypto.Hashes;
import me.sattle.checkout.internal.json.JsonArray;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonString;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.internal.nostr.Entry;
import me.sattle.checkout.internal.nostr.Events;
import me.sattle.checkout.internal.nostr.NostrEvent;
import me.sattle.checkout.internal.nostr.Records;
import me.sattle.checkout.internal.nostr.RelayPool;
import me.sattle.checkout.internal.onchain.Leases;
import me.sattle.checkout.internal.onchain.Witnesses;
import me.sattle.checkout.support.Chain;
import me.sattle.checkout.support.Js;
import me.sattle.checkout.support.LocalRelay;
import me.sattle.checkout.support.Once;
import me.sattle.checkout.support.TestMerchant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * More attacks on on-chain payments (F1, F2, G3, G4, H1, H2, I1, L1 to L3, R6, R7, and S6 for
 * witnesses), end to end against local relays and fakes of the explorers. Each test states what
 * must hold (OnchainSecurityTest holds S1 to S6). As checkout-node's on-chain security review
 * tests.
 */
class OnchainSecurityReviewTest {
  private static final List<String> LOT =
      List.of(Chain.depositAddress(1), Chain.depositAddress(2), Chain.depositAddress(3));

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

  private static RandomBytes random() {
    return new RandomBytes(new SecureRandom());
  }

  /** The lease of {@code address} by the sale of {@code ref}, from {@code createdAt}. */
  private NostrEvent leaseBy(String ref, String address, long createdAt, long seconds) {
    return Leases.leaseEvent(
        saleOf(ref),
        new Leases.Terms(
            address,
            1000,
            null,
            null,
            createdAt,
            createdAt + seconds,
            new Checkout.DeclaredPrice(1000, 0, "SAT"),
            ref,
            0,
            null),
        createdAt,
        random());
  }

  private NostrEvent leaseBy(String ref, String address) {
    return leaseBy(ref, address, merchant.now, 900);
  }

  /** A record anybody writes at a registry address (it is public): a throwaway key signs it. */
  private static NostrEvent junk(String d, long createdAt, int seed) {
    return Events.sign(
        30078,
        createdAt,
        List.of(List.of("d", d)),
        "x",
        Hashes.sha256(Text.utf8("throwaway " + seed)),
        random());
  }

  /** Every relay of the key takes {@code event}. */
  private void everywhere(NostrEvent event) {
    for (String url : merchant.keyRelays) merchant.net.get(url).take(event);
  }

  private List<LocalRelay> relays() {
    return merchant.keyRelays.stream().map(url -> merchant.net.get(url)).toList();
  }

  private static LocalRelay.Behaviour refusing(Function<NostrEvent, String> refuse) {
    LocalRelay.Behaviour behaviour = new LocalRelay.Behaviour();
    behaviour.refuseEvent = refuse;
    return behaviour;
  }

  private static LocalRelay.Behaviour delayed(long ms) {
    LocalRelay.Behaviour behaviour = new LocalRelay.Behaviour();
    behaviour.queryDelayMs = ms;
    return behaviour;
  }

  private static LocalRelay.Behaviour hiding(java.util.function.Predicate<JsonValue> hide) {
    LocalRelay.Behaviour behaviour = new LocalRelay.Behaviour();
    behaviour.hide = hide;
    return behaviour;
  }

  private void plain() {
    for (LocalRelay relay : relays()) relay.behaviour = new LocalRelay.Behaviour();
  }

  private static boolean at(NostrEvent event, String d) {
    return d.equals(event.tag("d"));
  }

  /** The kinds of the records of {@code author} at {@code d} a relay holds. */
  private List<String> leaseKinds(LocalRelay relay, String d, String author)
      throws InterruptedException {
    merchant.net.settle();
    List<String> kinds = new ArrayList<>();
    for (JsonValue value : relay.events) {
      NostrEvent event = Events.checked(value);
      if (event == null || !event.pubkey().equals(author) || !at(event, d)) continue;
      Leases.LeaseRecord record = Leases.openLeaseRecord(value, merchant.registryKey, d);
      kinds.add(
          record == null
              ? "unopened"
              : record.outside().kind().name().toLowerCase(java.util.Locale.ROOT));
    }
    return kinds;
  }

  private static Order sats(String ref, long sats) {
    return Order.builder(ref).sats(sats).build();
  }

  private static Object attempt(SattlemeClient sattleme, Order order) throws InterruptedException {
    try {
      return sattleme.createInvoice(order);
    } catch (SattlemeException e) {
      return e;
    }
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

  @Nested
  class F1F2LeasesWithdrawnOrConfirmed {
    @Test
    void f1AWithdrawalOnOneRelayOnlyFreesTheAddressThereForAnotherOrder() throws Exception {
      merchant.publishLot(List.of(LOT.get(0)), 91);
      merchant.pay = Js.with(merchant.pay, "maxSendable", 1_000_000);
      List<LocalRelay> relays = relays();
      LocalRelay r1 = relays.get(0);
      LocalRelay r2 = relays.get(1);
      long start = merchant.now;
      String d = Leases.leaseAddress(merchant.registryKey, LOT.get(0));
      String author = saleOf("F1-R").author;
      // Another server's short lease of the address reaches every relay right after R's: R's
      // lease is not confirmed. R's withdrawal of it reaches r1 only.
      NostrEvent competing = leaseBy("F1-C", LOT.get(0), start, 60);
      Once race = new Once();
      for (LocalRelay relay : relays) {
        AtomicInteger taken = new AtomicInteger();
        LocalRelay.Behaviour behaviour = new LocalRelay.Behaviour();
        behaviour.onEvent =
            (event, self) -> {
              if (event.pubkey().equals(author)) race.run(() -> everywhere(competing));
            };
        behaviour.refuseEvent =
            event ->
                relay != r1
                        && event.pubkey().equals(author)
                        && at(event, d)
                        && taken.getAndIncrement() > 0
                    ? "blocked: rate-limited"
                    : null;
        relay.behaviour = behaviour;
      }
      assertThrows(
          OnchainUnavailableException.class, () -> client().createInvoice(sats("F1-R", 49_000)));
      plain();
      assertEquals(List.of("withdrawn"), leaseKinds(r1, d, author));
      assertEquals(List.of("lease"), leaseKinds(r2, d, author));
      assertEquals(List.of("lease"), leaseKinds(relays.get(2), d, author));
      // The other lease over; R's still running on r2 and r3.
      merchant.now = start + 600;
      r1.behaviour = delayed(500);
      Object again = attempt(client(), sats("F1-R", 49_000));
      // R's lease was never confirmed: it gives R no address, and still holds it where it is.
      assertInstanceOf(OnchainUnavailableException.class, again);
      r1.behaviour = new LocalRelay.Behaviour();
      r2.behaviour = delayed(500);
      Object other = attempt(client(), sats("F1-D", 49_000));
      // Read on r1 and r3, R's withdrawal is its newest record: the address is free for D.
      assertEquals(LOT.get(0), assertInstanceOf(Invoice.class, other).onchainAddress());
    }

    @Test
    void f2AnInvoiceShownAndPaidKeepsItsAddressWhenTheOrderAsksAgain() throws Exception {
      merchant.publishLot(List.of(LOT.get(0), LOT.get(1)), 91);
      merchant.pay = Js.with(merchant.pay, "maxSendable", 1_000_000);
      LocalRelay r1 = relays().get(0);
      SattlemeClient sattleme = client();
      Invoice a =
          sattleme.createInvoice(Order.builder("F2-A").sats(49_000).durationSeconds(900).build());
      String x = a.onchainAddress();
      merchant.chain.add(x, Chain.payment(Chain.txid("F2 pays"), x, 49_000));
      PaymentStatus.Unconfirmed status =
          assertInstanceOf(PaymentStatus.Unconfirmed.class, sattleme.getStatus(a));
      assertEquals("49000", status.received());
      // Another server's lease of x, its withdrawal on r1 only (a blip).
      Derive.Sale b = saleOf("F2-B");
      everywhere(leaseBy("F2-B", x, merchant.now + 1, 900));
      r1.take(Leases.withdrawalEvent(b, x, merchant.now + 2, random()));
      merchant.now += 30;
      r1.behaviour = delayed(500);
      Object again =
          attempt(sattleme, Order.builder("F2-A").sats(49_000).durationSeconds(900).build());
      InvoicePendingException pending = assertInstanceOf(InvoicePendingException.class, again);
      assertEquals(x, pending.getInvoice().onchainAddress());
    }
  }

  @Test
  void g3AFloodAtALeasesDTagKeepsTheAddressOnlyWhileItLasts() throws Exception {
    merchant.publishLot(List.of(LOT.get(0)), 91);
    // On-chain only: no address, no invoice.
    merchant.pay = Js.with(merchant.pay, "maxSendable", 1_000_000);
    String d = Leases.leaseAddress(merchant.registryKey, LOT.get(0));
    String author = saleOf("G3-1").author;
    // Whoever watches the relays sees the new lease, and fills its d tag at once: 101 records of
    // one second, so that no read of it is complete.
    List<NostrEvent> flood = new ArrayList<>();
    for (int i = 0; i < 101; i += 1) flood.add(junk(d, merchant.now, i));
    Once flooded = new Once();
    for (LocalRelay relay : relays()) {
      LocalRelay.Behaviour behaviour = new LocalRelay.Behaviour();
      behaviour.onEvent =
          (event, self) -> {
            if (!event.pubkey().equals(author)) return;
            flooded.run(() -> {
              for (NostrEvent record : flood) everywhere(record);
            });
          };
      relay.behaviour = behaviour;
    }
    assertThrows(
        OnchainUnavailableException.class, () -> client().createInvoice(sats("G3-1", 49_000)));
    assertTrue(flooded.ran());
    for (LocalRelay relay : relays()) {
      assertEquals(List.of("withdrawn"), leaseKinds(relay, d, author));
    }
    // The flood over (its records gone): the address is free at once, not at the lease's end.
    Set<String> junkAuthors = new HashSet<>();
    merchant.net.settle();
    for (JsonValue value : relays().get(0).events) {
      NostrEvent event = Events.checked(value);
      if (event != null && at(event, d) && !event.pubkey().equals(author)) {
        junkAuthors.add(event.pubkey());
      }
    }
    for (LocalRelay relay : relays()) {
      relay.behaviour = hiding(event -> junkAuthors.contains(Js.text(event, "pubkey")));
    }
    assertEquals(LOT.get(0), client().createInvoice(sats("G3-2", 49_000)).onchainAddress());
  }

  @Test
  void g4OfTwoInvoicesThatMayStillBePaidTheOneShownAndPaidWins() throws Exception {
    merchant.publishLot(List.of(LOT.get(0), LOT.get(1)), 91);
    merchant.pay = Js.with(merchant.pay, "maxSendable", 1_000_000);
    List<LocalRelay> relays = relays();
    LocalRelay r1 = relays.get(0);
    String author = saleOf("G4-R").author;
    // The lease's mark (its second record) reaches r1 only: NetworkError, nothing shown.
    AtomicReference<String> first = new AtomicReference<>();
    for (LocalRelay relay : relays.subList(1, 3)) {
      AtomicInteger taken = new AtomicInteger();
      relay.behaviour =
          refusing(event -> {
            if (!event.pubkey().equals(author)) return null;
            String d = event.tag("d") == null ? "" : event.tag("d");
            first.compareAndSet(null, d);
            return d.equals(first.get()) && taken.getAndIncrement() > 0
                ? "blocked: rate-limited"
                : null;
          });
    }
    SattlemeClient sattleme = client();
    assertThrows(NetworkException.class, () -> sattleme.createInvoice(sats("G4-R", 49_000)));
    // Asked again while r1 is slow: its lease reads as not confirmed, so another invoice is
    // made, shown and paid.
    plain();
    r1.behaviour = delayed(500);
    Invoice shown = sattleme.createInvoice(sats("G4-R", 49_000));
    assertNotNull(shown.onchainAddress());
    r1.behaviour = new LocalRelay.Behaviour();
    JsonObject t = Chain.payment(Chain.txid("G4 shown"), shown.onchainAddress(), 49_000);
    merchant.chain.add(shown.onchainAddress(), t);
    assertEquals("unconfirmed", sattleme.getStatus(shown).state());
    merchant.chain.confirm(shown.onchainAddress(), Js.text(t, "txid"), merchant.chain.height - 5);
    // Asked again, every relay answering: the first invoice is confirmed on r1, older, and may
    // still be paid; the order was paid with the one shown.
    Object again = attempt(sattleme, sats("G4-R", 49_000));
    OrderPaidException paid = assertInstanceOf(OrderPaidException.class, again);
    assertEquals(shown.onchainAddress(), paid.getInvoice().onchainAddress());
  }

  @Test
  void h1TheCallThatLosesNeverEndsTheLeaseOfTheOneThatWon() throws Exception {
    merchant.publishLot(List.of(LOT.get(0)), 91);
    merchant.pay = Js.with(merchant.pay, "maxSendable", 1_000_000);
    String d = Leases.leaseAddress(merchant.registryKey, LOT.get(0));
    String author = saleOf("H1-R").author;
    long c = merchant.now;
    // Once the winner's mark (created_at c + 1) is kept, two seconds pass.
    for (LocalRelay relay : relays()) {
      LocalRelay.Behaviour behaviour = new LocalRelay.Behaviour();
      behaviour.onEvent =
          (event, self) -> {
            if (event.pubkey().equals(author) && event.createdAt() == c + 1 && at(event, d)) {
              merchant.now = c + 2;
            }
          };
      relay.behaviour = behaviour;
    }
    // The loser's writes reach the relays 300 ms late.
    WebSocketFactory slowWrites =
        (uri, headers, timeout, listener) -> {
          WebSocketConnection connection = merchant.net.open(uri, headers, timeout, listener);
          return new WebSocketConnection() {
            @Override
            public void send(String text) {
              if (text.startsWith("[\"EVENT\"")) {
                CompletableFuture.delayedExecutor(300, TimeUnit.MILLISECONDS)
                    .execute(() -> connection.send(text));
              } else {
                connection.send(text);
              }
            }

            @Override
            public void close() {
              connection.close();
            }
          };
        };
    SattlemeClient loserClient =
        merchant.clientBuilder().relayTimeoutMs(1000).webSocketFactory(slowWrites).build();
    CompletableFuture<Object> loser =
        CompletableFuture.supplyAsync(() -> outcomeOf(loserClient, sats("H1-R", 49_000)));
    CompletableFuture<Object> winner =
        CompletableFuture.supplyAsync(() -> outcomeOf(client(), sats("H1-R", 49_000)));
    Object lost = loser.get(30, TimeUnit.SECONDS);
    Object won = winner.get(30, TimeUnit.SECONDS);
    plain();
    assertInstanceOf(OnchainUnavailableException.class, lost);
    assertEquals(LOT.get(0), assertInstanceOf(Invoice.class, won).onchainAddress());
    // The winner's mark stays the order's newest record there, on every relay.
    for (LocalRelay relay : relays()) {
      assertEquals(List.of("lease"), leaseKinds(relay, d, author));
    }
    merchant.now = c + 30;
    // Another order gets no address; the order's invoice is still the one handed out.
    assertThrows(
        OnchainUnavailableException.class, () -> client().createInvoice(sats("H1-D", 49_000)));
    InvoicePendingException again =
        assertInstanceOf(InvoicePendingException.class, attempt(client(), sats("H1-R", 49_000)));
    assertEquals(LOT.get(0), again.getInvoice().onchainAddress());
  }

  private static Object outcomeOf(SattlemeClient sattleme, Order order) {
    try {
      return sattleme.createInvoice(order);
    } catch (SattlemeException e) {
      return e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return e;
    }
  }

  @Nested
  class H2OfTwoInvoicesThatMayStillBePaidTheOneShownIsHandedOut {
    /**
     * Call 1 (its server's clock {@code skew} s ahead): its mark reaches r1 only, NetworkError.
     * Call 2 (r1 slow) makes and shows another invoice. Call 3 (r2 slow) reads both.
     */
    private void twoPending(long skew) throws Exception {
      merchant.pay = Js.with(merchant.pay, "maxSendable", 1_000_000);
      String x = LOT.get(0);
      String y = LOT.get(1);
      merchant.publishLot(List.of(x), 91);
      List<LocalRelay> relays = relays();
      LocalRelay r1 = relays.get(0);
      LocalRelay r2 = relays.get(1);
      LocalRelay r3 = relays.get(2);
      String author = saleOf("H2-R").author;
      long c1 = merchant.now + skew;
      r2.behaviour =
          refusing(e -> e.pubkey().equals(author) && e.createdAt() > c1 ? "error: down" : null);
      r3.behaviour =
          refusing(e -> e.pubkey().equals(author) && e.createdAt() > c1 ? "error: down" : null);
      Clock ahead =
          new Clock() {
            @Override
            public ZoneId getZone() {
              return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
              return this;
            }

            @Override
            public Instant instant() {
              return Instant.ofEpochSecond(merchant.now + skew);
            }
          };
      assertThrows(
          NetworkException.class,
          () ->
              merchant
                  .clientBuilder()
                  .relayTimeoutMs(1000)
                  .clock(ahead)
                  .build()
                  .createInvoice(sats("H2-R", 49_000)));
      merchant.publishLot(List.of(x, y), 92);
      r2.behaviour = new LocalRelay.Behaviour();
      r3.behaviour = new LocalRelay.Behaviour();
      r1.behaviour = delayed(500);
      Invoice shown = client().createInvoice(sats("H2-R", 49_000));
      assertEquals(y, shown.onchainAddress());
      r1.behaviour = new LocalRelay.Behaviour();
      r2.behaviour = delayed(500);
      InvoicePendingException again =
          assertInstanceOf(InvoicePendingException.class, attempt(client(), sats("H2-R", 49_000)));
      assertEquals(shown.onchainAddress(), again.getInvoice().onchainAddress());
    }

    @Test
    void theFailedCallsServersClock3SecondsAhead() throws Exception {
      twoPending(3);
    }

    @Test
    void bothOfOneSecondTheOneNeverShownSortingLast() throws Exception {
      twoPending(0);
    }
  }

  @Test
  void i1AnObjectThatOnlyCarriesTheIdOfTheMarkNeverShownCountsForNothing() throws Exception {
    merchant.pay = Js.with(merchant.pay, "maxSendable", 1_000_000);
    String x = LOT.get(0);
    String y = LOT.get(1);
    merchant.publishLot(List.of(x), 91);
    List<LocalRelay> relays = relays();
    LocalRelay r1 = relays.get(0);
    LocalRelay r2 = relays.get(1);
    LocalRelay r3 = relays.get(2);
    String author = saleOf("I1-R").author;
    long c = merchant.now;
    // Call 1: its mark reaches r1 only, NetworkError.
    r2.behaviour =
        refusing(e -> e.pubkey().equals(author) && e.createdAt() > c ? "error: down" : null);
    r3.behaviour =
        refusing(e -> e.pubkey().equals(author) && e.createdAt() > c ? "error: down" : null);
    assertThrows(NetworkException.class, () -> client().createInvoice(sats("I1-R", 49_000)));
    // Call 2: r1 slow and refusing the order's records; its invoice, on y, is shown.
    merchant.publishLot(List.of(x, y), 92);
    merchant.now = c + 30;
    r2.behaviour = new LocalRelay.Behaviour();
    r3.behaviour = new LocalRelay.Behaviour();
    LocalRelay.Behaviour slowRefusing =
        refusing(e -> e.pubkey().equals(author) ? "error: down" : null);
    slowRefusing.queryDelayMs = 500;
    r1.behaviour = slowRefusing;
    Invoice shown = client().createInvoice(sats("I1-R", 49_000));
    assertEquals(y, shown.onchainAddress());
    JsonValue markX = recordAt(r1, author, x);
    JsonValue markY = recordAt(r2, author, y);
    // Call 3: r3 hides y's mark, and sends an object that only carries x's mark's id; it answers
    // last (as the relays of a single event loop answer in order): the first read, of a majority,
    // is r1's and r2's.
    r1.behaviour = new LocalRelay.Behaviour();
    String markYId = Js.text(markY, "id");
    LocalRelay.Behaviour hidingY = hiding(event -> markYId.equals(Js.text(event, "id")));
    hidingY.queryDelayMs = 200;
    r3.behaviour = hidingY;
    r3.store(Js.with(markX, "content", "not the event", "sig", "00".repeat(64)));
    merchant.now = c + 60;
    InvoicePendingException again =
        assertInstanceOf(InvoicePendingException.class, attempt(client(), sats("I1-R", 49_000)));
    assertEquals(y, again.getInvoice().onchainAddress());
  }

  private JsonValue recordAt(LocalRelay relay, String author, String address)
      throws InterruptedException {
    merchant.net.settle();
    String d = Leases.leaseAddress(merchant.registryKey, address);
    for (JsonValue value : relay.events) {
      NostrEvent event = Events.checked(value);
      if (event != null && event.pubkey().equals(author) && at(event, d)) return value;
    }
    throw new AssertionError("no record of " + address);
  }

  @Test
  void s6TheWitnessesReadAgainOnEveryRelayStillShowAnotherOrdersWitness() throws Exception {
    merchant.publishLot(List.of(LOT.get(0)), 91);
    SattlemeClient sattleme = client(4000);
    Invoice p = sattleme.createInvoice(sats("W6-P", 49_000));
    String x = p.onchainAddress();
    JsonObject t = Chain.payment(Chain.txid("W6 paid once"), x, 49_000);
    String txid = Js.text(t, "txid");
    // Another order's witness of t, in time for it: on r1 (which hides it) and r2 (late), not r3.
    Derive.SeenKeys seen = Derive.SeenKeys.of(merchant.integrationKey);
    String other = saleOf("W6-N").author;
    List<String> inputs = new ArrayList<>();
    for (JsonValue input : Js.list(t, "vin")) {
      inputs.add(Js.text(input, "txid") + ":" + Js.integer(input, "vout"));
    }
    NostrEvent witness =
        Records.seal(
            Constants.WITNESS_RECORD_TYPE,
            Js.obj(
                "version",
                1,
                "address",
                x,
                "txid",
                txid,
                "sale",
                other,
                "sats",
                49_000,
                "inputs",
                inputs,
                "replacement",
                false,
                "expiresAt",
                p.expiresAt()),
            seen.secretKey(),
            seen.encryptionKey(),
            Witnesses.witnessAddress(seen.addressKey(), x, txid, other),
            merchant.now,
            List.of(
                List.of("o", Witnesses.orderTag(seen.addressKey(), x, other)),
                List.of("o", Witnesses.addressTag(seen.addressKey(), x))),
            random());
    List<LocalRelay> relays = relays();
    relays.get(0).take(witness);
    relays.get(1).take(witness);
    relays.get(0).behaviour = hiding(event -> witness.id().equals(Js.text(event, "id")));
    relays.get(1).behaviour = delayed(1500);
    merchant.chain.add(x, t);
    // The first read (r1 and r3) misses it, so this check publishes its own witness; read again
    // on every relay, r2 shows the other's: t is another invoice's, and counts for nothing here.
    PaymentStatus.Open status = assertInstanceOf(PaymentStatus.Open.class, sattleme.getStatus(p));
    assertEquals("0", status.received());
  }

  @Test
  void r6TwoConfirmationsOnEveryRelayOneRelayMuteOneTimeoutInAll() throws Exception {
    merchant.publishLot(List.of(LOT.get(0), LOT.get(1)), 91);
    merchant.pay = Js.with(merchant.pay, "maxSendable", 1_000_000);
    List<LocalRelay> relays = relays();
    LocalRelay r1 = relays.get(0);
    LocalRelay r2 = relays.get(1);
    String author = saleOf("R6-R").author;
    // Each lease of the order meets another server's lease of its address at once: not
    // confirmed.
    Map<String, NostrEvent> others = new ConcurrentHashMap<>();
    Map<String, Once> races = new ConcurrentHashMap<>();
    for (String address : LOT.subList(0, 2)) {
      String d = Leases.leaseAddress(merchant.registryKey, address);
      others.put(d, leaseBy("R6-OTHER-" + address, address));
      races.put(d, new Once());
    }
    Set<String> competing = ConcurrentHashMap.newKeySet();
    for (LocalRelay relay : List.of(r1, r2)) {
      LocalRelay.Behaviour behaviour = new LocalRelay.Behaviour();
      behaviour.onEvent =
          (event, self) -> {
            String d = event.tag("d");
            if (!event.pubkey().equals(author) || d == null || !races.containsKey(d)) return;
            races
                .get(d)
                .run(() -> {
                  competing.add(d);
                  r1.take(others.get(d));
                  r2.take(others.get(d));
                });
          };
      relay.behaviour = behaviour;
    }
    LocalRelay.Behaviour mute = new LocalRelay.Behaviour();
    mute.mute = true;
    relays.get(2).behaviour = mute;
    long started = System.nanoTime();
    assertThrows(
        OnchainUnavailableException.class, () -> client(1000).createInvoice(sats("R6-R", 49_000)));
    long ms = (System.nanoTime() - started) / 1_000_000;
    assertEquals(2, competing.size());
    // The first confirmation waits for r3 until its timeout; the second counts it failed at
    // once.
    assertTrue(ms < 1700, ms + " ms");
  }

  @Test
  @Timeout(60)
  void r7ARelayThatHoldsTheLeaseStillPagingAtTheCutStillShowsIt() throws Exception {
    merchant.publishLot(List.of(LOT.get(0)), 91);
    merchant.pay = Js.with(merchant.pay, "maxSendable", 1_000_000);
    String d = Leases.leaseAddress(merchant.registryKey, LOT.get(0));
    List<LocalRelay> relays = relays();
    LocalRelay h = relays.get(1);
    LocalRelay r2 = relays.get(2);
    // Anybody's records at the address's d tag, older than the lease: 20 pages to read.
    for (int i = 0; i < 1950; i += 1) everywhere(junk(d, merchant.now - 10 - i, i));
    NostrEvent lease = leaseBy("R7-A", LOT.get(0));
    // r1 missed the lease; h hides it; r2 answers each page in 150 ms, its 20 pages outlasting
    // the one timeout the read waits after a majority.
    h.take(lease);
    r2.take(lease);
    h.behaviour = hiding(event -> lease.id().equals(Js.text(event, "id")));
    r2.behaviour = delayed(150);
    assertThrows(
        OnchainUnavailableException.class, () -> client(1000).createInvoice(sats("R7-D", 49_000)));
  }

  /** Whether {@code relay} holds a record of {@code author} at {@code d}. */
  private static boolean holds(LocalRelay relay, String author, String d) {
    for (JsonValue value : relay.events) {
      NostrEvent event = Events.withValidId(value);
      if (event != null && event.pubkey().equals(author) && at(event, d)) return true;
    }
    return false;
  }

  @Test
  @Timeout(60)
  void r8ARelayStillReadingWhenAReadOnEveryRelayEndsHasNotAnswered() throws Exception {
    merchant.publishLot(List.of(LOT.get(0)), 91);
    merchant.pay = Js.with(merchant.pay, "maxSendable", 1_000_000);
    String d = Leases.leaseAddress(merchant.registryKey, LOT.get(0));
    List<LocalRelay> relays = relays();
    LocalRelay r1 = relays.get(0);
    LocalRelay r2 = relays.get(1);
    LocalRelay r3 = relays.get(2);
    // Anybody's records at the address's d tag: a full first page, older than the lease.
    for (int i = 0; i < 150; i += 1) everywhere(junk(d, merchant.now - 10 - i, i));
    // r2 fails (it never answers). Once they hold the order's lease, r1 and r3 never answer a
    // second page of the address's leases; r3 answers each query 500 ms late. At the lease's
    // confirmation, r2 failed already, r1 stops incomplete one timeout after its first page, while
    // r3, its first page sent, still waits for its second: a majority can no longer read
    // completely, and only r1 answered.
    String author = saleOf("R8").author;
    Function<LocalRelay, java.util.function.Predicate<JsonObject>> second =
        relay ->
            filter ->
                filter.get("#d") instanceof JsonArray tags
                    && tags.items().contains(new JsonString(d))
                    && filter.has("until")
                    && holds(relay, author, d);
    LocalRelay.Behaviour first = new LocalRelay.Behaviour();
    first.muteQuery = second.apply(r1);
    r1.behaviour = first;
    LocalRelay.Behaviour mute = new LocalRelay.Behaviour();
    mute.mute = true;
    r2.behaviour = mute;
    LocalRelay.Behaviour late = delayed(500);
    late.muteQuery = second.apply(r3);
    r3.behaviour = late;
    // Fewer than a majority answered the confirmation: network, not a lease merely unconfirmed
    // (which would leave the order, paid on-chain only, without an address: OnchainUnavailable).
    // A relay timeout of 2 s: time enough on a busy machine for the reads before it.
    assertThrows(NetworkException.class, () -> client(2000).createInvoice(sats("R8", 49_000)));
    // The lease was published, then withdrawn: not confirmed.
    assertEquals(List.of("withdrawn"), leaseKinds(r1, d, author));
  }

  @Nested
  class L1L2ALeaseNotConfirmedBesideAnotherCallsMark {
    /** The mark of another call of the order {@code ref}: its lease of {@code address} at c. */
    private NostrEvent markOf(String ref, String address, long c) {
      return Leases.leaseEvent(
          saleOf(ref),
          new Leases.Terms(
              address,
              49_000,
              null,
              null,
              c,
              c + 900,
              new Checkout.DeclaredPrice(49_000, 0, "SAT"),
              ref,
              0,
              true),
          c + 1,
          random());
    }

    /** The withdrawals of {@code author} at {@code d} the relays received. */
    private List<NostrEvent> withdrawals(String d, String author) throws InterruptedException {
      merchant.net.settle();
      List<NostrEvent> found = new ArrayList<>();
      for (LocalRelay relay : relays()) {
        for (NostrEvent event : relay.received) {
          if (!event.pubkey().equals(author)) continue;
          Leases.LeaseRecord record =
              Leases.openLeaseRecord(event.toJson(), merchant.registryKey, d);
          if (record != null && record.outside().kind() == Leases.Kind.WITHDRAWN) found.add(event);
        }
      }
      return found;
    }

    @Test
    @Timeout(30)
    void l1FewerThanAMajorityAnswerTheConfirmationTheMinorityShowingTheMarkNotWithdrawn()
        throws Exception {
      merchant.publishLot(List.of(LOT.get(0)), 91);
      merchant.pay = Js.with(merchant.pay, "maxSendable", 1_000_000);
      String d = Leases.leaseAddress(merchant.registryKey, LOT.get(0));
      String author = saleOf("L1-R").author;
      NostrEvent mark = markOf("L1-R", LOT.get(0), merchant.now);
      List<LocalRelay> relays = relays();
      LocalRelay r2 = relays.get(1);
      LocalRelay r3 = relays.get(2);
      // Once this call's lease is taken, the other call's mark is everywhere, and r2 and r3
      // answer reads too late (their writes at once).
      Once marked = new Once();
      for (LocalRelay relay : relays) {
        LocalRelay.Behaviour behaviour = new LocalRelay.Behaviour();
        behaviour.onEvent =
            (event, self) -> {
              if (!event.pubkey().equals(author)) return;
              marked.run(() -> {
                everywhere(mark);
                r2.behaviour = delayed(5000);
                r3.behaviour = delayed(5000);
              });
            };
        relay.behaviour = behaviour;
      }
      assertThrows(NetworkException.class, () -> client().createInvoice(sats("L1-R", 49_000)));
      plain();
      // r1 showed the mark: no withdrawal, which could have replaced it (same second, a lower
      // id).
      assertEquals(List.of(), withdrawals(d, author));
      for (LocalRelay relay : relays) assertEquals(List.of("lease"), leaseKinds(relay, d, author));
      merchant.now += 30;
      assertThrows(
          OnchainUnavailableException.class, () -> client().createInvoice(sats("L1-D", 49_000)));
    }

    @Test
    @Timeout(30)
    void l2ACopyOfTheMarkWithADamagedSignatureSentFirstHidesNoValidCopy() throws Exception {
      merchant.publishLot(List.of(LOT.get(0)), 91);
      merchant.pay = Js.with(merchant.pay, "maxSendable", 1_000_000);
      String d = Leases.leaseAddress(merchant.registryKey, LOT.get(0));
      String author = saleOf("L2-R").author;
      NostrEvent mark = markOf("L2-R", LOT.get(0), merchant.now);
      JsonObject damaged =
          Js.with(
              mark.toJson(),
              "sig",
              (mark.sig().charAt(0) == '0' ? "1" : "0") + mark.sig().substring(1));
      List<LocalRelay> relays = relays();
      LocalRelay r1 = relays.get(0);
      LocalRelay r2 = relays.get(1);
      LocalRelay r3 = relays.get(2);
      // r1 sends the damaged copy alone, first; r2 and r3 the valid one, 100 ms later.
      Once marked = new Once();
      for (LocalRelay relay : relays) {
        LocalRelay.Behaviour behaviour = new LocalRelay.Behaviour();
        behaviour.onEvent =
            (event, self) -> {
              if (!event.pubkey().equals(author)) return;
              marked.run(() -> {
                everywhere(mark);
                r1.store(damaged);
                r1.behaviour =
                    hiding(e ->
                        mark.id().equals(Js.text(e, "id")) && mark.sig().equals(Js.text(e, "sig")));
                r2.behaviour = delayed(100);
                r3.behaviour = delayed(100);
              });
            };
        relay.behaviour = behaviour;
      }
      assertThrows(
          OnchainUnavailableException.class, () -> client().createInvoice(sats("L2-R", 49_000)));
      plain();
      assertEquals(List.of(), withdrawals(d, author));
      for (LocalRelay relay : List.of(r2, r3)) {
        assertEquals(List.of("lease"), leaseKinds(relay, d, author));
      }
    }
  }

  @Test
  void l3AFullPageWhoseOnlyNewEventHasNoUsableDateIsAskedAgainOnceThenStopsIncomplete() {
    Set<String> seen = new HashSet<>();
    List<Object> kept = new ArrayList<>();
    List<JsonObject> first = new ArrayList<>();
    for (int i = 0; i < 100; i += 1) first.add(Js.obj("id", "a" + i, "created_at", 1000));
    assertEquals(
        new RelayPool.PageEnd(1000L, false),
        RelayPool.afterCompletePage(page(first), seen, kept, 1));
    List<JsonObject> second = new ArrayList<>(first.subList(0, 99));
    second.add(Js.obj("id", "new", "created_at", Math.pow(2, 60)));
    // SPEC.md §13: a new event, undated, lets the read go on from the dated ones.
    assertEquals(
        new RelayPool.PageEnd(1000L, false),
        RelayPool.afterCompletePage(page(second), seen, kept, 2));
    // The same page again brings nothing new: incomplete, never a loop.
    assertEquals(
        new RelayPool.PageEnd(null, false),
        RelayPool.afterCompletePage(page(second), seen, kept, 3));
    // Kept all the same, once.
    assertEquals(101, kept.size());
  }

  private static List<Entry> page(List<JsonObject> events) {
    List<Entry> entries = new ArrayList<>();
    for (JsonObject event : events) entries.add(Entry.of(event, value -> value));
    return entries;
  }
}
