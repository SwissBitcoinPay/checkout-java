package me.sattle.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import me.sattle.checkout.internal.Derive;
import me.sattle.checkout.internal.Http;
import me.sattle.checkout.internal.RandomBytes;
import me.sattle.checkout.internal.RateSources;
import me.sattle.checkout.internal.Text;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.internal.nostr.Events;
import me.sattle.checkout.internal.nostr.NostrEvent;
import me.sattle.checkout.internal.nostr.RelayPool;
import me.sattle.checkout.internal.onchain.Witnesses;
import me.sattle.checkout.support.Chain;
import me.sattle.checkout.support.FakeHttp;
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
 * What the package refuses or bounds beyond the conformance vectors: options, texts other languages
 * cannot even hold, a custom rate source that fails, a channel whose reading key is off the curve,
 * the fan-out of payment checks, what a read keeps of what a relay sends, the witnesses of an
 * invoice. As checkout-node's hardening tests; its HTTP and WebSocket ones are in HttpTest and
 * WebSocketTest, against the platform's own clients.
 */
class HardeningTest {
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

  private SattlemeClient client() {
    return merchant.clientBuilder().relayTimeoutMs(1000).build();
  }

  @Nested
  class Options {
    /*
     * checkout-node also refuses options that are not an object, a coingeckoApiKey that is a
     * number, an httpTimeoutMs of 1.5, an onStatus that is not a function, an intervalMs of
     * 1000.5: the builder's types cannot hold them.
     */
    static Stream<Arguments> invalid() {
      HttpClient following =
          HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
      List<RateSource> named = Arrays.asList(RateSource.KRAKEN, RateSource.KRAKEN);
      List<RateSource> withNull = Arrays.asList(RateSource.KRAKEN, null);
      return Stream.<Arguments>of(
          Arguments.of(
              "rateSource", "null", (Consumer<SattlemeClient.Builder>) b -> b.rateSource(null)),
          Arguments.of(
              "rateSource",
              "an empty list",
              (Consumer<SattlemeClient.Builder>) b -> b.rateSources(List.of())),
          Arguments.of(
              "rateSource", "no list", (Consumer<SattlemeClient.Builder>) b -> b.rateSources(null)),
          Arguments.of(
              "rateSource",
              "a name twice",
              (Consumer<SattlemeClient.Builder>) b -> b.rateSources(named)),
          Arguments.of(
              "rateSource",
              "a null in the list",
              (Consumer<SattlemeClient.Builder>) b -> b.rateSources(withNull)),
          // 1 or more visible ASCII characters (SPEC.md §4): empty is invalid, not absent.
          Arguments.of(
              "coingeckoApiKey",
              "empty",
              (Consumer<SattlemeClient.Builder>) b -> b.coingeckoApiKey("")),
          Arguments.of(
              "coingeckoApiKey",
              "null",
              (Consumer<SattlemeClient.Builder>) b -> b.coingeckoApiKey(null)),
          Arguments.of(
              "coingeckoApiKey",
              "a space",
              (Consumer<SattlemeClient.Builder>) b -> b.coingeckoApiKey("CG-key with a space")),
          Arguments.of(
              "coingeckoApiKey",
              "not ASCII",
              (Consumer<SattlemeClient.Builder>) b -> b.coingeckoApiKey("CG-clé")),
          Arguments.of(
              "coingeckoApiKey",
              "a line feed",
              (Consumer<SattlemeClient.Builder>) b -> b.coingeckoApiKey("CG-key\n")),
          Arguments.of(
              "httpClient", "null", (Consumer<SattlemeClient.Builder>) b -> b.httpClient(null)),
          Arguments.of(
              "httpClient",
              "one following redirects",
              (Consumer<SattlemeClient.Builder>) b -> b.httpClient(following)),
          Arguments.of(
              "webSocketFactory",
              "null",
              (Consumer<SattlemeClient.Builder>) b -> b.webSocketFactory(null)),
          Arguments.of(
              "relayTimeoutMs",
              "3e9",
              (Consumer<SattlemeClient.Builder>) b -> b.relayTimeoutMs(3_000_000_000L)),
          Arguments.of(
              "relayTimeoutMs", "0", (Consumer<SattlemeClient.Builder>) b -> b.relayTimeoutMs(0)),
          Arguments.of(
              "relayTimeoutMs", "-1", (Consumer<SattlemeClient.Builder>) b -> b.relayTimeoutMs(-1)),
          Arguments.of(
              "httpTimeoutMs",
              "60001",
              (Consumer<SattlemeClient.Builder>) b -> b.httpTimeoutMs(60_001)),
          Arguments.of(
              "httpTimeoutMs", "99", (Consumer<SattlemeClient.Builder>) b -> b.httpTimeoutMs(99)),
          Arguments.of("logger", "null", (Consumer<SattlemeClient.Builder>) b -> b.logger(null)),
          Arguments.of("clock", "null", (Consumer<SattlemeClient.Builder>) b -> b.clock(null)),
          Arguments.of("random", "null", (Consumer<SattlemeClient.Builder>) b -> b.random(null)));
    }

