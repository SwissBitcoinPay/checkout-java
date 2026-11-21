package me.sattle.checkout.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import me.sattle.checkout.CustomRateSource;
import me.sattle.checkout.NetworkException;
import me.sattle.checkout.RateException;
import me.sattle.checkout.RateInfo;
import me.sattle.checkout.RateQuote;
import me.sattle.checkout.RateSource;
import me.sattle.checkout.SattlemeException;
import me.sattle.checkout.UnsupportedCurrencyException;
import me.sattle.checkout.internal.json.Json;
import me.sattle.checkout.internal.json.JsonArray;
import me.sattle.checkout.internal.json.JsonNumber;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonString;
import me.sattle.checkout.internal.json.JsonValue;

/**
 * The rate sources (SPEC.md §26): each gives the price of one bitcoin in a currency, checked (§25);
 * a list is asked one source at a time, in order, the next one only when the previous one gives no
 * usable price.
 */
public final class RateSources {
  private RateSources() {}

  /** What the rule of a client's or an order's {@code rateSource} says. */
  public static final String RULE =
      "\"coingecko\", \"mempool\", \"opennode\", \"coinbase\", \"kraken\" or a custom source, or a"
          + " non-empty list of them without the same name twice";

  /**
   * A price of one bitcoin, checked, and its RateInfo.
   *
   * @param price the exact decimal
   * @param info what the Invoice tells of it
   */
  public record Rate(Prices.Decimal price, RateInfo info) {}

  /**
   * What a source may use.
   *
   * @param http the HTTP client of the call
   * @param coingeckoApiKey CoinGecko's Demo key, for CoinGecko alone; or null
   */
  public record Context(Http http, String coingeckoApiKey) {
    @Override
    public String toString() {
      return "Context";
    }
  }

  /** A source's answer: its price's text, and when it was set (any JSON number), or null. */
  private record Quote(String price, Double time) {}

  /** How a list asks one source (the conformance vectors replace it). */
  @FunctionalInterface
  public interface Ask {
    /**
     * The checked price of {@code source}.
     *
     * @param source the source
     * @param currency the currency
     * @param context what it may use
     * @param now Unix seconds
     * @return the rate
     * @throws InterruptedException when the call is cancelled
     */
    Rate ask(RateSource source, String currency, Context context, long now)
        throws InterruptedException;
  }

  /**
   * The sources {@code sources} names, in the order they are asked: 1 or more, none null, a name at
   * most once.
   *
   * @param sources the sources as given
   * @return a copy, or null when they are not a valid list
   */
  public static List<RateSource> checkList(List<RateSource> sources) {
    if (sources == null || sources.isEmpty()) return null;
    Set<String> names = new HashSet<>();
    List<RateSource> list = new ArrayList<>();
    for (RateSource source : sources) {
      if (source == null) return null;
      if (!source.isCustom() && !names.add(source.name())) return null;
      list.add(source);
    }
    return List.copyOf(list);
  }

  /**
   * The price of one bitcoin in {@code currency} from {@code source}, checked (SPEC.md §25): an
   * exact positive decimal, not older than 3600 s, not further ahead than 300 s.
   *
   * @param source the source
   * @param currency the currency
   * @param context what it may use
   * @param now Unix seconds
   * @return the rate
   * @throws InterruptedException when the call is cancelled
   * @throws UnsupportedCurrencyException when the source has no price in the currency
   * @throws NetworkException when it does not answer
   * @throws RateException for anything else
   */
  public static Rate fetchRate(RateSource source, String currency, Context context, long now)
      throws InterruptedException {
    String name = source.name();
    Quote quote =
        source.isCustom()
            ? custom(source.customSource(), currency)
            : named(name, currency, context);
    Prices.Decimal price = Prices.parseDecimal(quote.price());
    if (price == null) throw new RateException(name + " gave no valid price in " + currency);
    Long time = null;
    if (quote.time() != null) {
      double value = quote.time();
      if (!new JsonNumber(value).isSafeInteger()
          || value > now + Constants.CLOCK_TOLERANCE_SECONDS) {
        throw new RateException(name + " gave an invalid date for its price");
      }
      time = (long) value;
      if (now - time > Constants.MAX_RATE_AGE_SECONDS) {
        throw new RateException(name + "'s price in " + currency + " is too old");
      }
    }
    return new Rate(price, new RateInfo(name, currency, Prices.formatDecimal(price), now, time));
  }

