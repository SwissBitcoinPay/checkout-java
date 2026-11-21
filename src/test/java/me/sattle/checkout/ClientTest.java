package me.sattle.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.module.ModuleDescriptor;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.support.Js;
import me.sattle.checkout.support.LocalRelay;
import me.sattle.checkout.support.TestMerchant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Java API itself: the module's exports, the errors of SPEC.md §5 (one class per code, each
 * unchecked, its code and whether it may be retried), the data model's JSON and serialization, and
 * each call's asynchronous form, cancelled as an interrupted thread is: InterruptedException or
 * CancellationException, never an error of §5.
 */
class ClientTest {
  private TestMerchant merchant;

  @BeforeEach
  void start() {
    merchant = new TestMerchant().start();
    merchant.publishChannel(merchant.channelValue(100), 100);
  }

  @AfterEach
  void stop() {
    merchant.stop();
  }

  @Test
  void exportsTheApiPackageAloneItsInternalsHidden() {
    Module module = SattlemeClient.class.getModule();
    assertTrue(module.isNamed());
    ModuleDescriptor descriptor = module.getDescriptor();
    assertEquals("me.sattle.checkout", descriptor.name());
    assertEquals(
        Set.of("me.sattle.checkout"),
        descriptor.exports().stream()
            .map(ModuleDescriptor.Exports::source)
            .collect(Collectors.toSet()));
    // Nothing exported to some modules only, nothing opened.
    assertTrue(descriptor.exports().stream().noneMatch(ModuleDescriptor.Exports::isQualified));
    assertTrue(descriptor.opens().isEmpty());
    assertTrue(
        descriptor.requires().stream()
            .anyMatch(r ->
                r.name().equals("java.net.http")
                    && r.modifiers().contains(ModuleDescriptor.Requires.Modifier.TRANSITIVE)));
  }

  @Test
  void raisesOneUncheckedErrorClassPerCodeOfSpec5() {
    Map<String, Boolean> codes = new TreeMap<>();
    DeclaredInvoice declared =
        new DeclaredInvoice("R-1", null, null, null, null, null, "1", null, "R-1", 1, 2, 1);
    List<SattlemeException> errors =
        List.of(
            new InvalidKeyException(),
            new InvalidOptionException("relayTimeoutMs", "bounds"),
            new InvalidOrderException("sats", "bounds"),
            new RevokedException(),
            new ChannelNotFoundException(false),
            new NetworkException("down"),
            new InvalidInvoiceException("checks"),
            new InvoicePendingException(declared, null),
            new OrderPaidException(declared, new PaymentStatus.PaidOnchain("1", List.of())),
            new UnsupportedCurrencyException("XAF", "mine"),
            new RateException("too old"),
            new CheckoutUnavailableException(),
            new OnchainUnavailableException());
    for (SattlemeException error : errors) {
      assertInstanceOf(RuntimeException.class, error);
      assertNull(codes.put(error.getCode(), error.isRetryable()), error.getCode());
    }
    assertEquals(
        new TreeMap<>(
            Map.ofEntries(
                Map.entry("invalid_key", false),
                Map.entry("invalid_option", false),
                Map.entry("invalid_order", false),
                Map.entry("revoked", false),
                Map.entry("channel_not_found", true),
                Map.entry("network", true),
                Map.entry("invalid_invoice", false),
                Map.entry("invoice_pending", false),
                Map.entry("order_paid", false),
                Map.entry("unsupported_currency", false),
                Map.entry("rate", true),
                Map.entry("checkout_unavailable", false),
                Map.entry("onchain_unavailable", true))),
        codes);
    // channel_not_found: retryable unless of a newer format.
    ChannelNotFoundException newer = new ChannelNotFoundException(true);
    assertFalse(newer.isRetryable());
    assertTrue(newer.isNewerFormat());
    // What each one carries.
    assertEquals("relayTimeoutMs", new InvalidOptionException("relayTimeoutMs", "x").getParam());
    assertEquals("XAF", new UnsupportedCurrencyException("XAF", "mine").getCurrency());
    OrderPaidException paid =
        new OrderPaidException(declared, new PaymentStatus.PaidLightning("ab".repeat(32)));
    assertEquals("ab".repeat(32), paid.getPreimage());
    assertSame(declared, paid.getInvoice());
  }

