package me.sattle.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import me.sattle.checkout.internal.Channels;
import me.sattle.checkout.internal.Checkout;
import me.sattle.checkout.internal.Derive;
import me.sattle.checkout.internal.Hex;
import me.sattle.checkout.internal.Http;
import me.sattle.checkout.internal.IntegrationKey;
import me.sattle.checkout.internal.Lnurl;
import me.sattle.checkout.internal.Model;
import me.sattle.checkout.internal.Orders;
import me.sattle.checkout.internal.Prices;
import me.sattle.checkout.internal.RateSources;
import me.sattle.checkout.internal.Tasks;
import me.sattle.checkout.internal.Urls;
import me.sattle.checkout.internal.json.Json;
import me.sattle.checkout.internal.json.JsonArray;
import me.sattle.checkout.internal.json.JsonNull;
import me.sattle.checkout.internal.json.JsonNumber;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonString;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.internal.lightning.Bolt11;
import me.sattle.checkout.internal.nostr.Events;
import me.sattle.checkout.internal.nostr.Records;
import me.sattle.checkout.internal.onchain.Payments;
import me.sattle.checkout.support.FakeHttp;
import me.sattle.checkout.support.Js;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The package against the conformance vectors every implementation shares (the pinned copy of the
 * specification, spec/vectors/), as checkout-node's conformance tests.
 */
class ConformanceTest {
  private static JsonValue vectors(String name) {
    return Js.spec("vectors/" + name);
  }

  /** A text as a test's name: JSON-quoted, so that an empty or blank one names it too. */
  private static String quoted(String text) {
    return Js.write(new JsonString(text));
  }

  private static String name(JsonValue item, String field) {
    String text = Js.text(item, field);
    return text == null ? Js.write(Js.get(item, field)) : text;
  }

  @Test
  void currenciesJsonIsThePackagesTable() {
    JsonObject currencies = Js.object(Js.spec("currencies.json"), "currencies");
    Map<String, Integer> table = new TreeMap<>();
    for (Map.Entry<String, JsonValue> entry : currencies.entries()) {
      table.put(entry.getKey(), (int) ((JsonNumber) entry.getValue()).value());
    }
    assertEquals(table, new TreeMap<>(Currencies.all()));
  }

  @TestFactory
  Stream<DynamicTest> integrationKeys() {
    return Js.list(vectors("integration-keys.json"), "cases").stream()
        .map(item ->
            dynamicTest(
                Js.text(item, "name"),
                () ->
                    assertEquals(
                        Js.get(item, "expected"),
                        Js.outcome(() -> {
                          IntegrationKey parsed = IntegrationKey.parse(Js.text(item, "key"));
                          return Js.obj(
                              "teamPublicKey", parsed.teamPublicKey(),
                              "integrationKey", Hex.encode(parsed.key()),
                              "relays", parsed.relays());
                        }))));
  }

  @Test
  void runsTheIntegrationKeyOfARelaysPartAfterAByteOrderMark() {
    // The case every implementation runs (SPEC.md §6, §8).
    List<String> names = new ArrayList<>();
    for (JsonValue item : Js.list(vectors("integration-keys.json"), "cases")) {
      names.add(Js.text(item, "name"));
    }
    assertTrue(
        names.contains("relays after a byte order mark (kept: U+FEFF is no host)"),
        names.toString());
  }

  @TestFactory
  Stream<DynamicTest> relayUrls() {
    return Js.list(vectors("relay-urls.json"), "cases").stream()
        .map(item ->
            dynamicTest(
                Js.text(item, "url") + ": " + Js.bool(item, "valid"),
                () -> assertEquals(Js.bool(item, "valid"), Urls.isRelayUrl(Js.text(item, "url")))));
  }

  @Test
  void relayAddressesBeyondTheVectors() {
    // A path holding a lone surrogate is refused (SPEC.md §6), a pair of them taken.
    assertFalse(Urls.isRelayUrl("wss://relay.example/\ud800"));
    assertFalse(Urls.isRelayUrl("wss://relay.example/a\udc00b"));
    assertTrue(Urls.isRelayUrl("wss://relay.example/😀"));
  }