    @ParameterizedTest(name = "refuses {0} ({1}) as an InvalidOptionException")
    @MethodSource("invalid")
    void refusesAnInvalidOption(String param, String what, Consumer<SattlemeClient.Builder> set) {
      SattlemeClient.Builder builder = SattlemeClient.builder().key(merchant.key());
      set.accept(builder);
      InvalidOptionException error = assertThrows(InvalidOptionException.class, builder::build);
      assertEquals(param, error.getParam());
      assertEquals("invalid_option", error.getCode());
      // Before any request.
      assertEquals(List.of(), merchant.http.calls);
    }

    @Test
    void takesTheBoundsOfEachTimeout() {
      for (long ms : new long[] {100, 60_000}) {
        SattlemeClient.builder().key(merchant.key()).relayTimeoutMs(ms).httpTimeoutMs(ms).build();
      }
    }

    @Test
    void refusesNoWaitOptionsBeforeAnyRequest() throws Exception {
      Invoice invoice = client().createInvoice(Order.builder("O-2").sats(1).build());
      int calls = merchant.http.calls.size();
      InvalidOptionException error =
          assertThrows(InvalidOptionException.class, () -> client().waitForPayment(invoice, null));
      assertEquals("options", error.getParam());
      assertEquals(calls, merchant.http.calls.size());
    }

    @Test
    void boundsWaitForPaymentsInterval() throws Exception {
      Invoice invoice = client().createInvoice(Order.builder("O-1").sats(1).build());
      for (long intervalMs : new long[] {100, 3_000_000_000L, 249, 0, -1}) {
        InvalidOptionException error =
            assertThrows(
                InvalidOptionException.class,
                () ->
                    client()
                        .waitForPayment(
                            invoice, WaitOptions.builder().intervalMs(intervalMs).build()));
        assertEquals("intervalMs", error.getParam());
        // And as a future, failed at once.
        java.util.concurrent.ExecutionException failed =
            assertThrows(
                java.util.concurrent.ExecutionException.class,
                () ->
                    client()
                        .waitForPaymentAsync(
                            invoice, WaitOptions.builder().intervalMs(intervalMs).build())
                        .get());
        assertInstanceOf(InvalidOptionException.class, failed.getCause());
      }
    }
  }

  /**
   * Every public method fails with the package's own errors (SPEC.md §5), with values a caller may
   * pass, but the one exception of Java's the README names: a status's constructor given a null
   * list (List.copyOf). The audit's list, and more.
   */
  @Nested
  class PublicMethods {
    @FunctionalInterface
    interface Call {
      Object run() throws Exception;
    }

