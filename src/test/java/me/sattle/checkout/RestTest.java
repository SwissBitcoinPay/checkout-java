package me.sattle.checkout;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import me.sattle.checkout.internal.Checkout;
import me.sattle.checkout.internal.Constants;
import me.sattle.checkout.internal.Declarations;
import me.sattle.checkout.internal.Derive;
import me.sattle.checkout.internal.RandomBytes;
import me.sattle.checkout.internal.json.JsonArray;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonString;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.internal.nostr.Events;
import me.sattle.checkout.internal.nostr.NostrEvent;
import me.sattle.checkout.internal.nostr.Records;
import me.sattle.checkout.internal.onchain.Rests;
import me.sattle.checkout.support.Chain;
import me.sattle.checkout.support.FakeHttp;
import me.sattle.checkout.support.Js;
import me.sattle.checkout.support.LocalRelay;
import me.sattle.checkout.support.TestMerchant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The rest of an on-chain underpayment (SPEC.md §31, "The rest of an underpayment"), end to end
 * against local relays and fakes of breez.tips and of the explorers: the invoice of the rest asked
 * by a payment check, declared with {@code rest} and published in a rest record that a checkout
 * link's page reads; asked once, never without time left nor outside the Lightning address's
 * bounds; a failure only logged; what the rest invoices paid counted with the on-chain payment
 * ({@code mixed}). As checkout-node's rest tests.
 */
class RestTest {
  private static final String ORIGIN = "https://pay.example";
  private static final String ADDRESS = Chain.depositAddress(1);

  private TestMerchant merchant;
  private SattlemeClient sattleme;
  private Invoice invoice;

  private SattlemeClient client() {
    return merchant.clientBuilder().relayTimeoutMs(1000).build();
  }

  @BeforeEach
  void start() throws Exception {
    merchant = new TestMerchant().start();
    merchant.publishChannel(
        merchant.channelValue(
            100, Map.of("relays", merchant.keyRelays, "checkout", Js.obj("origin", ORIGIN))),
        100);
    merchant.publishLot(List.of(ADDRESS), 90);
    sattleme = client();
    invoice =
        sattleme.createInvoice(
            Order.builder("R-1")
                .sats(49_000)
                .durationSeconds(600)
                .note("Order 1")
                .checkout(true)
                .build());
    assertEquals(ADDRESS, invoice.onchainAddress());
  }

  @AfterEach
  void stop() {
    merchant.stop();
  }

  /** The events the first relay holds now. */
  private List<JsonValue> held() throws InterruptedException {
    // On a majority when the call returned, on the others once their writes ended.
    merchant.net.settle();
    return merchant.net.get(merchant.keyRelays.get(0)).events;
  }

  private Derive.Sale saleOf(String ref) {
    return Derive.Sale.of(
        merchant.integrationKey, new Derive.Registry(merchant.registryKey, merchant.reader), ref);
  }

  /** The declarations of the sale of {@code ref} the relays hold, opened as the sale. */
  private List<Declarations.Opened> declarations(String ref) throws InterruptedException {
    Derive.Sale sale = saleOf(ref);
    List<Declarations.Opened> found = new ArrayList<>();
    for (JsonValue event : held()) {
      Declarations.Opened opened = Declarations.openDeclaration(event, sale);
      if (opened != null) found.add(opened);
    }
    return found;
  }

  /** The rest records of {@code invoice} the relays hold, as a checkout link's page opens them. */
  private List<Rests.RestInvoice> restRecords(Invoice invoice) throws InterruptedException {
    Derive.RestKeys keys =
        Derive.RestKeys.of(
            merchant.integrationKey, invoice.ref(), invoice.onchainAddress(), invoice.expiresAt());
    List<Rests.RestInvoice> found = new ArrayList<>();
    for (JsonValue event : held()) {
      Rests.OpenedRest opened =
          Rests.openRestRecord(
              event,
              keys,
              Long.parseLong(invoice.sats()),
              invoice.createdAt(),
              invoice.expiresAt());
      if (opened != null && !opened.isNewer()) found.add(opened.invoice());
    }
    return found;
  }