  @TestFactory
  Stream<DynamicTest> httpsUrls() {
    JsonValue file = vectors("https-urls.json");
    Stream<DynamicTest> urls =
        Js.list(file, "urls").stream()
            .map(item ->
                dynamicTest(
                    quoted(Js.text(item, "url")),
                    () -> {
                      Urls.HttpsUrl parsed = Urls.parseHttpsUrl(Js.text(item, "url"));
                      JsonValue read =
                          parsed == null
                              ? JsonNull.NULL
                              : Js.obj("host", parsed.host(), "port", parsed.port());
                      assertEquals(Js.get(item, "expected"), read);
                    }));
    Stream<DynamicTest> pairs =
        Js.list(file, "sameHost").stream()
            .map(item ->
                dynamicTest(
                    quoted(Js.text(item, "a")) + " and " + quoted(Js.text(item, "b")),
                    () ->
                        assertEquals(
                            Js.bool(item, "expected"),
                            Urls.parseHttpsUrl(Js.text(item, "a"))
                                .sameHost(Urls.parseHttpsUrl(Js.text(item, "b"))))));
    Stream<DynamicTest> addresses =
        Js.list(file, "lightningAddresses").stream()
            .map(item ->
                dynamicTest(
                    quoted(Js.text(item, "address")),
                    () ->
                        assertEquals(
                            Js.text(item, "payUrl"),
                            Urls.lightningAddressUrl(Js.text(item, "address")))));
    return Stream.of(urls, pairs, addresses).flatMap(s -> s);
  }

  /**
   * nostr-events.json: each event's serialization and id, through what the check of an event's id
   * reads of it and computes (Events.covered, as Events.withValidId).
   */
  @TestFactory
  Stream<DynamicTest> nostrEvents() {
    return Js.list(vectors("nostr-events.json"), "cases").stream()
        .map(item ->
            dynamicTest(
                Js.text(item, "name"),
                () -> {
                  Events.Covered covered = Events.covered(Js.object(item, "event"));
                  assertEquals(Js.text(item, "serialized"), covered.serialized());
                  assertEquals(Js.text(item, "id"), covered.id());
                  // An event of that id and any signature checks its id out as relays send it.
                  JsonObject sent =
                      Js.with(Js.get(item, "event"), "id", covered.id(), "sig", "00".repeat(64));
                  assertEquals(Js.text(item, "id"), Events.withValidId(sent).id());
                }));
  }

  /**
   * pay-requests.json: the pay request read from the answer of a Lightning address's pay URL
   * (Lnurl.fetchPayRequest, as createInvoice and a rest's invoice ask it): its callback, its
   * sendable sats, its commentAllowed and the description hash its invoices are checked against
   * (PayRequest.descriptionHash, as the invoice check); or the error.
   */
  @TestFactory
  Stream<DynamicTest> payRequests() {
    return Js.list(vectors("pay-requests.json"), "cases").stream()
        .map(item ->
            dynamicTest(
                Js.text(item, "name"),
                () -> {
                  JsonValue answer = Js.get(item, "http");
                  int status = (int) Js.integer(answer, "status");
                  FakeHttp http =
                      new FakeHttp(uri -> {
                        assertEquals("https://breez.tips/.well-known/lnurlp/shop", uri.toString());
                        return Js.text(answer, "bodyText") != null
                            ? FakeHttp.Answer.text(status, Js.text(answer, "bodyText"))
                            : FakeHttp.Answer.json(status, Js.get(answer, "body"));
                      });
                  try {
                    JsonValue result =
                        Js.outcome(() -> {
                          Lnurl.PayRequest pay =
                              Tasks.await(
                                  Lnurl.fetchPayRequest(new Http(http, 1000), "shop@breez.tips"));
                          return Js.obj(
                              "callback",
                              pay.callback(),
                              "sendable",
                              Js.obj(
                                  "min",
                                  Long.toString(pay.minSats()),
                                  "max",
                                  Long.toString(pay.maxSats())),
                              "commentAllowed",
                              pay.commentAllowed(),
                              "descriptionHash",
                              pay.descriptionHash());
                        });
                    assertEquals(Js.get(item, "expected"), result);
                  } finally {
                    http.shutdown();
                  }
                }));
  }