    static Stream<Arguments> misuses() {
      String key =
          "sattleme_ck_qEVejHuCZpF4XMmBSw5p7iZYxCtm20XxzT1fnJ9VB8o.uPTAz1d8bbQaw2Jx22Wwrga2vI2F8allY5NrPC3Aogk.cmVsYXkuZXhhbXBsZQ";
      SattlemeClient client = SattlemeClient.builder().key(key).build();
      String storedFiat =
          "{\"ref\":\"R\",\"bolt11\":null,\"paymentHash\":null,\"verifyUrl\":null,"
              + "\"onchainAddress\":null,\"paymentUri\":\"x\",\"sats\":\"1\",\"fiat\":{\"amount\":\"1\","
              + "\"currency\":\"CHF\",\"minor\":\"x\",\"digits\":2},\"rate\":null,\"note\":\"R\","
              + "\"createdAt\":1,\"expiresAt\":2,\"checkoutUrl\":null,\"redirectUrl\":null}";
      return Stream.of(
          Arguments.of(
              "key(null)", "invalid_key", (Call) () -> SattlemeClient.builder().key(null).build()),
          Arguments.of(
              "RateSource.custom(null)", "invalid_option", (Call) () -> RateSource.custom(null)),
          Arguments.of(
              "WebSocketFactory.jdk(null)",
              "invalid_option",
              (Call) () -> WebSocketFactory.jdk(null)),
          Arguments.of(
              "createInvoice(null)", "invalid_order", (Call) () -> client.createInvoice(null)),
          Arguments.of(
              "createInvoice(no ref)",
              "invalid_order",
              (Call) () -> client.createInvoice(Order.builder(null).sats(1).build())),
          Arguments.of(
              "createInvoice(fiat(null, null))",
              "invalid_order",
              (Call) () -> client.createInvoice(Order.builder("R").fiat(null, null).build())),
          Arguments.of("getStatus(null)", "invalid_order", (Call) () -> client.getStatus(null)),
          Arguments.of(
              "getStatus(all null)",
              "invalid_order",
              (Call)
                  () -> client.getStatus(InvoiceToCheck.of(null, null, null, null, null, null, 0))),
          Arguments.of(
              "waitForPayment(null, null)",
              "invalid_option",
              (Call) () -> client.waitForPayment(null, null)),
          Arguments.of(
              "findInvoices(null)", "invalid_order", (Call) () -> client.findInvoices(null)),
          Arguments.of(
              "InvoiceToCheck.fromJson(null)",
              "invalid_order",
              (Call) () -> InvoiceToCheck.fromJson(null)),
          Arguments.of(
              "Invoice.fromJson([])", "invalid_order", (Call) () -> Invoice.fromJson("[]")),
          Arguments.of(
              "DeclaredInvoice.fromJson({})",
              "invalid_order",
              (Call) () -> DeclaredInvoice.fromJson("{}")),
          Arguments.of("Fiat.of(null, CHF)", "invalid_order", (Call) () -> Fiat.of(null, "CHF")),
          Arguments.of("toSats(null)", "rate", (Call) () -> Fiat.of("24.50", "CHF").toSats(null)),
          Arguments.of(
              "toSats of minor x",
              "invalid_order",
              (Call) () -> new Fiat("1", "CHF", "x", 2).toSats("69012")),
          Arguments.of(
              "toSats of minor null",
              "invalid_order",
              (Call) () -> new Fiat("1", "CHF", null, 2).toSats("69012")),
          Arguments.of(
              "toSats of minor 0",
              "invalid_order",
              (Call) () -> new Fiat("0", "CHF", "0", 2).toSats("69012")),
          Arguments.of(
              "toSats of 31 digits",
              "invalid_order",
              (Call) () -> new Fiat("1", "CHF", "1" + "0".repeat(30), 2).toSats("69012")),
          Arguments.of(
              "toSats of digits -1",
              "invalid_order",
              (Call) () -> new Fiat("1", "CHF", "1", -1).toSats("69012")),
          // Ten to that power would take minutes: refused at once.
          Arguments.of(
              "toSats of digits 99 999 999",
              "invalid_order",
              (Call) () -> new Fiat("1", "CHF", "1", 99_999_999).toSats("69012")),
          Arguments.of(
              "toSats of a stored invoice's fiat",
              "invalid_order",
              (Call) () -> Invoice.fromJson(storedFiat).fiat().toSats("69012")));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("misuses")
    void failsWithAnErrorOfThePackage(String what, String code, Call call) {
      SattlemeException error = assertThrows(SattlemeException.class, call::run, what);
      assertEquals(code, error.getCode(), what);
    }

    @Test
    void takesWhatADataTypeIsGivenButANullListOfAStatus() {
      assertThrows(NullPointerException.class, () -> new PaymentStatus.Unconfirmed("1", 1, null));
      assertThrows(
          NullPointerException.class,
          () -> new PaymentStatus.PaidOnchain("1", Arrays.asList((String) null)));
      assertThrows(
          NullPointerException.class, () -> new PaymentStatus.PaidMixed("1", List.of(), null));
      // The rest is written as given.
      assertEquals(
          "{\"amount\":null,\"currency\":null,\"minor\":null,\"digits\":0}",
          new Fiat(null, null, null, 0).toJson());
      assertEquals(
          "{\"state\":\"paid\",\"method\":\"lightning\",\"preimage\":null}",
          new PaymentStatus.PaidLightning(null).toJson());
      assertEquals(
          new java.math.BigInteger("35502"), new Fiat("24.50", "CHF", "2450", 2).toSats("69012"));
    }
  }

