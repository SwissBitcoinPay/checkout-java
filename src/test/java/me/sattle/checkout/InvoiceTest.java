package me.sattle.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.net.http.HttpHeaders;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import me.sattle.checkout.internal.Derive;
import me.sattle.checkout.internal.Hex;
import me.sattle.checkout.internal.crypto.Hashes;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.nostr.Events;
import me.sattle.checkout.internal.nostr.NostrEvent;
import me.sattle.checkout.support.Eventually;
import me.sattle.checkout.support.FakeHttp;
import me.sattle.checkout.support.Js;
import me.sattle.checkout.support.Lightning;
import me.sattle.checkout.support.LocalRelay;
import me.sattle.checkout.support.Owner;
import me.sattle.checkout.support.TestMerchant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** createInvoice and findInvoices (SPEC.md §17 to §21, §24), as checkout-node's invoice tests. */
class InvoiceTest {
  private TestMerchant merchant;

  @BeforeEach
  void start() throws Exception {
    merchant = new TestMerchant().start();
    merchant.publishChannel(merchant.channelValue(100, Map.of("relays", merchant.keyRelays)), 100);
  }

  @AfterEach
  void stop() throws Exception {
    merchant.stop();
  }

  private SattlemeClient client() {
    return merchant.clientBuilder().relayTimeoutMs(1000).build();
  }

  private SattlemeClient client(Consumer<SattlemeClient.Builder> options) {
    SattlemeClient.Builder builder = merchant.clientBuilder().relayTimeoutMs(1000);
    options.accept(builder);
    return builder.build();
  }

  /**
   * Every declaration the relays received, once each relay received as many: a publication ends on
   * a majority, the last relay's copy may be on its way.
   */
  private List<NostrEvent> declarations() throws InterruptedException {
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
    List<NostrEvent> events = new ArrayList<>();
    for (String url : merchant.keyRelays) {
      for (NostrEvent event : merchant.net.get(url).received) {
        if (event.kind() == 30078) events.add(event);
      }
    }
    return events;
  }

  private static Order sats(String ref, long sats) {
    return Order.builder(ref).sats(sats).build();
  }

  private static RateSource fixed(String price) {
    return RateSource.custom(currency -> RateQuote.of(price));
  }

  private TestMerchant.MadeInvoice firstMade() {
    return new ArrayList<>(merchant.invoices.values()).get(0);
  }

  private FakeHttp.Call callbackCall() {
    synchronized (merchant.http.calls) {
      for (FakeHttp.Call call : merchant.http.calls) {
        if (call.uri().getPath().endsWith("/invoice")) return call;
      }
    }
    throw new AssertionError("No invoice was asked");
  }

  private static Map<String, String> query(FakeHttp.Call call) {
    Map<String, String> params = new java.util.LinkedHashMap<>();
    for (String pair : call.uri().getRawQuery().split("&")) {
      String[] parts = pair.split("=", 2);
      params.put(
          java.net.URLDecoder.decode(parts[0], java.nio.charset.StandardCharsets.UTF_8),
          java.net.URLDecoder.decode(parts[1], java.nio.charset.StandardCharsets.UTF_8));
    }
    return params;
  }

  private void tamper(Function<TestMerchant.MadeInvoice, String> pr) {
    merchant.tamper =
        made ->
            new TestMerchant.MadeInvoice(
                pr.apply(made),
                made.verify(),
                made.paymentHash(),
                made.preimage(),
                made.amountMsat(),
                made.expiry(),
                made.comment());
  }

  private Lightning.Spec spec(TestMerchant.MadeInvoice made) {
    Lightning.Spec spec = new Lightning.Spec();
    spec.amountMsat = made.amountMsat();
    spec.paymentHash = made.paymentHash();
    spec.descriptionHash = Lightning.metadataHash(TestMerchant.METADATA);
    spec.timestamp = merchant.now;
    spec.expirySeconds = 900L;
    return spec;
  }

  @Nested
  class CreateInvoice {
    @Test
    void keepsTheCallbacksOwnQueryParametersAndDropsItsFragment() throws Exception {
      merchant.pay = merchant.pay.put("callback", TestMerchant.CALLBACK + "?k=1#tag");
      client().createInvoice(sats("ORD-1", 1000));
      FakeHttp.Call callback = callbackCall();
      assertNull(callback.uri().getRawFragment());
      assertEquals(
          Map.of("k", "1", "amount", "1000000", "expiry", "900", "comment", "ORD-1"),
          query(callback));
      assertEquals(
          List.of("k", "amount", "expiry", "comment"), new ArrayList<>(query(callback).keySet()));
    }

    @Test
    void refusesAPayRequestWhoseCallbackIsNotAnHttpsUrl() throws Exception {
      for (String callback :
          List.of(
              "https://10.0.0.1/cb", "https://pay.localhost/cb", "https://user@breez.tips/cb")) {
        merchant.pay = merchant.pay.put("callback", callback);
        assertThrows(
            InvalidInvoiceException.class, () -> client().createInvoice(sats("ORD-1", 1000)));
      }
      for (String url : merchant.http.urls()) assertFalse(url.endsWith("/cb"), url);
    }

