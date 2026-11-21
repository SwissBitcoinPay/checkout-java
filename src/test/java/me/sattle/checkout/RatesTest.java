package me.sattle.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import me.sattle.checkout.internal.Http;
import me.sattle.checkout.internal.Prices;
import me.sattle.checkout.internal.RateSources;
import me.sattle.checkout.internal.Warn;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.support.FakeHttp;
import me.sattle.checkout.support.Js;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Prices, conversions and rate sources (SPEC.md §25, §26), as checkout-node's rates tests. */
class RatesTest {
  private static final long NOW = 1_791_385_300L;
  private static final Warn QUIET = message -> {};

  /** What a source gave, and what it asked. */
  record Priced(RateInfo info, List<FakeHttp.Call> calls) {}

  private static Priced priceOf(
      RateSource source, String currency, FakeHttp.Route route, String coingeckoApiKey)
      throws InterruptedException {
    FakeHttp http = new FakeHttp(route);
    try {
      RateSources.Rate rate =
          RateSources.fetchRate(
              source,
              currency,
              new RateSources.Context(new Http(http, 1000), coingeckoApiKey),
              NOW);
      return new Priced(rate.info(), List.copyOf(http.calls));
    } finally {
      http.shutdown();
    }
  }

  private static Priced priceOf(RateSource source, String currency, FakeHttp.Route route)
      throws InterruptedException {
    return priceOf(source, currency, route, null);
  }

  private static FakeHttp.Answer answer(Object json) {
    return FakeHttp.Answer.json(Js.of(json));
  }

  private static FakeHttp.Answer answer(Object json, int status) {
    return FakeHttp.Answer.json(status, Js.of(json));
  }

  private static final FakeHttp.Route NONE = uri -> answer(Js.obj(), 404);

  private static BigInteger fiatToSats(String amount, String currency, String price) {
    return Fiat.of(amount, currency).toSats(price);
  }

  @Nested
  class ExactConversion {
    @Test
    void roundsUpToTheNextWholeSat() {
      // ⌈2450 × 10^8 / 6 901 200⌉: the exact quotient is 35 501.07…
      BigInteger dividend = BigInteger.valueOf(2450L * 100_000_000L);
      assertEquals(BigInteger.valueOf(35_501), dividend.divide(BigInteger.valueOf(6_901_200)));
      assertNotEquals(BigInteger.ZERO, dividend.mod(BigInteger.valueOf(6_901_200)));
      assertEquals(BigInteger.valueOf(35_502), fiatToSats("24.50", "CHF", "69012"));
      assertEquals(BigInteger.valueOf(35_502), fiatToSats("24.5", "CHF", "69012.000"));
    }

    @Test
    void givesExactlyTheQuotientWhenItIsWhole() {
      // 1 CHF at 100 000 CHF/BTC: 1000 sats exactly.
      assertEquals(BigInteger.valueOf(1000), fiatToSats("1.00", "CHF", "100000"));
    }

    @Test
    void readsAPriceAsItsExactDecimalACoinbaseStringWith16DecimalsIncluded() {
      String price = "69025.1084416666694295";
      assertEquals(price, Prices.formatDecimal(Prices.parseDecimal(price)));
      // ⌈2450 × 10^8 × 10^16 / (690251084416666694295 × 10^2)⌉, on integers.
      BigInteger dividend =
          BigInteger.valueOf(2450L * 100_000_000L).multiply(BigInteger.TEN.pow(16));
      BigInteger divisor =
          new BigInteger("690251084416666694295").multiply(BigInteger.valueOf(100));
      assertEquals(
          dividend.add(divisor).subtract(BigInteger.ONE).divide(divisor),
          fiatToSats("24.50", "CHF", price));
    }

    @Test
    void readsExponentNotation() {
      assertEquals(new Prices.Decimal(BigInteger.valueOf(8), 8), Prices.parseDecimal("8e-8"));
      assertEquals("0.00000008", Prices.formatDecimal(Prices.parseDecimal("8e-8")));
      assertEquals(
          new Prices.Decimal(new BigInteger("1500000000000000000000"), 0),
          Prices.parseDecimal("1.5e+21"));
      assertEquals(
          new Prices.Decimal(BigInteger.valueOf(69012), 0), Prices.parseDecimal("6.9012e4"));
      assertEquals(
          new Prices.Decimal(BigInteger.valueOf(690125), 1), Prices.parseDecimal("69012.50"));
    }

