package me.sattle.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import me.sattle.checkout.internal.Checkout;
import me.sattle.checkout.internal.Constants;
import me.sattle.checkout.internal.Derive;
import me.sattle.checkout.internal.RandomBytes;
import me.sattle.checkout.internal.json.JsonArray;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonString;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.internal.nostr.Events;
import me.sattle.checkout.internal.nostr.NostrEvent;
import me.sattle.checkout.internal.nostr.Records;
import me.sattle.checkout.internal.onchain.Acceptances;
import me.sattle.checkout.internal.onchain.Leases;
import me.sattle.checkout.support.Chain;
import me.sattle.checkout.support.Js;
import me.sattle.checkout.support.LocalRelay;
import me.sattle.checkout.support.TestMerchant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The merchant's tolerance of on-chain underpayments (SPEC.md §31, "The merchant's tolerance",
 * "Acceptance records"), end to end against local relays and fakes of breez.tips and of the
 * explorers: read in the channel, kept in the order's lease, its payment judged with it whatever
 * the channel says later; a payment short by no more than it is paid (being confirmed first), one
 * short by more asks its whole rest; the pages of the order's checkout links told by an acceptance
 * record, never the tolerance itself. As checkout-node's tolerance tests.
 */
class ToleranceTest {
  private static final String ORIGIN = "https://pay.example";
  private static final String ADDRESS = Chain.depositAddress(1);

  /** 5 %: of 49 000 sats, 2 450 may be missing, 46 550 pay the order. */
  private static final int TOLERANCE = 500;

  private static final long SATS = 49_000;
  private static final long LEAST = 46_550;

  /** No {@code onchainTolerance} in the channel. */
  private static final Object ABSENT = new Object();

  private TestMerchant merchant;
  private SattlemeClient sattleme;

  @BeforeEach
  void start() {
    merchant = new TestMerchant().start();
    merchant.publishLot(List.of(ADDRESS), 90);
    sattleme = merchant.clientBuilder().relayTimeoutMs(1000).build();
  }

  @AfterEach
  void stop() {
    merchant.stop();
  }