  /** The invoices breez.tips made of {@code sats}. */
  private List<TestMerchant.MadeInvoice> madeOf(long sats) {
    synchronized (merchant.invoices) {
      return merchant.invoices.values().stream()
          .filter(made -> made.amountMsat().equals(BigInteger.valueOf(sats * 1000)))
          .toList();
    }
  }

  private void pay(String seed, long sats) {
    merchant.chain.add(ADDRESS, Chain.payment(Chain.txid(seed), ADDRESS, sats));
  }

  private PaymentStatus.Open open(PaymentStatus status) {
    return assertInstanceOf(PaymentStatus.Open.class, status);
  }

  private static boolean isRestOf(Declarations.Opened declaration, String address, long end) {
    return declaration.rest() != null
        && declaration.rest().address().equals(address)
        && declaration.rest().expiresAt() == end;
  }

  @Nested
  class AnOnchainUnderpayment {
    @Test
    void asksAnInvoiceOfTheRestDeclaresItWithRestAndPublishesItsRecord() throws Exception {
      merchant.now += 60;
      pay("part", 20_000);
      PaymentStatus status = sattleme.getStatus(invoice);
      List<TestMerchant.MadeInvoice> made = madeOf(29_000);
      assertEquals(1, made.size());
      TestMerchant.MadeInvoice rest = made.get(0);
      assertEquals(
          new PaymentStatus.Open(
              invoice.expiresAt(),
              "20000",
              new PaymentStatus.Rest(
                  "29000",
                  rest.pr(),
                  "bitcoin:" + ADDRESS + "?amount=0.00029&lightning=" + rest.pr())),
          status);
      // Until the invoice's end, with the order's note.
      assertEquals(invoice.expiresAt() - merchant.now, rest.expiry());
      assertEquals("Order 1", rest.comment());
      // Declared for the order's sale, with the order's price and note, marked as a rest.
      assertTrue(
          declarations("R-1").stream()
              .anyMatch(d ->
                  d.invoice().equals(rest.pr())
                      && d.note().equals("Order 1")
                      && isRestOf(d, ADDRESS, invoice.expiresAt())));
      // Its record, which the checkout link's page reads with the keys its link's record
      // carries.
      List<Rests.RestInvoice> records = restRecords(invoice);
      assertEquals(1, records.size());
      assertEquals(rest.pr(), records.get(0).bolt11());
      assertEquals(29_000, records.get(0).sats());
      assertEquals(rest.verify(), records.get(0).verifyUrl());
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
      Derive.RestKeys keys =
          Derive.RestKeys.of(merchant.integrationKey, "R-1", ADDRESS, invoice.expiresAt());
      Checkout.RestRef ref = Checkout.parseValue(value).rest();
      assertEquals(keys.author(), ref.author());
      assertArrayEquals(keys.key(), ref.key());
      // The rest invoice is no invoice of the order.
      List<DeclaredInvoice> found = sattleme.findInvoices("R-1");
      assertEquals(1, found.size());
      assertEquals(invoice.bolt11(), found.get(0).bolt11());
      assertEquals(ADDRESS, found.get(0).onchainAddress());
      assertEquals(List.of(), merchant.warnings);
    }