  @TestFactory
  Stream<DynamicTest> bolt11() {
    return Js.list(vectors("bolt11.json"), "cases").stream()
        .map(item ->
            dynamicTest(
                Js.text(item, "name"),
                () -> {
                  Bolt11 decoded = Bolt11.decode(Js.text(item, "invoice"));
                  JsonValue read =
                      decoded == null
                          ? JsonNull.NULL
                          : Js.obj(
                              "network", decoded.network(),
                              "amountMsat",
                                  decoded.amountMsat() == null
                                      ? null
                                      : decoded.amountMsat().toString(),
                              "timestamp", decoded.timestamp(),
                              "expirySeconds", decoded.expirySeconds(),
                              "paymentHash", decoded.paymentHash(),
                              "descriptionHash", decoded.descriptionHash());
                  assertEquals(Js.get(item, "expected"), read);
                }));
  }

  /**
   * fiat.json's {@code digits}: what the public {@link Currencies#currencyDigits} gives for a text
   * (SPEC.md §4, Functions), the decimals of the currency whose code it is exactly, or none.
   */
  @TestFactory
  Stream<DynamicTest> currencyDigits() {
    return Js.list(vectors("fiat.json"), "digits").stream()
        .map(item ->
            dynamicTest(
                "currencyDigits(" + Js.write(Js.get(item, "code")) + ")",
                () -> {
                  java.util.OptionalInt digits = Currencies.currencyDigits(Js.text(item, "code"));
                  assertEquals(
                      Js.get(item, "expected"),
                      digits.isPresent() ? Js.of(digits.getAsInt()) : JsonNull.NULL);
                }));
  }

  @Test
  void givesCurrencyDigitsAsAPublicFunctionOfThePackageThatNeedsNoClient() throws Exception {
    // SPEC.md §4, Functions: a shop asks it before it has a client, or a key.
    java.lang.reflect.Method method = Currencies.class.getMethod("currencyDigits", String.class);
    assertTrue(java.lang.reflect.Modifier.isPublic(Currencies.class.getModifiers()));
    assertTrue(java.lang.reflect.Modifier.isPublic(method.getModifiers()));
    assertTrue(java.lang.reflect.Modifier.isStatic(method.getModifiers()));
    assertEquals(java.util.OptionalInt.class, method.getReturnType());
    // Exactly the table, every code of it, and the order's price reads its decimals there.
    for (Map.Entry<String, Integer> currency : Currencies.all().entrySet()) {
      assertEquals(
          java.util.OptionalInt.of(currency.getValue()),
          Currencies.currencyDigits(currency.getKey()));
      assertEquals(
          (int) currency.getValue(), Fiat.of("1", currency.getKey()).digits(), currency.getKey());
    }
    assertEquals(java.util.OptionalInt.empty(), Currencies.currencyDigits(null));
  }

  @TestFactory
  Stream<DynamicTest> fiat() {
    return Js.list(vectors("fiat.json"), "cases").stream()
        .map(item -> {
          JsonValue input = Js.get(item, "input");
          return dynamicTest(
              Js.write(input),
              () ->
                  assertEquals(
                      Js.get(item, "expected"),
                      Js.outcome(() -> {
                        Fiat fiat =
                            Fiat.of(
                                Json.string(Js.get(input, "amount")),
                                Json.string(Js.get(input, "currency")));
                        return Js.obj(
                            "amount", fiat.amount(),
                            "minor", fiat.minor(),
                            "digits", fiat.digits(),
                            "currency", fiat.currency());
                      })));
        });
  }

  private static JsonValue read(String text) {
    Prices.Decimal decimal = text == null ? null : Prices.parseDecimal(text);
    return decimal == null ? JsonNull.NULL : new JsonString(Prices.formatDecimal(decimal));
  }