  @Test
  void writesTheDataModelAsJsonAndReadsItBack() throws Exception {
    Invoice invoice =
        merchant
            .clientBuilder()
            .rateSource(RateSource.custom(currency -> RateQuote.of("99000")))
            .build()
            .createInvoice(Order.builder("J-1").fiat("24.50", "CHF").note("Order 1").build());
    assertEquals(invoice, Invoice.fromJson(invoice.toJson()));
    JsonObject json = (JsonObject) Js.parse(invoice.toJson());
    assertEquals(
        Js.obj("amount", "24.50", "currency", "CHF", "minor", "2450", "digits", 2),
        json.get("fiat"));
    assertEquals("24.50", invoice.fiat().amount());
    // Each status, its JSON as SPEC.md §3 writes it.
    assertEquals(
        Js.obj("state", "open", "expiresAt", 5, "received", "0", "rest", null),
        Js.parse(new PaymentStatus.Open(5, "0", null).toJson()));
    assertEquals(
        Js.obj(
            "state", "unconfirmed", "received", "49000", "confirmations", 1, "txids", List.of("t")),
        Js.parse(new PaymentStatus.Unconfirmed("49000", 1, List.of("t")).toJson()));
    assertEquals(
        Js.obj("state", "paid", "method", "lightning", "preimage", "p"),
        Js.parse(new PaymentStatus.PaidLightning("p").toJson()));
    assertEquals(
        Js.obj("state", "expired", "received", "0"),
        Js.parse(new PaymentStatus.Expired("0").toJson()));
    assertTrue(new PaymentStatus.PaidLightning("p").isFinal());
    assertTrue(new PaymentStatus.Expired("0").isFinal());
    assertFalse(new PaymentStatus.Open(5, "0", null).isFinal());
  }

