package me.sattle.checkout.internal;

import java.math.BigInteger;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;
import me.sattle.checkout.CheckoutUnavailableException;
import me.sattle.checkout.DeclaredInvoice;
import me.sattle.checkout.InvalidInvoiceException;
import me.sattle.checkout.InvalidOrderException;
import me.sattle.checkout.Invoice;
import me.sattle.checkout.InvoicePendingException;
import me.sattle.checkout.Merchant;
import me.sattle.checkout.NetworkException;
import me.sattle.checkout.OrderPaidException;
import me.sattle.checkout.PaymentStatus;
import me.sattle.checkout.RateInfo;
import me.sattle.checkout.RateSource;
import me.sattle.checkout.SattlemeException;
import me.sattle.checkout.WebSocketFactory;
import me.sattle.checkout.internal.lightning.Bolt11;
import me.sattle.checkout.internal.nostr.NostrEvent;
import me.sattle.checkout.internal.nostr.Quorum;
import me.sattle.checkout.internal.nostr.RelayPool;
import me.sattle.checkout.internal.onchain.Acceptances;
import me.sattle.checkout.internal.onchain.Addresses;
import me.sattle.checkout.internal.onchain.BitcoinNode;
import me.sattle.checkout.internal.onchain.CallExplorer;
import me.sattle.checkout.internal.onchain.Explorer;
import me.sattle.checkout.internal.onchain.Leases;
import me.sattle.checkout.internal.onchain.Lot;
import me.sattle.checkout.internal.onchain.MerchantAcceptances;
import me.sattle.checkout.internal.onchain.Methods;
import me.sattle.checkout.internal.onchain.Payments;
import me.sattle.checkout.internal.onchain.Rests;
import me.sattle.checkout.internal.onchain.Watch;
import me.sattle.checkout.internal.onchain.Witnesses;

/**
 * What the client does (SPEC.md §4, §17, §21 to §24, §30, §31), made for a server that may live for
 * one call: nothing is kept between calls, each call reads the channel again (a removal stops the
 * very next invoice), opens one connection per relay and closes them when it is done.
 *
 * <p>Each call runs on the thread that makes it (an asynchronous call, on a thread of its own),
 * blocking while it waits; what it asks at the same time runs on threads of its own (its forks),
 * and every request is asynchronous (HTTP and the relays' WebSockets). An interrupted call cancels
 * its forks and requests, closes its connections and fails with {@link InterruptedException}.
 */
public final class Engine {
  // Transient, as every field that leads to the key (SattlemeClient's).
  private final transient IntegrationKey access;
  private final transient List<RateSource> rateSources;
  private final transient String coingeckoApiKey;
  private final transient Http http;
  private final transient WebSocketFactory sockets;
  private final transient long relayTimeoutMs;
  private final transient Warn logger;
  private final transient Clock clock;
  private final transient RandomBytes random;
  private final transient RateSources.Ask ask;
  private final transient Derive.SeenKeys seen;

  /** One invoice at a time per order, in this process. */
  private final transient KeyedLocks orders = new KeyedLocks();

  /** One invoice of the rest at a time per invoice ({@code ref/address}), in this process. */
  private final transient KeyedLocks restAsks = new KeyedLocks();

  /**
   * The engine of a client.
   *
   * @param access the integration key
   * @param rateSources the client's rate sources
   * @param coingeckoApiKey CoinGecko's Demo key, or null
   * @param httpClient the HTTP client (following no redirect)
   * @param httpTimeoutMs the bound of each HTTP request
   * @param sockets opens the WebSockets
   * @param relayTimeoutMs the bound of each relay request
   * @param logger where warnings go
   * @param clock the clock
   * @param random the CSPRNG
   * @param ask how a rate source is asked (the conformance vectors replace it)
   */
  public Engine(
      IntegrationKey access,
      List<RateSource> rateSources,
      String coingeckoApiKey,
      HttpClient httpClient,
      long httpTimeoutMs,
      WebSocketFactory sockets,
      long relayTimeoutMs,
      Warn logger,
      Clock clock,
      RandomBytes random,
      RateSources.Ask ask) {
    this.access = access;
    this.rateSources = rateSources;
    this.coingeckoApiKey = coingeckoApiKey;
    this.http = new Http(httpClient, httpTimeoutMs);
    this.sockets = sockets;
    this.relayTimeoutMs = relayTimeoutMs;
    this.logger = logger;
    this.clock = clock;
    this.random = random;
    this.ask = ask;
    this.seen = Derive.SeenKeys.of(access.key());
  }

  /**
   * The integration key's relays and team: no secret.
   *
   * @return its description
   */
  public String describe() {
    return "team=" + access.teamPublicKey() + ", relays=" + access.relays();
  }

  /** Unix seconds, rounded down. */
  long seconds() {
    return Math.floorDiv(clock.millis(), 1000L);
  }

  /**
   * What the server needs of the merchant, read with the key alone (the integration's channel):
   * with the merchant's Bitcoin node, or null for mempool.space (the pages of checkout links read
   * it too when its {@code payers} is true), and the explorer the call reads (the node with its
   * fallback, else mempool.space).
   */
  private record MerchantChannel(
      String businessName,
      String lightningAddress,
      Derive.Registry registry,
      List<String> relays,
      String checkoutOrigin,
      int tolerance,
      BitcoinNode bitcoinNode,
      CallExplorer explorer) {
    @Override
    public String toString() {
      return "MerchantChannel[relays=" + relays + "]";
    }
  }

  /**
   * What the reads of the explorer of one call share (SPEC.md §31, "in the same call"): when the
   * node failed, and, for {@code waitForPayment}, who hears the node each channel read names (its
   * WebSocket follows it).
   *
   * @param failures the call's failures of the node
   * @param onNode hears the node of each channel read (null: none), or null
   */
  private record Call(CallExplorer.Failures failures, Consumer<BitcoinNode> onNode) {
    /** A call of its own: its reads alone share its failures. */
    static Call of() {
      return new Call(new CallExplorer.Failures(), null);
    }
  }

  /** The relays of a call: its connections, what it runs, the merchant, the sale's records. */
  private record CallRelays(
      RelayPool pool, Scope scope, MerchantChannel merchant, Sales.SaleRecords records) {}

  /** What runs with the relays of one call. */
  @FunctionalInterface
  private interface RelayWork<T> {
    T run(RelayPool pool, Scope scope) throws InterruptedException;
  }