  @TestFactory
  Stream<DynamicTest> prices() {
    JsonValue file = vectors("prices.json");
    Stream<DynamicTest> text =
        Js.list(file, "text").stream()
            .map(item ->
                dynamicTest(
                    "text " + Js.text(item, "price"),
                    () -> assertEquals(Js.get(item, "expected"), read(Js.text(item, "price")))));
    Stream<DynamicTest> numbers =
        Js.list(file, "numbers").stream()
            .map(item ->
                dynamicTest(
                    "number " + Js.text(item, "json"),
                    () -> {
                      double value = ((JsonNumber) Js.parse(Js.text(item, "json"))).value();
                      assertEquals(Js.get(item, "expected"), read(Prices.numberText(value)));
                    }));
    return Stream.concat(text, numbers);
  }

  @TestFactory
  Stream<DynamicTest> conversions() {
    return Js.list(vectors("conversions.json"), "cases").stream()
        .map(item -> {
          JsonValue fiat = Js.get(item, "fiat");
          String price = Js.text(item, "price");
          return dynamicTest(
              Js.text(fiat, "amount") + " " + Js.text(fiat, "currency") + " at " + price,
              () ->
                  assertEquals(
                      Js.text(item, "sats"),
                      Fiat.of(Js.text(fiat, "amount"), Js.text(fiat, "currency"))
                          .toSats(price)
                          .toString()));
        });
  }

  @TestFactory
  Stream<DynamicTest> orderText() {
    JsonValue file = vectors("order-text.json");
    Stream<DynamicTest> refs =
        Js.list(file, "refs").stream()
            .map(item ->
                dynamicTest(
                    "ref " + Js.text(item, "ref"),
                    () ->
                        assertEquals(
                            Js.bool(item, "valid")
                                ? Js.get(item, "ref")
                                : Js.obj("error", "invalid_order", "param", "ref"),
                            Js.outcome(() -> Orders.checkRef(Js.text(item, "ref"))))));
    Stream<DynamicTest> notes =
        Js.list(file, "notes").stream()
            .map(item ->
                dynamicTest(
                    "note " + name(item, "note"),
                    () -> {
                      Order.Builder order = Order.builder(Js.text(item, "ref")).sats(1);
                      String note = Js.text(item, "note");
                      if (note != null) order.note(note);
                      assertEquals(
                          Js.get(item, "expected"),
                          Js.outcome(() ->
                              Js.obj(
                                  "label", Orders.checkOrder(order.build(), List.of()).label())));
                    }));
    Stream<DynamicTest> comments =
        Js.list(file, "comments").stream()
            .map(item ->
                dynamicTest(
                    "comment "
                        + Js.text(item, "label")
                        + ", commentAllowed "
                        + Js.integer(item, "commentAllowed"),
                    () -> {
                      FakeHttp http =
                          new FakeHttp(uri ->
                              FakeHttp.Answer.json(
                                  Js.obj("pr", "lnbc1", "verify", "https://x.example/v")));
                      Lnurl.PayRequest pay =
                          new Lnurl.PayRequest(
                              "https://x.example/cb",
                              1000,
                              1_000_000,
                              "[]",
                              Js.integer(item, "commentAllowed"));
                      Tasks.await(
                          Lnurl.requestInvoice(
                              new Http(http, 1000), pay, 1, 60, Js.text(item, "label")));
                      String sent = http.calls.get(0).query("comment");
                      assertEquals(Js.bool(item, "sent") ? Js.text(item, "label") : null, sent);
                      http.shutdown();
                    }));
    return Stream.of(refs, notes, comments).flatMap(s -> s);
  }

  /** Which check an InvalidInvoiceException names, by its message (as checkout-node reads it). */
  private static String checkOf(String message) {
    String[][] checks = {
      {"Not a BOLT11", "bolt11"},
      {"mainnet", "network"},
      {"amount", "amount"},
      {"does not pay the merchant", "description_hash"},
      {"verify URL", "verify_url"},
      {"longer than asked", "duration"},
      {"clock", "clock"},
      {"no time left", "expired"}
    };
    for (String[] check : checks) if (message.contains(check[0])) return check[1];
    return message;
  }

