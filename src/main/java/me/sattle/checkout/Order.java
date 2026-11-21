package me.sattle.checkout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An order (SPEC.md §17): its reference, and its amount in sats or its price in a currency. Made
 * with {@link #builder(String)}; nothing is checked until {@link SattlemeClient#createInvoice},
 * which checks it before any request ({@link InvalidOrderException} naming the parameter).
 *
 * <pre>{@code
 * Order order = Order.builder("order-42").fiat("24.50", "CHF").note("Two coffees").build();
 * }</pre>
 */
public final class Order {
  private final String ref;
  private final String sats;
  private final String fiatAmount;
  private final String fiatCurrency;
  private final boolean fiat;
  private final List<RateSource> rateSources;
  private final Integer durationSeconds;
  private final String note;
  private final boolean checkout;
  private final String redirectUrl;

  private Order(Builder builder) {
    this.ref = builder.ref;
    this.sats = builder.sats;
    this.fiatAmount = builder.fiatAmount;
    this.fiatCurrency = builder.fiatCurrency;
    this.fiat = builder.fiat;
    this.rateSources =
        builder.rateSources == null
            ? null
            : Collections.unmodifiableList(new ArrayList<>(builder.rateSources));
    this.durationSeconds = builder.durationSeconds;
    this.note = builder.note;
    this.checkout = builder.checkout;
    this.redirectUrl = builder.redirectUrl;
  }

  /**
   * A new order.
   *
   * @param ref the order's reference: 1 to 64 characters {@code A–Z a–z 0–9 . _ : -}. One order,
   *     one sale: never reused for another order by anything that holds the key, never taken from
   *     what the customer sends
   * @return its builder
   */
  public static Builder builder(String ref) {
    return new Builder(ref);
  }

  /**
   * The order's reference.
   *
   * @return the reference, as given
   */
  public String ref() {
    return ref;
  }

  /**
   * The amount in sats, as given.
   *
   * @return its digits, or null when the order is priced in a currency (or has no amount)
   */
  public String sats() {
    return sats;
  }

  /**
   * Whether the order is priced in a currency ({@link Builder#fiat}).
   *
   * @return true with a price in a currency
   */
  public boolean hasFiat() {
    return fiat;
  }

  /**
   * The price in a currency, as given.
   *
   * @return its amount ({@code "24.50"}), or null
   */
  public String fiatAmount() {
    return fiatAmount;
  }

  /**
   * The currency of the price.
   *
   * @return its ISO 4217 code, or null
   */
  public String fiatCurrency() {
    return fiatCurrency;
  }

  /**
   * The order's own rate sources, asked in order.
   *
   * @return the sources as given, or null when it has none (the client's are asked)
   */
  public List<RateSource> rateSources() {
    return rateSources;
  }

  /**
   * Seconds the invoice can be paid.
   *
   * @return the duration as given, or null for the default (900)
   */
  public Integer durationSeconds() {
    return durationSeconds;
  }

  /**
   * What the merchant's app shows.
   *
   * @return the note as given, or null (the reference is shown)
   */
  public String note() {
    return note;
  }

  /**
   * Whether the invoice gets a checkout link.
   *
   * @return true for a checkout link
   */
  public boolean checkout() {
    return checkout;
  }

  /**
   * Where the checkout link sends the customer once the invoice is paid.
   *
   * @return the URL as given, or null
   */
  public String redirectUrl() {
    return redirectUrl;
  }

  @Override
  public String toString() {
    return "Order[ref=" + ref + "]";
  }

  /** Builds an {@link Order}. */
  public static final class Builder {
    private final String ref;
    private String sats;
    private String fiatAmount;
    private String fiatCurrency;
    private boolean fiat;
    private List<RateSource> rateSources;
    private Integer durationSeconds;
    private String note;
    private boolean checkout;
    private String redirectUrl;

    private Builder(String ref) {
      this.ref = ref;
    }

    /**
     * The amount in sats, from 1 to 2 100 000 000 000 000 (21 million bitcoins). Give it or {@link
     * #fiat}, not both.
     *
     * @param sats the amount
     * @return this builder
     */
    public Builder sats(long sats) {
      this.sats = Long.toString(sats);
      return this;
    }

    /**
     * The amount in sats, as a string of 1 to 18 digits.
     *
     * @param sats the amount
     * @return this builder
     */
    public Builder sats(String sats) {
      this.sats = sats;
      return this;
    }

    /**
     * The price in a currency, converted at the rate of the order's rate sources (else the
     * client's): an amount of digits with at most the currency's decimals ({@link Currencies}),
     * never a float.
     *
     * @param amount the amount: {@code "24.50"}
     * @param currency an ISO 4217 code in upper case: {@code "CHF"}
     * @return this builder
     */
    public Builder fiat(String amount, String currency) {
      this.fiat = true;
      this.fiatAmount = amount;
      this.fiatCurrency = currency;
      return this;
    }

    /**
     * The rate source of this order (with {@link #fiat} only).
     *
     * @param source the source
     * @return this builder
     */
    public Builder rateSource(RateSource source) {
      List<RateSource> list = new ArrayList<>();
      list.add(source);
      this.rateSources = list;
      return this;
    }

    /**
     * The rate sources of this order (with {@link #fiat} only), asked one at a time in order: 1 or
     * more, a name at most once.
     *
     * @param sources the sources
     * @return this builder
     */
    public Builder rateSources(List<RateSource> sources) {
      this.rateSources =
          sources == null ? Collections.singletonList(null) : new ArrayList<>(sources);
      return this;
    }

    /**
     * Seconds the invoice can be paid: 60 to 3600, 900 when not given.
     *
     * @param durationSeconds the duration
     * @return this builder
     */
    public Builder durationSeconds(int durationSeconds) {
      this.durationSeconds = durationSeconds;
      return this;
    }

    /**
     * What the merchant's app shows (and the customer, on a checkout link): 100 characters at most
     * once its spaces around are removed, no control characters; the reference when not given.
     *
     * @param note the note
     * @return this builder
     */
    public Builder note(String note) {
      this.note = note;
      return this;
    }

    /**
     * Asks for a checkout link ({@link Invoice#checkoutUrl()}): a page of the merchant's web app
     * where the customer pays the invoice.
     *
     * @param checkout true for a checkout link
     * @return this builder
     */
    public Builder checkout(boolean checkout) {
      this.checkout = checkout;
      return this;
    }

    /**
     * With {@link #checkout} only: where the checkout link sends the customer once the invoice is
     * paid, an https URL or an app's link ({@code myshop://order/42}). Not a proof of payment.
     *
     * @param redirectUrl the URL, or null for none
     * @return this builder
     */
    public Builder redirectUrl(String redirectUrl) {
      this.redirectUrl = redirectUrl;
      return this;
    }

    /**
     * The order.
     *
     * @return the order, unchecked
     */
    public Order build() {
      return new Order(this);
    }
  }
}