    /*
     * checkout-node also refuses the numbers 0, 69012 and NaN: a price is a String here, so a
     * number cannot be given; null is the only value that is not one.
     */
    static Stream<String> invalidPrices() {
      return Stream.of(
          null,
          "0",
          "0.000",
          "-69012",
          "",
          "abc",
          "1,5",
          " ",
          " 69012",
          "69012\n",
          "0x10",
          "1e65",
          "1".repeat(65),
          "Infinity",
          "NaN");
    }

    @ParameterizedTest(name = "refuses the price \"{0}\"")
    @MethodSource("invalidPrices")
    void refusesThePrice(String price) {
      assertNull(Prices.parseDecimal(price));
      RateException error = assertThrows(RateException.class, () -> fiatToSats("1", "CHF", price));
      assertEquals("rate", error.getCode());
    }

    @Test
    void takesTheCurrencysDecimalsFromItsTableWhateverThePlatformsLocaleData() {
      assertEquals(OptionalInt.of(2), Currencies.currencyDigits("CHF"));
      assertEquals(OptionalInt.of(0), Currencies.currencyDigits("JPY"));
      assertEquals(OptionalInt.of(3), Currencies.currencyDigits("BHD"));
      // The app's decimals (Intl), not ISO 4217's 2: the app leaves out sales of other decimals.
      assertEquals(OptionalInt.of(0), Currencies.currencyDigits("RSD"));
      // Not currencies an order is priced in: any three letters, a fund, gold, bitcoin, lower case.
      for (String code :
          List.of("ABC", "CHE", "XAU", "XXX", "BTC", "SAT", "chf", "hasOwnProperty", "")) {
        assertEquals(OptionalInt.empty(), Currencies.currencyDigits(code), code);
      }
      assertEquals(OptionalInt.empty(), Currencies.currencyDigits(null));
      assertEquals(new Fiat("1500", "JPY", "1500", 0), Prices.parseFiat("1500", "JPY"));
      Fiat chf = Prices.parseFiat("24.500", "CHF");
      assertEquals("24.50", chf.amount());
      assertEquals("CHF", chf.currency());
      assertEquals("2450", chf.minor());
      assertEquals(Fiat.of("24.500", "CHF"), chf);
    }

    /* checkout-node also refuses the number 24.5: an amount is a String here. */
    static Stream<Arguments> invalidFiats() {
      return Stream.of(
          Arguments.of("24.505", "CHF"),
          Arguments.of("1.5", "JPY"),
          Arguments.of("0", "CHF"),
          Arguments.of("0.00", "CHF"),
          Arguments.of("-1", "CHF"),
          Arguments.of("1e3", "CHF"),
          Arguments.of("24.50", "SAT"),
          Arguments.of("24.50", "CHFX"),
          Arguments.of("99999999999999999", "CHF"),
          Arguments.of(null, "CHF"),
          Arguments.of("24.50", null));
    }

    @ParameterizedTest(name = "refuses the price {0} {1}")
    @MethodSource("invalidFiats")
    void refusesTheFiat(String amount, String currency) {
      InvalidOrderException error =
          assertThrows(InvalidOrderException.class, () -> Prices.parseFiat(amount, currency));
      assertEquals("fiat", error.getParam());
    }
  }

  @Nested
  class TheRateSources {
    @Test
    void knowsItsSourcesByName() {
      List<String> names = new ArrayList<>();
      for (String name : List.of("coingecko", "mempool", "opennode", "coinbase", "kraken")) {
        Optional<RateSource> source = RateSource.byName(name);
        assertTrue(source.isPresent(), name);
        assertEquals(name, source.get().name());
        assertFalse(source.get().isCustom());
        names.add(source.get().name());
      }
      assertEquals(List.of("coingecko", "mempool", "opennode", "coinbase", "kraken"), names);
      // The default list, in the order SPEC.md §26 asks it.
      assertEquals(
          List.of("coingecko", "mempool", "coinbase", "kraken", "opennode"),
          RateSource.DEFAULT_LIST.stream().map(RateSource::name).toList());
    }