  @TestFactory
  Stream<DynamicTest> invoiceChecks() {
    JsonValue file = vectors("invoice-checks.json");
    JsonValue payRequest = Js.get(file, "payRequest");
    Lnurl.PayRequest pay =
        new Lnurl.PayRequest(
            Js.text(payRequest, "callback"), 1000, 100_000_000, Js.text(payRequest, "metadata"), 0);
    return Js.list(file, "cases").stream()
        .map(item ->
            dynamicTest(
                Js.text(item, "name"),
                () -> {
                  JsonValue answer = Js.get(item, "answer");
                  Lnurl.InvoiceAnswer invoice =
                      new Lnurl.InvoiceAnswer(Js.text(answer, "pr"), Js.text(answer, "verify"));
                  long sats = Long.parseLong(Js.text(item, "sats"));
                  long duration = Js.integer(item, "durationSeconds");
                  long now = Js.integer(item, "now");
                  JsonValue result;
                  try {
                    Lnurl.checkInvoice(invoice, pay, sats, duration, now);
                    result = Js.obj("valid", true);
                  } catch (InvalidInvoiceException e) {
                    result = Js.obj("error", e.getCode(), "check", checkOf(e.getMessage()));
                  }
                  assertEquals(Js.get(item, "expected"), result);
                  // The check by name, as the vectors name them.
                  String failed = Lnurl.failedCheck(invoice, pay, sats, duration, now);
                  JsonValue expected = Js.get(item, "expected");
                  assertEquals(Js.text(expected, "check"), failed);
                }));
  }

  @TestFactory
  Stream<DynamicTest> payments() {
    JsonValue file = vectors("payments.json");
    JsonValue invoice = Js.get(file, "invoice");
    Lnurl.LightningInvoice lightning =
        new Lnurl.LightningInvoice(
            Js.text(invoice, "bolt11"),
            Js.text(invoice, "paymentHash"),
            Js.text(invoice, "verifyUrl"),
            Js.integer(invoice, "expiresAt"));
    return Js.list(file, "cases").stream()
        .map(item ->
            dynamicTest(
                Js.text(item, "name"),
                () -> {
                  JsonValue answer = Js.get(item, "http");
                  int status = (int) Js.integer(answer, "status");
                  FakeHttp http =
                      new FakeHttp(uri ->
                          Js.text(answer, "bodyText") != null
                              ? FakeHttp.Answer.text(status, Js.text(answer, "bodyText"))
                              : FakeHttp.Answer.json(status, Js.get(answer, "body")));
                  long now = Js.integer(item, "now");
                  // An invoice without an address: its status is its Lightning part's.
                  JsonValue result =
                      Js.outcome(() -> {
                        Lnurl.LightningPart part =
                            Tasks.await(Lnurl.verifyPayment(new Http(http, 1000), lightning, now));
                        return Model.status(
                            Payments.combineParts(
                                1,
                                lightning.expiresAt(),
                                null,
                                now,
                                Tasks.Settled.ok(part),
                                null,
                                null));
                      });
                  http.shutdown();
                  assertEquals(Js.get(item, "expected"), result);
                }));
  }

  /**
   * Invoices as a shop stores them, read back from their JSON and checked before a payment check
   * asks anything (SPEC.md §22): valid, or {@code invalid_order} ({@code invoice}).
   */
  @TestFactory
  Stream<DynamicTest> storedInvoices() {
    return Js.list(vectors("payments.json"), "stored").stream()
        .map(item ->
            dynamicTest(
                Js.text(item, "name"),
                () -> {
                  String json = Js.write(Js.get(item, "invoice"));
                  JsonValue result =
                      Js.outcome(() -> {
                        Orders.checkStoredInvoice(InvoiceToCheck.fromJson(json));
                        return Js.obj("valid", true);
                      });
                  assertEquals(Js.get(item, "expected"), result);
                }));
  }