    @Test
    void isAskedByCreateInvoicesCheckOfTheOrdersPendingInvoiceBeforeInvoicePending()
        throws Exception {
      merchant.now += 60;
      pay("part", 20_000);
      // Its payment checked, alone (SPEC.md §21 step 1): as any payment check, it asks for the
      // rest.
      InvoicePendingException pending =
          assertThrows(
              InvoicePendingException.class,
              () ->
                  sattleme.createInvoice(Order.builder("R-1").sats(49_000).checkout(true).build()));
      assertEquals(ADDRESS, pending.getInvoice().onchainAddress());
      List<TestMerchant.MadeInvoice> made = madeOf(29_000);
      assertEquals(1, made.size());
      assertTrue(
          declarations("R-1").stream()
              .anyMatch(d ->
                  d.invoice().equals(made.get(0).pr())
                      && isRestOf(d, ADDRESS, invoice.expiresAt())));
      // Its record, for the pages of the order's checkout links (the new one included).
      List<Rests.RestInvoice> records = restRecords(invoice);
      assertEquals(1, records.size());
      assertEquals(made.get(0).pr(), records.get(0).bolt11());
      // Shown by the next check, never asked again.
      assertEquals(made.get(0).pr(), open(sattleme.getStatus(invoice)).rest().bolt11());
      assertEquals(made, madeOf(29_000));
      assertEquals(List.of(), merchant.warnings);
    }

    @Test
    void asksItOnceAndAgainForANewRest() throws Exception {
      pay("part", 20_000);
      PaymentStatus first = sattleme.getStatus(invoice);
      assertEquals(first, client().getStatus(invoice));
      assertEquals(1, madeOf(29_000).size());
      // More sent on-chain: a new rest, a new invoice of it.
      pay("more", 10_000);
      PaymentStatus.Open second = open(sattleme.getStatus(invoice));
      assertEquals("30000", second.received());
      assertEquals("19000", second.rest().sats());
      assertEquals(1, madeOf(19_000).size());
      assertEquals(
          List.of(19_000L, 29_000L),
          restRecords(invoice).stream().map(Rests.RestInvoice::sats).sorted().toList());
    }

    @Test
    void isLightningOnlyBelow1000Sats() throws Exception {
      pay("part", 48_500);
      PaymentStatus.Open status = open(sattleme.getStatus(invoice));
      TestMerchant.MadeInvoice made = madeOf(500).get(0);
      assertEquals("48500", status.received());
      assertEquals(
          new PaymentStatus.Rest("500", made.pr(), "lightning:" + made.pr()), status.rest());
    }

    @Test
    void asksNoneWithLessThanAMinuteLeftNorOutsideTheLightningAddressesBounds() throws Exception {
      pay("part", 20_000);
      merchant.now = invoice.expiresAt() - 59;
      assertEquals(
          new PaymentStatus.Rest("29000", null, "bitcoin:" + ADDRESS + "?amount=0.00029"),
          open(sattleme.getStatus(invoice)).rest());
      merchant.now = invoice.expiresAt() - 600;
      JsonObject pay = merchant.pay;
      merchant.pay = Js.with(pay, "maxSendable", 28_000_000);
      assertNull(open(sattleme.getStatus(invoice)).rest().bolt11());
      merchant.pay = Js.with(pay, "minSendable", 29_001_000);
      assertNull(open(sattleme.getStatus(invoice)).rest().bolt11());
      assertEquals(List.of(), madeOf(29_000));
      assertEquals(List.of(), merchant.warnings);
      // Past the end, no rest at all.
      merchant.now = invoice.expiresAt();
      assertNull(open(sattleme.getStatus(invoice)).rest());
    }

    @Test
    void logsALightningAddressThatFailsAndAsksAgainAtTheNextCheck() throws Exception {
      pay("part", 20_000);
      merchant.override =
          uri ->
              (uri.getScheme() + "://" + uri.getRawAuthority() + uri.getRawPath())
                      .equals(TestMerchant.CALLBACK)
                  ? FakeHttp.Answer.json(500, Js.obj("oops", true))
                  : null;
      assertNull(open(sattleme.getStatus(invoice)).rest().bolt11());
      assertEquals(1, merchant.warnings.size(), merchant.warnings.toString());
      assertTrue(
          merchant.warnings.get(0).contains("no Lightning invoice of the rest"),
          merchant.warnings.get(0));
      merchant.override = null;
      PaymentStatus.Open again = open(sattleme.getStatus(invoice));
      List<TestMerchant.MadeInvoice> made = madeOf(29_000);
      assertEquals(made.get(made.size() - 1).pr(), again.rest().bolt11());
    }