  @Nested
  class NotesOtherLanguagesCannotHold {
    @Test
    void refusesALoneSurrogate() {
      for (String note : List.of("Order \ud800", "Order \udc00", "\udc00\ud800")) {
        InvalidOrderException error =
            assertThrows(
                InvalidOrderException.class,
                () -> client().createInvoice(Order.builder("N-1").sats(1).note(note).build()));
        assertEquals("note", error.getParam());
      }
      assertEquals(List.of(), merchant.http.calls);
    }

    @Test
    void countsANoteInCodePoints100EmojisFit() throws Exception {
      String note = "😀".repeat(100);
      Invoice invoice = client().createInvoice(Order.builder("N-2").sats(1).note(note).build());
      assertEquals(note, invoice.note());
      // 400 bytes in UTF-8: more than breez.tips's 255, so not sent as the comment.
      assertNull(merchant.invoices.values().iterator().next().comment());
      // 101 are too many.
      assertThrows(
          InvalidOrderException.class,
          () ->
              client().createInvoice(Order.builder("N-3").sats(1).note("😀".repeat(101)).build()));
    }
  }

  @Nested
  class ACustomRateSource {
    private final RateSources.Context context =
        new RateSources.Context(
            new Http(new FakeHttp(uri -> FakeHttp.Answer.json(404, Js.obj())), 1000), null);

    @Test
    void turnsItsOwnErrorsIntoARateExceptionWithTheCause() {
      IOException cause = new IOException("fetch failed");
      RateException error =
          assertThrows(
              RateException.class,
              () ->
                  RateSources.fetchRate(
                      RateSource.custom(currency -> {
                        throw cause;
                      }),
                      "CHF",
                      context,
                      1_791_378_000L));
      assertSame(cause, error.getCause());
      RuntimeException unchecked = new IllegalStateException("broken");
      RateException wrapped =
          assertThrows(
              RateException.class,
              () ->
                  RateSources.fetchRate(
                      RateSource.custom(currency -> {
                        throw unchecked;
                      }),
                      "CHF",
                      context,
                      1_791_378_000L));
      assertSame(unchecked, wrapped.getCause());
    }

    @Test
    void keepsThePackagesErrorsItThrows() {
      for (SattlemeException thrown :
          List.of(
              new UnsupportedCurrencyException("XOF", "mine"),
              new NetworkException("down"),
              new RateException("mine"))) {
        SattlemeException error =
            assertThrows(
                SattlemeException.class,
                () ->
                    RateSources.fetchRate(
                        RateSource.custom(currency -> {
                          throw thrown;
                        }),
                        "XOF",
                        context,
                        1_791_378_000L));
        assertSame(thrown, error);
      }
    }
  }