    @Test
    void coinGeckoDatedWithOrWithoutADemoKey() throws Exception {
      FakeHttp.Route route =
          uri ->
              uri.getPath().endsWith("/simple/price")
                  ? answer(Js.obj("bitcoin", Js.obj("chf", 69012, "last_updated_at", NOW - 30)))
                  : answer(Js.obj(), 404);
      Priced free = priceOf(RateSource.COINGECKO, "CHF", route);
      assertEquals(new RateInfo("coingecko", "CHF", "69012", NOW, NOW - 30), free.info());
      assertEquals(
          "https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=chf&include_last_updated_at=true",
          free.calls().get(0).uri().toString());
      assertEquals(Optional.empty(), free.calls().get(0).headers().firstValue("x-cg-demo-api-key"));
      // CoinGecko answers HTTP 403 to a request without a User-Agent (SPEC.md §15).
      assertEquals(
          List.of("sattleme-Checkout/1"), free.calls().get(0).headers().allValues("User-Agent"));
      Priced demo = priceOf(RateSource.COINGECKO, "CHF", route, "demo");
      assertEquals(List.of("demo"), demo.calls().get(0).headers().allValues("x-cg-demo-api-key"));
      assertEquals(
          List.of("sattleme-Checkout/1"), demo.calls().get(0).headers().allValues("User-Agent"));
      assertThrows(
          UnsupportedCurrencyException.class,
          () -> priceOf(RateSource.COINGECKO, "XYZ", uri -> answer(Js.obj("bitcoin", Js.obj()))));
      assertThrows(
          RateException.class,
          () -> priceOf(RateSource.COINGECKO, "CHF", uri -> answer(Js.obj("nope", 1))));
    }

    @Test
    void mempoolSpaceDatedItsSevenCurrencies() throws Exception {
      Object[] currencies = {
        "USD", 82977, "EUR", 74000, "GBP", 63000, "CAD", 118000, "CHF", 69080, "AUD", 119000, "JPY",
        13187638
      };
      JsonObject prices = Js.obj(currencies);
      JsonObject body = Js.with(Js.obj("time", NOW - 60), currencies);
      Priced priced = priceOf(RateSource.MEMPOOL, "CHF", uri -> answer(body));
      assertEquals(new RateInfo("mempool", "CHF", "69080", NOW, NOW - 60), priced.info());
      assertEquals("https://mempool.space/api/v1/prices", priced.calls().get(0).uri().toString());
      assertThrows(
          UnsupportedCurrencyException.class,
          () -> priceOf(RateSource.MEMPOOL, "SEK", uri -> answer(body)));
      // Undated: refused.
      assertThrows(
          RateException.class, () -> priceOf(RateSource.MEMPOOL, "CHF", uri -> answer(prices)));
    }

    @Test
    void openNode() throws Exception {
      JsonObject body =
          Js.obj(
              "data",
              Js.obj(
                  "BTCCHF", Js.obj("CHF", 68991.16, "BTC", 0.00001449),
                  "BTCJPY", Js.obj("JPY", 13185808.44, "BTC", 8e-8)));
      Priced priced = priceOf(RateSource.OPENNODE, "CHF", uri -> answer(body));
      assertEquals("68991.16", priced.info().price());
      assertEquals("https://api.opennode.com/v1/rates", priced.calls().get(0).uri().toString());
      assertThrows(
          UnsupportedCurrencyException.class,
          () -> priceOf(RateSource.OPENNODE, "SEK", uri -> answer(body)));
      assertThrows(
          RateException.class,
          () -> priceOf(RateSource.OPENNODE, "CHF", uri -> answer(Js.obj("data", List.of()))));
    }

    @Test
    void coinbaseItsDecimalStringsKeptAsWritten() throws Exception {
      JsonObject data =
          Js.obj(
              "currency",
              "BTC",
              "rates",
              Js.obj("CHF", "69025.1084416666694295", "USD", "83000.1"));
      JsonObject body = Js.obj("data", data);
      Priced priced = priceOf(RateSource.COINBASE, "CHF", uri -> answer(body));
      assertEquals("69025.1084416666694295", priced.info().price());
      assertEquals(
          "https://api.coinbase.com/v2/exchange-rates?currency=BTC",
          priced.calls().get(0).uri().toString());
      assertThrows(
          UnsupportedCurrencyException.class,
          () -> priceOf(RateSource.COINBASE, "SEK", uri -> answer(body)));
      assertThrows(
          RateException.class,
          () ->
              priceOf(
                  RateSource.COINBASE,
                  "CHF",
                  uri -> answer(Js.obj("data", Js.with(data, "currency", "ETH")))));
    }