  @Test
  void writesEachTypeOfTheDataModelAsSpecMdSection3WithToJsonWhateverTypeItIsHeldAs() {
    // The README: write them with toJson(), the JSON of SPEC.md §3, never another serializer.
    String hash = "11".repeat(32);
    String txid = "22".repeat(32);
    PaymentStatus.Rest rest = new PaymentStatus.Rest("19000", null, "bitcoin:bc1p?amount=0.00019");
    List<PaymentStatus> statuses =
        List.of(
            new PaymentStatus.Open(1_791_379_180L, "0", null),
            new PaymentStatus.Open(1_791_379_180L, "30000", rest),
            new PaymentStatus.Unconfirmed("49000", 1, List.of(txid)),
            new PaymentStatus.PaidLightning(hash),
            new PaymentStatus.PaidOnchain("49000", List.of(txid)),
            new PaymentStatus.PaidMixed("49000", List.of(txid), List.of(hash)),
            new PaymentStatus.Expired("0"));
    List<String> written =
        List.of(
            "{\"state\":\"open\",\"expiresAt\":1791379180,\"received\":\"0\",\"rest\":null}",
            "{\"state\":\"open\",\"expiresAt\":1791379180,\"received\":\"30000\",\"rest\":"
                + "{\"sats\":\"19000\",\"bolt11\":null,\"paymentUri\":\"bitcoin:bc1p?amount=0.00019\"}}",
            "{\"state\":\"unconfirmed\",\"received\":\"49000\",\"confirmations\":1,\"txids\":[\""
                + txid
                + "\"]}",
            "{\"state\":\"paid\",\"method\":\"lightning\",\"preimage\":\"" + hash + "\"}",
            "{\"state\":\"paid\",\"method\":\"onchain\",\"received\":\"49000\",\"txids\":[\""
                + txid
                + "\"]}",
            "{\"state\":\"paid\",\"method\":\"mixed\",\"received\":\"49000\",\"txids\":[\""
                + txid
                + "\"],\"preimages\":[\""
                + hash
                + "\"]}",
            "{\"state\":\"expired\",\"received\":\"0\"}");
    for (int i = 0; i < statuses.size(); i += 1) {
      PaymentStatus status = statuses.get(i);
      assertEquals(written.get(i), status.toJson(), status.getClass().getSimpleName());
    }
    // Each subtype held as itself.
    assertEquals(written.get(3), new PaymentStatus.PaidLightning(hash).toJson());
    PaymentStatus.Paid paid = new PaymentStatus.PaidMixed("49000", List.of(txid), List.of(hash));
    assertEquals(written.get(5), paid.toJson());
    PaymentStatus.Expired expired = new PaymentStatus.Expired("0");
    assertEquals(written.get(6), expired.toJson());
    Fiat fiat = new Fiat("24.50", "CHF", "2450", 2);
    RateInfo rate = new RateInfo("coingecko", "CHF", "69012", 1_791_378_000L, null);
    assertEquals(
        "{\"amount\":\"24.50\",\"currency\":\"CHF\",\"minor\":\"2450\",\"digits\":2}",
        fiat.toJson());
    assertEquals(
        "{\"source\":\"coingecko\",\"currency\":\"CHF\",\"price\":\"69012\",\"fetchedAt\":1791378000,"
            + "\"time\":null}",
        rate.toJson());
    assertEquals(
        "{\"businessName\":\"Sunrise Bakery\",\"minSats\":\"1\",\"maxSats\":\"2\",\"onchainAddresses\":0}",
        new Merchant("Sunrise Bakery", "1", "2", 0).toJson());
    Invoice invoice =
        new Invoice(
            "R-1",
            "lnbc1",
            hash,
            "https://breez.tips/lnurlp/verify/" + hash,
            null,
            "lightning:lnbc1",
            "35502",
            fiat,
            rate,
            "Order 1",
            1_791_378_000L,
            1_791_378_900L,
            null,
            null);
    assertEquals(
        "{\"ref\":\"R-1\",\"bolt11\":\"lnbc1\",\"paymentHash\":\""
            + hash
            + "\",\"verifyUrl\":\"https://breez.tips/lnurlp/verify/"
            + hash
            + "\",\"onchainAddress\":null,\"paymentUri\":\"lightning:lnbc1\",\"sats\":\"35502\",\"fiat\":"
            + fiat.toJson()
            + ",\"rate\":"
            + rate.toJson()
            + ",\"note\":\"Order 1\",\"createdAt\":1791378000,\"expiresAt\":1791378900,"
            + "\"checkoutUrl\":null,\"redirectUrl\":null}",
        invoice.toJson());
    assertEquals(invoice, Invoice.fromJson(invoice.toJson()));
    DeclaredInvoice declared =
        new DeclaredInvoice(
            "R-1",
            "lnbc1",
            hash,
            "https://breez.tips/lnurlp/verify/" + hash,
            null,
            "lightning:lnbc1",
            "35502",
            fiat,
            "Order 1",
            1_791_378_000L,
            1_791_378_900L,
            1_791_378_001L);
    assertEquals(
        "{\"ref\":\"R-1\",\"bolt11\":\"lnbc1\",\"paymentHash\":\""
            + hash
            + "\",\"verifyUrl\":\"https://breez.tips/lnurlp/verify/"
            + hash
            + "\",\"onchainAddress\":null,\"paymentUri\":\"lightning:lnbc1\",\"sats\":\"35502\",\"fiat\":"
            + fiat.toJson()
            + ",\"note\":\"Order 1\",\"createdAt\":1791378000,\"expiresAt\":1791378900,"
            + "\"declaredAt\":1791378001}",
        declared.toJson());
    assertEquals(declared, DeclaredInvoice.fromJson(declared.toJson()));
  }