    @Test
    void logsAPayRequestWhoseMetadataHoldsALoneSurrogateAndStillGivesTheStatus() throws Exception {
      pay("part", 20_000);
      // No UTF-8 to hash (SPEC.md §16): invalid_invoice, logged, the status given (SPEC.md §23).
      merchant.pay = merchant.pay.put("metadata", "[[\"text/plain\",\"Pay \ud800\"]]");
      PaymentStatus.Open status = open(sattleme.getStatus(invoice));
      assertEquals("20000", status.received());
      assertEquals("29000", status.rest().sats());
      assertNull(status.rest().bolt11());
      assertEquals(1, merchant.warnings.size(), merchant.warnings.toString());
      assertTrue(
          merchant.warnings.get(0).contains("no Lightning invoice of the rest"),
          merchant.warnings.get(0));
      // Its callback never asked.
      assertEquals(List.of(), madeOf(29_000));
    }

    @Test
    void refusesAnInvoiceThatDoesNotPayTheMerchantLogged() throws Exception {
      pay("part", 20_000);
      merchant.tamper =
          made ->
              new TestMerchant.MadeInvoice(
                  made.pr().substring(0, made.pr().length() - 8) + "qqqqqqqq",
                  made.verify(),
                  made.paymentHash(),
                  made.preimage(),
                  made.amountMsat(),
                  made.expiry(),
                  made.comment());
      assertNull(open(sattleme.getStatus(invoice)).rest().bolt11());
      assertEquals(1, merchant.warnings.size(), merchant.warnings.toString());
      assertEquals(List.of(), declarations("R-1").stream().filter(d -> d.rest() != null).toList());
    }

    @Test
    void checksAnInvoiceOfTheRestAnotherCheckAskedMeanwhile() throws Exception {
      pay("part", 20_000);
      // Paid as soon as it is made: the check that waited for the other finds it paid.
      merchant.tamper =
          made -> {
            merchant.payInvoice(made.paymentHash());
            return made;
          };
      CompletableFuture<PaymentStatus> first = sattleme.getStatusAsync(invoice);
      CompletableFuture<PaymentStatus> second = sattleme.getStatusAsync(invoice);
      List<PaymentStatus> statuses =
          List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
      assertEquals(1, madeOf(29_000).size());
      String pr = madeOf(29_000).get(0).pr();
      // One check asked it and shows it; the other, which waited, finds it paid.
      PaymentStatus.Open asked =
          (PaymentStatus.Open)
              statuses.stream()
                  .filter(s -> s instanceof PaymentStatus.Open)
                  .findFirst()
                  .orElseThrow();
      assertEquals(pr, asked.rest().bolt11());
      PaymentStatus.Unconfirmed waited =
          (PaymentStatus.Unconfirmed)
              statuses.stream()
                  .filter(s -> s instanceof PaymentStatus.Unconfirmed)
                  .findFirst()
                  .orElseThrow();
      assertEquals("49000", waited.received());
    }