  /** Publishes the channel, with {@code tolerance} (ABSENT: no field), dated {@code createdAt}. */
  private void publishChannel(Object tolerance, long createdAt) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("relays", merchant.keyRelays);
    fields.put("checkout", Js.obj("origin", ORIGIN));
    if (tolerance != ABSENT) fields.put("onchainTolerance", tolerance);
    merchant.publishChannel(merchant.channelValue(createdAt, fields), createdAt);
  }

  /** The events the relay holds. */
  private List<JsonValue> held(String relay) throws InterruptedException {
    // On a majority when the call returned, on the others once their writes ended.
    merchant.net.settle();
    return merchant.net.get(relay).events;
  }

  private List<JsonValue> held() throws InterruptedException {
    return held(merchant.keyRelays.get(0));
  }

  private Derive.Sale saleOf(String ref) {
    return Derive.Sale.of(
        merchant.integrationKey, new Derive.Registry(merchant.registryKey, merchant.reader), ref);
  }

  /** The inside of the sale of {@code ref}'s lease the relays hold. */
  private JsonObject leaseInside(String ref) throws InterruptedException {
    Derive.Sale sale = saleOf(ref);
    List<JsonObject> leases = new ArrayList<>();
    for (JsonValue event : held()) {
      try {
        Leases.SaleRecord opened = Leases.openSaleLease(event, sale);
        if (opened instanceof Leases.SaleLease lease) leases.add(lease.lease().inside());
      } catch (Records.NewerFormat e) {
        // Not this sale's lease.
      }
    }
    assertEquals(1, leases.size());
    return leases.get(0);
  }

  /** The acceptance records of {@code invoice} the relay holds, as a link's page opens them. */
  private List<Records.Opened<Long>> acceptances(Invoice invoice, String relay)
      throws InterruptedException {
    Derive.RestKeys keys =
        Derive.RestKeys.of(
            merchant.integrationKey, invoice.ref(), invoice.onchainAddress(), invoice.expiresAt());
    List<Records.Opened<Long>> found = new ArrayList<>();
    for (JsonValue event : held(relay)) {
      Records.Opened<Long> opened =
          Acceptances.openAcceptanceRecord(event, keys, Long.parseLong(invoice.sats()));
      if (opened != null && !opened.isNewer()) found.add(opened);
    }
    return found;
  }

  private List<Records.Opened<Long>> acceptances(Invoice invoice) throws InterruptedException {
    return acceptances(invoice, merchant.keyRelays.get(0));
  }

  private static List<Long> receivedOf(List<Records.Opened<Long>> records) {
    return records.stream().map(Records.Opened::value).toList();
  }

  /** The invoices breez.tips made of {@code sats}. */
  private List<TestMerchant.MadeInvoice> madeOf(long sats) {
    synchronized (merchant.invoices) {
      return merchant.invoices.values().stream()
          .filter(made -> made.amountMsat().equals(BigInteger.valueOf(sats * 1000)))
          .toList();
    }
  }

  private Invoice createInvoice(String ref) throws InterruptedException {
    Invoice invoice =
        sattleme.createInvoice(
            Order.builder(ref)
                .sats(SATS)
                .durationSeconds(600)
                .note("Order 1")
                .checkout(true)
                .build());
    assertEquals(ADDRESS, invoice.onchainAddress());
    return invoice;
  }

  private Invoice createInvoice() throws InterruptedException {
    return createInvoice("R-1");
  }

  @Nested
  class TheOrdersTolerance {
    @Test
    void isTheChannelsKeptInTheOrdersLeaseNeverInWhatTheShopGets() throws Exception {
      publishChannel(TOLERANCE, 100);
      Invoice invoice = createInvoice();
      JsonObject inside = leaseInside("R-1");
      assertEquals(SATS, Js.integer(inside, "sats"));
      assertEquals(TOLERANCE, Js.integer(inside, "tolerance"));
      // Neither the Invoice nor the checkout link's record (the customer reads it) holds it.
      assertEquals(
          List.of(
              "bolt11",
              "checkoutUrl",
              "createdAt",
              "expiresAt",
              "fiat",
              "note",
              "onchainAddress",
              "paymentHash",
              "paymentUri",
              "rate",
              "redirectUrl",
              "ref",
              "sats",
              "verifyUrl"),
          ((JsonObject) Js.parse(invoice.toJson())).names().stream().sorted().toList());
      assertFalse(invoice.toJson().toLowerCase(Locale.ROOT).contains("toleran"));
      assertFalse(invoice.toString().toLowerCase(Locale.ROOT).contains("toleran"));
      String url = invoice.checkoutUrl();
      Checkout.Link link =
          assertInstanceOf(
              Checkout.Link.class, Checkout.parseFragment(url.substring(url.indexOf('#'))));
      NostrEvent event =
          held().stream()
              .map(Events::checked)
              .filter(e -> e != null && e.pubkey().equals(link.author()))
              .findFirst()
              .orElseThrow();
      String plaintext =
          DeclarationTest.decrypt(event.content(), Derive.checkoutEncryptionKey(link.linkKey()));
      assertFalse(plaintext.toLowerCase(Locale.ROOT).contains("toleran"));
      JsonValue record = Js.parse(plaintext);
      assertEquals("checkout", Js.text(record, "t"));
      assertNotNull(Checkout.parseValue(Js.get(record, "v")));
    }

    static Stream<Arguments> noTolerance() {
      return Stream.of(
          Arguments.of("absent", ABSENT),
          Arguments.of("0", 0),
          Arguments.of("a fraction", 2.5),
          Arguments.of("a string", "500"),
          Arguments.of("negative", -1),
          Arguments.of("above 10 %", 1001));
    }

    @ParameterizedTest(name = "is none when the channel's is {0}: the lease holds none")
    @MethodSource("noTolerance")
    void isNoneWhenTheChannelsIsNotOne(String name, Object value) throws Exception {
      publishChannel(value, 100);
      createInvoice();
      assertFalse(leaseInside("R-1").has("tolerance"));
    }

    @Test
    void isTenPercentAtMost() throws Exception {
      publishChannel(1000, 100);
      createInvoice();
      assertEquals(1000, Js.integer(leaseInside("R-1"), "tolerance"));
    }
  }

  @Nested
  class APaymentWithinTheTolerance {
    private Invoice invoice;

    @BeforeEach
    void create() throws Exception {
      publishChannel(TOLERANCE, 100);
      invoice = createInvoice();
      merchant.now += 60;
    }

    @Test
    void paysTheOrderBeingConfirmedThenPaidOnBothExplorersWithoutARest() throws Exception {
      JsonObject tx = Chain.payment(Chain.txid("least"), ADDRESS, LEAST);
      String txid = Js.text(tx, "txid");
      merchant.chain.add(ADDRESS, tx);
      assertEquals(
          new PaymentStatus.Unconfirmed(Long.toString(LEAST), 0, List.of(txid)),
          sattleme.getStatus(invoice));
      assertEquals(List.of(), madeOf(SATS - LEAST));
      merchant.chain.confirm(ADDRESS, txid, merchant.chain.height - 2);
      merchant.now = invoice.expiresAt() + 3600;
      PaymentStatus.PaidOnchain paid =
          new PaymentStatus.PaidOnchain(Long.toString(LEAST), List.of(txid));
      assertEquals(paid, sattleme.getStatus(invoice));
      // The order is paid: no new invoice for it.
      OrderPaidException error =
          assertThrows(
              OrderPaidException.class,
              () -> sattleme.createInvoice(Order.builder("R-1").sats(SATS).build()));
      assertEquals(paid, error.getStatus());
      assertNull(error.getPreimage());
    }

    @Test
    void asksTheWholeRestOfAPaymentShortByMore() throws Exception {
      merchant.chain.add(ADDRESS, Chain.payment(Chain.txid("short"), ADDRESS, LEAST - 1));
      PaymentStatus status = sattleme.getStatus(invoice);
      List<TestMerchant.MadeInvoice> made = madeOf(SATS - LEAST + 1);
      assertEquals(1, made.size());
      assertEquals(
          new PaymentStatus.Open(
              invoice.expiresAt(),
              Long.toString(LEAST - 1),
              new PaymentStatus.Rest(
                  Long.toString(SATS - LEAST + 1),
                  made.get(0).pr(),
                  "bitcoin:" + ADDRESS + "?amount=0.00002451&lightning=" + made.get(0).pr())),
          status);
      assertEquals(List.of(), acceptances(invoice));
    }

    @Test
    void isReachedByASecondPaymentAfterAnUnderpayment() throws Exception {
      merchant.chain.add(ADDRESS, Chain.payment(Chain.txid("first"), ADDRESS, 44_100));
      PaymentStatus.Open open =
          assertInstanceOf(PaymentStatus.Open.class, sattleme.getStatus(invoice));
      assertEquals("44100", open.received());
      assertEquals("4900", open.rest().sats());
      merchant.chain.add(ADDRESS, Chain.payment(Chain.txid("second"), ADDRESS, 2450));
      PaymentStatus.Unconfirmed unconfirmed =
          assertInstanceOf(PaymentStatus.Unconfirmed.class, sattleme.getStatus(invoice));
      assertEquals(Long.toString(LEAST), unconfirmed.received());
    }

    @Test
    void withAnInvoiceOfTheRestPaidNeedsOnchainOnlyWhatTheToleranceLeaves() throws Exception {
      JsonObject tx = Chain.payment(Chain.txid("part"), ADDRESS, 20_000);
      String txid = Js.text(tx, "txid");
      merchant.chain.add(ADDRESS, tx);
      sattleme.getStatus(invoice);
      TestMerchant.MadeInvoice rest = madeOf(29_000).get(0);
      merchant.payInvoice(rest.paymentHash());
      merchant.chain.confirm(ADDRESS, txid, merchant.chain.height - 2);
      // 20 000 on-chain, 3 deep: more than the 17 550 the tolerance leaves due on-chain.
      assertEquals(
          new PaymentStatus.PaidMixed("49000", List.of(txid), List.of(rest.preimage())),
          sattleme.getStatus(invoice));
    }

    @Test
    void isWaitedForByWaitForPaymentUntilPaid() throws Exception {
      merchant.startExplorerSocket();
      JsonObject tx = Chain.payment(Chain.txid("least"), ADDRESS, LEAST);
      String txid = Js.text(tx, "txid");
      merchant.chain.add(ADDRESS, tx);
      merchant.chain.confirm(ADDRESS, txid, merchant.chain.height - 2);
      assertEquals(
          new PaymentStatus.PaidOnchain(Long.toString(LEAST), List.of(txid)),
          sattleme.waitForPayment(invoice, WaitOptions.builder().intervalMs(250).build()));
    }
  }

  @Nested
  class TheToleranceOfTheOrdersCreation {
    @Test
    void stillJudgesItsPaymentWhenTheChannelsIsLowered() throws Exception {
      publishChannel(TOLERANCE, 100);
      Invoice invoice = createInvoice();
      publishChannel(0, 200);
      merchant.now += 60;
      merchant.chain.add(ADDRESS, Chain.payment(Chain.txid("least"), ADDRESS, LEAST));
      assertEquals("unconfirmed", sattleme.getStatus(invoice).state());
    }

    @Test
    void neverJudgesAPaymentOfAnOrderCreatedWithoutOne() throws Exception {
      publishChannel(ABSENT, 100);
      Invoice invoice = createInvoice();
      publishChannel(TOLERANCE, 200);
      merchant.now += 60;
      merchant.chain.add(ADDRESS, Chain.payment(Chain.txid("least"), ADDRESS, LEAST));
      PaymentStatus.Open open =
          assertInstanceOf(PaymentStatus.Open.class, sattleme.getStatus(invoice));
      assertEquals(Long.toString(LEAST), open.received());
      assertEquals(Long.toString(SATS - LEAST), open.rest().sats());
      // Past its end, for good: never paid by a tolerance set afterwards.
      merchant.now = invoice.expiresAt() + 3600;
      assertEquals(new PaymentStatus.Expired(Long.toString(LEAST)), sattleme.getStatus(invoice));
    }
  }

  @Nested
  class TheAcceptanceRecord {
    private Invoice invoice;

    @BeforeEach
    void create() throws Exception {
      publishChannel(TOLERANCE, 100);
      invoice = createInvoice();
      merchant.now += 60;
    }

    @Test
    void tellsTheCheckoutLinksPageWhatWasReceivedOnEveryRelayOnce() throws Exception {
      merchant.chain.add(ADDRESS, Chain.payment(Chain.txid("least"), ADDRESS, 47_000));
      sattleme.getStatus(invoice);
      for (String relay : merchant.keyRelays) {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (acceptances(invoice, relay).isEmpty() && System.nanoTime() < deadline) {
          Thread.sleep(10);
        }
        assertEquals(List.of(47_000L), receivedOf(acceptances(invoice, relay)), relay);
      }
      // Its address, from the invoice's rest key, and its life, the links'.
      Derive.RestKeys keys =
          Derive.RestKeys.of(merchant.integrationKey, "R-1", ADDRESS, invoice.expiresAt());
      NostrEvent event = acceptances(invoice).get(0).event();
      assertEquals(keys.author(), event.pubkey());
      assertEquals(
          List.of(
              List.of("d", Derive.acceptanceRecordAddress(keys.key())),
              List.of("expiration", Long.toString(invoice.expiresAt() + 604_800))),
          event.tags());
      // What was received, never the tolerance.
      assertEquals(
          Js.obj("t", "checkout-accepted", "v", Js.obj("version", 1, "received", 47_000)),
          Js.parse(DeclarationTest.decrypt(event.content(), keys.encryptionKey())));
      LocalRelay first = merchant.net.get(merchant.keyRelays.get(0));
      merchant.net.settle();
      int sent = first.received.size();
      sattleme.getStatus(invoice);
      merchant.net.settle();
      // Known on a majority: not published again.
      assertEquals(sent, first.received.size());
    }

    @Test
    void isPublishedAgainOfLessWhenAReplacementLowersWhatWasReceived() throws Exception {
      JsonObject first = Chain.payment(Chain.txid("first"), ADDRESS, 48_000);
      merchant.chain.add(ADDRESS, first);
      sattleme.getStatus(invoice);
      merchant.now += 1;
      merchant.chain.drop(ADDRESS, Js.text(first, "txid"));
      // A fee bump of the same coin, paying less.
      List<String> coins = new ArrayList<>();
      for (JsonValue input : Js.list(first, "vin")) {
        coins.add(Js.text(input, "txid") + ":" + Js.integer(input, "vout"));
      }
      merchant.chain.add(ADDRESS, Chain.payment(Chain.txid("bump"), ADDRESS, 47_000, coins, null));
      PaymentStatus.Unconfirmed status =
          assertInstanceOf(PaymentStatus.Unconfirmed.class, sattleme.getStatus(invoice));
      assertEquals("47000", status.received());
      List<Records.Opened<Long>> records = new ArrayList<>(acceptances(invoice));
      records.sort(
          Comparator.comparingLong((Records.Opened<Long> record) -> -record.event().createdAt()));
      assertEquals(47_000L, records.get(0).value());
    }

    @Test
    void isNeverPublishedOverOneOfANewerFormatWhichIsWarnedOf() throws Exception {
      Derive.RestKeys keys =
          Derive.RestKeys.of(merchant.integrationKey, "R-1", ADDRESS, invoice.expiresAt());
      NostrEvent newer =
          Records.seal(
              Constants.ACCEPTANCE_RECORD_TYPE,
              Js.obj("version", 2),
              keys.secretKey(),
              keys.encryptionKey(),
              Derive.acceptanceRecordAddress(keys.key()),
              merchant.now - 1,
              List.of(
                  List.of(
                      "expiration",
                      Long.toString(invoice.expiresAt() + Constants.CHECKOUT_LIFE_SECONDS))),
              new RandomBytes(new SecureRandom()));
      // On one relay: a relay that sends one is enough (it is never written over). The read of the
      // acceptance records goes on at a majority: the first two relays answer it, the third stays
      // mute (else whichever two relays answer first would decide).
      merchant.net.get(merchant.keyRelays.get(0)).take(newer);
      merchant.net.get(merchant.keyRelays.get(2)).behaviour.muteQuery =
          filter ->
              filter.get("authors") instanceof JsonArray authors
                  && authors.items().contains(new JsonString(keys.author()));
      merchant.chain.add(ADDRESS, Chain.payment(Chain.txid("least"), ADDRESS, 47_000));
      merchant.net.settle();
      List<Integer> sent =
          merchant.keyRelays.stream().map(url -> merchant.net.get(url).received.size()).toList();
      assertEquals("unconfirmed", sattleme.getStatus(invoice).state());
      merchant.net.settle();
      for (int i = 0; i < merchant.keyRelays.size(); i += 1) {
        List<NostrEvent> received = merchant.net.get(merchant.keyRelays.get(i)).received;
        assertEquals(
            List.of(),
            received.subList(sent.get(i), received.size()).stream()
                .filter(event -> event.pubkey().equals(keys.author()))
                .toList(),
            merchant.keyRelays.get(i));
      }
      assertTrue(
          merchant.warnings.stream()
              .anyMatch(warning ->
                  warning.contains("the acceptance record of a payment has a newer format")),
          merchant.warnings.toString());
    }

    @Test
    void isNeverPublishedForAPaymentOfTheWholeAmount() throws Exception {
      merchant.chain.add(ADDRESS, Chain.payment(Chain.txid("whole"), ADDRESS, SATS));
      assertEquals("unconfirmed", sattleme.getStatus(invoice).state());
      assertEquals(List.of(), acceptances(invoice));
    }

    @Test
    void publishesNothingWhenFewerThanAMajorityAnswerItsReadAndPublishesAtTheNextCheck()
        throws Exception {
      Derive.RestKeys keys =
          Derive.RestKeys.of(merchant.integrationKey, "R-1", ADDRESS, invoice.expiresAt());
      String address = Derive.acceptanceRecordAddress(keys.key());
      boolean[] muting = {true};
      for (String relay : merchant.keyRelays.subList(1, merchant.keyRelays.size())) {
        LocalRelay.Behaviour behaviour = new LocalRelay.Behaviour();
        // The read of the acceptance records alone never answered there.
        behaviour.muteQuery =
            filter ->
                muting[0]
                    && filter.get("#d") instanceof JsonArray d
                    && d.items().contains(new JsonString(address));
        merchant.net.get(relay).behaviour = behaviour;
      }
      merchant.chain.add(ADDRESS, Chain.payment(Chain.txid("least"), ADDRESS, LEAST));
      PaymentStatus.Unconfirmed status =
          assertInstanceOf(PaymentStatus.Unconfirmed.class, sattleme.getStatus(invoice));
      assertEquals(Long.toString(LEAST), status.received());
      // What those relays hold, of a newer format perhaps, cannot be told: nothing written over it.
      for (String relay : merchant.keyRelays) {
        assertEquals(List.of(), acceptances(invoice, relay), relay);
      }
      assertEquals(1, merchant.warnings.size(), merchant.warnings.toString());
      assertTrue(
          merchant.warnings.get(0).contains("the acceptance record of a payment was not published"),
          merchant.warnings.get(0));
      muting[0] = false;
      sattleme.getStatus(invoice);
      assertEquals(List.of(LEAST), receivedOf(acceptances(invoice)));
    }

    @Test
    void logsAFailedPublicationNeverTheChecksErrorAndPublishesAtTheNextCheck() throws Exception {
      Derive.RestKeys keys =
          Derive.RestKeys.of(merchant.integrationKey, "R-1", ADDRESS, invoice.expiresAt());
      String address = Derive.acceptanceRecordAddress(keys.key());
      boolean[] refusing = {true};
      for (String relay : merchant.keyRelays) {
        LocalRelay.Behaviour behaviour = new LocalRelay.Behaviour();
        behaviour.refuseEvent =
            event -> refusing[0] && address.equals(event.tag("d")) ? "blocked: no" : null;
        merchant.net.get(relay).behaviour = behaviour;
      }
      merchant.chain.add(ADDRESS, Chain.payment(Chain.txid("least"), ADDRESS, LEAST));
      PaymentStatus.Unconfirmed status =
          assertInstanceOf(PaymentStatus.Unconfirmed.class, sattleme.getStatus(invoice));
      assertEquals(Long.toString(LEAST), status.received());
      assertEquals(1, merchant.warnings.size(), merchant.warnings.toString());
      assertTrue(
          merchant.warnings.get(0).contains("the acceptance record of a payment was not published"),
          merchant.warnings.get(0));
      assertFalse(merchant.warnings.get(0).matches("(?s).*(toleran|500).*"));
      assertEquals(List.of(), acceptances(invoice));
      refusing[0] = false;
      sattleme.getStatus(invoice);
      assertEquals(List.of(LEAST), receivedOf(acceptances(invoice)));
    }
  }
}
