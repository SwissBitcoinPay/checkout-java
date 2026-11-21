package me.sattle.checkout;

import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import me.sattle.checkout.internal.Constants;
import me.sattle.checkout.internal.Engine;
import me.sattle.checkout.internal.Http;
import me.sattle.checkout.internal.IntegrationKey;
import me.sattle.checkout.internal.JdkWebSockets;
import me.sattle.checkout.internal.Orders;
import me.sattle.checkout.internal.RandomBytes;
import me.sattle.checkout.internal.RateSources;
import me.sattle.checkout.internal.Tasks;
import me.sattle.checkout.internal.Warn;

/**
 * A shop's server for one sattle.me integration (SPEC.md §4): makes the invoice of each order (a
 * Lightning invoice, a deposit address of the merchant's wallet, or both), declares it in the
 * merchant's registry, checks its payment. Made for a server that may live for one call: nothing is
 * kept between calls, each call reads the merchant again (a removal stops the next invoice) and
 * closes its connections; there is nothing to close.
 *
 * <pre>{@code
 * SattlemeClient client =
 *     SattlemeClient.builder().key(System.getenv("SATTLEME_INTEGRATION_KEY")).build();
 * Invoice invoice = client.createInvoice(Order.builder("order-42").sats(49_000).build());
 * }</pre>
 *
 * <p>Every method blocks the calling thread until it is done, and has an asynchronous twin ({@code
 * …Async}) that runs it on a thread of its own. A cancelled call stops its requests and fails with
 * the platform's cancellation, never with a {@link SattlemeException}: an interrupted thread makes
 * a blocking method throw {@link InterruptedException}; cancelling the future of an asynchronous
 * one ({@link CompletableFuture#cancel}) interrupts its thread, and the future ends with a {@link
 * java.util.concurrent.CancellationException}. A client is safe to share between threads: within
 * one client, calls for the same order run one after the other.
 */
public final class SattlemeClient {
  // Transient, as every field that leads to the key: the serializers an application calls on what
  // it logs or returns (Gson, Jackson's fields, Java's serialization, reflective toString) skip it.
  private final transient Engine engine;
  private final transient List<RateSource> rateSources;

  private SattlemeClient(Engine engine, List<RateSource> rateSources) {
    this.engine = engine;
    this.rateSources = rateSources;
  }

  /**
   * A new client.
   *
   * @return its builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * The merchant: its business's name, the bounds of its Lightning address in sats, and how many
   * deposit addresses the integration's lot holds. Never its Lightning address.
   *
   * @return the merchant
   * @throws InterruptedException when the calling thread is interrupted
   * @throws RevokedException when the merchant removed the integration
   * @throws SattlemeException for any other failure (SPEC.md §5)
   */
  public Merchant merchant() throws InterruptedException {
    return cancellable(engine::merchant);
  }

  /**
   * {@link #merchant()} on a thread of its own.
   *
   * @return the merchant; cancelling the future cancels the call
   */
  public CompletableFuture<Merchant> merchantAsync() {
    return Tasks.async(this::merchant);
  }

  /**
   * The invoice of an order (SPEC.md §17): a Lightning invoice asked to the merchant's Lightning
   * address and checked, when its amount is within the address's bounds; a deposit address of the
   * merchant's wallet leased from the integration's lot, from 1 000 sats (or both). Then the
   * invoice is declared in the merchant's registry on a majority of its relays. Only then may it be
   * shown. Store the result with the order ({@link Invoice#toJson()}).
   *
   * <p>One order, one invoice at a time: {@link InvoicePendingException} (with that invoice) while
   * an earlier invoice of the same {@code ref} may still be paid (or its on-chain payment is being
   * confirmed), {@link OrderPaidException} when one was paid. With {@code checkout(true)}, the
   * invoice also gets a checkout link ({@link Invoice#checkoutUrl()}).
   *
   * @param order the order
   * @return the invoice
   * @throws InterruptedException when the calling thread is interrupted
   * @throws InvalidOrderException when a parameter of the order is invalid (before any request)
   * @throws SattlemeException for any other failure (SPEC.md §5)
   */
  public Invoice createInvoice(Order order) throws InterruptedException {
    return cancellable(() -> engine.createInvoice(Orders.checkOrder(order, rateSources)));
  }