    @Test
    void declaresAgainOnAMajorityAnInvoiceOfTheRestAMinorityHoldsBeforeItIsUsed() throws Exception {
      pay("part", 20_000);
      Derive.Sale sale = saleOf("R-1");
      LocalRelay a = merchant.net.get(merchant.keyRelays.get(0));
      LocalRelay b = merchant.net.get(merchant.keyRelays.get(1));
      LocalRelay c = merchant.net.get(merchant.keyRelays.get(2));
      java.util.function.Function<NostrEvent, String> refuse =
          event -> event.pubkey().equals(sale.author) ? "blocked" : null;
      // Its declaration reaches one relay of three: not to be shown.
      b.behaviour.refuseEvent = refuse;
      c.behaviour.refuseEvent = refuse;
      assertNull(open(sattleme.getStatus(invoice)).rest().bolt11());
      assertEquals(1, merchant.warnings.size(), merchant.warnings.toString());
      List<TestMerchant.MadeInvoice> made = madeOf(29_000);
      assertEquals(1, made.size());
      // Still refused: read on the relay that holds it, it cannot be declared on a majority, so
      // nothing is told of the rest (and no other invoice of it is asked). The others answer
      // later: a read ends on a majority, the one holding it among them (checkout-node's relays
      // answer in order).
      b.behaviour.queryDelayMs = 200;
      c.behaviour.queryDelayMs = 200;
      assertThrows(NetworkException.class, () -> sattleme.getStatus(invoice));
      assertEquals(made, madeOf(29_000));
      b.behaviour.queryDelayMs = 0;
      // Taken again, the third relay slow: read on the first two, declared on a majority, shown.
      b.behaviour.refuseEvent = null;
      c.behaviour.refuseEvent = null;
      c.behaviour.queryDelayMs = 500;
      assertEquals(made.get(0).pr(), open(sattleme.getStatus(invoice)).rest().bolt11());
      long declared =
          List.of(a, b, c).stream()
              .filter(relay ->
                  relay.events.stream()
                      .anyMatch(event -> {
                        Declarations.Opened opened = Declarations.openDeclaration(event, sale);
                        return opened != null
                            && opened.decoded().paymentHash().equals(made.get(0).paymentHash());
                      }))
              .count();
      assertTrue(declared >= 2, "declared on " + declared);
      // Paid, then read without the first relay: still counted, never asked again.
      merchant.payInvoice(made.get(0).paymentHash());
      c.behaviour.queryDelayMs = 0;
      a.behaviour.queryDelayMs = 500;
      PaymentStatus.Unconfirmed status =
          assertInstanceOf(PaymentStatus.Unconfirmed.class, sattleme.getStatus(invoice));
      assertEquals("49000", status.received());
      assertEquals(made, madeOf(29_000));
    }

    @Test
    void showsAnInvoiceOfTheRestOnlyOnceItsRecordIsOnAMajorityOfTheRelays() throws Exception {
      pay("part", 20_000);
      String author =
          Derive.RestKeys.of(merchant.integrationKey, "R-1", ADDRESS, invoice.expiresAt()).author();
      List<LocalRelay> relays =
          merchant.keyRelays.stream().map(url -> merchant.net.get(url)).toList();
      java.util.function.IntSupplier recordedOn =
          () ->
              (int)
                  relays.stream()
                      .filter(relay ->
                          relay.events.stream().anyMatch(e -> author.equals(Js.text(e, "pubkey"))))
                      .count();
      // Its record taken by one relay of three: a checkout link's page reading the other two
      // would not see it, nor its payment. Declared, never shown.
      for (LocalRelay relay : relays.subList(1, relays.size())) {
        relay.behaviour.refuseEvent =
            event -> event.pubkey().equals(author) ? "blocked: not now" : null;
      }
      assertEquals(
          new PaymentStatus.Rest("29000", null, "bitcoin:" + ADDRESS + "?amount=0.00029"),
          open(sattleme.getStatus(invoice)).rest());
      merchant.net.settle();
      assertEquals(1, recordedOn.getAsInt());
      assertTrue(
          merchant.warnings.get(0).contains("record of an invoice of the rest was not published"),
          merchant.warnings.toString());
      // Still refused: still not shown, never asked again.
      assertNull(open(sattleme.getStatus(invoice)).rest().bolt11());
      List<TestMerchant.MadeInvoice> made = madeOf(29_000);
      assertEquals(1, made.size());
      // Taken: published again on a majority at the next check, shown.
      for (LocalRelay relay : relays) relay.behaviour.refuseEvent = null;
      assertEquals(made.get(0).pr(), open(sattleme.getStatus(invoice)).rest().bolt11());
      merchant.net.settle();
      assertTrue(recordedOn.getAsInt() >= 2);
      assertEquals(made, madeOf(29_000));
    }