    @Test
    void refusesAPayRequestWhoseMetadataHoldsALoneSurrogateItHasNoUtf8ToHash() throws Exception {
      // Valid JSON (an escape), but no text (SPEC.md §6, §16): JavaScript's TextEncoder hashes
      // U+FFFD for it, as a server of breez.tips's language would make its invoices.
      String metadata = "[[\"text/plain\",\"Pay \ud800\"]]";
      merchant.pay = merchant.pay.put("metadata", metadata);
      tamper(made -> {
        Lightning.Spec spec = spec(made);
        spec.descriptionHash =
            Hex.encode(
                Hashes.sha256(
                    "[[\"text/plain\",\"Pay \ufffd\"]]"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return Lightning.makeInvoice(spec);
      });
      assertThrows(
          InvalidInvoiceException.class, () -> client().createInvoice(sats("ORD-1", 1000)));
      // Before its callback is asked.
      for (String url : merchant.http.urls())
        assertFalse(url.startsWith(TestMerchant.CALLBACK), url);
      assertEquals(Map.of(), merchant.invoices);
    }

    @Test
    void failsWithANetworkErrorOnACallbackWhoseHostCannotBeReached() throws Exception {
      // An https URL by its rule (SPEC.md §15), but a punycode label that decodes to nothing.
      merchant.pay = merchant.pay.put("callback", "https://xn--abc.example/cb");
      assertThrows(NetworkException.class, () -> client().createInvoice(sats("ORD-1", 1000)));
    }

    @Test
    void asksChecksAndDeclaresTheInvoiceOfAnOrderInSats() throws Exception {
      Invoice invoice =
          client().createInvoice(Order.builder("ORD-1042").sats(49_000).note("Order 1042").build());
      TestMerchant.MadeInvoice made = firstMade();
      assertEquals(
          new Invoice(
              "ORD-1042",
              made.pr(),
              made.paymentHash(),
              made.verify(),
              // Within the Lightning address's bounds, and no lot of addresses: Lightning only.
              null,
              "lightning:" + made.pr(),
              "49000",
              null,
              null,
              "Order 1042",
              merchant.now,
              merchant.now + 900,
              // No checkout link was asked.
              null,
              null),
          invoice);
      // JSON as is: an integrator stores it with the order.
      assertEquals(invoice, Invoice.fromJson(invoice.toJson()));
      assertEquals(
          Js.obj(
              "ref",
              "ORD-1042",
              "bolt11",
              made.pr(),
              "paymentHash",
              made.paymentHash(),
              "verifyUrl",
              made.verify(),
              "onchainAddress",
              null,
              "paymentUri",
              "lightning:" + made.pr(),
              "sats",
              "49000",
              "fiat",
              null,
              "rate",
              null,
              "note",
              "Order 1042",
              "createdAt",
              merchant.now,
              "expiresAt",
              merchant.now + 900,
              "checkoutUrl",
              null,
              "redirectUrl",
              null),
          Js.parse(invoice.toJson()));

      assertEquals(
          Map.of("amount", "49000000", "expiry", "900", "comment", "Order 1042"),
          query(callbackCall()));
      // Every request, with the User-Agent every implementation sends (SPEC.md §15).
      client().getStatus(invoice);
      assertTrue(merchant.http.calls.size() >= 3);
      synchronized (merchant.http.calls) {
        for (FakeHttp.Call call : merchant.http.calls) {
          HttpHeaders headers = call.headers();
          assertEquals(
              Map.of(
                  "Accept",
                  List.of("application/json"),
                  "User-Agent",
                  List.of("sattleme-Checkout/1")),
              headers.map());
        }
      }

      // Declared on every relay of the account, signed by the order's sale.
      List<NostrEvent> events = declarations();
      assertEquals(3, events.size());
      NostrEvent event = events.get(0);
      Derive.Sale sale =
          Derive.Sale.of(
              merchant.integrationKey,
              new Derive.Registry(merchant.registryKey, merchant.reader),
              "ORD-1042");
      assertTrue(Events.checked(event.toJson()) != null);
      assertEquals(sale.author, event.pubkey());
      assertEquals(
          Js.obj(
              "version", 1,
              "invoice", made.pr(),
              "verifyUrl", made.verify(),
              "sale",
                  Js.obj(
                      "key", Hex.encode(sale.key),
                      "fiat", Js.obj("minor", 49000, "digits", 0, "currency", "SAT"),
                      "note", "Order 1042"),
              "taker", Derive.takerTag(Derive.takerKey(merchant.integrationKey), sale.author)),
          Owner.openAsOwner(event, merchant.registryKey, merchant.readerSecret));
    }

    @Test
    void namesTheOrderByItsReferenceWithoutANoteAndCommentsOnlyAsAllowed() throws Exception {
      client().createInvoice(sats("A-1", 100));
      merchant.pay = merchant.pay.put("commentAllowed", 2);
      client().createInvoice(Order.builder("A-2").sats("100").durationSeconds(60).build());
      JsonObject withoutComment = new JsonObject();
      for (Map.Entry<String, me.sattle.checkout.internal.json.JsonValue> entry :
          merchant.pay.entries()) {
        if (!entry.getKey().equals("commentAllowed"))
          withoutComment.put(entry.getKey(), entry.getValue());
      }
      merchant.pay = withoutComment;
      client().createInvoice(Order.builder("A-3").sats(100).durationSeconds(3600).build());
      List<List<Object>> comments = new ArrayList<>();
      for (TestMerchant.MadeInvoice made : sortedMade()) {
        comments.add(java.util.Arrays.asList(made.comment(), made.expiry()));
      }
      assertEquals(
          List.of(
              java.util.Arrays.asList("A-1", 900L),
              java.util.Arrays.asList(null, 60L),
              java.util.Arrays.asList(null, 3600L)),
          comments);
      assertEquals(
          "A-1",
          Js.text(
              Js.get(
                  Owner.openAsOwner(
                      declarations().get(0), merchant.registryKey, merchant.readerSecret),
                  "sale"),
              "note"));
    }

    static Stream<Arguments> invalidOrders() {
      RateSource bitstamp = RateSource.byName("bitstamp").orElse(null);
      return Stream.of(
          Arguments.of("ref", Order.builder(null).sats(1).build()),
          Arguments.of("ref", Order.builder("").sats(1).build()),
          Arguments.of("ref", Order.builder("x".repeat(65)).sats(1).build()),
          Arguments.of("ref", Order.builder("CMD 1").sats(1).build()),
          Arguments.of("ref", Order.builder("CMD/1").sats(1).build()),
          Arguments.of("ref", Order.builder("Bestellung-ä").sats(1).build()),
          Arguments.of("sats", Order.builder("A").build()),
          Arguments.of("sats", Order.builder("A").sats(1).fiat("1", "CHF").build()),
          Arguments.of("sats", Order.builder("A").sats(0).build()),
          Arguments.of("sats", Order.builder("A").sats(-1).build()),
          Arguments.of("sats", Order.builder("A").sats("1.5").build()),
          Arguments.of("sats", Order.builder("A").sats("0x10").build()),
          Arguments.of("sats", Order.builder("A").sats("1e3").build()),
          Arguments.of("sats", Order.builder("A").sats(9_007_199_254_740_992L).build()),
          Arguments.of("durationSeconds", Order.builder("A").sats(1).durationSeconds(59).build()),
          Arguments.of("durationSeconds", Order.builder("A").sats(1).durationSeconds(3601).build()),
          Arguments.of("note", Order.builder("A").sats(1).note("n".repeat(101)).build()),
          Arguments.of("note", Order.builder("A").sats(1).note("line\nbreak").build()),
          Arguments.of("fiat", Order.builder("A").fiat("24.5.0", "CHF").build()),
          Arguments.of("fiat", Order.builder("A").fiat("24.50", "SAT").build()),
          Arguments.of("rateSource", Order.builder("A").sats(1).rateSource(fixed("1")).build()),
          Arguments.of(
              "rateSource", Order.builder("A").sats(1).rateSource(RateSource.KRAKEN).build()),
          Arguments.of(
              "rateSource", Order.builder("A").fiat("1", "CHF").rateSource(bitstamp).build()),
          Arguments.of(
              "rateSource", Order.builder("A").fiat("1", "CHF").rateSources(List.of()).build()),
          Arguments.of("rateSource", Order.builder("A").fiat("1", "CHF").rateSources(null).build()),
          Arguments.of(
              "rateSource",
              Order.builder("A")
                  .fiat("1", "CHF")
                  .rateSources(List.of(RateSource.KRAKEN, RateSource.KRAKEN))
                  .build()),
          Arguments.of(
              "rateSource",
              Order.builder("A")
                  .sats(1)
                  .rateSources(List.of(RateSource.KRAKEN, RateSource.COINGECKO))
                  .build()));
    }

    @ParameterizedTest(name = "refuses an invalid {0} before any request")
    @MethodSource("invalidOrders")
    void refusesAnInvalidParameterBeforeAnyRequest(String param, Order order) throws Exception {
      InvalidOrderException error =
          assertThrows(InvalidOrderException.class, () -> client().createInvoice(order));
      assertEquals(param, error.getParam());
      assertEquals(0, merchant.invoices.size());
      assertEquals(0, declarations().size());
    }

    @Test
    void acceptsTheEdges() throws Exception {
      String ref = "A".repeat(60) + ".:_-";
      client()
          .createInvoice(
              Order.builder(ref).sats(1).note("é".repeat(100)).durationSeconds(60).build());
      // Given once a majority took it; the last relay takes it a moment later.
      for (int i = 0; i < 100 && declarations().size() < 3; i += 1) Thread.sleep(10);
      assertEquals(3, declarations().size());
    }

    @Test
    void keepsAnAmountWithinTheLightningAddressBoundsRoundedInwards() throws Exception {
      merchant.pay = merchant.pay.put("minSendable", 10_001).put("maxSendable", 1_000_999);
      SattlemeClient sattleme = client();
      Merchant read = sattleme.merchant();
      assertEquals("11", read.minSats());
      assertEquals("1000", read.maxSats());
      InvalidOrderException below =
          assertThrows(InvalidOrderException.class, () -> sattleme.createInvoice(sats("B-10", 10)));
      assertEquals("sats", below.getParam());
      // From 1 000 sats on, on-chain only: no address without a lot.
      OnchainUnavailableException above =
          assertThrows(
              OnchainUnavailableException.class,
              () -> sattleme.createInvoice(sats("B-1001", 1001)));
      assertTrue(above.isRetryable());
      sattleme.createInvoice(sats("B-11", 11));
      sattleme.createInvoice(sats("B-1000", 1000));
      assertEquals(2, merchant.invoices.size());
    }

    static Stream<Arguments> tampered() {
      return Stream.of(
          Arguments.of("an invoice of another network", "network"),
          Arguments.of("another amount", "amount"),
          Arguments.of("no amount", "noAmount"),
          Arguments.of("another merchant's invoice", "description"),
          Arguments.of("a longer expiry", "longer"),
          Arguments.of("no expiry (3600 s)", "noExpiry"),
          Arguments.of("a date 5 minutes off", "early"),
          Arguments.of("a date in the future", "late"),
          Arguments.of("a verify URL in http", "http"),
          Arguments.of("a verify URL on another host", "host"),
          Arguments.of("not an invoice", "garbage"));
    }

    @ParameterizedTest(name = "refuses {0}, and declares nothing")
    @MethodSource("tampered")
    void refusesATamperedInvoice(String name, String how) throws Exception {
      merchant.tamper =
          made -> {
            Lightning.Spec spec = spec(made);
            String pr = null;
            String verify = made.verify();
            switch (how) {
              case "network" -> spec.network = "tb";
              case "amount" ->
                  spec.amountMsat = made.amountMsat().subtract(BigInteger.valueOf(1000));
              case "noAmount" -> spec.amountMsat = null;
              case "description" -> spec.descriptionHash = Lightning.metadataHash("[]");
              case "longer" -> spec.expirySeconds = 901L;
              case "noExpiry" -> spec.expirySeconds = null;
              case "early" -> spec.timestamp = merchant.now - 301;
              case "late" -> spec.timestamp = merchant.now + 301;
              case "http" -> verify = "http://breez.tips/lnurlp/verify/" + made.paymentHash();
              case "host" -> verify = "https://evil.test/verify/" + made.paymentHash();
              case "garbage" -> pr = "lnbc1nope";
              default -> throw new IllegalArgumentException(how);
            }
            if (pr == null)
              pr =
                  how.equals("http") || how.equals("host")
                      ? made.pr()
                      : Lightning.makeInvoice(spec);
            return new TestMerchant.MadeInvoice(
                pr,
                verify,
                made.paymentHash(),
                made.preimage(),
                made.amountMsat(),
                made.expiry(),
                made.comment());
          };
      assertThrows(InvalidInvoiceException.class, () -> client().createInvoice(sats("C-1", 1000)));
      assertEquals(0, declarations().size());
    }

    @Test
    void refusesAnInvoiceDatedWithinFiveMinutesButAlreadyOver() throws Exception {
      tamper(made -> {
        Lightning.Spec spec = spec(made);
        spec.timestamp = merchant.now - 200;
        spec.expirySeconds = 60L;
        return Lightning.makeInvoice(spec);
      });
      assertThrows(
          InvalidInvoiceException.class,
          () ->
              client().createInvoice(Order.builder("C-3").sats(1000).durationSeconds(60).build()));
      assertEquals(0, declarations().size());
    }

    @Test
    void refusesAValidInvoiceTooLargeToBeDeclared() throws Exception {
      // 70 unknown fields of 1023 words: some 72 000 characters, more than a NIP-44 plaintext
      // holds.
      tamper(made -> {
        Lightning.Spec spec = spec(made);
        for (int i = 0; i < 70; i += 1) {
          spec.extra.add(
              new Object[] {31, new ArrayList<>(java.util.Collections.nCopies(1023, 0))});
        }
        return Lightning.makeInvoice(spec);
      });
      InvalidInvoiceException error =
          assertThrows(
              InvalidInvoiceException.class, () -> client().createInvoice(sats("C-4", 1000)));
      assertTrue(error.getMessage().contains("too large to be declared"), error.getMessage());
      assertEquals(0, declarations().size());
    }

    @Test
    void acceptsAnInvoiceDatedWithinFiveMinutes() throws Exception {
      tamper(made -> {
        Lightning.Spec spec = spec(made);
        spec.timestamp = merchant.now - 300;
        spec.expirySeconds = 899L;
        return Lightning.makeInvoice(spec);
      });
      Invoice invoice = client().createInvoice(sats("C-2", 1000));
      assertEquals(merchant.now - 300 + 899, invoice.expiresAt());
    }

    @Test
    void refusesAnAnswerWithoutAnInvoiceOrTheServicesRefusal() throws Exception {
      merchant.override =
          uri ->
              uri.getPath().endsWith("/invoice")
                  ? FakeHttp.Answer.json(Js.obj("verify", "https://breez.tips/v"))
                  : null;
      assertThrows(InvalidInvoiceException.class, () -> client().createInvoice(sats("D-1", 1)));
      merchant.override =
          uri ->
              uri.getPath().endsWith("/invoice")
                  ? FakeHttp.Answer.json(
                      400, Js.obj("status", "ERROR", "reason", "Amount out of range"))
                  : null;
      assertThrows(NetworkException.class, () -> client().createInvoice(sats("D-2", 1)));
      merchant.override =
          uri -> uri.getPath().endsWith("/invoice") ? FakeHttp.Answer.json(502, Js.obj()) : null;
      assertThrows(NetworkException.class, () -> client().createInvoice(sats("D-3", 1)));
      assertEquals(0, declarations().size());
    }

    @Test
    void givesTheInvoiceOnceAMajorityOfTheRelaysTookItsDeclaration() throws Exception {
      merchant.net.get("wss://relay-a.test").behaviour = refusing("blocked: no");
      client().createInvoice(sats("E-1", 1));
    }

    @Test
    void givesNoInvoiceWithoutAMajority() throws Exception {
      merchant.net.get("wss://relay-a.test").behaviour = refusing("blocked: no");
      merchant.net.get("wss://relay-b.test").behaviour = refusing("error: internal error");
      assertThrows(NetworkException.class, () -> client().createInvoice(sats("E-2", 1)));
      merchant.net.get("wss://relay-b.test").behaviour = new LocalRelay.Behaviour();
      merchant.net.remove("wss://relay-c.test");
      assertThrows(NetworkException.class, () -> client().createInvoice(sats("E-3", 1)));
    }

    @Test
    void authenticatesToARelayAsTheOrdersSale() throws Exception {
      merchant.net.get("wss://relay-a.test").behaviour = authenticating();
      merchant.net.get("wss://relay-b.test").behaviour = authenticating();
      client().createInvoice(sats("E-4", 1));
      String sale = saleAuthor("E-4");
      LocalRelay b = merchant.net.get("wss://relay-b.test");
      // On a majority when the call returned, on relay-b once its write ended.
      Eventually.until(
          () -> b.events.stream().anyMatch(event -> sale.equals(Js.text(event, "pubkey"))));
      assertTrue(b.authenticated.contains(sale));
      long held = b.events.stream().filter(event -> sale.equals(Js.text(event, "pubkey"))).count();
      assertEquals(1, held);
    }

    @Test
    void declaresOnTheAccountsRelaysTheChannelNames() throws Exception {
      merchant.stop();
      merchant = new TestMerchant().start("wss://account-a.test", "wss://account-b.test");
      List<String> all = new ArrayList<>(merchant.keyRelays);
      all.addAll(List.of("wss://account-a.test", "wss://account-b.test"));
      merchant.publishChannel(
          merchant.channelValue(
              100, Map.of("relays", List.of("wss://account-a.test", "wss://account-b.test"))),
          100,
          all,
          merchant.teamSecret);
      client().createInvoice(sats("E-5", 1));
      merchant.net.settle();
      assertEquals(2, merchant.net.get("wss://account-b.test").events.size());
      assertEquals(0, merchant.net.get("wss://relay-a.test").received.size());
    }

    @Test
    void refusesOnceTheMerchantRemovedTheIntegration() throws Exception {
      merchant.publishChannel(Js.obj("version", 1, "status", "removed", "updatedAt", 200), 200);
      assertThrows(RevokedException.class, () -> client().createInvoice(sats("F-1", 1)));
      assertEquals(0, merchant.http.calls.size());
    }

    @Test
    void convertsAPriceInACurrencyAtItsRateAndDeclaresThePrice() throws Exception {
      SattlemeClient sattleme = client(builder -> builder.rateSource(fixed("69012")));
      Invoice invoice =
          sattleme.createInvoice(
              Order.builder("G-1").fiat("24.50", "CHF").note("Order 1042").build());
      assertEquals("35502", invoice.sats());
      assertEquals(new Fiat("24.50", "CHF", "2450", 2), invoice.fiat());
      assertEquals(new RateInfo("custom", "CHF", "69012", merchant.now, null), invoice.rate());
      assertEquals(BigInteger.valueOf(35_502_000), firstMade().amountMsat());
      assertEquals(
          Js.obj("minor", 2450, "digits", 2, "currency", "CHF"),
          Js.get(
              Js.get(
                  Owner.openAsOwner(
                      declarations().get(0), merchant.registryKey, merchant.readerSecret),
                  "sale"),
              "fiat"));
      // Another source for one invoice.
      Invoice other =
          sattleme.createInvoice(
              Order.builder("G-2").fiat("1500", "JPY").rateSource(fixed("15000000")).build());
      assertEquals("10000", other.sats());
      assertEquals("custom", other.rate().source());
      assertEquals("15000000", other.rate().price());
    }

    @Test
    void refusesAConvertedAmountOutsideTheBounds() throws Exception {
      merchant.pay = merchant.pay.put("minSendable", 100_000);
      SattlemeClient sattleme = client(builder -> builder.rateSource(fixed("100000")));
      InvalidOrderException error =
          assertThrows(
              InvalidOrderException.class,
              () -> sattleme.createInvoice(Order.builder("G-3").fiat("0.05", "CHF").build()));
      assertEquals("fiat", error.getParam());
    }
  }

  private List<TestMerchant.MadeInvoice> sortedMade() {
    List<TestMerchant.MadeInvoice> made = new ArrayList<>(merchant.invoices.values());
    // The fake makes them with preimages 10, 11, 12…: in that order.
    made.sort(java.util.Comparator.comparing(TestMerchant.MadeInvoice::preimage));
    return made;
  }

  private String saleAuthor(String ref) {
    return Derive.Sale.of(
            merchant.integrationKey,
            new Derive.Registry(merchant.registryKey, merchant.reader),
            ref)
        .author;
  }

  private static LocalRelay.Behaviour refusing(String reason) {
    LocalRelay.Behaviour behaviour = new LocalRelay.Behaviour();
    behaviour.refuse = reason;
    return behaviour;
  }

  private static LocalRelay.Behaviour authenticating() {
    LocalRelay.Behaviour behaviour = new LocalRelay.Behaviour();
    behaviour.requireAuth = true;
    return behaviour;
  }

  private static LocalRelay.Behaviour delayed(long ms) {
    LocalRelay.Behaviour behaviour = new LocalRelay.Behaviour();
    behaviour.queryDelayMs = ms;
    return behaviour;
  }

  @Nested
  class OneOrderOneInvoiceAtATime {
    @Test
    void refusesASecondInvoiceWhileTheFirstMayBePaidThenAllowsOne() throws Exception {
      SattlemeClient sattleme = client();
      Invoice first =
          sattleme.createInvoice(Order.builder("H-1").sats(1).durationSeconds(60).build());
      InvoicePendingException pending =
          assertThrows(InvoicePendingException.class, () -> sattleme.createInvoice(sats("H-1", 1)));
      assertEquals(first.bolt11(), pending.getInvoice().bolt11());
      assertEquals(first.expiresAt(), pending.getInvoice().expiresAt());
      assertEquals("1", pending.getInvoice().sats());
      assertNull(pending.getInvoice().fiat());
      assertEquals("H-1", pending.getInvoice().note());
      // Its end, then the 120 s a late payment may still be reported in.
      merchant.now = first.expiresAt() + 119;
      assertThrows(InvoicePendingException.class, () -> sattleme.createInvoice(sats("H-1", 1)));
      merchant.now = first.expiresAt() + 120;
      Invoice second = sattleme.createInvoice(sats("H-1", 1));
      assertNotEquals(first.bolt11(), second.bolt11());
      // Another order is another sale.
      sattleme.createInvoice(sats("H-2", 1));
    }

    @Test
    void refusesANewInvoiceForAnOrderAlreadyPaid() throws Exception {
      SattlemeClient sattleme = client();
      Invoice first =
          sattleme.createInvoice(Order.builder("H-3").sats(1).durationSeconds(60).build());
      merchant.payInvoice(first.paymentHash());
      merchant.now = first.expiresAt() + 600;
      OrderPaidException error =
          assertThrows(OrderPaidException.class, () -> sattleme.createInvoice(sats("H-3", 1)));
      assertEquals(merchant.invoices.get(first.paymentHash()).preimage(), error.getPreimage());
    }

    @Test
    void refusesANewInvoiceForAnOrderPaidWhileItsInvoiceMayStillBePaid() throws Exception {
      SattlemeClient sattleme = client();
      Invoice first = sattleme.createInvoice(Order.builder("H-4").sats(1).checkout(false).build());
      merchant.payInvoice(first.paymentHash());
      // Within its time: its payment checked even alone (SPEC.md §21, step 1).
      OrderPaidException error =
          assertThrows(OrderPaidException.class, () -> sattleme.createInvoice(sats("H-4", 1)));
      assertEquals(first.bolt11(), error.getInvoice().bolt11());
      assertEquals(
          new PaymentStatus.PaidLightning(merchant.invoices.get(first.paymentHash()).preimage()),
          error.getStatus());
    }

    @Test
    void givesTheErrorOfThePaymentCheckOfTheOnlyInvoiceThatMayStillBePaid() throws Exception {
      SattlemeClient sattleme = client();
      Invoice first = sattleme.createInvoice(sats("H-5", 1));
      // Its Lightning address does not answer: it may have been paid.
      merchant.override =
          uri ->
              uri.toString().startsWith("https://breez.tips/lnurlp/verify/")
                  ? FakeHttp.Answer.text(503, "")
                  : null;
      assertThrows(NetworkException.class, () -> sattleme.createInvoice(sats("H-5", 1)));
      merchant.override = null;
      InvoicePendingException pending =
          assertThrows(InvoicePendingException.class, () -> sattleme.createInvoice(sats("H-5", 1)));
      assertEquals(first.bolt11(), pending.getInvoice().bolt11());
    }

    @Test
    void declaresAPendingInvoiceOnAMajorityAgainBeforeHandingItOut() throws Exception {
      SattlemeClient sattleme = client();
      merchant.net.get("wss://relay-a.test").behaviour = refusing("blocked: no");
      merchant.net.get("wss://relay-b.test").behaviour = refusing("blocked: no");
      assertThrows(NetworkException.class, () -> sattleme.createInvoice(sats("H-7", 1)));
      TestMerchant.MadeInvoice made = firstMade();
      // Only relay-c holds its declaration. relay-a answers reads late: relay-b and relay-c are the
      // majority read, and the declaration found is not handed out while the others refuse it.
      LocalRelay.Behaviour lateRefusing = refusing("blocked: no");
      lateRefusing.queryDelayMs = 300;
      merchant.net.get("wss://relay-a.test").behaviour = lateRefusing;
      assertThrows(NetworkException.class, () -> sattleme.createInvoice(sats("H-7", 1)));
      // Now a majority takes it again: then, and only then, it is handed out.
      merchant.net.get("wss://relay-a.test").behaviour = delayed(300);
      merchant.net.get("wss://relay-b.test").behaviour = new LocalRelay.Behaviour();
      InvoicePendingException pending =
          assertThrows(InvoicePendingException.class, () -> sattleme.createInvoice(sats("H-7", 1)));
      assertEquals(made.pr(), pending.getInvoice().bolt11());
      String sale = saleAuthor("H-7");
      merchant.net.settle();
      for (String url : merchant.keyRelays) {
        long held =
            merchant.net.get(url).events.stream()
                .filter(event -> sale.equals(Js.text(event, "pubkey")))
                .count();
        assertEquals(1, held, url);
      }
      assertEquals(1, merchant.invoices.size());
    }

    @Test
    void mayMakeAnotherInvoiceWhenTheMajorityReadLacksOneNeverHandedOut() throws Exception {
      SattlemeClient sattleme = client();
      merchant.net.get("wss://relay-a.test").behaviour = refusing("blocked: no");
      merchant.net.get("wss://relay-b.test").behaviour = refusing("blocked: no");
      // Declared on relay-c alone: "do not show this invoice".
      assertThrows(NetworkException.class, () -> sattleme.createInvoice(sats("H-10", 1)));
      // relay-a and relay-b answer first, without it: a new invoice, declared on a majority.
      merchant.net.get("wss://relay-a.test").behaviour = new LocalRelay.Behaviour();
      merchant.net.get("wss://relay-b.test").behaviour = new LocalRelay.Behaviour();
      merchant.net.get("wss://relay-c.test").behaviour = delayed(300);
      Invoice second = sattleme.createInvoice(sats("H-10", 1));
      assertEquals(2, merchant.invoices.size());
      assertEquals(sortedMade().get(1).pr(), second.bolt11());
    }

    @Test
    void refusesANewInvoiceForAPaidOrderEvenWhenAnotherCheckGetsNoAnswer() throws Exception {
      SattlemeClient sattleme = client();
      Invoice first =
          sattleme.createInvoice(Order.builder("H-8").sats(1).durationSeconds(60).build());
      merchant.now = first.expiresAt() + 120;
      Invoice second =
          sattleme.createInvoice(Order.builder("H-8").sats(1).durationSeconds(60).build());
      merchant.payInvoice(second.paymentHash());
      merchant.now = second.expiresAt() + 600;
      merchant.override =
          uri ->
              uri.getPath().endsWith(first.paymentHash())
                  ? FakeHttp.Answer.json(502, Js.obj())
                  : null;
      OrderPaidException error =
          assertThrows(OrderPaidException.class, () -> sattleme.createInvoice(sats("H-8", 1)));
      assertEquals(second.bolt11(), error.getInvoice().bolt11());
      // Unpaid, one check without an answer: retry later.
      Invoice other =
          sattleme.createInvoice(Order.builder("H-9").sats(1).durationSeconds(60).build());
      merchant.now = other.expiresAt() + 120;
      merchant.override =
          uri ->
              uri.getPath().endsWith(other.paymentHash())
                  ? FakeHttp.Answer.json(502, Js.obj())
                  : null;
      assertThrows(NetworkException.class, () -> sattleme.createInvoice(sats("H-9", 1)));
    }

    @Test
    void makesOneInvoiceWhenTwoCallsForOneOrderRace() throws Exception {
      SattlemeClient sattleme = client();
      CompletableFuture<Invoice> a = sattleme.createInvoiceAsync(sats("H-4", 1));
      CompletableFuture<Invoice> b = sattleme.createInvoiceAsync(sats("H-4", 1));
      List<String> outcomes = new ArrayList<>();
      for (CompletableFuture<Invoice> call : List.of(a, b)) {
        try {
          call.get();
          outcomes.add("fulfilled");
        } catch (ExecutionException e) {
          assertInstanceOf(InvoicePendingException.class, e.getCause());
          outcomes.add("rejected");
        }
      }
      outcomes.sort(null);
      assertEquals(List.of("fulfilled", "rejected"), outcomes);
      assertEquals(1, merchant.invoices.size());
    }

    @Test
    void findsTheInvoicesDeclaredForAnOrderAndNeedsAMajorityTo() throws Exception {
      SattlemeClient sattleme = client(builder -> builder.rateSource(fixed("69012")));
      Invoice first =
          sattleme.createInvoice(
              Order.builder("H-5").fiat("24.50", "CHF").durationSeconds(60).build());
      merchant.now = first.expiresAt() + 120;
      Invoice second = sattleme.createInvoice(sats("H-5", 7));
      List<DeclaredInvoice> found = sattleme.findInvoices("H-5");
      assertEquals(
          List.of(first.bolt11(), second.bolt11()),
          found.stream().map(DeclaredInvoice::bolt11).toList());
      assertEquals(
          new DeclaredInvoice(
              "H-5",
              first.bolt11(),
              first.paymentHash(),
              first.verifyUrl(),
              null,
              "lightning:" + first.bolt11(),
              "35502",
              new Fiat("24.50", "CHF", "2450", 2),
              "H-5",
              first.createdAt(),
              first.expiresAt(),
              first.createdAt()),
          found.get(0));
      assertEquals("7", found.get(1).sats());
      assertNull(found.get(1).fiat());
      assertEquals(List.of(), sattleme.findInvoices("H-6"));
      merchant.net.remove("wss://relay-a.test");
      merchant.net.remove("wss://relay-b.test");
      assertThrows(NetworkException.class, () -> sattleme.findInvoices("H-5"));
    }
  }

  @Nested
  class Relays {
    @Test
    void makesAnInvoiceOverOneConnectionPerRelay() throws Exception {
      SattlemeClient sattleme = client();
      Invoice first =
          sattleme.createInvoice(Order.builder("R-1").sats(1).durationSeconds(60).build());
      assertEquals(
          Map.of("wss://relay-a.test", 1, "wss://relay-b.test", 1, "wss://relay-c.test", 1),
          Map.copyOf(merchant.net.connections));
      merchant.now = first.expiresAt() + 120;
      // The channel, the order's declarations page after page, the new declaration: one connection.
      sattleme.createInvoice(Order.builder("R-1").sats(1).durationSeconds(60).build());
      assertEquals(
          Map.of("wss://relay-a.test", 2, "wss://relay-b.test", 2, "wss://relay-c.test", 2),
          Map.copyOf(merchant.net.connections));
      for (int i = 0; i < 100 && openConnections() > 0; i += 1) Thread.sleep(10);
      for (String url : merchant.keyRelays) {
        LocalRelay relay = merchant.net.get(url);
        // Every subscription closed once read, and every connection once the call is done.
        assertFalse(relay.closedSubscriptions.isEmpty(), url);
        assertEquals(0, relay.openConnections(), url);
      }
    }

    @ParameterizedTest(name = "is not slowed by a relay that never answers ({0})")
    @ValueSource(strings = {"mute", "blackhole"})
    void isNotSlowedByARelayThatNeverAnswers(String kind) throws Exception {
      if (kind.equals("mute")) {
        LocalRelay.Behaviour mute = new LocalRelay.Behaviour();
        mute.mute = true;
        merchant.net.get("wss://relay-c.test").behaviour = mute;
      } else {
        merchant.net.remove("wss://relay-c.test");
        merchant.net.blackholes.add("wss://relay-c.test");
      }
      SattlemeClient sattleme = client(builder -> builder.relayTimeoutMs(5000));
      long started = System.nanoTime();
      sattleme.createInvoice(sats("R-2", 1));
      assertTrue(elapsedMs(started) < 1500, "took " + elapsedMs(started));
      assertEquals(1, sattleme.findInvoices("R-2").size());
      assertTrue(elapsedMs(started) < 3000, "took " + elapsedMs(started));
    }

    @Test
    void stopsWhenItsThreadIsInterruptedAndClosesItsConnections() throws Exception {
      for (String url : merchant.keyRelays) merchant.net.get(url).behaviour = delayed(2000);
      SattlemeClient sattleme = client(builder -> builder.relayTimeoutMs(5000));
      Object[] outcome = new Object[1];
      Thread call =
          new Thread(() -> {
            try {
              outcome[0] = sattleme.createInvoice(sats("R-3", 1));
            } catch (Throwable e) {
              outcome[0] = e;
            }
          });
      long started = System.nanoTime();
      call.start();
      Thread.sleep(100);
      call.interrupt();
      call.join(5000);
      assertInstanceOf(InterruptedException.class, outcome[0]);
      assertTrue(elapsedMs(started) < 1000, "took " + elapsedMs(started));
      // The relays see each connection close a moment later.
      for (int i = 0; i < 100 && openConnections() > 0; i += 1) Thread.sleep(10);
      assertEquals(0, openConnections());
      assertEquals(0, merchant.invoices.size());
    }

    @Test
    void stopsWhenItsFutureIsCancelled() throws Exception {
      for (String url : merchant.keyRelays) merchant.net.get(url).behaviour = delayed(2000);
      SattlemeClient sattleme = client(builder -> builder.relayTimeoutMs(5000));
      CompletableFuture<Invoice> call = sattleme.createInvoiceAsync(sats("R-4", 1));
      Thread.sleep(100);
      assertTrue(call.cancel(true));
      assertTrue(call.isCancelled());
      for (int i = 0; i < 100 && openConnections() > 0; i += 1) Thread.sleep(10);
      assertEquals(0, openConnections());
      assertEquals(0, merchant.invoices.size());
    }
  }

  private int openConnections() {
    int open = 0;
    for (String url : merchant.keyRelays) {
      LocalRelay relay = merchant.net.relays.get(url);
      if (relay != null) open += relay.openConnections();
    }
    return open;
  }

  private static long elapsedMs(long started) {
    return (System.nanoTime() - started) / 1_000_000;
  }

  @Nested
  class RatesByName {
    @Test
    void takesASourceByItsNameCoinGeckoWithItsDemoKey() throws Exception {
      merchant.override =
          uri ->
              uri.getHost().equals("api.coingecko.com")
                  ? FakeHttp.Answer.json(
                      Js.obj("bitcoin", Js.obj("chf", 69012, "last_updated_at", merchant.now - 30)))
                  : uri.getHost().equals("api.kraken.com")
                      ? FakeHttp.Answer.json(
                          Js.obj(
                              "error", List.of(),
                              "result", Js.obj("XBTCHF", Js.obj("c", List.of("70000.0", "0.1")))))
                      : null;
      SattlemeClient sattleme =
          client(builder -> builder.rateSource(RateSource.COINGECKO).coingeckoApiKey("demo-key"));
      Invoice invoice = sattleme.createInvoice(Order.builder("S-1").fiat("24.50", "CHF").build());
      assertEquals("35502", invoice.sats());
      assertEquals("coingecko", invoice.rate().source());
      assertEquals("69012", invoice.rate().price());
      FakeHttp.Call call = callTo("api.coingecko.com");
      assertEquals(List.of("demo-key"), call.headers().allValues("x-cg-demo-api-key"));
      Invoice other =
          sattleme.createInvoice(
              Order.builder("S-2").fiat("7", "CHF").rateSource(RateSource.KRAKEN).build());
      assertEquals("kraken", other.rate().source());
      assertEquals("70000", other.rate().price());
    }

    @Test
    void refusesAnUnknownSourceWhenTheClientIsMade() throws Exception {
      List<List<RateSource>> invalid = new ArrayList<>();
      invalid.add(java.util.Collections.singletonList(RateSource.byName("bitstamp").orElse(null)));
      invalid.add(List.of());
      invalid.add(List.of(RateSource.KRAKEN, RateSource.KRAKEN));
      invalid.add(
          java.util.Arrays.asList(RateSource.KRAKEN, RateSource.byName("bitstamp").orElse(null)));
      invalid.add(null);
      for (List<RateSource> sources : invalid) {
        InvalidOptionException error =
            assertThrows(
                InvalidOptionException.class,
                () -> client(builder -> builder.rateSources(sources)));
        assertEquals("rateSource", error.getParam());
      }
      InvalidOptionException key =
          assertThrows(
              InvalidOptionException.class, () -> client(builder -> builder.coingeckoApiKey("")));
      assertEquals("coingeckoApiKey", key.getParam());
    }

    @Test
    void withoutRateSourceAsksTheDefaultListInOrderUntilOneGivesAPrice() throws Exception {
      merchant.override =
          uri ->
              uri.getHost().equals("api.coingecko.com")
                  ? FakeHttp.Answer.json(429, Js.obj("status", Js.obj("error_code", 429)))
                  : uri.getHost().equals("mempool.space")
                      ? FakeHttp.Answer.json(Js.obj("time", merchant.now - 10, "CHF", 69012))
                      : null;
      Invoice invoice = client().createInvoice(Order.builder("D-1").fiat("24.50", "CHF").build());
      assertEquals("35502", invoice.sats());
      assertEquals("mempool", invoice.rate().source());
      assertEquals("69012", invoice.rate().price());
      List<String> asked = new ArrayList<>();
      synchronized (merchant.http.calls) {
        for (FakeHttp.Call call : merchant.http.calls) {
          if (!call.uri().getHost().endsWith("breez.tips")) asked.add(call.uri().getHost());
        }
      }
      assertEquals(List.of("api.coingecko.com", "mempool.space"), asked);
      // No key given: CoinGecko is asked without one.
      assertEquals(List.of(), callTo("api.coingecko.com").headers().allValues("x-cg-demo-api-key"));
      assertEquals(
          List.of(
              "Rate source coingecko failed (network): coingecko answered HTTP 429; trying mempool"),
          merchant.warnings);
    }

    @Test
    void asksAnOrdersListInItsOrderAndOnlyThatList() throws Exception {
      merchant.override =
          uri ->
              uri.getHost().equals("api.kraken.com")
                  ? FakeHttp.Answer.json(Js.obj("error", List.of("EQuery:Unknown asset pair")))
                  : uri.getHost().equals("api.coinbase.com")
                      ? FakeHttp.Answer.json(
                          Js.obj(
                              "data",
                              Js.obj("currency", "BTC", "rates", Js.obj("CHF", "69012.00"))))
                      : null;
      SattlemeClient sattleme = client(builder -> builder.rateSource(RateSource.COINGECKO));
      Invoice invoice =
          sattleme.createInvoice(
              Order.builder("D-2")
                  .fiat("24.50", "CHF")
                  .rateSources(List.of(RateSource.KRAKEN, RateSource.COINBASE))
                  .build());
      assertEquals("coinbase", invoice.rate().source());
      assertEquals("69012", invoice.rate().price());
      List<String> hosts = new ArrayList<>();
      synchronized (merchant.http.calls) {
        for (FakeHttp.Call call : merchant.http.calls) hosts.add(call.uri().getHost());
      }
      assertTrue(hosts.contains("api.kraken.com"));
      assertFalse(hosts.contains("api.coingecko.com"));
    }

    @Test
    void givesTheLastNetworkOrRateErrorWhenNoSourceOfTheListHasAPrice() throws Exception {
      merchant.override =
          uri ->
              uri.getHost().equals("api.kraken.com")
                  ? FakeHttp.Answer.json(Js.obj("error", List.of("EQuery:Unknown asset pair")))
                  : uri.getHost().equals("api.coinbase.com")
                      ? FakeHttp.Answer.json(503, Js.obj())
                      : null;
      SattlemeClient sattleme = client();
      assertThrows(
          NetworkException.class,
          () ->
              sattleme.createInvoice(
                  Order.builder("D-3")
                      .fiat("1", "CHF")
                      .rateSources(List.of(RateSource.COINBASE, RateSource.KRAKEN))
                      .build()));
      // Only sources without the currency: unsupported_currency, naming them.
      UnsupportedCurrencyException error =
          assertThrows(
              UnsupportedCurrencyException.class,
              () ->
                  sattleme.createInvoice(
                      Order.builder("D-4")
                          .fiat("1", "THB")
                          .rateSources(List.of(RateSource.KRAKEN, RateSource.MEMPOOL))
                          .build()));
      assertEquals("None of kraken, mempool has a price in THB", error.getMessage());
      assertEquals(0, merchant.invoices.size());
    }

    @Test
    void putsAnOrdersPendingInvoiceBeforeAFailingRateSource() throws Exception {
      SattlemeClient sattleme = client(builder -> builder.rateSource(fixed("69012")));
      sattleme.createInvoice(Order.builder("S-3").fiat("1", "CHF").build());
      SattlemeClient failing =
          client(builder ->
              builder.rateSource(
                  RateSource.custom(currency -> {
                    throw new NetworkException("down");
                  })));
      assertThrows(
          InvoicePendingException.class,
          () -> failing.createInvoice(Order.builder("S-3").fiat("1", "CHF").build()));
      assertThrows(
          NetworkException.class,
          () -> failing.createInvoice(Order.builder("S-4").fiat("1", "CHF").build()));
      assertEquals(1, merchant.invoices.size());
    }
  }

  private FakeHttp.Call callTo(String host) {
    synchronized (merchant.http.calls) {
      for (FakeHttp.Call call : merchant.http.calls) {
        if (call.uri().getHost().equals(host)) return call;
      }
    }
    throw new AssertionError("No call to " + host);
  }
}