  /**
   * {@link #createInvoice(Order)} on a thread of its own.
   *
   * @param order the order
   * @return the invoice; cancelling the future cancels the call
   */
  public CompletableFuture<Invoice> createInvoiceAsync(Order order) {
    Orders.CheckedOrder checked;
    try {
      checked = Orders.checkOrder(order, rateSources);
    } catch (SattlemeException e) {
      return CompletableFuture.failedFuture(e);
    }
    return Tasks.async(() -> engine.createInvoice(checked));
  }

  /**
   * Where the payment of an invoice is (SPEC.md §22, §31), checked: its Lightning part (LUD-21),
   * its on-chain part (the explorer, mempool.space or the merchant's Bitcoin node with its
   * fallback, and the witnesses of its order), and the Lightning invoices of the rest of an
   * on-chain underpayment, all at once. Underpaid on-chain, it asks for an invoice of the rest (a
   * failure is logged; the next check asks again). It never opens the explorer's WebSocket.
   *
   * @param invoice the invoice, as {@link #createInvoice} gave it, or as stored ({@link
   *     Invoice#fromJson}, {@link InvoiceToCheck#fromJson})
   * @return its status
   * @throws InterruptedException when the calling thread is interrupted
   * @throws InvalidOrderException ({@code invoice}) when it is not an invoice made by createInvoice
   * @throws NetworkException when a service does not answer (retry)
   * @throws InvalidInvoiceException when an answer is not about this invoice, or its preimage is
   *     wrong
   */
  public PaymentStatus getStatus(InvoiceToCheck invoice) throws InterruptedException {
    return cancellable(() -> engine.getStatus(Orders.checkStoredInvoice(invoice)));
  }

  /**
   * {@link #getStatus(InvoiceToCheck)} on a thread of its own.
   *
   * @param invoice the invoice
   * @return its status; cancelling the future cancels the call
   */
  public CompletableFuture<PaymentStatus> getStatusAsync(InvoiceToCheck invoice) {
    Orders.StoredInvoice stored;
    try {
      stored = Orders.checkStoredInvoice(invoice);
    } catch (SattlemeException e) {
      return CompletableFuture.failedFuture(e);
    }
    return Tasks.async(() -> engine.getStatus(stored));
  }

  /**
   * {@link #waitForPayment(InvoiceToCheck, WaitOptions)} with the defaults.
   *
   * @param invoice the invoice
   * @return its final status: {@link PaymentStatus.Paid} or {@link PaymentStatus.Expired}
   * @throws InterruptedException when the calling thread is interrupted
   */
  public PaymentStatus waitForPayment(InvoiceToCheck invoice) throws InterruptedException {
    return waitForPayment(invoice, WaitOptions.defaults());
  }

  /**
   * Checks the payment until it is paid or expired (SPEC.md §23): its Lightning part, and the
   * invoices of the rest of an on-chain underpayment, every {@code intervalMs}; its on-chain part
   * (with an address) each time the explorer's WebSocket (mempool.space's, or the merchant's
   * Bitcoin node's with its fallback) says something changed for the address, every 60 s at least,
   * and at once when an invoice of the rest is paid. Network errors are retried (one that each
   * check meets again logged once); {@code onStatus} hears each status. An on-chain payment takes
   * about 30 minutes to be paid once unconfirmed: a server that cannot wait that long checks again
   * later with {@link #getStatus}.
   *
   * @param invoice the invoice
   * @param options how it waits
   * @return its final status: {@link PaymentStatus.Paid} or {@link PaymentStatus.Expired}
   * @throws InterruptedException when the calling thread is interrupted
   * @throws InvalidOptionException when an option is invalid
   * @throws InvalidOrderException ({@code invoice}) when it is not an invoice made by createInvoice
   * @throws SattlemeException any error that is not a network one
   */
  public PaymentStatus waitForPayment(InvoiceToCheck invoice, WaitOptions options)
      throws InterruptedException {
    return cancellable(() -> {
      Waiting waiting = waiting(invoice, options);
      return engine.waitForPayment(waiting.stored(), waiting.interval(), waiting.onStatus());
    });
  }