    @Test
    void neverPublishesARestRecordOverOneOfANewerFormatAndWarns() throws Exception {
      pay("part", 20_000);
      Derive.RestKeys keys =
          Derive.RestKeys.of(merchant.integrationKey, "R-1", ADDRESS, invoice.expiresAt());
      List<LocalRelay> relays =
          merchant.keyRelays.stream().map(url -> merchant.net.get(url)).toList();
      // Its record taken by the first relay alone: not known on a majority yet.
      for (LocalRelay relay : relays.subList(1, relays.size())) {
        relay.behaviour.refuseEvent =
            event -> event.pubkey().equals(keys.author()) ? "blocked: not now" : null;
      }
      sattleme.getStatus(invoice);
      List<TestMerchant.MadeInvoice> made = madeOf(29_000);
      assertEquals(1, made.size());
      // A newer version wrote that invoice's record, in a format this one cannot read, on the
      // second relay.
      String d = Derive.restRecordAddress(keys.key(), made.get(0).paymentHash());
      NostrEvent newer =
          Records.seal(
              Constants.REST_RECORD_TYPE,
              Js.obj("version", 2),
              keys.secretKey(),
              keys.encryptionKey(),
              d,
              merchant.now - 1,
              List.of(
                  List.of(
                      "expiration",
                      Long.toString(invoice.expiresAt() + Constants.CHECKOUT_LIFE_SECONDS))),
              new RandomBytes(new SecureRandom()));
      relays.get(1).take(newer);
      for (LocalRelay relay : relays) relay.behaviour.refuseEvent = null;
      // The read of the rest records goes on at a majority: the first two relays answer it, the
      // third stays mute (else whichever two relays answer first would decide).
      relays.get(2).behaviour.muteQuery =
          filter ->
              filter.get("authors") instanceof JsonArray authors
                  && authors.items().contains(new JsonString(keys.author()));
      merchant.net.settle();
      int sent = relays.get(1).received.size();
      merchant.warnings.clear();
      // A newer version recorded the invoice: it is shown, and nothing is published over that
      // record.
      assertEquals(made.get(0).pr(), open(sattleme.getStatus(invoice)).rest().bolt11());
      assertEquals(made, madeOf(29_000));
      merchant.net.settle();
      List<NostrEvent> received = relays.get(1).received;
      assertEquals(
          List.of(),
          received.subList(sent, received.size()).stream()
              .filter(event -> event.pubkey().equals(keys.author()))
              .toList());
      assertTrue(relays.get(1).events.contains(newer.toJson()));
      assertTrue(
          merchant.warnings.stream()
              .anyMatch(warning ->
                  warning.contains("a record of an invoice of the rest has a newer format")),
          merchant.warnings.toString());
    }