  @Test
  void quotesAndBoundsWhatAServerWritesInAMessage() throws Exception {
    // A Lightning address's refusal past the grace period, its reason forging a log line of its
    // own: one line, the reason quoted as a JSON string, 200 of its UTF-16 units at most.
    Invoice invoice = client().createInvoice(Order.builder("H-1").sats(500).build());
    merchant.now = invoice.expiresAt() + 121;
    String reason = "Internal error\nWARNING order H-2 paid " + "x".repeat(1000);
    merchant.override =
        uri ->
            uri.toString().equals(invoice.verifyUrl())
                ? FakeHttp.Answer.json(200, Js.obj("status", "ERROR", "reason", reason))
                : null;
    NetworkException error =
        assertThrows(NetworkException.class, () -> client().getStatus(invoice));
    assertFalse(error.getMessage().contains("\n"), error.getMessage());
    assertTrue(
        error.getMessage().contains("\"Internal error\\nWARNING order H-2 paid xxx"),
        error.getMessage());
    assertTrue(error.getMessage().length() < 300, error.getMessage());
    // Control characters and lone surrogates escaped; a pair never cut in two.
    assertEquals("\"a\\nb\\ud800\"", Text.excerpt("a\nb\ud800"));
    assertEquals(
        "\"" + "x".repeat(199) + "\u2026\"", Text.excerpt("x".repeat(199) + "\ud83d\ude00y"));
    assertEquals("\"" + "x".repeat(200) + "\"", Text.excerpt("x".repeat(200)));
  }

  @Test
  void ignoresAChannelWhoseReadingKeyIsOffTheCurve() throws Exception {
    merchant.publishChannel(
        merchant.channelValue(200, Map.of("registryReader", "00".repeat(32))),
        200,
        merchant.keyRelays,
        merchant.teamSecret);
    // The newest valid copy stands: the one of 100.
    assertEquals("Sunrise Bakery", client().merchant().businessName());
    merchant.stop();
    merchant = new TestMerchant().start();
    merchant.publishChannel(
        merchant.channelValue(100, Map.of("registryReader", "00".repeat(32))), 100);
    assertThrows(ChannelNotFoundException.class, () -> client().merchant());
  }

  @Test
  void checksTheEarlierInvoicesOfAnOrderFourAtATime() throws Exception {
    SattlemeClient sattleme = client();
    for (int i = 0; i < 6; i += 1) {
      sattleme.createInvoice(Order.builder("E-1").sats(1).durationSeconds(60).build());
      merchant.now += 60 + 120;
    }
    AtomicInteger running = new AtomicInteger();
    AtomicInteger most = new AtomicInteger();
    FakeHttp counting =
        new FakeHttp(uri -> {
          boolean verify = uri.toString().contains("/verify/");
          if (verify) {
            most.accumulateAndGet(running.incrementAndGet(), Math::max);
            // Long enough for the four of a round to overlap on a loaded machine.
            Thread.sleep(300);
          }
          try {
            return merchant.answer(uri);
          } finally {
            if (verify) running.decrementAndGet();
          }
        });
    try {
      merchant
          .clientBuilder()
          .relayTimeoutMs(1000)
          .httpClient(counting)
          .build()
          .createInvoice(Order.builder("E-1").sats(1).build());
    } finally {
      counting.shutdown();
    }
    assertEquals(4, most.get());
    assertEquals(7, merchant.invoices.size());
  }

  @Nested
  class Relays {
    @Test
    void keepsOfWhatARelaySendsOnlyWhatTheReadKeepsAndStillPagesThroughTheRest() throws Exception {
      String url = merchant.keyRelays.get(0);
      LocalRelay relay = merchant.net.get(url);
      String filler = "x".repeat(60_000);
      for (int i = 0; i < 300; i += 1) {
        String hex = Integer.toHexString(i);
        relay.store(
            Js.obj(
                "id",
                "0".repeat(64 - hex.length()) + hex,
                "pubkey",
                "ab".repeat(32),
                "created_at",
                1_000_000 - i,
                "kind",
                30078,
                "tags",
                List.of(List.of("d", "cd".repeat(32))),
                "content",
                filler,
                "sig",
                "00".repeat(64)));
      }
      String wanted = "0".repeat(62) + "ff";
      me.sattle.checkout.internal.nostr.Keep keep =
          value -> wanted.equals(Js.text(value, "id")) ? value : null;
      for (boolean paged : new boolean[] {false, true}) {
        // 18 MB to read: time enough on a loaded machine.
        RelayPool pool = pool(10_000);
        try {
          Map<String, List<Object>> answers =
              pool.queryRelays(
                      List.of(url),
                      Js.obj("kinds", List.of(30078)),
                      new byte[32],
                      1,
                      paged,
                      keep,
                      false)
                  .get()
                  .events();
          List<Object> kept = answers.get(url);
          assertEquals(1, kept.size());
          assertEquals(wanted, Js.text((JsonValue) kept.get(0), "id"));
          assertEquals(filler, Js.text((JsonValue) kept.get(0), "content"));
        } finally {
          pool.close();
        }
      }
      // A complete read: 300 events in pages of 100, one kept; the read complete.
      RelayPool pool = pool(10_000);
      try {
        RelayPool.CompleteAnswers complete =
            pool.queryComplete(
                    List.of(url), Js.obj("kinds", List.of(30078)), new byte[32], 1, keep, false)
                .get();
        assertEquals(1, complete.complete());
        assertEquals(1, complete.answers().get(url).size());
      } finally {
        pool.close();
      }
      assertEquals(
          4,
          relay.queries.stream().filter(filter -> Js.of(100).equals(filter.get("limit"))).count());
    }