    @Test
    void krakenWhateverTheResultsKeyItsErrorsRead() throws Exception {
      java.util.function.Function<String, FakeHttp.Answer> ticker =
          key ->
              answer(
                  Js.obj(
                      "error",
                      List.of(),
                      "result",
                      Js.obj(key, Js.obj("c", List.of("69050.00000", "0.1"), "a", List.of("1")))));
      Priced chf = priceOf(RateSource.KRAKEN, "CHF", uri -> ticker.apply("XBTCHF"));
      assertEquals("69050", chf.info().price());
      assertEquals(
          "https://api.kraken.com/0/public/Ticker?pair=XBTCHF",
          chf.calls().get(0).uri().toString());
      Priced usd = priceOf(RateSource.KRAKEN, "USD", uri -> ticker.apply("XXBTZUSD"));
      assertEquals("69050", usd.info().price());
      assertThrows(
          UnsupportedCurrencyException.class,
          () ->
              priceOf(
                  RateSource.KRAKEN,
                  "ZZZ",
                  uri -> answer(Js.obj("error", List.of("EQuery:Unknown asset pair")))));
      assertThrows(
          RateException.class,
          () ->
              priceOf(
                  RateSource.KRAKEN,
                  "CHF",
                  uri -> answer(Js.obj("error", List.of("EService:Unavailable")))));
      assertThrows(
          RateException.class,
          () ->
              priceOf(
                  RateSource.KRAKEN,
                  "CHF",
                  uri ->
                      answer(
                          Js.obj(
                              "error",
                              List.of(),
                              "result",
                              Js.obj(
                                  "A",
                                  Js.obj("c", List.of("1")),
                                  "B",
                                  Js.obj("c", List.of("2")))))));
    }

    @Test
    void knowsNoOtherName() {
      // checkout-node refuses an unknown name when it is asked; here no RateSource of one exists.
      for (String name : List.of("bitstamp", "CoinGecko", "KRAKEN", "custom", "", " kraken")) {
        assertEquals(Optional.empty(), RateSource.byName(name), name);
      }
      assertEquals(Optional.empty(), RateSource.byName(null));
    }

    @Test
    void aCustomSourceAStringOrADatedPrice() throws Exception {
      assertEquals(
          new RateInfo("custom", "CHF", "70000.5", NOW, null),
          priceOf(RateSource.custom(currency -> RateQuote.of("70000.5")), "CHF", NONE).info());
      Priced dated =
          priceOf(RateSource.custom(currency -> RateQuote.of("70000", NOW - 5)), "CHF", NONE);
      assertEquals(NOW - 5, dated.info().time());
      // checkout-node refuses numbers too: a quote's price is a String here.
      for (String bad : new String[] {"0", "-5", "abc", "Infinity", null}) {
        assertThrows(
            RateException.class,
            () -> priceOf(RateSource.custom(currency -> RateQuote.of(bad)), "CHF", NONE),
            String.valueOf(bad));
      }
      RateException none =
          assertThrows(
              RateException.class, () -> priceOf(RateSource.custom(currency -> null), "CHF", NONE));
      assertEquals("The custom rate source gave no price", none.getMessage());
      assertThrows(
          UnsupportedCurrencyException.class,
          () ->
              priceOf(
                  RateSource.custom(currency -> {
                    throw new UnsupportedCurrencyException(currency, "mine");
                  }),
                  "CHF",
                  NONE));
    }

    @Test
    void refusesAPriceOlderThanAnHourOrDatedInTheFuture() throws Exception {
      assertEquals(
          NOW - 3600,
          priceOf(RateSource.custom(currency -> RateQuote.of("69000", NOW - 3600)), "CHF", NONE)
              .info()
              .time());
      assertThrows(
          RateException.class,
          () ->
              priceOf(
                  RateSource.custom(currency -> RateQuote.of("69000", NOW - 3601)), "CHF", NONE));
      assertThrows(
          RateException.class,
          () ->
              priceOf(
                  RateSource.custom(currency -> RateQuote.of("69000", NOW + 3600)), "CHF", NONE));
    }

    @Test
    void anHttpFailureIsANetworkErrorRetryable() {
      NetworkException down =
          assertThrows(
              NetworkException.class,
              () -> priceOf(RateSource.COINBASE, "CHF", uri -> answer(Js.obj(), 503)));
      assertTrue(down.isRetryable());
      assertThrows(
          NetworkException.class,
          () ->
              priceOf(
                  RateSource.COINBASE,
                  "CHF",
                  uri -> {
                    throw new IOException("fetch failed");
                  }));
    }

    @Test
    void readsAnHttpStatusAsNetworkFrom500To599And408And429ElseAsRate() {
      // SPEC.md §26: 600 and above is no server error; a redirect is no answer (SPEC.md §15).
      for (int status : new int[] {500, 503, 599, 408, 429, 302, 301, 307}) {
        NetworkException error =
            assertThrows(
                NetworkException.class,
                () -> priceOf(RateSource.COINBASE, "CHF", uri -> answer(Js.obj(), status)),
                "HTTP " + status);
        assertTrue(error.isRetryable());
      }
      for (int status : new int[] {600, 601, 999, 400, 401, 403, 404, 410, 499, 204, 299}) {
        RateException error =
            assertThrows(
                RateException.class,
                () -> priceOf(RateSource.COINBASE, "CHF", uri -> answer(Js.obj(), status)),
                "HTTP " + status);
        assertTrue(error.isRetryable());
      }
    }
  }