    @Test
    void showsAnInvoiceOfTheRestWhoseRecordAMajorityTookThoughFewerAnswerTheReadOfRestRecords()
        throws Exception {
      pay("part", 20_000);
      String author =
          Derive.RestKeys.of(merchant.integrationKey, "R-1", ADDRESS, invoice.expiresAt()).author();
      // Two relays of three take its record, then stall on the read of the rest records.
      for (String url : merchant.keyRelays.subList(1, merchant.keyRelays.size())) {
        merchant.net.get(url).behaviour.muteQuery =
            filter -> {
              JsonValue authors = filter.get("authors");
              return authors instanceof JsonArray list
                  && list.items().contains(new JsonString(author))
                  && !filter.has("#d");
            };
      }
      PaymentStatus.Open status = open(sattleme.getStatus(invoice));
      TestMerchant.MadeInvoice made = madeOf(29_000).get(0);
      assertEquals("29000", status.rest().sats());
      assertEquals(made.pr(), status.rest().bolt11());
      assertEquals(1, restRecords(invoice).size());
      // The next check, which took nothing, cannot tell: not shown until a majority answers, and
      // no rest record published again after such a read (SPEC.md §31). A rest invoice of exactly
      // the rest, not paid nor ended, exists, whatever its record: no second one is asked.
      merchant.net.settle();
      List<Integer> sent = new ArrayList<>();
      for (String url : merchant.keyRelays) sent.add(merchant.net.get(url).received.size());
      int asked = merchant.http.urls().size();
      assertNull(open(sattleme.getStatus(invoice)).rest().bolt11());
      assertEquals(
          List.of(),
          merchant.http.urls().subList(asked, merchant.http.urls().size()).stream()
              .filter(url -> url.startsWith(TestMerchant.CALLBACK))
              .toList());
      assertEquals(List.of(made), madeOf(29_000));
      merchant.net.settle();
      for (int i = 0; i < merchant.keyRelays.size(); i += 1) {
        List<NostrEvent> received = merchant.net.get(merchant.keyRelays.get(i)).received;
        assertEquals(
            List.of(),
            received.subList(sent.get(i), received.size()).stream()
                .filter(event -> event.pubkey().equals(author))
                .toList());
      }
      for (String url : merchant.keyRelays) merchant.net.get(url).behaviour.muteQuery = null;
      assertEquals(made.pr(), open(sattleme.getStatus(invoice)).rest().bolt11());
      assertEquals(List.of(made), madeOf(29_000));
    }
  }

  @Nested
  class AMixedPayment {
    private JsonObject tx;
    private TestMerchant.MadeInvoice rest;

    private void underpaid() throws Exception {
      tx = Chain.payment(Chain.txid("part"), ADDRESS, 20_000);
      merchant.chain.add(ADDRESS, tx);
      PaymentStatus.Open status = open(sattleme.getStatus(invoice));
      assertNotNull(status.rest());
      rest = merchant.invoices.get(madeOf(29_000).get(0).paymentHash());
    }

    private String txid() {
      return Js.text(tx, "txid");
    }

    @Test
    void isUnconfirmedOnceTheRestIsPaidThenPaidAtThreeConfirmationsWithThePreimage()
        throws Exception {
      underpaid();
      merchant.payInvoice(rest.paymentHash());
      assertEquals(
          new PaymentStatus.Unconfirmed("49000", 0, List.of(txid())), sattleme.getStatus(invoice));
      merchant.chain.confirm(ADDRESS, txid(), merchant.chain.height - 2);
      merchant.now = invoice.expiresAt() + 3600;
      assertEquals(
          new PaymentStatus.PaidMixed("49000", List.of(txid()), List.of(rest.preimage())),
          sattleme.getStatus(invoice));
      // The order is paid: its next invoice is refused, without the preimage of its own invoice.
      OrderPaidException error =
          assertThrows(
              OrderPaidException.class,
              () -> sattleme.createInvoice(Order.builder("R-1").sats(49_000).build()));
      assertNull(error.getPreimage());
      assertEquals("mixed", error.getStatus().method());
    }

    @Test
    void countsARestPaidWhenTheOnchainPaymentIsGoneAndExpiresWithAllReceived() throws Exception {
      underpaid();
      merchant.payInvoice(rest.paymentHash());
      merchant.chain.drop(ADDRESS, txid());
      PaymentStatus.Open status = open(sattleme.getStatus(invoice));
      assertEquals("29000", status.received());
      assertEquals("20000", status.rest().sats());
      merchant.now = invoice.expiresAt() + 3600;
      assertEquals(new PaymentStatus.Expired("29000"), sattleme.getStatus(invoice));
    }