  @Test
  void serializesTheDataModelButNeverWhatHoldsTheKey() throws Exception {
    Invoice invoice = merchant.client().createInvoice(Order.builder("J-2").sats(1).build());
    for (Object value :
        List.of(
            invoice,
            new PaymentStatus.Open(5, "1", new PaymentStatus.Rest("2", null, null)),
            new PaymentStatus.PaidMixed("3", List.of("t"), List.of("p")),
            new Merchant("Sunrise Bakery", "1", "2", 0))) {
      assertInstanceOf(Serializable.class, value);
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
        out.writeObject(value);
      }
      try (ObjectInputStream in =
          new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
        assertEquals(value, in.readObject());
      }
    }
    assertFalse(Serializable.class.isAssignableFrom(SattlemeClient.class));
    String text = merchant.client().toString();
    assertTrue(text.startsWith("SattlemeClient["), text);
    assertTrue(text.contains(merchant.team), text);
  }

  @Test
  void givesEachCallAsAFutureTheSameOutcome() throws Exception {
    SattlemeClient sattleme = merchant.client();
    assertEquals(sattleme.merchant(), sattleme.merchantAsync().get(10, TimeUnit.SECONDS));
    Invoice invoice =
        sattleme.createInvoiceAsync(Order.builder("A-1").sats(1).build()).get(10, TimeUnit.SECONDS);
    assertEquals(
        sattleme.getStatus(invoice), sattleme.getStatusAsync(invoice).get(10, TimeUnit.SECONDS));
    merchant.payInvoice(invoice.paymentHash());
    assertEquals(
        "paid",
        sattleme
            .waitForPaymentAsync(invoice, WaitOptions.builder().intervalMs(250).build())
            .get(10, TimeUnit.SECONDS)
            .state());
    assertEquals(
        List.of(invoice.bolt11()),
        sattleme.findInvoicesAsync("A-1").get(10, TimeUnit.SECONDS).stream()
            .map(DeclaredInvoice::bolt11)
            .toList());
    // An error of the package: the future's cause, as the call throws it.
    ExecutionException error =
        assertThrows(
            ExecutionException.class,
            () -> sattleme.createInvoiceAsync(Order.builder("bad ref!").sats(1).build()).get());
    assertEquals("ref", assertInstanceOf(InvalidOrderException.class, error.getCause()).getParam());
  }

  @Test
  void cancelsACallItsFutureCancelledItsConnectionsClosed() throws Exception {
    // Relays that never answer: the call waits, then its future is cancelled.
    for (String url : merchant.keyRelays) {
      LocalRelay.Behaviour mute = new LocalRelay.Behaviour();
      mute.mute = true;
      merchant.net.get(url).behaviour = mute;
    }
    SattlemeClient sattleme = merchant.clientBuilder().relayTimeoutMs(10_000).build();
    for (CompletableFuture<?> call :
        List.<CompletableFuture<?>>of(
            sattleme.merchantAsync(),
            sattleme.createInvoiceAsync(Order.builder("C-1").sats(1).build()),
            sattleme.findInvoicesAsync("C-1"))) {
      Thread.sleep(100);
      assertTrue(call.cancel(true));
      assertThrows(CancellationException.class, () -> call.get(5, TimeUnit.SECONDS));
    }
    for (String url : merchant.keyRelays) {
      LocalRelay relay = merchant.net.get(url);
      long deadline = System.nanoTime() + 3_000_000_000L;
      while (relay.openConnections() > 0 && System.nanoTime() < deadline) Thread.sleep(10);
      assertEquals(0, relay.openConnections(), url);
    }
  }

  @Test
  void throwsInterruptedExceptionNeverAnErrorOfSpec5WhenItsThreadIsInterrupted() throws Exception {
    for (String url : merchant.keyRelays) {
      LocalRelay.Behaviour mute = new LocalRelay.Behaviour();
      mute.mute = true;
      merchant.net.get(url).behaviour = mute;
    }
    SattlemeClient sattleme = merchant.clientBuilder().relayTimeoutMs(10_000).build();
    List<Throwable> thrown = new CopyOnWriteArrayList<>();
    Thread thread =
        new Thread(() -> {
          try {
            sattleme.merchant();
          } catch (Throwable e) {
            thrown.add(e);
          }
        });
    thread.start();
    Thread.sleep(100);
    thread.interrupt();
    thread.join(5000);
    assertEquals(1, thrown.size());
    assertInstanceOf(InterruptedException.class, thrown.get(0));
    // A thread interrupted before the call: at once.
    Thread.currentThread().interrupt();
    try {
      assertThrows(InterruptedException.class, sattleme::merchant);
    } finally {
      Thread.interrupted();
    }
  }

  /** A call of a client. */
  @FunctionalInterface
  interface Call {
    Object run(SattlemeClient client) throws Exception;
  }

  /**
   * {@code call}, its request to a URL that {@code hangs} never answered, its thread interrupted:
   * InterruptedException, within seconds, never an error of SPEC.md §5.
   */
  private void interruptedWhileHanging(
      String what, java.util.function.Predicate<java.net.URI> hangs, Call call) throws Exception {
    java.util.concurrent.CountDownLatch hanging = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch never = new java.util.concurrent.CountDownLatch(1);
    me.sattle.checkout.support.FakeHttp http =
        new me.sattle.checkout.support.FakeHttp(uri -> {
          if (hangs.test(uri)) {
            hanging.countDown();
            never.await();
          }
          return merchant.answer(uri);
        });
    SattlemeClient client =
        merchant
            .clientBuilder()
            .relayTimeoutMs(60_000)
            .httpTimeoutMs(60_000)
            .httpClient(http)
            .build();
    List<Throwable> thrown = new CopyOnWriteArrayList<>();
    Thread thread =
        new Thread(() -> {
          try {
            call.run(client);
            thrown.add(new AssertionError("returned"));
          } catch (Throwable e) {
            thrown.add(e);
          }
        });
    try {
      thread.start();
      assertTrue(hanging.await(20, TimeUnit.SECONDS), what + ": never asked");
      thread.interrupt();
      thread.join(10_000);
      assertEquals(1, thrown.size(), what + ": " + thrown);
      assertInstanceOf(InterruptedException.class, thrown.get(0), what);
    } finally {
      never.countDown();
      http.shutdown();
    }
  }

  @Test
  void failsWithTheCancellationWhileARequestHangsAndReleasesTheOrder() throws Exception {
    // The audit's: each call cancelled while one of its requests hangs (SPEC.md §4).
    merchant.publishChannel(merchant.channelValue(200, Map.of("relays", merchant.keyRelays)), 200);
    merchant.publishLot(List.of(me.sattle.checkout.support.Chain.depositAddress(1)), 91);
    interruptedWhileHanging(
        "createInvoice, the pay URL",
        uri -> uri.toString().equals(TestMerchant.PAY_URL),
        client -> client.createInvoice(Order.builder("C-1").sats(500).build()));
    interruptedWhileHanging(
        "createInvoice, the callback",
        uri -> uri.toString().startsWith(TestMerchant.CALLBACK),
        client -> client.createInvoice(Order.builder("C-2").sats(500).build()));
    interruptedWhileHanging(
        "createInvoice, a rate source",
        uri -> uri.getHost().contains("coingecko"),
        client -> client.createInvoice(Order.builder("C-3").fiat("10.00", "CHF").build()));
    interruptedWhileHanging(
        "createInvoice, the explorer while it leases an address",
        uri -> uri.toString().endsWith("/txs"),
        client -> client.createInvoice(Order.builder("C-4").sats(49_000).build()));
    Invoice invoice = merchant.client().createInvoice(Order.builder("C-5").sats(49_000).build());
    interruptedWhileHanging(
        "getStatus, the verify URL",
        uri -> uri.toString().contains("/verify/"),
        client -> client.getStatus(invoice));
    interruptedWhileHanging(
        "getStatus, the explorer",
        uri -> uri.toString().endsWith("/txs"),
        client -> client.getStatus(invoice));
    interruptedWhileHanging(
        "waitForPayment, the explorer",
        uri -> uri.toString().endsWith("/txs"),
        client -> client.waitForPayment(invoice));
    // The order's lock released: a call of the same order runs.
    assertThrows(
        InvoicePendingException.class,
        () -> merchant.client().createInvoice(Order.builder("C-5").sats(49_000).build()));
  }

  /** An HTTP client of the merchant's fakes that holds the requests {@code held} gives true for. */
  private static me.sattle.checkout.support.FakeHttp holding(
      TestMerchant merchant,
      java.util.function.Predicate<java.net.URI> held,
      java.util.concurrent.CountDownLatch never) {
    return new me.sattle.checkout.support.FakeHttp(uri -> {
      if (held.test(uri)) never.await(30, TimeUnit.SECONDS);
      return merchant.answer(uri);
    });
  }

  /** Every request {@code http} was asked is over; those to {@code url} were cancelled. */
  private static void allOverAndCancelled(me.sattle.checkout.support.FakeHttp http, String url) {
    boolean asked = false;
    for (me.sattle.checkout.support.FakeHttp.Sent sent : http.sent) {
      assertTrue(sent.result().isDone(), sent.uri() + " still running");
      if (sent.uri().toString().equals(url)) {
        asked = true;
        assertTrue(sent.result().isCancelled(), url + " answered");
      }
    }
    assertTrue(asked, url + " never asked");
  }

  @Test
  void endsThePayRequestOfMerchantOnceTheReadOfTheLotFailed() throws Exception {
    // SPEC.md §4, §13: merchant() asks the Lightning address's pay request while it reads the lot;
    // the lot's read fails (two relays of three never answer it: network) while the pay request
    // hangs. Once merchant() failed, nothing it started runs on: the pay request is cancelled.
    String lot = me.sattle.checkout.internal.Derive.lotAddress(merchant.integrationKey);
    for (String url : merchant.keyRelays.subList(0, 2)) {
      merchant.net.get(url).behaviour.muteQuery = filter -> Js.write(filter).contains(lot);
    }
    java.util.concurrent.CountDownLatch never = new java.util.concurrent.CountDownLatch(1);
    me.sattle.checkout.support.FakeHttp http =
        holding(merchant, uri -> uri.toString().equals(TestMerchant.PAY_URL), never);
    try {
      SattlemeClient client = merchant.clientBuilder().httpClient(http).relayTimeoutMs(500).build();
      assertThrows(NetworkException.class, client::merchant);
      allOverAndCancelled(http, TestMerchant.PAY_URL);
    } finally {
      never.countDown();
      http.shutdown();
    }
  }

  @Test
  void endsThePayRequestAndTheRateOfCreateInvoiceOnceAnEarlierInvoiceWasFoundPaid()
      throws Exception {
    // SPEC.md §4, §13: createInvoice asks the pay request and the rate (a list of sources) while it
    // checks the order's earlier invoices; one was paid (OrderPaidException) while both hang. Once
    // it failed, nothing it started runs on: the pay request is cancelled, the rate's source
    // interrupted and ended, the next source never asked, nothing logged.
    Invoice paid = merchant.client().createInvoice(Order.builder("C-9").sats(500).build());
    merchant.payInvoice(paid.paymentHash());
    java.util.concurrent.CountDownLatch never = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch asked = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicBoolean interrupted =
        new java.util.concurrent.atomic.AtomicBoolean();
    java.util.concurrent.CompletableFuture<Void> ended =
        new java.util.concurrent.CompletableFuture<>();
    RateSource holds =
        RateSource.custom(currency -> {
          asked.countDown();
          try {
            never.await(30, TimeUnit.SECONDS);
            return RateQuote.of("69012");
          } catch (InterruptedException e) {
            interrupted.set(true);
            throw e;
          } finally {
            ended.complete(null);
          }
        });
    me.sattle.checkout.support.FakeHttp http =
        holding(merchant, uri -> uri.toString().equals(TestMerchant.PAY_URL), never);
    try {
      SattlemeClient client =
          merchant
              .clientBuilder()
              .httpClient(http)
              .rateSources(List.of(holds, RateSource.MEMPOOL))
              .build();
      int warned = merchant.warnings.size();
      assertThrows(
          OrderPaidException.class,
          () -> client.createInvoice(Order.builder("C-9").fiat("10.00", "CHF").build()));
      assertEquals(0, asked.getCount());
      assertTrue(ended.isDone(), "the rate source still runs");
      assertTrue(interrupted.get());
      allOverAndCancelled(http, TestMerchant.PAY_URL);
      Thread.sleep(300);
      assertEquals(
          List.of(), http.urls().stream().filter(url -> url.contains("mempool.space")).toList());
      assertEquals(warned, merchant.warnings.size(), merchant.warnings.toString());
    } finally {
      never.countDown();
      http.shutdown();
    }
  }

  @Test
  void namesItsRateSourcesAndCurrencies() {
    assertEquals(155, Currencies.all().size());
    assertEquals(
        new TreeSet<>(List.of("coingecko", "mempool", "opennode", "coinbase", "kraken")),
        new TreeSet<>(
            RateSource.DEFAULT_LIST.stream().map(RateSource::name).collect(Collectors.toList())));
    RateSource custom = RateSource.custom(currency -> RateQuote.of("1"));
    assertTrue(custom.isCustom());
    assertEquals("custom", custom.name());
    assertNotNull(custom.customSource());
    // An error of the package's (SPEC.md §5), never Java's NullPointerException.
    assertEquals(
        "rateSource",
        assertThrows(InvalidOptionException.class, () -> RateSource.custom(null)).getParam());
  }
}