    @Test
    void readsARelaysCopiesOfOneEventOnceAndKeepsNothingARelayAddsToIt() throws Exception {
      String url = "wss://copies.test";
      LocalRelay relay = merchant.net.add(url);
      NostrEvent event =
          merchant.channelEvent(merchant.channelValue(100), 100, merchant.teamSecret);
      // Copies that check out: the id and the signature cover none of what a relay adds.
      String padding = "x".repeat(100_000);
      for (int i = 0; i < 50; i += 1) relay.store(event.toJson().put("padding", padding));
      byte[] encryptionKey = Derive.channelEncryptionKey(merchant.integrationKey);
      me.sattle.checkout.internal.nostr.Keep open =
          me.sattle.checkout.internal.nostr.Records.recordsThatOpen(
              (d, author) -> author.equals(merchant.team) ? encryptionKey : null);
      AtomicInteger opened = new AtomicInteger();
      me.sattle.checkout.internal.nostr.Keep keep =
          value -> {
            opened.incrementAndGet();
            return open.keep(value);
          };
      JsonObject filter = Js.obj("kinds", List.of(30078));
      List<java.util.function.Function<RelayPool, Map<String, List<Object>>>> reads =
          List.of(
              pool ->
                  pool.queryRelays(List.of(url), filter, new byte[32], 1, false, keep, false)
                      .join()
                      .events(),
              pool ->
                  pool.queryRelays(List.of(url), filter, new byte[32], 1, true, keep, false)
                      .join()
                      .events(),
              pool ->
                  pool.queryComplete(List.of(url), filter, new byte[32], 1, keep, false)
                      .join()
                      .answers());
      for (java.util.function.Function<RelayPool, Map<String, List<Object>>> read : reads) {
        opened.set(0);
        int asked = relay.queries.size();
        RelayPool pool = pool(10_000);
        Map<String, List<Object>> answers;
        try {
          answers = read.apply(pool);
        } finally {
          pool.close();
        }
        // One copy, its NIP-01 members alone.
        assertEquals(List.of(event.toJson()), answers.get(url));
        assertEquals(
            List.of("id", "pubkey", "created_at", "kind", "tags", "content", "sig"),
            List.copyOf(((JsonObject) answers.get(url).get(0)).names()));
        // One opening per query that sent the copies (a paged read asks their second again).
        long sent =
            relay.queries.subList(asked, relay.queries.size()).stream()
                .filter(query ->
                    query.get("until") == null || Js.integer(query, "until") >= event.createdAt())
                .count();
        assertTrue(sent >= 1);
        assertEquals(sent, opened.get());
      }
    }

    private RelayPool pool() {
      return pool(2000);
    }

    private RelayPool pool(long timeoutMs) {
      return new RelayPool(
          merchant.net, timeoutMs, new RandomBytes(new SecureRandom()), () -> merchant.now);
    }