  /**
   * {@link #waitForPayment(InvoiceToCheck, WaitOptions)} on a thread of its own.
   *
   * @param invoice the invoice
   * @param options how it waits
   * @return its final status; cancelling the future cancels the call
   */
  public CompletableFuture<PaymentStatus> waitForPaymentAsync(
      InvoiceToCheck invoice, WaitOptions options) {
    Waiting waiting;
    try {
      waiting = waiting(invoice, options);
    } catch (SattlemeException e) {
      return CompletableFuture.failedFuture(e);
    }
    return Tasks.async(
        () -> engine.waitForPayment(waiting.stored(), waiting.interval(), waiting.onStatus()));
  }

  /** A call of the client, on the calling thread. */
  @FunctionalInterface
  private interface Call<T> {
    T run() throws InterruptedException;
  }

  /**
   * Runs {@code call}: interrupted meanwhile, it fails with {@link InterruptedException}, even when
   * a step it was blocked in (the shop's code, a lookup) ended with an error of SPEC.md §5 after
   * the interrupt (SPEC.md §4: a cancellation is never such an error).
   */
  private static <T> T cancellable(Call<T> call) throws InterruptedException {
    try {
      return call.run();
    } catch (SattlemeException e) {
      if (Thread.interrupted()) {
        InterruptedException cancelled = new InterruptedException("The call was cancelled");
        cancelled.initCause(e);
        throw cancelled;
      }
      throw e;
    }
  }

  private record Waiting(
      Orders.StoredInvoice stored,
      long interval,
      java.util.function.Consumer<PaymentStatus> onStatus) {}

  private static Waiting waiting(InvoiceToCheck invoice, WaitOptions options) {
    if (options == null) throw new InvalidOptionException("options", "WaitOptions, or none");
    long interval =
        Orders.checkMilliseconds(
            options.intervalMs(),
            Constants.DEFAULT_INTERVAL_MS,
            Constants.MIN_INTERVAL_MS,
            Constants.MAX_INTERVAL_MS,
            "intervalMs");
    return new Waiting(Orders.checkStoredInvoice(invoice), interval, options.onStatus());
  }

  /**
   * The invoices declared for the order {@code ref} in the merchant's registry, oldest first
   * (SPEC.md §24): to find an order's invoice again after a failure. Read on a majority of the
   * merchant's relays.
   *
   * @param ref the order's reference
   * @return its invoices
   * @throws InterruptedException when the calling thread is interrupted
   * @throws InvalidOrderException ({@code ref}) when it is not a reference
   * @throws NetworkException when fewer than a majority of the relays answered
   */
  public List<DeclaredInvoice> findInvoices(String ref) throws InterruptedException {
    return cancellable(() -> engine.findInvoices(Orders.checkRef(ref)));
  }

  /**
   * {@link #findInvoices(String)} on a thread of its own.
   *
   * @param ref the order's reference
   * @return its invoices; cancelling the future cancels the call
   */
  public CompletableFuture<List<DeclaredInvoice>> findInvoicesAsync(String ref) {
    String checked;
    try {
      checked = Orders.checkRef(ref);
    } catch (SattlemeException e) {
      return CompletableFuture.failedFuture(e);
    }
    return Tasks.async(() -> engine.findInvoices(checked));
  }

  /** The integration key's team and relays: never the key. */
  @Override
  public String toString() {
    return "SattlemeClient[" + engine.describe() + "]";
  }

  /**
   * Builds a {@link SattlemeClient} (SPEC.md §4, "Construction"): an invalid option fails {@link
   * #build()} with {@link InvalidOptionException} naming it, before any request.
   */
  public static final class Builder {
    private static final Pattern VISIBLE_ASCII = Pattern.compile("[\\x21-\\x7e]+");

    // Transient, as the client's: the key and the CoinGecko key among them.
    private transient String key;
    private transient boolean rateSourceGiven;
    private transient List<RateSource> rateSources;
    private transient String coingeckoApiKey;
    private transient boolean httpClientGiven;
    private transient HttpClient httpClient;
    private transient boolean webSocketFactoryGiven;
    private transient WebSocketFactory webSocketFactory;
    private transient Long relayTimeoutMs;
    private transient Long httpTimeoutMs;
    private transient boolean loggerGiven;
    private transient System.Logger logger;
    private transient boolean clockGiven;
    private transient Clock clock;
    private transient boolean randomGiven;
    private transient SecureRandom random;