  /**
   * The first usable price of {@code sources}, asked one at a time in their order (SPEC.md §26):
   * the next one only when the previous one gives no usable price ({@code network}, {@code rate} or
   * {@code unsupported_currency}, each logged when another source follows). A cancellation, or any
   * other error a custom source throws, stops the list. Without a price: {@code
   * unsupported_currency} when every source raised it, else the last {@code network} or {@code
   * rate}.
   *
   * @param sources the sources
   * @param currency the currency
   * @param context what they may use
   * @param now the clock, Unix seconds
   * @param logger where the failures replaced go
   * @param ask how a source is asked
   * @return the rate
   * @throws InterruptedException when the call is cancelled
   */
  public static Rate fetchFirstRate(
      List<RateSource> sources,
      String currency,
      Context context,
      LongSupplier now,
      Warn logger,
      Ask ask)
      throws InterruptedException {
    SattlemeException failure = null;
    UnsupportedCurrencyException unsupported = null;
    for (int index = 0; index < sources.size(); index += 1) {
      RateSource source = sources.get(index);
      try {
        return ask.ask(source, currency, context, now.getAsLong());
      } catch (UnsupportedCurrencyException e) {
        unsupported = e;
        warnNext(logger, sources, index, e);
      } catch (NetworkException | RateException e) {
        failure = e;
        warnNext(logger, sources, index, e);
      }
    }
    if (failure != null) throw failure;
    if (sources.size() > 1) {
      List<String> names = new ArrayList<>();
      for (RateSource source : sources) names.add(source.name());
      String list = String.join(", ", names);
      throw new UnsupportedCurrencyException(
          currency, list, "None of " + list + " has a price in " + currency);
    }
    throw unsupported;
  }

  private static void warnNext(
      Warn logger, List<RateSource> sources, int index, SattlemeException e) {
    if (index + 1 < sources.size()) {
      logger.warn(
          "Rate source "
              + sources.get(index).name()
              + " failed ("
              + e.getCode()
              + "): "
              + e.getMessage()
              + "; trying "
              + sources.get(index + 1).name());
    }
  }

  /**
   * A custom source's price: an error of the package as it is, any other failure a RateException.
   */
  private static Quote custom(CustomRateSource source, String currency)
      throws InterruptedException {
    RateQuote answer;
    try {
      answer = source.price(currency);
    } catch (InterruptedException e) {
      throw e;
    } catch (Exception e) {
      if (Thread.interrupted()) {
        InterruptedException cancelled = new InterruptedException("The call was cancelled");
        cancelled.initCause(e);
        throw cancelled;
      }
      if (e instanceof SattlemeException) throw (SattlemeException) e;
      throw new RateException("The custom rate source failed", e);
    }
    if (Thread.interrupted()) throw new InterruptedException("The call was cancelled");
    if (answer == null) throw new RateException("The custom rate source gave no price");
    return new Quote(answer.price(), answer.time() == null ? null : (double) (long) answer.time());
  }

  private static Quote named(String name, String currency, Context context)
      throws InterruptedException {
    switch (name) {
      case "coingecko":
        return coingecko(currency, context);
      case "mempool":
        return mempool(currency, context);
      case "opennode":
        return opennode(currency, context);
      case "coinbase":
        return coinbase(currency, context);
      case "kraken":
        return kraken(currency, context);
      default:
        throw new RateException("No rate source named " + name);
    }
  }

  /** GET a source's JSON object: NetworkException without an answer, RateException otherwise. */
  private static JsonObject sourceJson(
      String name, String url, Context context, Map<String, String> headers)
      throws InterruptedException {
    Http.Answer answer = Tasks.await(context.http().get(url, headers));
    if (!answer.ok()) {
      int status = answer.status();
      // A server error, a timeout, a rate limit: no answer. Anything else, 600 and above
      // included, is no price (SPEC.md §26).
      if ((status >= 500 && status <= 599) || status == 408 || status == 429) {
        throw new NetworkException(name + " answered HTTP " + status);
      }
      throw new RateException(name + " answered HTTP " + status);
    }
    JsonObject body = Json.object(answer.json());
    if (body == null) throw new RateException(name + " gave no JSON object");
    return body;
  }

  private static JsonObject objectAt(JsonValue value, String name) {
    JsonObject object = Json.object(value);
    if (object == null) throw new RateException(name + ": unexpected answer");
    return object;
  }