    @Test
    void neverTrustsAnEventARelaySendsAgainstItsOwnIdOrSignature() throws Exception {
      // A relay's copy with another id or a broken signature checks out nowhere (SPEC.md §11).
      NostrEvent event =
          Events.sign(
              30078,
              1_791_378_000L,
              List.of(),
              "x",
              me.sattle.checkout.support.FixedRandom.bytes(5),
              new RandomBytes(new SecureRandom()));
      assertTrue(Events.checked(event.toJson()) != null);
      assertNull(Events.checked(Js.with(event.toJson(), "content", "y")));
      assertNull(Events.checked(Js.with(event.toJson(), "sig", "00".repeat(64))));
      assertNull(Events.checked(Js.with(event.toJson(), "id", "00".repeat(32))));
    }
  }

  @Nested
  class TheWitnessesOfAnInvoice {
    private final Derive.SeenKeys seen = Derive.SeenKeys.of(filled(2));
    private final String address = Chain.depositAddress("this");
    private final String elsewhere = Chain.depositAddress("elsewhere");
    private final String sale = "ab".repeat(32);
    private final long expiresAt = 1_791_379_180L;

    private static byte[] filled(int value) {
      byte[] bytes = new byte[32];
      Arrays.fill(bytes, (byte) value);
      return bytes;
    }

    private JsonValue witness(String of, String tx, String ofSale) {
      return Witnesses.witnessEvent(
              seen,
              new Witnesses.Witness(
                  of,
                  Chain.txid(tx),
                  ofSale,
                  1000,
                  List.of(),
                  false,
                  ofSale == null ? null : expiresAt),
              1_791_378_400L,
              new RandomBytes(new SecureRandom()))
          .toJson();
    }

    /** A record of a newer format (its content {@code #}) at the witness address of {@code tx}. */
    private JsonValue newer(String of, String tx, List<List<String>> tags, boolean sign) {
      List<List<String>> all = new ArrayList<>();
      all.add(List.of("d", Witnesses.witnessAddress(seen.addressKey(), of, Chain.txid(tx), sale)));
      all.addAll(tags);
      NostrEvent event =
          Events.sign(
              30078,
              1_791_378_400L,
              all,
              "#",
              seen.secretKey(),
              new RandomBytes(new SecureRandom()));
      JsonObject json = event.toJson();
      return sign ? json : Js.with(json, "sig", "00".repeat(64));
    }

    private Witnesses.OrderWitnesses read(List<JsonValue> events) {
      return Witnesses.orderWitnessesOf(events, seen, address, sale, expiresAt);
    }

    @Test
    void readsThoseOfThisReadOfTheSaleOfNoneOfAnotherSaleAtTheAddress() {
      Witnesses.OrderWitnesses witnesses =
          read(
              List.of(
                  witness(address, "mine", sale),
                  witness(address, "none", null),
                  witness(address, "another's", "cd".repeat(32))));
      assertEquals(List.of(Chain.txid("mine")), new ArrayList<>(witnesses.sale().keySet()));
      assertEquals(List.of(Chain.txid("none")), new ArrayList<>(witnesses.none()));
      assertEquals(List.of(Chain.txid("another's")), new ArrayList<>(witnesses.others().keySet()));
    }

    @Test
    void ignoresAWitnessAtAnotherAddressATransactionPayingTwoOrdersPaysEachItsPart() {
      Witnesses.OrderWitnesses witnesses =
          read(List.of(witness(elsewhere, "batch", "cd".repeat(32))));
      assertEquals(
          List.of(0, 0, 0),
          List.of(witnesses.sale().size(), witnesses.others().size(), witnesses.none().size()));
    }

    @Test
    void keepsAWitnessOfANewerFormatOnlyWithTheReadsTagsAndTheAuthorsSignature() {
      List<List<String>> tag =
          List.of(List.of("o", Witnesses.addressTag(seen.addressKey(), address)));
      List<List<String>> otherTag =
          List.of(List.of("o", Witnesses.addressTag(seen.addressKey(), elsewhere)));
      assertEquals(1, read(List.of(newer(address, "t", tag, true))).newer().size());
      // Another address's, which a relay sends against the filter: of no invoice of this read.
      assertEquals(0, read(List.of(newer(address, "t", otherTag, true))).newer().size());
      // Without the seen secret's signature: nothing (SPEC.md §12), not an undecided payment.
      assertEquals(0, read(List.of(newer(address, "t", tag, false))).newer().size());
    }
  }
}
