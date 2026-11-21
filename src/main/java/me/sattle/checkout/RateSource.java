package me.sattle.checkout;

import java.util.List;
import java.util.Optional;

/**
 * Where an invoice priced in a currency gets the price of one bitcoin (SPEC.md §26): a source the
 * package knows by name, or the integrator's own ({@link #custom}). A client or an order takes one,
 * or a list of them asked in order, the next one only when the previous one gives no usable price.
 */
public final class RateSource {
  /** CoinGecko ({@code /simple/price}, dated). Give it a Demo key: {@code coingeckoApiKey}. */
  public static final RateSource COINGECKO = new RateSource("coingecko", null);

  /** mempool.space ({@code /api/v1/prices}, dated): USD, EUR, GBP, CAD, CHF, AUD and JPY. */
  public static final RateSource MEMPOOL = new RateSource("mempool", null);

  /** OpenNode ({@code /v1/rates}, undated). */
  public static final RateSource OPENNODE = new RateSource("opennode", null);

  /** Coinbase ({@code /v2/exchange-rates}, undated). */
  public static final RateSource COINBASE = new RateSource("coinbase", null);

  /** Kraken ({@code /0/public/Ticker}, undated): the last trade's price. */
  public static final RateSource KRAKEN = new RateSource("kraken", null);

  /**
   * The sources a client given none asks, in this order: the dated ones first (their price's age is
   * checked), CoinGecko for its currencies, then mempool.space for its 7; then the undated ones,
   * Coinbase and Kraken (two exchanges), and OpenNode.
   */
  public static final List<RateSource> DEFAULT_LIST =
      List.of(COINGECKO, MEMPOOL, COINBASE, KRAKEN, OPENNODE);

  /** The sources the package knows by name. */
  private static final List<RateSource> NAMED =
      List.of(COINGECKO, MEMPOOL, OPENNODE, COINBASE, KRAKEN);

  private final String name;
  private final CustomRateSource custom;

  private RateSource(String name, CustomRateSource custom) {
    this.name = name;
    this.custom = custom;
  }

  /**
   * The integrator's own source.
   *
   * @param source the source
   * @return it, as a rate source
   * @throws InvalidOptionException ({@code rateSource}) when {@code source} is null
   */
  public static RateSource custom(CustomRateSource source) {
    if (source == null) {
      throw new InvalidOptionException("rateSource", "a custom source is a CustomRateSource");
    }
    return new RateSource("custom", source);
  }

  /**
   * The source the package knows by {@code name}: {@code "coingecko"}, {@code "mempool"}, {@code
   * "opennode"}, {@code "coinbase"} or {@code "kraken"}, in that case ({@code "custom"} is no name:
   * a custom source is a function).
   *
   * @param name the name
   * @return the source, or empty for any other name
   */
  public static Optional<RateSource> byName(String name) {
    for (RateSource source : NAMED) {
      if (source.name.equals(name)) return Optional.of(source);
    }
    return Optional.empty();
  }

  /**
   * Its name, as RateInfo's {@code source} gives it.
   *
   * @return its name, or {@code "custom"}
   */
  public String name() {
    return name;
  }

  /**
   * Whether it is the integrator's own.
   *
   * @return true for a custom source
   */
  public boolean isCustom() {
    return custom != null;
  }

  /**
   * The integrator's own source.
   *
   * @return it, or null for a named source
   */
  public CustomRateSource customSource() {
    return custom;
  }

  @Override
  public String toString() {
    return "RateSource[" + name + "]";
  }
}