  /** A source's price: a string as written, a JSON number as its shortest decimal. */
  private static String priceOf(JsonValue value, String name, String currency) {
    if (value == null) throw new UnsupportedCurrencyException(currency, name);
    String text =
        value instanceof JsonString
            ? ((JsonString) value).value()
            : value instanceof JsonNumber ? Prices.numberText(((JsonNumber) value).value()) : null;
    if (text == null) throw new RateException(name + ": unexpected price");
    return text;
  }

  private static Quote coingecko(String currency, Context context) throws InterruptedException {
    String code = Text.asciiLower(currency);
    String url =
        "https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies="
            + code
            + "&include_last_updated_at=true";
    Map<String, String> headers =
        context.coingeckoApiKey() == null
            ? Map.of()
            : Map.of("x-cg-demo-api-key", context.coingeckoApiKey());
    JsonObject body = sourceJson("coingecko", url, context, headers);
    JsonObject bitcoin = objectAt(body.get("bitcoin"), "coingecko");
    JsonValue time = bitcoin.get("last_updated_at");
    return new Quote(
        priceOf(bitcoin.get(code), "coingecko", currency),
        time instanceof JsonNumber ? ((JsonNumber) time).value() : null);
  }

  private static Quote mempool(String currency, Context context) throws InterruptedException {
    if (!Constants.MEMPOOL_CURRENCIES.contains(currency)) {
      throw new UnsupportedCurrencyException(currency, "mempool");
    }
    JsonObject body =
        sourceJson("mempool", "https://mempool.space/api/v1/prices", context, Map.of());
    JsonValue time = body.get("time");
    if (!(time instanceof JsonNumber)) throw new RateException("mempool: no time");
    return new Quote(priceOf(body.get(currency), "mempool", currency), ((JsonNumber) time).value());
  }

  private static Quote opennode(String currency, Context context) throws InterruptedException {
    JsonObject body =
        sourceJson("opennode", "https://api.opennode.com/v1/rates", context, Map.of());
    JsonObject data = objectAt(body.get("data"), "opennode");
    JsonValue pair = data.get("BTC" + currency);
    if (pair == null) throw new UnsupportedCurrencyException(currency, "opennode");
    return new Quote(priceOf(objectAt(pair, "opennode").get(currency), "opennode", currency), null);
  }

  private static Quote coinbase(String currency, Context context) throws InterruptedException {
    JsonObject body =
        sourceJson(
            "coinbase",
            "https://api.coinbase.com/v2/exchange-rates?currency=BTC",
            context,
            Map.of());
    JsonObject data = objectAt(body.get("data"), "coinbase");
    if (!"BTC".equals(Json.string(data.get("currency")))) {
      throw new RateException("coinbase: not the rates of BTC");
    }
    return new Quote(
        priceOf(objectAt(data.get("rates"), "coinbase").get(currency), "coinbase", currency), null);
  }

  private static Quote kraken(String currency, Context context) throws InterruptedException {
    JsonObject body =
        sourceJson(
            "kraken",
            "https://api.kraken.com/0/public/Ticker?pair=XBT" + currency,
            context,
            Map.of());
    JsonArray errors = Json.array(body.get("error"));
    if (errors == null) throw new RateException("kraken: unexpected answer");
    for (JsonValue error : errors.items()) {
      String text = Json.string(error);
      if (text != null && text.startsWith("EQuery:Unknown asset pair")) {
        throw new UnsupportedCurrencyException(currency, "kraken");
      }
    }
    if (errors.size() > 0) throw new RateException("kraken: " + javascriptString(errors.get(0)));
    JsonObject result = objectAt(body.get("result"), "kraken");
    if (result.size() != 1) throw new RateException("kraken: unexpected answer");
    JsonValue entry = result.entries().iterator().next().getValue();
    JsonArray last = Json.array(objectAt(entry, "kraken").get("c"));
    if (last == null) throw new RateException("kraken: unexpected answer");
    // c[0] of an empty array is absent, as JavaScript reads it.
    return new Quote(priceOf(last.size() > 0 ? last.get(0) : null, "kraken", currency), null);
  }

  /** {@code String(value)} of a JSON value, for a message: its excerpt (Text.excerpt). */
  private static String javascriptString(JsonValue value) {
    String text = Json.string(value);
    return Text.excerpt(text != null ? text : Json.write(value));
  }
}