    @Test
    void neverLeasesTheAddressAgainForTheOrderOnceAWitnessNamesItItsPaymentGone() throws Exception {
      underpaid();
      merchant.payInvoice(rest.paymentHash());
      // The on-chain payment gone, the explorer shows nothing paid the address; its witness does
      // (SPEC.md §31, "Leasing an address"): the earlier invoice's 29 000 never counts for a new
      // one.
      merchant.chain.drop(ADDRESS, txid());
      merchant.now = invoice.expiresAt() + 421;
      assertEquals(new PaymentStatus.Expired("29000"), sattleme.getStatus(invoice));
      Invoice next =
          sattleme.createInvoice(Order.builder("R-1").sats(49_000).durationSeconds(600).build());
      assertNull(next.onchainAddress());
      assertNotEquals(invoice.bolt11(), next.bolt11());
    }

    @Test
    void letsTheOrderGetANewInvoiceOnceTheInvoiceAndItsRestEndedUnpaid() throws Exception {
      underpaid();
      merchant.now = invoice.expiresAt() + 100;
      assertThrows(
          InvoicePendingException.class,
          () -> sattleme.createInvoice(Order.builder("R-1").sats(49_000).build()));
      // The rest invoice ended with the invoice: both final 120 s later.
      merchant.now = invoice.expiresAt() + 200;
      Invoice next = sattleme.createInvoice(Order.builder("R-1").sats(49_000).build());
      assertNotEquals(invoice.bolt11(), next.bolt11());
      // The address was paid: never leased again.
      assertNull(next.onchainAddress());
    }

    @Test
    void isPaidByWaitForPaymentOnceTheRestIsPaidWithoutAPushOfTheExplorer() throws Exception {
      merchant.startExplorerSocket();
      underpaid();
      merchant.chain.confirm(ADDRESS, txid(), merchant.chain.height - 2);
      List<PaymentStatus> statuses = new CopyOnWriteArrayList<>();
      CompletableFuture<PaymentStatus> waiting =
          sattleme.waitForPaymentAsync(
              invoice, WaitOptions.builder().intervalMs(250).onStatus(statuses::add).build());
      for (int i = 0; i < 200 && statuses.isEmpty(); i += 1) Thread.sleep(10);
      PaymentStatus.Open last = open(statuses.get(statuses.size() - 1));
      assertEquals("29000", last.rest().sats());
      merchant.payInvoice(rest.paymentHash());
      assertEquals(
          new PaymentStatus.PaidMixed("49000", List.of(txid()), List.of(rest.preimage())),
          waiting.get(20, TimeUnit.SECONDS));
    }

    @Test
    void neverEndsWaitForPaymentOnWhatWasDueBeforeTheRestWasPaid() throws Exception {
      underpaid();
      merchant.chain.confirm(ADDRESS, txid(), merchant.chain.height - 2);
      // The rest paid in time, its verify URL failing once past the grace period: the full check
      // (no push of the explorer here, the next one is 60 s later) judges the on-chain part
      // against the whole amount, final, and cannot tell the rest.
      merchant.payInvoice(rest.paymentHash());
      merchant.now = invoice.expiresAt() + 121;
      AtomicInteger failures = new AtomicInteger(1);
      merchant.override =
          uri ->
              uri.toString().equals(rest.verify()) && failures.getAndDecrement() > 0
                  ? FakeHttp.Answer.json(500, Js.obj("oops", true))
                  : null;
      List<PaymentStatus> statuses = new CopyOnWriteArrayList<>();
      // The rest seen paid by the next Lightning check: only a full check, with the 20 000
      // on-chain all that is due, gives the status (never "expired" with 49 000 received).
      assertEquals(
          new PaymentStatus.PaidMixed("49000", List.of(txid()), List.of(rest.preimage())),
          sattleme.waitForPayment(
              invoice, WaitOptions.builder().intervalMs(250).onStatus(statuses::add).build()));
      // Failed once, then read by the Lightning check and by the full check it started.
      assertEquals(-2, failures.get());
      assertEquals(
          List.of(), statuses.stream().filter(s -> s instanceof PaymentStatus.Expired).toList());
    }
  }
}