  /**
   * Runs {@code run} with the relays of one call: one connection per relay, closed once the call is
   * done (a write still running past the quorum first ends, bounded by its timeout); at once when
   * it is cancelled.
   */
  private <T> T withRelays(RelayWork<T> run) throws InterruptedException {
    RelayPool pool = new RelayPool(sockets, relayTimeoutMs, random, this::seconds);
    boolean cancelled = true;
    try (Scope scope = new Scope()) {
      T result = run.run(pool, scope);
      cancelled = false;
      return result;
    } catch (RuntimeException e) {
      cancelled = Thread.currentThread().isInterrupted();
      throw e;
    } finally {
      if (cancelled) pool.close();
      else pool.release();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // merchant()

  /**
   * The merchant (SPEC.md §4): its business's name, the bounds of its Lightning address in sats,
   * and how many deposit addresses the integration's lot holds.
   *
   * @return the merchant
   * @throws InterruptedException when the call is cancelled
   */
  public Merchant merchant() throws InterruptedException {
    return withRelays((pool, scope) -> {
      MerchantChannel merchant = channel(pool, logger, new CallExplorer.Failures());
      CompletableFuture<Lnurl.PayRequest> payRequest =
          scope.track(Lnurl.fetchPayRequest(http, merchant.lightningAddress()));
      List<String> lot = lot(pool, merchant);
      Lnurl.PayRequest pay = Tasks.await(payRequest);
      return new Merchant(
          merchant.businessName(),
          Long.toString(pay.minSats()),
          Long.toString(pay.maxSats()),
          lot.size());
    });
  }

  // ---------------------------------------------------------------------------------------------
  // createInvoice()

  /**
   * The invoice of an order (SPEC.md §17).
   *
   * @param order the order, checked
   * @return the invoice
   * @throws InterruptedException when the call is cancelled
   */
  public Invoice createInvoice(Orders.CheckedOrder order) throws InterruptedException {
    // One at a time per order in this process: two calls for one ref would both pass the check of
    // its earlier invoices.
    KeyedLocks.Held held = orders.lock(order.ref());
    try {
      return withRelays((pool, scope) -> createInvoice(pool, scope, order));
    } finally {
      held.close();
    }
  }

  private Invoice createInvoice(RelayPool pool, Scope scope, Orders.CheckedOrder order)
      throws InterruptedException {
    // The explorer of this call: its reads (leasing, the checks of earlier invoices) share it.
    MerchantChannel merchant = channel(pool, logger, new CallExplorer.Failures());
    if (order.checkout() && merchant.checkoutOrigin() == null)
      throw new CheckoutUnavailableException();
    Derive.Sale sale = Derive.Sale.of(access.key(), merchant.registry(), order.ref());
    // Asked while the registry is read: the Lightning address and the rate need nothing of it.
    CompletableFuture<Lnurl.PayRequest> payRequest =
        scope.track(Lnurl.fetchPayRequest(http, merchant.lightningAddress()));
    Tasks.Fork<RateSources.Rate> quote =
        order.fiat() != null && order.rateSources() != null
            ? scope.fork(() ->
                RateSources.fetchFirstRate(
                    order.rateSources(),
                    order.fiat().currency(),
                    new RateSources.Context(http, coingeckoApiKey),
                    this::seconds,
                    logger,
                    ask))
            : null;
    checkEarlierInvoices(pool, scope, sale, merchant, order);
    Lnurl.PayRequest pay = Tasks.await(payRequest);

    long sats;
    RateInfo rate = null;
    if (order.fiat() != null) {
      RateSources.Rate quoted = quote.join();
      BigInteger converted =
          Prices.minorToSats(
              new BigInteger(order.fiat().minor()), order.fiat().digits(), quoted.price());
      rate = quoted.info();
      if (converted.compareTo(BigInteger.valueOf(Constants.MAX_SATS)) > 0) {
        throw new InvalidOrderException(
            "fiat", converted + " sats is more than 21 million bitcoins");
      }
      sats = converted.longValueExact();
    } else {
      sats = order.sats();
    }
    Methods.Of methods =
        Methods.paymentMethods(
            sats, pay.minSats(), pay.maxSats(), order.fiat() != null ? "fiat" : "sats");

    Lnurl.InvoiceAnswer answer = null;
    Bolt11 decoded = null;
    if (methods.lightning()) {
      answer =
          Tasks.await(
              Lnurl.requestInvoice(http, pay, sats, order.durationSeconds(), order.label()));
      decoded = Lnurl.checkInvoice(answer, pay, sats, order.durationSeconds(), seconds());
    }
    long leasedAt = seconds();
    long createdAt = decoded != null ? decoded.timestamp() : leasedAt;
    long expiresAt = decoded != null ? decoded.expiresAt() : leasedAt + order.durationSeconds();
    Checkout.DeclaredPrice price =
        order.fiat() != null
            ? new Checkout.DeclaredPrice(
                Long.parseLong(order.fiat().minor()),
                order.fiat().digits(),
                order.fiat().currency())
            : new Checkout.DeclaredPrice(sats, 0, Constants.SATS_CURRENCY);

    NostrEvent event = null;
    if (answer != null && decoded != null) {
      try {
        event =
            Declarations.declarationEvent(
                sale,
                answer.pr(),
                answer.verify(),
                decoded.paymentHash(),
                price,
                order.label(),
                seconds(),
                null,
                random);
      } catch (RuntimeException e) {
        // A valid invoice too long for a NIP-44 plaintext (65 535 bytes) cannot be declared.
        throw new InvalidInvoiceException("The invoice is too large to be declared", e);
      }
    }

    // What the lease of an address holds, with on-chain.
    Leases.Terms terms =
        new Leases.Terms(
            null,
            sats,
            answer == null ? null : answer.pr(),
            answer == null ? null : answer.verify(),
            createdAt,
            expiresAt,
            price,
            order.label(),
            // The order's for good: every check judges its payment with it.
            merchant.tolerance(),
            null);
    if (methods.onchain()) {
      // The lease holds the invoice too, sealed twice: a lease that does not fit a NIP-44
      // plaintext fails now, before anything is asked or published (SPEC.md §17, step 6). The real
      // one differs from this one by its address alone, of one length for every deposit address,
      // and is never larger (marked confirmed, it is shorter).
      try {
        Leases.leaseEvent(
            sale,
            terms.with(SIZED_ADDRESS, false),
            leasedAt,
            // Never published: its size alone counts, the client's random bytes are left alone.
            RandomBytes.zeros());
      } catch (RuntimeException e) {
        throw new InvalidInvoiceException(
            "The invoice is too large for the lease of a deposit address", e);
      }
    }

    // The address before the link and the declaration: when the lease fails, nothing is published.
    Methods.Leased lease = null;
    if (methods.onchain()) {
      Methods.LeaseOutcome leased =
          leaseAddress(pool, scope, merchant, sale, terms, decoded != null ? null : leasedAt);
      Methods.methodsWith(methods, leased);
      if (leased instanceof Methods.Leased held) {
        lease = held;
      } else if (leased instanceof Methods.ExplorerFailed failed) {
        logger.warn(
            Warn.PREFIX
                + "no deposit address leased, the explorer did not answer ("
                + failed.error().getMessage()
                + "): Lightning only");
      }
    }
    String onchainAddress = lease == null ? null : lease.address();

    // On a failure below, the lease stays: it holds its address until its end, and was confirmed.
    String checkoutUrl = null;
    // The link before the declaration: when the link fails, nothing is declared and the order gets
    // a new invoice at once; when the declaration fails, the link's URL was never given and its
    // keys are gone, so nobody can open it.
    if (order.checkout()) {
      checkoutUrl =
          publishCheckout(
              pool,
              merchant,
              Checkout.checkoutValue(
                  merchant.businessName(),
                  answer == null ? null : answer.pr(),
                  answer == null ? null : answer.verify(),
                  onchainAddress,
                  sats,
                  createdAt,
                  expiresAt,
                  order.fiat() != null ? price : null,
                  order.label(),
                  order.redirectUrl(),
                  onchainAddress == null ? null : restRef(order.ref(), onchainAddress, expiresAt),
                  // The node the page reads, with an address: the channel's with payers true.
                  onchainAddress == null
                      ? null
                      : BitcoinNode.recordNodeFor(merchant.bitcoinNode())),
              expiresAt);
    }
    if (event != null) declare(pool, event, merchant, sale.secretKey);

    String bolt11 = answer == null ? null : answer.pr();
    return new Invoice(
        order.ref(),
        bolt11,
        decoded == null ? null : decoded.paymentHash(),
        answer == null ? null : answer.verify(),
        onchainAddress,
        Addresses.paymentUri(onchainAddress, sats, bolt11),
        Long.toString(sats),
        order.fiat(),
        rate,
        order.label(),
        createdAt,
        expiresAt,
        checkoutUrl,
        order.checkout() ? order.redirectUrl() : null);
  }

  /** A text of a deposit address's length ({@code bc1p} and 58 more): a lease's size before it. */
  private static final String SIZED_ADDRESS = "bc1p" + "q".repeat(58);

  /** The rest author and the rest key of an invoice with an address. */
  private Checkout.RestRef restRef(String ref, String address, long expiresAt) {
    Derive.RestKeys keys = Derive.RestKeys.of(access.key(), ref, address, expiresAt);
    return new Checkout.RestRef(keys.author(), keys.key());
  }

  /**
   * Leases a deposit address of the integration's lot for {@code sale} (SPEC.md §31, "Leasing an
   * address"). {@code leasedAt}: the lease's date when it is the Invoice's (an order paid on-chain
   * only).
   */
  private Methods.LeaseOutcome leaseAddress(
      RelayPool pool,
      Scope scope,
      MerchantChannel merchant,
      Derive.Sale sale,
      Leases.Terms terms,
      Long leasedAt)
      throws InterruptedException {
    List<String> lot = lot(pool, merchant);
    if (lot.isEmpty()) return new Methods.NoAddress();
    List<String> relays = merchant.relays();
    // The leases, complete or not (the confirmation decides), and the addresses a witness names.
    long readAt = seconds();
    Tasks.Fork<Leases.LeaseRead> leasesRead =
        scope.fork(() ->
            Leases.readLeases(
                pool, merchant.registry().key(), lot, relays, readAt, random, false, logger));
    Tasks.Fork<Set<String>> namedRead =
        scope.fork(() -> Witnesses.readNamedAddresses(pool, seen, lot, relays, random));
    Tasks.await(Tasks.all(List.of(leasesRead.future(), namedRead.future())));
    Leases.LeaseRead leases = leasesRead.join();
    Set<String> named = namedRead.join();
    if (!leases.answered()) {
      throw new NetworkException("Fewer than a majority of the merchant's relays answered");
    }
    long now = seconds();
    // Not where the read shows a record of the sale (its signature checked): an earlier call of
    // the order tried it, and a new lease could be of the second of its withdrawal.
    List<String> candidates = new ArrayList<>();
    for (String address : lot) {
      List<Leases.LeaseRecord> records = leases.records().get(address);
      if (named.contains(address) || Leases.isLeased(records, now)) continue;
      boolean tried = false;
      for (Leases.LeaseRecord record : records) {
        if (record.event().pubkey().equals(sale.author) && record.signed()) {
          tried = true;
          break;
        }
      }
      if (!tried) candidates.add(address);
    }
    List<String> free = shuffle(candidates, random);
    for (String address : free.subList(0, Math.min(free.size(), Constants.MAX_ADDRESS_TRIES))) {
      List<Explorer.Tx> txs;
      try {
        // The explorer, with its fallback (SPEC.md §31): with "none" and the node down, no
        // address (network for an order paid on-chain only, Lightning only otherwise).
        txs =
            merchant
                .explorer()
                .read(explorer -> Explorer.addressTransactions(http, explorer, address));
      } catch (NetworkException e) {
        return new Methods.ExplorerFailed(e);
      }
      List<Explorer.Tx> paid = new ArrayList<>();
      for (Explorer.Tx tx : txs) if (Explorer.paysTo(tx, address) > 0) paid.add(tx);
      if (!paid.isEmpty()) {
        // Paid on-chain already: of no order, for good.
        publishWitnessesOfNone(pool, merchant, address, paid);
        continue;
      }
      // Published unconfirmed: it holds the address, never gives the invoice one.
      NostrEvent event =
          Leases.leaseEvent(
              sale, terms.with(address, false), leasedAt != null ? leasedAt : seconds(), random);
      Leases.LeaseRead again;
      try {
        publishLeaseRecord(pool, relays, event, sale);
        // On every relay, each until its timeout: a relay that hides a lease cannot hide it while
        // one that holds it answers in time.
        again =
            Leases.readLeases(
                pool,
                merchant.registry().key(),
                List.of(address),
                relays,
                seconds(),
                random,
                true,
                logger);
      } catch (RuntimeException e) {
        // On some relays only: withdrawn, never the order's.
        withdraw(pool, relays, sale, address, event);
        throw e;
      }
      List<Leases.LeaseRecord> records = again.records().get(address);
      Leases.LeaseEvent own =
          new Leases.LeaseEvent(event.id(), event.pubkey(), event.createdAt(), event.sig());
      boolean confirmed =
          again.answered() && again.complete() && Leases.isConfirmed(records, own, seconds());
      if (!confirmed) {
        // Another server leased it at once, the read could not be complete, or fewer than a
        // majority answered: withdrawn, unless the read shows another record of the sale there
        // whose signature checks out (another call of the order made it at once).
        boolean another = false;
        for (Leases.LeaseRecord record : records) {
          if (record.event().pubkey().equals(sale.author)
              && !record.event().id().equals(event.id())
              && record.signed()) {
            another = true;
            break;
          }
        }
        if (!another) withdraw(pool, relays, sale, address, event);
        if (!again.answered()) {
          throw new NetworkException("Fewer than a majority of the merchant's relays answered");
        }
        continue;
      }
      // Confirmed: never withdrawn from now on, whatever fails. Marked confirmed: from now on the
      // address is the order's.
      NostrEvent marked =
          Leases.leaseEvent(
              sale, terms.with(address, true), Math.max(seconds(), event.createdAt() + 1), random);
      publishLeaseRecord(pool, relays, marked, sale);
      return new Methods.Leased(address, marked);
    }
    return new Methods.NoAddress();
  }

  /**
   * Withdraws {@code lease}, the lease of {@code address} by {@code sale} that was not confirmed:
   * its withdrawal, a second after it, published on the merchant's relays; a failure only logged.
   */
  private void withdraw(
      RelayPool pool, List<String> relays, Derive.Sale sale, String address, NostrEvent lease)
      throws InterruptedException {
    NostrEvent event = Leases.withdrawalEvent(sale, address, lease.createdAt() + 1, random);
    try {
      Quorum.publish(pool, relays, event, sale.secretKey, "The withdrawal was not taken");
    } catch (NetworkException e) {
      logger.warn(
          Warn.PREFIX
              + "a lease that was not confirmed could not be withdrawn; it holds its deposit address"
              + " until its end");
    }
  }

  private void publishLeaseRecord(
      RelayPool pool, List<String> relays, NostrEvent event, Derive.Sale sale)
      throws InterruptedException {
    Quorum.publish(
        pool,
        relays,
        event,
        sale.secretKey,
        "Fewer than a majority of the merchant's relays took the lease of a deposit address: try"
            + " again");
  }

  /** Publishes a witness none of each transaction of {@code txs} paying {@code address}. */
  private void publishWitnessesOfNone(
      RelayPool pool, MerchantChannel merchant, String address, List<Explorer.Tx> txs)
      throws InterruptedException {
    List<CompletableFuture<?>> publications = new ArrayList<>();
    for (Explorer.Tx tx : txs) {
      List<String> coins = tx.coins();
      Witnesses.Witness witness =
          new Witnesses.Witness(
              address,
              tx.txid(),
              null,
              Explorer.paysTo(tx, address),
              coins.subList(0, Math.min(coins.size(), Constants.MAX_WITNESS_INPUTS)),
              false,
              null);
      publications.add(
          logNetworkFailure(
              Witnesses.publishWitness(pool, seen, witness, merchant.relays(), seconds(), random),
              logger,
              "the witness of a paid address was not published: "));
    }
    Tasks.await(Tasks.all(publications));
  }

  /** {@code future}, its network failure logged as {@code message} and its error's message. */
  private static CompletableFuture<Void> logNetworkFailure(
      CompletableFuture<Void> future, Warn logger, String message) {
    return future
        .handle((ignored, error) -> {
          if (error == null) return CompletableFuture.<Void>completedFuture(null);
          Throwable cause = Tasks.unwrap(error);
          if (!(cause instanceof NetworkException))
            return CompletableFuture.<Void>failedFuture(cause);
          logger.warn(Warn.PREFIX + message + cause.getMessage());
          return CompletableFuture.<Void>completedFuture(null);
        })
        .thenCompose(next -> next);
  }

  /** The integration's lot of deposit addresses, read on the merchant's relays. */
  private List<String> lot(RelayPool pool, MerchantChannel merchant) throws InterruptedException {
    return Lot.readLot(pool, access, merchant.relays(), random, logger);
  }

  /**
   * Publishes a new checkout link holding {@code value} on a majority of the merchant's relays, and
   * gives its URL. The author's secret answers the relays' authentication, then is dropped with the
   * link.
   */
  private String publishCheckout(
      RelayPool pool,
      MerchantChannel merchant,
      me.sattle.checkout.internal.json.JsonObject value,
      long expiresAt)
      throws InterruptedException {
    Checkout.NewLink link;
    try {
      link =
          Checkout.newCheckoutLink(
              merchant.checkoutOrigin(), merchant.relays(), value, expiresAt, seconds(), random);
    } catch (RuntimeException e) {
      throw new InvalidInvoiceException("The invoice is too large for a checkout link", e);
    }
    Quorum.publish(
        pool,
        merchant.relays(),
        link.event(),
        link.secretKey(),
        "Fewer than a majority of the merchant's relays took the checkout link: try again");
    return link.url();
  }

  /**
   * Refuses a new invoice for the sale while an earlier one may still be paid, or when one was paid
   * (SPEC.md §21): read in the registry, so that it holds even when the shop lost what it stored.
   */
  private void checkEarlierInvoices(
      RelayPool pool,
      Scope scope,
      Derive.Sale sale,
      MerchantChannel merchant,
      Orders.CheckedOrder order)
      throws InterruptedException {
    Sales.SaleRecords records = declared(pool, sale, merchant, false, logger);
    List<Sales.SaleDeclaration> earlier = records.invoices();
    CallRelays relays = new CallRelays(pool, scope, merchant, records);
    List<DeclaredInvoice> invoices = new ArrayList<>();
    for (Sales.SaleDeclaration declaration : earlier) invoices.add(declaration.invoice());
    List<Sales.SaleDeclaration> mayBePaid = new ArrayList<>();
    for (int index : Sales.mayStillBePaid(invoices, seconds())) mayBePaid.add(earlier.get(index));
    if (!mayBePaid.isEmpty()) {
      // Their payments checked, even of one alone: an invoice already paid is never handed out
      // again. Several: the sale's records are read on every relay at the same time, and the one
      // shown is the one a majority holds.
      Tasks.Fork<List<Tasks.Settled<PaymentStatus>>> checks =
          scope.fork(() -> checkEach(scope, mayBePaid, relays));
      Tasks.Fork<Sales.SaleRecords> everyRelay =
          mayBePaid.size() > 1
              ? scope.fork(() -> declared(pool, sale, merchant, true, logger))
              : null;
      List<Tasks.Settled<PaymentStatus>> statuses = checks.join();
      Tasks.Settled<Sales.SaleRecords> everywhere = everyRelay == null ? null : everyRelay.settle();
      List<Boolean> held =
          Sales.heldBy(
              mayBePaid,
              everywhere != null && everywhere.ok() ? everywhere.value().sentBy() : null,
              merchant.relays().size());
      Sales.Outcome outcome = Sales.pendingOutcome(statuses, held);
      if (outcome instanceof Sales.PaidOutcome paid) {
        throw new OrderPaidException(
            mayBePaid.get(paid.index()).invoice(),
            (PaymentStatus.Paid) statuses.get(paid.index()).value());
      }
      if (outcome instanceof Sales.FailedOutcome failed) throw statuses.get(failed.index()).error();
      // Fewer than a majority answered the read again: after a payment and an error.
      if (outcome instanceof Sales.Unread) {
        throw everywhere != null && !everywhere.ok()
            ? everywhere.error()
            : new NetworkException("Fewer than a majority of the merchant's relays answered");
      }
      Sales.SaleDeclaration open = mayBePaid.get(((Sales.HandOut) outcome).index());
      // Its createInvoice may have failed with fewer than a majority of the relays holding its
      // declaration or its lease: published again before it is handed out.
      if (open.event() != null) declare(pool, open.event(), merchant, sale.secretKey);
      if (open.lease() != null) publishLease(pool, open.lease(), merchant, sale.secretKey);
      DeclaredInvoice invoice = open.invoice();
      // A new link to it, for a shop that lost the one it stored (or never got it).
      String checkoutUrl = null;
      if (order.checkout()) {
        checkoutUrl =
            publishCheckout(
                pool,
                merchant,
                Checkout.checkoutValue(
                    merchant.businessName(),
                    invoice.bolt11(),
                    invoice.verifyUrl(),
                    invoice.onchainAddress(),
                    Long.parseLong(invoice.sats()),
                    invoice.createdAt(),
                    invoice.expiresAt(),
                    invoice.fiat() == null
                        ? null
                        : new Checkout.DeclaredPrice(
                            Long.parseLong(invoice.fiat().minor()),
                            invoice.fiat().digits(),
                            invoice.fiat().currency()),
                    invoice.note(),
                    order.redirectUrl(),
                    invoice.onchainAddress() == null
                        ? null
                        : restRef(order.ref(), invoice.onchainAddress(), invoice.expiresAt()),
                    // The node of the channel this call read.
                    invoice.onchainAddress() == null
                        ? null
                        : BitcoinNode.recordNodeFor(merchant.bitcoinNode())),
                invoice.expiresAt());
      }
      throw new InvoicePendingException(invoice, checkoutUrl);
    }
    List<Tasks.Settled<PaymentStatus>> statuses = checkEach(scope, earlier, relays);
    for (int index = 0; index < statuses.size(); index += 1) {
      Tasks.Settled<PaymentStatus> status = statuses.get(index);
      if (status.ok() && status.value() instanceof PaymentStatus.Paid paid) {
        throw new OrderPaidException(earlier.get(index).invoice(), paid);
      }
    }
    // An on-chain payment being confirmed, or a transaction undecided: its time to be paid is
    // over, nothing is published again, and no new link.
    for (int index = 0; index < statuses.size(); index += 1) {
      Tasks.Settled<PaymentStatus> status = statuses.get(index);
      if (status.ok()
          && (status.value() instanceof PaymentStatus.Unconfirmed
              || status.value() instanceof PaymentStatus.Open)) {
        throw new InvoicePendingException(earlier.get(index).invoice(), null);
      }
    }
    for (Tasks.Settled<PaymentStatus> status : statuses) {
      if (!status.ok()) throw status.error();
    }
  }

  /** The status of each of {@code invoices}, a few at a time: never thrown but a cancellation. */
  private List<Tasks.Settled<PaymentStatus>> checkEach(
      Scope scope, List<Sales.SaleDeclaration> invoices, CallRelays relays)
      throws InterruptedException {
    int count = invoices.size();
    AtomicReferenceArray<Tasks.Settled<PaymentStatus>> results = new AtomicReferenceArray<>(count);
    AtomicInteger next = new AtomicInteger();
    List<CompletableFuture<?>> workers = new ArrayList<>();
    for (int worker = 0;
        worker < Math.min(Constants.MAX_PARALLEL_PAYMENT_CHECKS, count);
        worker += 1) {
      workers.add(
          scope
              .fork(() -> {
                for (int index = next.getAndIncrement();
                    index < count;
                    index = next.getAndIncrement()) {
                  Orders.StoredInvoice stored = storedOf(invoices.get(index).invoice());
                  try {
                    Parts parts = check(stored, relays, logger, null);
                    results.set(index, Tasks.Settled.ok(statusOf(stored, parts, seconds())));
                  } catch (RuntimeException e) {
                    results.set(index, Tasks.Settled.failed(e));
                  }
                }
                return null;
              })
              .future());
    }
    Tasks.await(Tasks.all(workers));
    List<Tasks.Settled<PaymentStatus>> statuses = new ArrayList<>();
    for (int index = 0; index < count; index += 1) statuses.add(results.get(index));
    return statuses;
  }

  /** Publishes a declaration on a majority of the merchant's relays, else NetworkException. */
  private void declare(
      RelayPool pool, NostrEvent event, MerchantChannel merchant, byte[] saleSecret)
      throws InterruptedException {
    Quorum.publish(
        pool,
        merchant.relays(),
        event,
        saleSecret,
        "Fewer than a majority of the merchant's relays took the invoice's declaration: do not show"
            + " this invoice, try again");
  }

  /** Publishes a lease again on a majority of the merchant's relays, else NetworkException. */
  private void publishLease(
      RelayPool pool, NostrEvent event, MerchantChannel merchant, byte[] saleSecret)
      throws InterruptedException {
    Quorum.publish(
        pool,
        merchant.relays(),
        event,
        saleSecret,
        "Fewer than a majority of the merchant's relays took the invoice's lease: do not show this"
            + " invoice, try again");
  }

  // ---------------------------------------------------------------------------------------------
  // getStatus()

  /**
   * Where the payment of an invoice is (SPEC.md §22, §31): every part checked at once.
   *
   * @param stored the invoice, checked
   * @return its status
   * @throws InterruptedException when the call is cancelled
   */
  public PaymentStatus getStatus(Orders.StoredInvoice stored) throws InterruptedException {
    return statusOf(stored, check(stored, null, logger, Call.of()), seconds());
  }

  /**
   * The last result of each part of an invoice (null: it has none).
   *
   * @param lightning the Lightning part
   * @param onchain the on-chain part
   * @param rests the rest part
   */
  private record Parts(
      Tasks.Settled<Lnurl.LightningPart> lightning,
      Tasks.Settled<Payments.OnchainPart> onchain,
      Payments.RestPart rests) {}

  /** The status of {@code stored} from its parts at {@code now}; throws a part's error. */
  private static PaymentStatus statusOf(Orders.StoredInvoice stored, Parts parts, long now) {
    return Payments.combineParts(
        stored.sats(),
        stored.expiresAt(),
        stored.onchainAddress(),
        now,
        parts.lightning(),
        parts.onchain(),
        parts.rests());
  }

  /**
   * Every part of {@code stored} checked at once (SPEC.md §31, "The on-chain payment"): its
   * Lightning part, and with an address its on-chain part and its rest part, read on the merchant's
   * relays (over {@code relays} when given; else over the connections of a call of its own). A part
   * that fails is its error, never thrown; a cancellation is.
   */
  private Parts check(Orders.StoredInvoice stored, CallRelays relays, Warn warn, Call call)
      throws InterruptedException {
    CompletableFuture<Tasks.Settled<Lnurl.LightningPart>> lightning =
        stored.lightning() == null
            ? CompletableFuture.completedFuture(null)
            : Tasks.settle(Lnurl.verifyPayment(http, stored.lightning(), seconds()));
    if (stored.onchainAddress() == null) return new Parts(Tasks.await(lightning), null, null);
    try {
      if (relays != null) return addressParts(stored, relays, lightning, warn);
      return withRelays((pool, scope) -> {
        MerchantChannel merchant = channel(pool, warn, call.failures());
        if (call.onNode() != null) call.onNode().accept(merchant.bitcoinNode());
        return addressParts(stored, new CallRelays(pool, scope, merchant, null), lightning, warn);
      });
    } catch (RuntimeException e) {
      // The relays or the channel failed: the address's parts cannot be told.
      return new Parts(Tasks.await(lightning), Tasks.Settled.failed(e), null);
    } finally {
      lightning.cancel(true);
    }
  }

  /**
   * The on-chain part and the rest part of {@code stored} (it has an address and a reference), at
   * once: the sale's records read while the explorer is asked; then, when the status is open with a
   * rest and no invoice of it, one asked; and the records of its rest invoices published again
   * where no relay holds them.
   */
  private Parts addressParts(
      Orders.StoredInvoice stored,
      CallRelays relays,
      CompletableFuture<Tasks.Settled<Lnurl.LightningPart>> lightningCheck,
      Warn warn)
      throws InterruptedException {
    RelayPool pool = relays.pool();
    Scope scope = relays.scope();
    MerchantChannel merchant = relays.merchant();
    String address = stored.onchainAddress();
    Derive.Sale sale = Derive.Sale.of(access.key(), merchant.registry(), stored.ref());
    CompletableFuture<Sales.SaleRecords> read =
        relays.records() != null
            ? CompletableFuture.completedFuture(relays.records())
            : scope.fork(() -> declared(pool, sale, merchant, false, warn)).future();
    CompletableFuture<Tasks.Settled<Payments.RestPart>> restCheck =
        scope
            .fork(() -> {
              Tasks.Settled<Sales.SaleRecords> found = Tasks.await(Tasks.settle(read));
              if (!found.ok()) return Tasks.Settled.<Payments.RestPart>failed(found.error());
              try {
                // This invoice's own: another invoice of the order may have leased the address
                // before.
                List<Rests.RestInvoice> invoices =
                    found
                        .value()
                        .rests()
                        .getOrDefault(Sales.restKeyOf(address, stored.expiresAt()), List.of());
                // One whose declaration may be on a minority of the relays only: on a majority
                // before it counts, is shown or recorded, else the rest part fails.
                for (Rests.RestInvoice invoice : invoices) {
                  NostrEvent event = found.value().unsureRests().get(invoice.paymentHash());
                  if (event != null) declare(pool, event, merchant, sale.secretKey);
                }
                List<CompletableFuture<Tasks.Settled<Lnurl.LightningPart>>> checks =
                    new ArrayList<>();
                for (Rests.RestInvoice invoice : invoices) {
                  checks.add(
                      Tasks.settle(Lnurl.verifyPayment(http, lightningOf(invoice), seconds())));
                }
                Tasks.await(Tasks.all(checks));
                List<Tasks.Settled<Lnurl.LightningPart>> results = new ArrayList<>();
                for (CompletableFuture<Tasks.Settled<Lnurl.LightningPart>> check : checks) {
                  results.add(check.join());
                }
                return Tasks.Settled.ok(Payments.RestPart.of(invoices, results));
              } catch (RuntimeException e) {
                return Tasks.Settled.<Payments.RestPart>failed(e);
              }
            })
            .future();
    // The order's tolerance, its lease's (the invoice's own terms): without the records, or another
    // lease of the address, none.
    Payments.Wait<Integer> tolerance =
        () -> {
          Tasks.Settled<Sales.SaleRecords> found = Tasks.await(Tasks.settle(read));
          if (!found.ok()) return 0;
          Leases.OpenedLease lease = found.value().leases().get(address);
          return lease != null
                  && lease.terms().sats() == stored.sats()
                  && lease.terms().expiresAt() == stored.expiresAt()
              ? lease.terms().tolerance()
              : 0;
        };
    CompletableFuture<Tasks.Settled<Payments.OnchainPart>> onchainCheck =
        scope
            .fork(() -> {
              try {
                return Tasks.Settled.ok(
                    Payments.checkOnchainPart(
                        onchainCheck(
                            stored,
                            sale,
                            merchant,
                            pool,
                            warn,
                            // A rest invoice that failed counts as not paid: the on-chain part
                            // then needs more.
                            () -> {
                              Tasks.Settled<Payments.RestPart> rest = Tasks.await(restCheck);
                              return rest.ok() ? Payments.restSummary(rest.value()).paid() : 0L;
                            },
                            tolerance)));
              } catch (RuntimeException e) {
                return Tasks.Settled.<Payments.OnchainPart>failed(e);
              }
            })
            .future();
    Tasks.await(Tasks.all(List.of(lightningCheck, onchainCheck, restCheck)));
    Tasks.Settled<Lnurl.LightningPart> lightning = lightningCheck.join();
    Tasks.Settled<Payments.OnchainPart> onchain = onchainCheck.join();
    Tasks.Settled<Payments.RestPart> restResult = restCheck.join();
    // The sale's records unread: whether a rest invoice was paid cannot be told.
    Payments.RestPart rests =
        restResult.ok() ? restResult.value() : Payments.RestPart.failed(restResult.error());
    if (!restResult.ok()) return new Parts(lightning, onchain, rests);
    Sales.SaleRecords found = read.join();
    long now = seconds();
    PaymentStatus status;
    try {
      status = statusOf(stored, new Parts(lightning, onchain, rests), now);
    } catch (RuntimeException e) {
      // A part failed: the status is its error, nothing is asked.
      status = null;
    }
    Leases.OpenedLease held = found.leases().get(address);
    Leases.Terms lease = held == null ? null : held.terms();
    /* The rest invoices whose rest record a majority took in this check. */
    Set<String> recorded = new HashSet<>();
    // Whether one may be asked, as far as this check's time tells (askRest decides).
    if (status instanceof PaymentStatus.Open open
        && lease != null
        && Rests.restToAsk(
                open.rest(),
                stored.sats(),
                stored.expiresAt(),
                now,
                lease.sats(),
                lease.expiresAt())
            != null) {
      Asked asked =
          askRest(stored, open.rest(), lease, rests.invoices(), pool, merchant, sale, warn);
      if (asked != null) {
        if (asked.recorded()) recorded.add(asked.invoice().paymentHash());
        List<Rests.RestInvoice> invoices = new ArrayList<>(rests.invoices());
        invoices.add(asked.invoice());
        List<Tasks.Settled<Lnurl.LightningPart>> results = new ArrayList<>(rests.results());
        results.add(asked.result());
        rests = Payments.RestPart.of(invoices, results);
        // Asked meanwhile by another check and paid already: less is due on-chain.
        if (isPaid(asked.result())) {
          long paidByRests = Payments.restSummary(rests).paid();
          try {
            onchain =
                Tasks.Settled.ok(
                    Payments.checkOnchainPart(
                        onchainCheck(
                            stored, sale, merchant, pool, warn, () -> paidByRests, tolerance)));
          } catch (RuntimeException e) {
            onchain = Tasks.Settled.failed(e);
          }
        }
      }
    }
    if (!rests.invoices().isEmpty() && now < stored.expiresAt() + Constants.GRACE_SECONDS) {
      Set<String> unrecorded =
          publishMissingRests(stored, rests.invoices(), recorded, pool, merchant, warn);
      rests = rests.withUnrecorded(unrecorded);
    }
    Parts parts = new Parts(lightning, onchain, rests);
    publishAcceptance(stored, parts, pool, merchant, warn);
    return parts;
  }

  private Payments.OnchainCheck onchainCheck(
      Orders.StoredInvoice stored,
      Derive.Sale sale,
      MerchantChannel merchant,
      RelayPool pool,
      Warn warn,
      Payments.Wait<Long> paidByRests,
      Payments.Wait<Integer> tolerance) {
    String address = stored.onchainAddress();
    // The merchant's acceptance of this invoice: the team's record, read on every relay.
    MerchantAcceptances.Keys accepted =
        MerchantAcceptances.Keys.of(
            access.key(), access.teamPublicKey(), sale.author, address, stored.expiresAt());
    return new Payments.OnchainCheck(
        address,
        stored.sats(),
        stored.expiresAt(),
        sale.author,
        this::seconds,
        http,
        merchant.explorer(),
        everyRelay ->
            Witnesses.readOrderWitnesses(
                pool,
                seen,
                address,
                sale.author,
                stored.expiresAt(),
                merchant.relays(),
                random,
                everyRelay,
                warn),
        (txid, of) -> Witnesses.witnessAddress(seen.addressKey(), address, txid, of),
        witness ->
            Witnesses.publishWitness(pool, seen, witness, merchant.relays(), seconds(), random),
        warn,
        paidByRests,
        tolerance,
        () ->
            MerchantAcceptances.readMerchantAcceptance(
                pool, accepted, stored.sats(), stored.expiresAt(), merchant.relays(), random));
  }

  /**
   * When the order's tolerance or the merchant's acceptance accepted less than its amount, has the
   * pages of its checkout links know it (SPEC.md §31, "Acceptance records"); a failure is logged,
   * never the check's.
   */
  private void publishAcceptance(
      Orders.StoredInvoice stored, Parts parts, RelayPool pool, MerchantChannel merchant, Warn warn)
      throws InterruptedException {
    PaymentStatus status;
    try {
      status = statusOf(stored, parts, seconds());
    } catch (RuntimeException e) {
      // A part failed: nothing is accepted.
      return;
    }
    Acceptances.publishAcceptance(
        pool,
        status,
        stored.sats(),
        stored.expiresAt(),
        () ->
            Derive.RestKeys.of(
                access.key(), stored.ref(), stored.onchainAddress(), stored.expiresAt()),
        merchant.relays(),
        this::seconds,
        random,
        warn);
  }

  /**
   * A rest invoice asked by a check.
   *
   * @param invoice the rest invoice
   * @param result its Lightning part
   * @param recorded whether a majority took its rest record in this call
   */
  private record Asked(
      Rests.RestInvoice invoice, Tasks.Settled<Lnurl.LightningPart> result, boolean recorded) {}

  /**
   * Decides whether to ask the merchant's Lightning address for an invoice of {@code rest} (SPEC.md
   * §31, "Asking for a rest invoice"), asks it, declares it, then publishes its rest record. One at
   * a time per invoice in this process: once its turn came and the rest invoices were read again
   * (another check's invoice of the same rest, declared meanwhile, is taken instead), decided by
   * the clock read when the pay request answered, never earlier: that time is the one of the
   * invoice's expiry, its checks and its records. Null when none was asked or none could be made (a
   * failure logged).
   */
  private Asked askRest(
      Orders.StoredInvoice stored,
      PaymentStatus.Rest rest,
      Leases.Terms lease,
      List<Rests.RestInvoice> known,
      RelayPool pool,
      MerchantChannel merchant,
      Derive.Sale sale,
      Warn warn)
      throws InterruptedException {
    String address = stored.onchainAddress();
    KeyedLocks.Held held = restAsks.lock(sale.ref + "/" + address);
    try {
      try {
        // Another check may have asked for it meanwhile.
        Sales.SaleRecords again = declared(pool, sale, merchant, false, warn);
        long restSats = Long.parseLong(rest.sats());
        long seen = seconds();
        Set<String> before = new HashSet<>();
        for (Rests.RestInvoice invoice : known) before.add(invoice.paymentHash());
        Rests.RestInvoice meanwhile = null;
        for (Rests.RestInvoice invoice :
            again.rests().getOrDefault(Sales.restKeyOf(address, stored.expiresAt()), List.of())) {
          if (!before.contains(invoice.paymentHash())
              && invoice.sats() == restSats
              && seen < invoice.expiresAt()) {
            meanwhile = invoice;
            break;
          }
        }
        if (meanwhile != null) {
          // On a majority, as any rest invoice found.
          NostrEvent event = again.unsureRests().get(meanwhile.paymentHash());
          if (event != null) declare(pool, event, merchant, sale.secretKey);
          // Checked as any rest invoice: it may have been paid already.
          Tasks.Settled<Lnurl.LightningPart> result =
              Tasks.await(
                  Tasks.settle(Lnurl.verifyPayment(http, lightningOf(meanwhile), seconds())));
          return new Asked(meanwhile, result, false);
        }
        Lnurl.PayRequest pay =
            Tasks.await(Lnurl.fetchPayRequest(http, merchant.lightningAddress()));
        // The time of the decision, of the invoice's expiry, of its checks and of its records: a
        // check that waited for its turn has less time left.
        long now = seconds();
        Rests.RestAsk ask =
            Rests.restToAsk(
                rest, stored.sats(), stored.expiresAt(), now, lease.sats(), lease.expiresAt());
        if (ask == null || !Rests.sendable(ask.sats(), pay.minSats(), pay.maxSats())) return null;
        long expirySeconds = ask.expirySeconds();
        Lnurl.InvoiceAnswer answer =
            Tasks.await(Lnurl.requestInvoice(http, pay, ask.sats(), expirySeconds, lease.note()));
        Bolt11 decoded = Lnurl.checkInvoice(answer, pay, ask.sats(), expirySeconds, now);
        NostrEvent event;
        try {
          event =
              Declarations.declarationEvent(
                  sale,
                  answer.pr(),
                  answer.verify(),
                  decoded.paymentHash(),
                  lease.price(),
                  lease.note(),
                  now,
                  new Declarations.RestOf(address, stored.expiresAt()),
                  random);
        } catch (RuntimeException e) {
          throw new InvalidInvoiceException("The invoice is too large to be declared", e);
        }
        declare(pool, event, merchant, sale.secretKey);
        Rests.RestInvoice invoice =
            new Rests.RestInvoice(
                answer.pr(),
                decoded.paymentHash(),
                answer.verify(),
                ask.sats(),
                decoded.timestamp(),
                decoded.expiresAt());
        // Declared: it may be shown. Its record, for the checkout links' pages.
        boolean recorded = publishRest(stored, invoice, now, pool, merchant, warn);
        return new Asked(invoice, Tasks.Settled.ok(Lnurl.LightningPart.OPEN), recorded);
      } catch (SattlemeException e) {
        warn.warn(
            Warn.PREFIX
                + "no Lightning invoice of the rest of an underpayment ("
                + e.getMessage()
                + "); asked again at the next check");
        return null;
      }
    } finally {
      held.close();
    }
  }

  /**
   * Publishes the rest record of {@code invoice}, dated {@code createdAt}, on a majority; a failure
   * only logged.
   */
  private boolean publishRest(
      Orders.StoredInvoice stored,
      Rests.RestInvoice invoice,
      long createdAt,
      RelayPool pool,
      MerchantChannel merchant,
      Warn warn)
      throws InterruptedException {
    Derive.RestKeys keys =
        Derive.RestKeys.of(access.key(), stored.ref(), stored.onchainAddress(), stored.expiresAt());
    try {
      Rests.publishRestRecord(
          pool,
          Rests.restRecordEvent(
              keys,
              invoice.bolt11(),
              invoice.verifyUrl(),
              invoice.paymentHash(),
              stored.expiresAt(),
              createdAt,
              random),
          keys,
          merchant.relays());
      return true;
    } catch (NetworkException e) {
      warn.warn(
          Warn.PREFIX
              + "the record of an invoice of the rest was not published ("
              + e.getMessage()
              + "); published again at the next check");
      return false;
    }
  }

  /**
   * Publishes again the rest records of {@code invoices} that fewer than a majority of the relays
   * sent, so that the checkout links' pages see each of them, never over a record of a newer format
   * (it holds its address, and its invoice counts as known). Gives the payment hashes of those
   * still not known on a majority.
   */
  private Set<String> publishMissingRests(
      Orders.StoredInvoice stored,
      List<Rests.RestInvoice> invoices,
      Set<String> recorded,
      RelayPool pool,
      MerchantChannel merchant,
      Warn warn)
      throws InterruptedException {
    Derive.RestKeys keys =
        Derive.RestKeys.of(access.key(), stored.ref(), stored.onchainAddress(), stored.expiresAt());
    Rests.RestRecords read =
        Rests.readRestRecords(
            pool,
            keys,
            stored.sats(),
            -Constants.MAX_MINOR_UNITS,
            stored.expiresAt(),
            merchant.relays(),
            random,
            warn);
    List<Rests.RestInvoice> unknown = new ArrayList<>();
    for (Rests.RestInvoice invoice : invoices) {
      if (!recorded.contains(invoice.paymentHash())) unknown.add(invoice);
    }
    Set<String> unrecorded = new HashSet<>();
    // Not read: whether the page can see the others is not known.
    if (read == null) {
      for (Rests.RestInvoice invoice : unknown) unrecorded.add(invoice.paymentHash());
      return unrecorded;
    }
    for (Rests.RestInvoice invoice : unknown) {
      if (read.held().contains(invoice.paymentHash())) continue;
      // A record of a newer format at its record address: a newer version recorded it, never
      // written over (SPEC.md §12); it counts as known.
      if (read.newer().contains(Derive.restRecordAddress(keys.key(), invoice.paymentHash()))) {
        continue;
      }
      if (!publishRest(stored, invoice, seconds(), pool, merchant, warn)) {
        unrecorded.add(invoice.paymentHash());
      }
    }
    return unrecorded;
  }

  // ---------------------------------------------------------------------------------------------
  // waitForPayment()

  /**
   * Checks the payment until it is paid or expired (SPEC.md §23, §31, "Waiting for an on-chain
   * payment"). Network errors are retried (one that each check meets again logged once), {@code
   * onStatus} hears each status.
   *
   * @param stored the invoice, checked
   * @param interval between two checks, in milliseconds
   * @param onStatus hears each status, or null
   * @return the final status
   * @throws InterruptedException when the call is cancelled
   */
  public PaymentStatus waitForPayment(
      Orders.StoredInvoice stored, long interval, Consumer<PaymentStatus> onStatus)
      throws InterruptedException {
    OnceWarnings warnings = new OnceWarnings(logger);
    if (stored.onchainAddress() != null) {
      return new AddressWait(stored, interval, onStatus, warnings).run();
    }
    for (; ; ) {
      try {
        Parts parts = check(stored, null, warnings.logger(), Call.of());
        PaymentStatus status = statusOf(stored, parts, seconds());
        if (onStatus != null) onStatus.accept(status);
        if (status.isFinal()) return status;
      } catch (NetworkException e) {
        warnings.failed(e);
      }
      warnings.checked();
      Thread.sleep(interval);
    }
  }

  /**
   * The warnings of the checks of one waitForPayment (SPEC.md §23): a warning each check meets
   * again is logged once, and again only once a check went without it.
   */
  private static final class OnceWarnings {
    private final Warn logger;
    private Set<String> before = new HashSet<>();
    private Set<String> now = new HashSet<>();

    OnceWarnings(Warn logger) {
      this.logger = logger;
    }

    Warn logger() {
      return message -> {
        boolean log;
        synchronized (this) {
          log = !before.contains(message) && !now.contains(message);
          now.add(message);
        }
        if (log) logger.warn(message);
      };
    }

    /** Warns of a check that failed: it is checked again. */
    void failed(NetworkException error) {
      logger()
          .warn(
              Warn.PREFIX + "the payment check failed (" + error.getMessage() + "); checked again");
    }

    /** A check ended: a warning it did not meet stopped. */
    synchronized void checked() {
      before = now;
      now = new HashSet<>();
    }
  }

  /** A part not checked yet. */
  private static final Object UNSET = new Object();

  /**
   * {@code waitForPayment} of an invoice with an address: each status combines the last result of
   * each part. A full check at each change the explorer's WebSocket pushes and every 60 s; between
   * them, the Lightning invoice and the rest invoices the last full check found, every {@code
   * interval}. Its state lives on the calling thread, which runs what comes in, in order.
   */
  private final class AddressWait {
    private final Orders.StoredInvoice stored;
    private final long interval;
    private final Consumer<PaymentStatus> onStatus;
    private final OnceWarnings warnings;
    private final LinkedBlockingQueue<Runnable> events = new LinkedBlockingQueue<>();
    private final Scope scope = new Scope();

    /**
     * The explorer's reads of this call and its WebSocket share the node's failures; each channel
     * read names the node the WebSocket follows.
     */
    private final Call call =
        new Call(new CallExplorer.Failures(), node -> events.add(() -> watch(node)));

    /**
     * The explorer's WebSocket: of the node the last channel read names (SPEC.md §31), with its
     * fallback, opened once a check read the channel (before, whether mempool.space may be asked is
     * not known), and opened again when the channel names another node; null before.
     */
    private Watch watcher;

    /** What the WebSocket follows: the node's fallback and URL, or mempool.space. */
    private String watched;

    /** The last result of each part; UNSET until it was checked once, null when there is none. */
    private Object lightning;

    private Object onchain = UNSET;
    private Payments.RestPart rests;
    private boolean done;
    private PaymentStatus outcome;
    private RuntimeException failure;
    private ScheduledFuture<?> lightningTimer;
    private ScheduledFuture<?> onchainTimer;
    private boolean checking;
    private boolean again;
    private boolean lightningChecking;

    /** Full checks started, the last one that ended, and the first one a status must wait for. */
    private int started;

    private int ended;
    private int waitFor;

    AddressWait(
        Orders.StoredInvoice stored,
        long interval,
        Consumer<PaymentStatus> onStatus,
        OnceWarnings warnings) {
      this.stored = stored;
      this.interval = interval;
      this.onStatus = onStatus;
      this.warnings = warnings;
      this.lightning = stored.lightning() == null ? null : UNSET;
    }

    PaymentStatus run() throws InterruptedException {
      try {
        checkOnchain();
        while (!done) events.take().run();
        if (failure != null) throw failure;
        return outcome;
      } finally {
        done = true;
        if (watcher != null) watcher.close();
        if (lightningTimer != null) lightningTimer.cancel(false);
        if (onchainTimer != null) onchainTimer.cancel(false);
        scope.close();
      }
    }

    /** The WebSocket of {@code node} (null: mempool.space's), unless it is the one open. */
    private void watch(BitcoinNode node) {
      String key = node == null ? "mempool" : node.fallback().json() + " " + node.url();
      if (done || key.equals(watched)) return;
      if (watcher != null) watcher.close();
      watched = key;
      watcher =
          Watch.watchAddress(
              sockets,
              Duration.ofMillis(relayTimeoutMs),
              new CallExplorer(node, call.failures(), clock::millis),
              stored.onchainAddress(),
              () -> events.add(this::checkOnchain));
    }

    private void finish(PaymentStatus status, RuntimeException error) {
      if (done) return;
      done = true;
      outcome = status;
      failure = error;
    }

    @SuppressWarnings("unchecked")
    private Tasks.Settled<Lnurl.LightningPart> lightningPart() {
      return (Tasks.Settled<Lnurl.LightningPart>) lightning;
    }

    @SuppressWarnings("unchecked")
    private Tasks.Settled<Payments.OnchainPart> onchainPart() {
      return (Tasks.Settled<Payments.OnchainPart>) onchain;
    }

    private void report() {
      if (done || lightning == UNSET || onchain == UNSET) return;
      // A rest invoice was paid after the last full check began: its on-chain part was judged
      // against what was due then, never combined with it.
      if (ended < waitFor) return;
      PaymentStatus status;
      try {
        status = statusOf(stored, new Parts(lightningPart(), onchainPart(), rests), seconds());
        if (onStatus != null) onStatus.accept(status);
      } catch (NetworkException e) {
        warnings.failed(e);
        return;
      } catch (RuntimeException e) {
        finish(null, e);
        return;
      }
      if (status.isFinal()) finish(status, null);
    }

    /** Whether a Lightning invoice (the order's, or one of the rest) may still change. */
    private boolean lightningPending() {
      boolean own = lightning == UNSET || (lightning != null && !isFinal(lightningPart()));
      if (own) return true;
      if (rests == null) return false;
      for (Tasks.Settled<Lnurl.LightningPart> result : rests.results()) {
        if (!isFinal(result)) return true;
      }
      return false;
    }

    private void scheduleLightning(long delay) {
      if (done || lightningTimer != null || lightningChecking || !lightningPending()) return;
      lightningTimer = Tasks.schedule(delay, () -> events.add(this::checkLightning));
    }

    private void checkLightning() {
      lightningTimer = null;
      if (done) return;
      lightningChecking = true;
      Payments.RestPart known = rests;
      boolean ownPending =
          stored.lightning() != null
              && (lightning == UNSET || (lightning != null && !isFinal(lightningPart())));
      CompletableFuture<Tasks.Settled<Lnurl.LightningPart>> own =
          ownPending
              ? scope.track(Tasks.settle(Lnurl.verifyPayment(http, stored.lightning(), seconds())))
              : null;
      List<CompletableFuture<Tasks.Settled<Lnurl.LightningPart>>> results = new ArrayList<>();
      if (known != null) {
        for (int index = 0; index < known.invoices().size(); index += 1) {
          Tasks.Settled<Lnurl.LightningPart> result = known.results().get(index);
          results.add(
              isFinal(result)
                  ? CompletableFuture.completedFuture(result)
                  : scope.track(
                      Tasks.settle(
                          Lnurl.verifyPayment(
                              http, lightningOf(known.invoices().get(index)), seconds()))));
        }
      }
      List<CompletableFuture<?>> all = new ArrayList<>(results);
      if (own != null) all.add(own);
      Tasks.all(all)
          .whenComplete(
              (ignored, error) -> events.add(() -> lightningChecked(own, results, known)));
    }

    private void lightningChecked(
        CompletableFuture<Tasks.Settled<Lnurl.LightningPart>> own,
        List<CompletableFuture<Tasks.Settled<Lnurl.LightningPart>>> answers,
        Payments.RestPart known) {
      lightningChecking = false;
      if (done) return;
      if (own != null) lightning = own.getNow(null);
      boolean paid = false;
      // A full check may have found other rest invoices meanwhile: its results stand.
      if (known != null && rests == known) {
        List<Tasks.Settled<Lnurl.LightningPart>> results = new ArrayList<>();
        for (int index = 0; index < answers.size(); index += 1) {
          Tasks.Settled<Lnurl.LightningPart> result = answers.get(index).getNow(null);
          results.add(result);
          if (isPaid(result) && !isPaid(known.results().get(index))) paid = true;
        }
        rests = new Payments.RestPart(known.invoices(), results, known.error(), known.unrecorded());
      }
      // Paid by a rest invoice: what is due on-chain changed, a full check tells the status.
      if (paid) {
        waitFor = started + 1;
        checkOnchain();
      } else {
        report();
      }
      scheduleLightning(interval);
    }

    private void checkOnchain() {
      if (done) return;
      if (checking) {
        again = true;
        return;
      }
      checking = true;
      started += 1;
      int mine = started;
      // Every 60 s at least: a socket may go quiet without closing.
      if (onchainTimer != null) onchainTimer.cancel(false);
      onchainTimer =
          Tasks.schedule(Constants.ONCHAIN_CHECK_INTERVAL_MS, () -> events.add(this::checkOnchain));
      scope
          .fork(() -> check(stored, null, warnings.logger(), call))
          .future()
          .whenComplete((parts, error) ->
              events.add(
                  () -> onchainChecked(mine, parts, error == null ? null : Tasks.unwrap(error))));
    }

    private void onchainChecked(int mine, Parts parts, Throwable error) {
      checking = false;
      if (done) return;
      if (error != null) {
        finish(null, Tasks.rethrown(error));
        return;
      }
      ended = mine;
      onchain = parts.onchain();
      rests = parts.rests();
      if (parts.lightning() != null) lightning = parts.lightning();
      report();
      // A full check ended: what it, and the checks of the Lightning invoices since the last one,
      // no longer met has stopped.
      warnings.checked();
      scheduleLightning(interval);
      if (again) {
        again = false;
        checkOnchain();
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // findInvoices()

  /**
   * The invoices declared for the order {@code ref} in the merchant's registry, oldest first
   * (SPEC.md §24).
   *
   * @param ref the reference, checked
   * @return the invoices
   * @throws InterruptedException when the call is cancelled
   */
  public List<DeclaredInvoice> findInvoices(String ref) throws InterruptedException {
    return withRelays((pool, scope) -> {
      MerchantChannel merchant = channel(pool, logger, new CallExplorer.Failures());
      Derive.Sale sale = Derive.Sale.of(access.key(), merchant.registry(), ref);
      List<DeclaredInvoice> invoices = new ArrayList<>();
      for (Sales.SaleDeclaration declaration :
          declared(pool, sale, merchant, false, logger).invoices()) {
        invoices.add(declaration.invoice());
      }
      return List.copyOf(invoices);
    });
  }

  private Sales.SaleRecords declared(
      RelayPool pool, Derive.Sale sale, MerchantChannel merchant, boolean everyRelay, Warn warn)
      throws InterruptedException {
    return Sales.readSaleDeclarations(pool, sale, merchant.relays(), warn, everyRelay);
  }

  /**
   * The integration's channel, read on the connections of {@code pool}, with the explorer of the
   * call: the node it names with its fallback, else mempool.space, the node's failures those of the
   * call ({@code failures}).
   */
  private MerchantChannel channel(RelayPool pool, Warn warn, CallExplorer.Failures failures)
      throws InterruptedException {
    Channels.Channel channel = Channels.readChannel(pool, access, random, warn).channel();
    return new MerchantChannel(
        channel.businessName(),
        channel.lightningAddress(),
        Declarations.registryOf(channel.registryKey(), channel.registryReader()),
        channel.relays() != null ? channel.relays() : access.relays(),
        channel.checkoutOrigin(),
        channel.tolerance(),
        channel.bitcoinNode(),
        new CallExplorer(channel.bitcoinNode(), failures, clock::millis));
  }

  /** What the Lightning part of a payment check needs of a rest invoice. */
  private static Lnurl.LightningInvoice lightningOf(Rests.RestInvoice invoice) {
    return new Lnurl.LightningInvoice(
        invoice.bolt11(), invoice.paymentHash(), invoice.verifyUrl(), invoice.expiresAt());
  }

  /** Whether a Lightning part is final: paid, or expired for good. */
  private static boolean isFinal(Tasks.Settled<Lnurl.LightningPart> result) {
    return result != null && result.ok() && result.value().isFinal();
  }

  private static boolean isPaid(Tasks.Settled<Lnurl.LightningPart> result) {
    return result != null && result.ok() && result.value().isPaid();
  }

  /** What a payment check asks of a declared invoice. */
  private static Orders.StoredInvoice storedOf(DeclaredInvoice invoice) {
    return new Orders.StoredInvoice(
        invoice.ref(),
        invoice.bolt11() == null
            ? null
            : new Lnurl.LightningInvoice(
                invoice.bolt11(), invoice.paymentHash(), invoice.verifyUrl(), invoice.expiresAt()),
        invoice.onchainAddress(),
        Long.parseLong(invoice.sats()),
        invoice.expiresAt());
  }

  /**
   * {@code items} in a random order, from the CSPRNG (so that two servers rarely try the same
   * address first): Fisher–Yates, each draw without bias.
   *
   * @param items the items
   * @param random the CSPRNG
   * @param <T> their type
   * @return them, shuffled
   */
  public static <T> List<T> shuffle(List<T> items, RandomBytes random) {
    List<T> shuffled = new ArrayList<>(items);
    for (int i = shuffled.size() - 1; i > 0; i -= 1) {
      long bound = i + 1;
      // The largest multiple of bound within 2^32: draws at or above it are drawn again.
      long limit = (1L << 32) - ((1L << 32) % bound);
      long draw;
      do {
        byte[] bytes = random.next(4);
        draw =
            ((bytes[0] & 0xffL) << 24)
                | ((bytes[1] & 0xffL) << 16)
                | ((bytes[2] & 0xffL) << 8)
                | (bytes[3] & 0xffL);
      } while (draw >= limit);
      int j = (int) (draw % bound);
      T item = shuffled.get(i);
      shuffled.set(i, shuffled.get(j));
      shuffled.set(j, item);
    }
    return shuffled;
  }

  /**
   * The keys still locked (for tests).
   *
   * @return how many orders and rests are locked
   */
  public int locked() {
    return orders.size() + restAsks.size();
  }

  /**
   * The relays of the integration key (for its description).
   *
   * @return its relays
   */
  public List<String> keyRelays() {
    return access.relays();
  }

  /**
   * The client's rate sources.
   *
   * @return the sources
   */
  public List<RateSource> rateSources() {
    return rateSources;
  }
}