  /** A vector's rateSource as Java writes it: a name, a list of names; anything else, no source. */
  private static List<RateSource> sourcesOf(JsonValue value) {
    List<RateSource> sources = new ArrayList<>();
    if (value instanceof JsonArray array) {
      for (JsonValue item : array.items()) {
        sources.add(
            item instanceof JsonString name ? RateSource.byName(name.value()).orElse(null) : null);
      }
    } else {
      // A number, an object, null: Java's types refuse them; the nearest a caller can give is none.
      sources.add(
          value instanceof JsonString name ? RateSource.byName(name.value()).orElse(null) : null);
    }
    return sources;
  }

  private static JsonValue names(List<RateSource> sources) {
    JsonArray names = new JsonArray();
    for (RateSource source : sources) names.add(source.name());
    return Js.obj("sources", names);
  }

  /**
   * A source that answers with an HTTP status other than 2xx, as the platform's client gives it
   * (SPEC.md §26): network for 500 to 599, 408, 429 and a redirect (no answer, SPEC.md §15), rate
   * for any other.
   */
  @TestFactory
  Stream<DynamicTest> rateSourceStatuses() {
    return Js.list(vectors("rate-sources.json"), "statuses").stream()
        .map(item ->
            dynamicTest(
                "HTTP " + Js.integer(item, "status"),
                () -> {
                  int status = (int) Js.integer(item, "status");
                  FakeHttp http = new FakeHttp(uri -> FakeHttp.Answer.text(status, ""));
                  try {
                    JsonValue result =
                        Js.outcome(() -> {
                          RateSources.fetchRate(
                              RateSource.COINGECKO,
                              "CHF",
                              new RateSources.Context(new Http(http, 1000), null),
                              1_791_378_000L);
                          return Js.obj("ok", true);
                        });
                    assertEquals(Js.get(item, "expected"), result);
                  } finally {
                    http.shutdown();
                  }
                }));
  }