    private Builder() {}

    /**
     * The integration's secret key ({@code sattleme_ck_…}, "Integrations" in the app): from the
     * environment or a vault. Required.
     *
     * @param key the key
     * @return this builder
     */
    public Builder key(String key) {
      this.key = key;
      return this;
    }

    /**
     * Where invoices priced in a currency get their rate; {@link RateSource#DEFAULT_LIST} when not
     * given. Each order may give its own.
     *
     * @param source the source
     * @return this builder
     */
    public Builder rateSource(RateSource source) {
      List<RateSource> list = new ArrayList<>();
      list.add(source);
      return rateSources(list);
    }

    /**
     * The rate sources, asked one at a time in order, the next one only when the previous one gives
     * no usable price: 1 or more, a name at most once.
     *
     * @param sources the sources
     * @return this builder
     */
    public Builder rateSources(List<RateSource> sources) {
      this.rateSourceGiven = true;
      this.rateSources = sources == null ? null : new ArrayList<>(sources);
      return this;
    }

    /**
     * CoinGecko's Demo API key, sent to CoinGecko only: 1 or more visible ASCII characters. Without
     * it, its keyless API.
     *
     * @param coingeckoApiKey the key
     * @return this builder
     */
    public Builder coingeckoApiKey(String coingeckoApiKey) {
      this.coingeckoApiKey = coingeckoApiKey == null ? "" : coingeckoApiKey;
      return this;
    }

    /**
     * The HTTP client (a proxy, a test): it must follow no redirect ({@link
     * HttpClient.Redirect#NEVER}, the default of {@link HttpClient#newHttpClient()}). By default, a
     * client of the platform's that follows none. It carries every request, those to the merchant's
     * Bitcoin node included: in plain {@code http} (HTTP/1.1) to a node of a local network, its
     * proxy and its rules applying (a client that cannot reach the node leaves the node's fallback
     * to apply, SPEC.md §31).
     *
     * @param httpClient the client
     * @return this builder
     */
    public Builder httpClient(HttpClient httpClient) {
      this.httpClientGiven = true;
      this.httpClient = httpClient;
      return this;
    }

    /**
     * What opens the WebSockets of the relays and of the explorer (mempool.space's, or the
     * merchant's Bitcoin node's: {@code ws://} for a node of a local network): {@link
     * WebSocketFactory#jdk(HttpClient)} of the HTTP client by default.
     *
     * @param webSocketFactory the factory
     * @return this builder
     */
    public Builder webSocketFactory(WebSocketFactory webSocketFactory) {
      this.webSocketFactoryGiven = true;
      this.webSocketFactory = webSocketFactory;
      return this;
    }

    /**
     * Per relay request, connection included: 100 to 60 000 ms, 10 000 by default.
     *
     * @param relayTimeoutMs the timeout
     * @return this builder
     */
    public Builder relayTimeoutMs(long relayTimeoutMs) {
      this.relayTimeoutMs = relayTimeoutMs;
      return this;
    }

    /**
     * Per HTTP request (Lightning address, rate sources, the explorer: mempool.space or the
     * merchant's Bitcoin node, BlockCypher), body included: 100 to 60 000 ms, 10 000 by default.
     *
     * @param httpTimeoutMs the timeout
     * @return this builder
     */
    public Builder httpTimeoutMs(long httpTimeoutMs) {
      this.httpTimeoutMs = httpTimeoutMs;
      return this;
    }

    /**
     * Where warnings go (at {@link System.Logger.Level#WARNING}; never a secret): {@code
     * System.getLogger("me.sattle.checkout")} by default.
     *
     * @param logger the logger
     * @return this builder
     */
    public Builder logger(System.Logger logger) {
      this.loggerGiven = true;
      this.logger = logger;
      return this;
    }

    /**
     * The clock: the system's by default. For tests.
     *
     * @param clock the clock
     * @return this builder
     */
    public Builder clock(Clock clock) {
      this.clockGiven = true;
      this.clock = clock;
      return this;
    }