  @Nested
  class ListsOfRateSources {
    private final RateSources.Context context =
        new RateSources.Context(new Http(new FakeHttp(NONE), 1000), null);

    @Test
    void keepsAListOfFunctionsAndNamesInItsOrderAFunctionAloneToo() {
      RateSource mine = RateSource.custom(currency -> RateQuote.of("1"));
      assertEquals(List.of(mine), RateSources.checkList(List.of(mine)));
      assertEquals(
          List.of(mine, RateSource.KRAKEN, mine),
          RateSources.checkList(List.of(mine, RateSource.KRAKEN, mine)));
      assertNull(RateSources.checkList(List.of(RateSource.KRAKEN, mine, RateSource.KRAKEN)));
      assertNull(RateSources.checkList(List.of()));
      List<RateSource> withNull = new ArrayList<>();
      withNull.add(null);
      assertNull(RateSources.checkList(withNull));
    }

    @Test
    void asksCustomSourcesOneAtATimeAndLogsEachFailureReplaced() throws Exception {
      List<String> asked = new CopyOnWriteArrayList<>();
      List<String> warnings = new CopyOnWriteArrayList<>();
      List<RateSource> sources =
          List.of(
              RateSource.custom(currency -> {
                asked.add("a");
                throw new NetworkException("down");
              }),
              RateSource.custom(currency -> {
                asked.add("b");
                throw new IllegalStateException("broken");
              }),
              RateSource.custom(currency -> {
                asked.add("c");
                return RateQuote.of("69012.50");
              }),
              RateSource.custom(currency -> {
                asked.add("d");
                return RateQuote.of("1");
              }));
      RateSources.Rate rate =
          RateSources.fetchFirstRate(
              sources, "CHF", context, () -> NOW, warnings::add, RateSources::fetchRate);
      assertEquals("custom", rate.info().source());
      assertEquals("69012.5", rate.info().price());
      assertEquals(List.of("a", "b", "c"), asked);
      assertEquals(
          List.of(
              "Rate source custom failed (network): down; trying custom",
              "Rate source custom failed (rate): The custom rate source failed; trying custom"),
          warnings);
    }

    @Test
    void stopsAtACancellationAndAtAnErrorOfAnotherKind() throws Exception {
      // checkout-node's signal: here the thread interrupted while the source ran.
      AtomicBoolean second = new AtomicBoolean();
      RateSource next =
          RateSource.custom(currency -> {
            second.set(true);
            return RateQuote.of("1");
          });
      List<RateSource> list =
          List.of(
              RateSource.custom(currency -> {
                Thread.currentThread().interrupt();
                throw new NetworkException("down");
              }),
              next);
      assertThrows(
          InterruptedException.class,
          () ->
              RateSources.fetchFirstRate(
                  list, "CHF", context, () -> NOW, QUIET, RateSources::fetchRate));
      assertFalse(Thread.interrupted());
      assertFalse(second.get());
      InvalidOrderException invalid = new InvalidOrderException("fiat", "not for this shop");
      InvalidOrderException thrown =
          assertThrows(
              InvalidOrderException.class,
              () ->
                  RateSources.fetchFirstRate(
                      List.of(
                          RateSource.custom(currency -> {
                            throw invalid;
                          }),
                          next),
                      "CHF",
                      context,
                      () -> NOW,
                      QUIET,
                      RateSources::fetchRate));
      assertSame(invalid, thrown);
      assertFalse(second.get());
      // A source that throws InterruptedException itself is a cancellation too.
      assertThrows(
          InterruptedException.class,
          () ->
              RateSources.fetchFirstRate(
                  List.of(
                      RateSource.custom(currency -> {
                        throw new InterruptedException();
                      }),
                      next),
                  "CHF",
                  context,
                  () -> NOW,
                  QUIET,
                  RateSources::fetchRate));
      assertFalse(second.get());
    }

    @Test
    void keepsASingleSourcesOwnError() {
      UnsupportedCurrencyException own = new UnsupportedCurrencyException("XAF", "mine");
      UnsupportedCurrencyException thrown =
          assertThrows(
              UnsupportedCurrencyException.class,
              () ->
                  RateSources.fetchFirstRate(
                      List.of(
                          RateSource.custom(currency -> {
                            throw own;
                          })),
                      "XAF",
                      context,
                      () -> NOW,
                      QUIET,
                      RateSources::fetchRate));
      assertSame(own, thrown);
    }
  }
}