  @TestFactory
  Stream<DynamicTest> rateSources() {
    JsonValue file = vectors("rate-sources.json");
    String key = Js.text(Js.spec("vectors/app/app.json"), "secretKey");
    DynamicTest defaults =
        dynamicTest(
            "the default list",
            () ->
                assertEquals(
                    Js.get(file, "default"), Js.get(names(RateSource.DEFAULT_LIST), "sources")));
    Stream<DynamicTest> values =
        Js.list(file, "values").stream()
            .map(item ->
                dynamicTest(
                    "rateSource: " + Js.text(item, "name"),
                    () -> {
                      JsonObject object = (JsonObject) item;
                      boolean absent = !object.has("rateSource");
                      List<RateSource> given = absent ? null : sourcesOf(object.get("rateSource"));
                      JsonValue expected = object.get("expected");
                      // The client.
                      JsonValue read =
                          Js.outcome(() -> {
                            SattlemeClient.Builder builder = SattlemeClient.builder().key(key);
                            if (!absent) builder.rateSources(given);
                            builder.build();
                            List<RateSource> sources =
                                absent ? RateSource.DEFAULT_LIST : RateSources.checkList(given);
                            return names(sources);
                          });
                      assertEquals(expected, read);
                      // An order: invalid_order, and the client's sources when it has none.
                      Order.Builder order = Order.builder("A-1").fiat("1", "CHF");
                      if (!absent) order.rateSources(given);
                      JsonValue checked =
                          Js.outcome(() ->
                              names(
                                  Orders.checkOrder(order.build(), List.of(RateSource.KRAKEN))
                                      .rateSources()));
                      if (((JsonObject) expected).has("error")) {
                        assertEquals(
                            Js.obj("error", "invalid_order", "param", "rateSource"), checked);
                      } else {
                        assertEquals(
                            absent ? names(List.of(RateSource.KRAKEN)) : expected, checked);
                      }
                    }));
    JsonValue lists = Js.get(file, "lists");
    String currency = Js.text(lists, "currency");
    long now = Js.integer(lists, "now");
    Stream<DynamicTest> cases =
        Js.list(lists, "cases").stream()
            .map(item ->
                dynamicTest(
                    "a list: " + Js.text(item, "name"),
                    () -> {
                      JsonObject answers = Js.object(item, "answers");
                      List<String> asked = new ArrayList<>();
                      RateSources.Ask ask =
                          (source, cur, context, at) -> {
                            String sourceName = source.name();
                            asked.add(sourceName);
                            JsonValue answer = answers.get(sourceName);
                            if (answer instanceof JsonString kind) {
                              switch (kind.value()) {
                                case "network" ->
                                    throw new NetworkException(sourceName + " is down");
                                case "rate" ->
                                    throw new RateException(sourceName + " gave no price");
                                case "unsupported_currency" ->
                                    throw new UnsupportedCurrencyException(cur, sourceName);
                                default -> throw new IllegalStateException(kind.value());
                              }
                            }
                            JsonValue time = Js.get(answer, "time");
                            RateQuote quote =
                                new RateQuote(
                                    Js.text(answer, "price"),
                                    time instanceof JsonNumber number
                                        ? (long) number.value()
                                        : null);
                            // The price, checked as a source's: §25.
                            RateSources.Rate got =
                                RateSources.fetchRate(
                                    RateSource.custom(c -> quote), cur, context, at);
                            RateInfo info = got.info();
                            return new RateSources.Rate(
                                got.price(),
                                new RateInfo(
                                    sourceName,
                                    info.currency(),
                                    info.price(),
                                    info.fetchedAt(),
                                    info.time()));
                          };
                      List<RateSource> sources = new ArrayList<>();
                      for (String sourceName : Js.strings(item, "sources")) {
                        sources.add(RateSource.byName(sourceName).orElseThrow());
                      }
                      List<String> warnings = new ArrayList<>();
                      JsonValue result;
                      try {
                        RateSources.Rate rate =
                            RateSources.fetchFirstRate(
                                sources,
                                currency,
                                new RateSources.Context(null, null),
                                () -> now,
                                warnings::add,
                                ask);
                        result =
                            Js.obj(
                                "source",
                                rate.info().source(),
                                "price",
                                rate.info().price(),
                                "asked",
                                asked);
                      } catch (SattlemeException e) {
                        result = Js.obj("error", e.getCode(), "asked", asked);
                      }
                      assertEquals(Js.get(item, "expected"), result);
                      // Each failure the next source replaces is logged, never the last one.
                      assertEquals(asked.size() - 1, warnings.size());
                    }));
    return Stream.of(Stream.of(defaults), values, cases).flatMap(s -> s);
  }

  @TestFactory
  Stream<DynamicTest> redirectUrls() {
    return Js.list(vectors("redirect-urls.json"), "cases").stream()
        .map(item ->
            dynamicTest(
                quoted(Js.text(item, "url")),
                () -> {
                  String url = Js.text(item, "url");
                  JsonObject expected = Js.object(item, "expected");
                  Order order =
                      Order.builder("A-1").sats(1).checkout(true).redirectUrl(url).build();
                  JsonValue result =
                      Js.outcome(() ->
                          Js.obj("redirectUrl", Orders.checkOrder(order, List.of()).redirectUrl()));
                  if (expected.has("error")) {
                    assertEquals(expected, result);
                    assertNull(Checkout.redirectShown(url));
                  } else {
                    assertEquals(Js.obj("redirectUrl", url), result);
                    assertEquals(expected, Js.obj("shown", Checkout.redirectShown(url)));
                  }
                }));
  }