    /**
     * Where random bytes come from: a new {@link SecureRandom} by default. For reproducible tests
     * only.
     *
     * @param random the CSPRNG
     * @return this builder
     */
    public Builder random(SecureRandom random) {
      this.randomGiven = true;
      this.random = random;
      return this;
    }

    /**
     * The client, its options checked.
     *
     * @return the client
     * @throws InvalidKeyException when the key is not an integration key
     * @throws InvalidOptionException naming the first invalid option: {@code httpClient} too when
     *     Java's own HTTP client, which the client uses, reads an answer's head without a bound in
     *     this Java (before Java 17.0.11, 21.0.3 and 22.0.1, or with {@code jdk.http.maxHeaderSize}
     *     set to 0 or less, or above 10 MiB)
     */
    public SattlemeClient build() {
      IntegrationKey access = IntegrationKey.parse(key);
      List<RateSource> sources = RateSource.DEFAULT_LIST;
      if (rateSourceGiven) {
        sources = RateSources.checkList(rateSources);
        if (sources == null) throw new InvalidOptionException("rateSource", RateSources.RULE);
      }
      // Visible ASCII: its HTTP header carries it as it is.
      if (coingeckoApiKey != null && !VISIBLE_ASCII.matcher(coingeckoApiKey).matches()) {
        throw new InvalidOptionException("coingeckoApiKey", "1 or more visible ASCII characters");
      }
      if (httpClientGiven
          && (httpClient == null || httpClient.followRedirects() != HttpClient.Redirect.NEVER)) {
        throw new InvalidOptionException(
            "httpClient", "an HttpClient that follows no redirect (HttpClient.Redirect.NEVER)");
      }
      long relayTimeout =
          Orders.checkMilliseconds(
              relayTimeoutMs,
              Constants.DEFAULT_TIMEOUT_MS,
              Constants.MIN_TIMEOUT_MS,
              Constants.MAX_TIMEOUT_MS,
              "relayTimeoutMs");
      long httpTimeout =
          Orders.checkMilliseconds(
              httpTimeoutMs,
              Constants.DEFAULT_TIMEOUT_MS,
              Constants.MIN_TIMEOUT_MS,
              Constants.MAX_TIMEOUT_MS,
              "httpTimeoutMs");
      HttpClient client =
          httpClientGiven
              ? httpClient
              : HttpClient.newBuilder()
                  .followRedirects(HttpClient.Redirect.NEVER)
                  .connectTimeout(Duration.ofMillis(httpTimeout))
                  .build();
      if (webSocketFactoryGiven && webSocketFactory == null) {
        throw new InvalidOptionException("webSocketFactory", "a WebSocketFactory");
      }
      // Java's own HTTP client (the default, or one of java.net.http given, its WebSockets too)
      // bounds an answer's head only from some updates on (SPEC.md §15).
      boolean javasOwnHttp =
          !httpClientGiven
              || httpClient.getClass().getModule() == HttpClient.class.getModule()
              || !webSocketFactoryGiven
              || webSocketFactory instanceof JdkWebSockets;
      String unbounded = javasOwnHttp ? Http.javaHeadUnbounded() : null;
      if (unbounded != null) throw new InvalidOptionException("httpClient", unbounded);
      WebSocketFactory sockets =
          webSocketFactoryGiven ? webSocketFactory : WebSocketFactory.jdk(client);
      if (loggerGiven && logger == null)
        throw new InvalidOptionException("logger", "a System.Logger");
      System.Logger log = loggerGiven ? logger : System.getLogger("me.sattle.checkout");
      if (clockGiven && clock == null) throw new InvalidOptionException("clock", "a Clock");
      if (randomGiven && random == null)
        throw new InvalidOptionException("random", "a SecureRandom");
      Warn warn = message -> log.log(System.Logger.Level.WARNING, message);
      Engine engine =
          new Engine(
              access,
              sources,
              coingeckoApiKey,
              client,
              httpTimeout,
              sockets,
              relayTimeout,
              warn,
              clockGiven ? clock : Clock.systemUTC(),
              new RandomBytes(randomGiven ? random : new SecureRandom()),
              RateSources::fetchRate);
      return new SattlemeClient(engine, sources);
    }

    @Override
    public String toString() {
      return "SattlemeClient.Builder";
    }
  }
}