  @TestFactory
  Stream<DynamicTest> checkoutLinks() {
    JsonValue file = vectors("checkout-links.json");
    Stream<DynamicTest> origins =
        Js.list(file, "origins").stream()
            .map(item ->
                dynamicTest(
                    "origin of "
                        + Js.write(
                            Js.get(item, "checkout") == null
                                ? JsonNull.NULL
                                : Js.get(item, "checkout")),
                    () -> {
                      JsonValue checkout = Js.get(item, "checkout");
                      JsonValue expected = Js.get(item, "expected");
                      assertEquals(expected, Js.of(Checkout.checkoutOriginOf(checkout)));
                      // Whatever it holds, the channel stays valid.
                      JsonObject channel =
                          Js.obj(
                              "version",
                              1,
                              "status",
                              "active",
                              "role",
                              "integration",
                              "profile",
                              Js.obj(
                                  "businessName",
                                  "Sunrise Bakery",
                                  "lightningAddress",
                                  "a@breez.tips"),
                              "registryKey",
                              "11".repeat(32),
                              "registryReader",
                              "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798");
                      if (checkout != null) channel.put("checkout", checkout);
                      channel.put("updatedAt", 100);
                      Channels.Channel parsed = Channels.parseChannel(channel, 100);
                      assertEquals(expected, Js.of(parsed.checkoutOrigin()));
                    }));
    Stream<DynamicTest> derivations =
        Js.list(file, "derivations").stream()
            .map(item ->
                dynamicTest(
                    "derives the keys of " + Js.text(item, "linkKey"),
                    () -> {
                      byte[] linkKey = Js.hex(item, "linkKey");
                      assertEquals(Js.text(item, "address"), Derive.checkoutAddress(linkKey));
                      assertEquals(
                          Js.text(item, "encryptionKey"),
                          Hex.encode(Derive.checkoutEncryptionKey(linkKey)));
                    }));
    Stream<DynamicTest> urls =
        Js.list(file, "urls").stream()
            .map(item ->
                dynamicTest(
                    "writes " + Js.text(item, "url"),
                    () ->
                        assertEquals(
                            Js.text(item, "url"),
                            Checkout.checkoutUrl(
                                Js.text(item, "origin"),
                                Js.text(item, "author"),
                                Js.hex(item, "linkKey"),
                                Js.strings(item, "relays")))));
    Stream<DynamicTest> fragments =
        Js.list(file, "fragments").stream()
            .map(item ->
                dynamicTest(
                    "fragment: " + Js.text(item, "name"),
                    () -> {
                      Checkout.Fragment read = Checkout.parseFragment(Js.text(item, "fragment"));
                      JsonValue result =
                          read instanceof Checkout.Link link
                              ? Js.obj(
                                  "author", link.author(),
                                  "linkKey", Hex.encode(link.linkKey()),
                                  "relays", link.relays())
                              : new JsonString(
                                  read instanceof Checkout.Newer
                                      ? "newer"
                                      : read instanceof Checkout.Invalid ? "invalid" : "other");
                      assertEquals(Js.get(item, "expected"), result);
                    }));
    Stream<DynamicTest> records =
        Js.list(file, "records").stream()
            .map(item ->
                dynamicTest(
                    "record: " + Js.text(item, "name"),
                    () -> {
                      JsonValue result;
                      try {
                        Checkout.CheckoutRecord record = Checkout.parseValue(Js.get(item, "value"));
                        result =
                            record == null
                                ? new JsonString("invalid")
                                : Js.obj(
                                    "sats", Long.toString(record.sats()),
                                    "paymentHash", record.paymentHash(),
                                    "onchainAddress", record.onchainAddress(),
                                    "expiresAt", record.expiresAt(),
                                    "redirect", record.redirectShown(),
                                    "rest",
                                        record.rest() == null
                                            ? null
                                            : Js.obj(
                                                "author", record.rest().author(),
                                                "key", Hex.encode(record.rest().key())));
                      } catch (Records.NewerFormat e) {
                        result = new JsonString("newer");
                      }
                      assertEquals(Js.get(item, "expected"), result);
                    }));
    return Stream.of(origins, derivations, urls, fragments, records).flatMap(s -> s);
  }

  @Test
  void anInvalidPriceIsARateError() {
    Fiat fiat = Fiat.of("24.50", "CHF");
    assertEquals("35502", fiat.toSats("69012").toString());
    assertThrows(RateException.class, () -> fiat.toSats("0"));
    assertThrows(RateException.class, () -> fiat.toSats("-1"));
    assertThrows(InvalidOrderException.class, () -> Fiat.of("24.505", "CHF"));
  }
}
