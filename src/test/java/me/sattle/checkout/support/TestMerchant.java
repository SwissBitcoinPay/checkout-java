package me.sattle.checkout.support;

import java.math.BigInteger;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import me.sattle.checkout.SattlemeClient;
import me.sattle.checkout.internal.Base64Strict;
import me.sattle.checkout.internal.Constants;
import me.sattle.checkout.internal.Derive;
import me.sattle.checkout.internal.Hex;
import me.sattle.checkout.internal.RandomBytes;
import me.sattle.checkout.internal.Text;
import me.sattle.checkout.internal.crypto.Curve;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.internal.nostr.NostrEvent;
import me.sattle.checkout.internal.nostr.Records;

/**
 * A merchant for tests, as the app would set it up: a team, an integration's key, the channel the
 * team publishes on the test's relays, the registry, and a Lightning address (breez.tips) answered
 * by a fake HTTP client that makes real-looking invoices.
 */
public final class TestMerchant {
  /** The merchant's Lightning address. */
  public static final String LIGHTNING_ADDRESS = "sattleme02c6047f94@breez.tips";

  /** Its LNURL metadata. */
  public static final String METADATA =
      Js.write(
          Js.arr(
              Js.arr("text/identifier", LIGHTNING_ADDRESS),
              Js.arr("text/plain", "Pay to Sunrise Bakery")));

  /** Its pay URL. */
  public static final String PAY_URL = "https://breez.tips/.well-known/lnurlp/sattleme02c6047f94";

  /** Its callback. */
  public static final String CALLBACK = "https://breez.tips/lnurlp/sattleme02c6047f94/invoice";

  /** The relays of the test, the explorer's WebSocket among them. */
  public final RelayNet net = new RelayNet();

  /** The team's secret. */
  public final byte[] teamSecret = FixedRandom.bytes(1);

  /** The team (hex). */
  public final String team = Curve.publicKeyOf(teamSecret);

  /** The integration's key. */
  public final byte[] integrationKey = FixedRandom.bytes(2);

  /** The registry key. */
  public final byte[] registryKey = FixedRandom.bytes(3);

  /** The merchant's reading secret. */
  public final byte[] readerSecret = FixedRandom.bytes(4);

  /** The registry reader (hex). */
  public final String reader = Curve.publicKeyOf(readerSecret);

  /** The relays of the key. */
  public List<String> keyRelays =
      new ArrayList<>(List.of("wss://relay-a.test", "wss://relay-b.test", "wss://relay-c.test"));

  /** Unix seconds: the clock of the test. */
  public volatile long now = 1_791_378_000L;

  /**
   * An invoice the fake breez.tips made, with what pays it.
   *
   * @param pr the invoice
   * @param verify its verify URL
   * @param paymentHash its payment hash
   * @param preimage its preimage
   * @param amountMsat its amount
   * @param expiry its expiry
   * @param comment the comment it was asked with, or null
   */
  public record MadeInvoice(
      String pr,
      String verify,
      String paymentHash,
      String preimage,
      BigInteger amountMsat,
      long expiry,
      String comment) {}

  /** The invoices breez.tips made, by payment hash. */
  public final Map<String, MadeInvoice> invoices =
      java.util.Collections.synchronizedMap(new LinkedHashMap<>());

  /** Payment hashes paid. */
  public final Set<String> paid = ConcurrentHashMap.newKeySet();

  /** What breez.tips's pay request says. */
  public volatile JsonObject pay =
      Js.obj(
          "tag",
          "payRequest",
          "callback",
          CALLBACK,
          "minSendable",
          1000,
          "maxSendable",
          100_000_000_000L,
          "metadata",
          METADATA,
          "commentAllowed",
          255);

  /** Changes the invoice breez.tips gives (a test of a check). */
  public volatile Function<MadeInvoice, MadeInvoice> tamper;

  /** The explorers and BlockCypher. */
  public final Chain chain = new Chain();

  /** Overrides the answer of a URL (null: not overridden). */
  public volatile Function<URI, FakeHttp.Answer> override;

  private int seed = 10;

  /** The HTTP client of the client. */
  public final FakeHttp http = new FakeHttp(this::route);

  /** The warnings the client logged. */
  public final List<String> warnings = new CopyOnWriteArrayList<>();

  /** A merchant of a test. */
  public TestMerchant() {}

  /**
   * The text of an integration key, as the merchant's app writes it.
   *
   * @param team the team (hex)
   * @param key the key
   * @param relays its relays
   * @return the key's text
   */
  public static String formatKey(String team, byte[] key, List<String> relays) {
    List<String> hosts = new ArrayList<>();
    for (String relay : relays) hosts.add(relay.substring("wss://".length()));
    return Constants.INTEGRATION_KEY_PREFIX
        + String.join(
            ".",
            Base64Strict.encodeUrl(Hex.decode(team)),
            Base64Strict.encodeUrl(key),
            Base64Strict.encodeUrl(Text.utf8(String.join(",", hosts))));
  }

  /**
   * The integration key's text.
   *
   * @return the key
   */
  public String key() {
    return formatKey(team, integrationKey, keyRelays);
  }

  /**
   * Starts the key's relays and {@code others}.
   *
   * @param others more relays
   * @return this merchant
   */
  public TestMerchant start(String... others) {
    Set<String> urls = new LinkedHashSet<>(keyRelays);
    urls.addAll(List.of(others));
    for (String url : urls) net.add(url);
    return this;
  }

  /**
   * Starts the key's relays and {@code others}, each with its behaviour.
   *
   * @param others more relays
   * @param behaviours the behaviour of each relay that has one
   * @return this merchant
   */
  public TestMerchant start(List<String> others, Map<String, LocalRelay.Behaviour> behaviours) {
    Set<String> urls = new LinkedHashSet<>(keyRelays);
    urls.addAll(others);
    for (String url : urls) net.add(url, behaviours.getOrDefault(url, new LocalRelay.Behaviour()));
    return this;
  }

  /** Stops the relays and the HTTP client. */
  public void stop() {
    net.close();
    http.shutdown();
  }

  /**
   * The test's clock: {@link #now}, as milliseconds.
   *
   * @return the clock
   */
  public Clock clock() {
    return new Clock() {
      @Override
      public ZoneOffset getZone() {
        return ZoneOffset.UTC;
      }

      @Override
      public Clock withZone(java.time.ZoneId zone) {
        return this;
      }

      @Override
      public Instant instant() {
        return Instant.ofEpochSecond(now);
      }
    };
  }

  /**
   * A client of this merchant's integration, its warnings in {@link #warnings}.
   *
   * @return the builder, ready to build
   */
  public SattlemeClient.Builder clientBuilder() {
    return SattlemeClient.builder()
        .key(key())
        .httpClient(http)
        .webSocketFactory(net)
        .relayTimeoutMs(2000)
        .httpTimeoutMs(2000)
        .clock(clock())
        .logger(new ListLogger(warnings));
  }

  /**
   * A client of this merchant's integration.
   *
   * @return the client
   */
  public SattlemeClient client() {
    return clientBuilder().build();
  }

  /**
   * The channel's value, active, as the merchant's app writes it.
   *
   * @param createdAt its date
   * @return the value
   */
  public JsonObject channelValue(long createdAt) {
    return channelValue(createdAt, Map.of());
  }

  /**
   * The channel's value, active, with more fields.
   *
   * @param createdAt its date
   * @param extra fields that replace or follow the others
   * @return the value
   */
  public JsonObject channelValue(long createdAt, Map<String, Object> extra) {
    JsonObject value =
        Js.obj(
            "version",
            1,
            "status",
            "active",
            "name",
            "Shopify store",
            "role",
            "integration",
            "profile",
            Js.obj(
                "businessName",
                "Sunrise Bakery",
                "lightningAddress",
                LIGHTNING_ADDRESS,
                "currency",
                "CHF",
                "onchain",
                true,
                "logo",
                null),
            "registryKey",
            Hex.encode(registryKey),
            "registryReader",
            reader,
            "linkOwner",
            "ab".repeat(32),
            "updatedAt",
            createdAt);
    for (Map.Entry<String, Object> entry : new LinkedHashMap<>(extra).entrySet()) {
      value.put(entry.getKey(), Js.of(entry.getValue()));
    }
    return value;
  }

  /**
   * The channel's event, signed by {@code signer}.
   *
   * @param value its value
   * @param createdAt its date
   * @param signer the author's secret
   * @return the event
   */
  public NostrEvent channelEvent(JsonValue value, long createdAt, byte[] signer) {
    return Records.seal(
        Constants.CHANNEL_RECORD_TYPE,
        value,
        signer,
        Derive.channelEncryptionKey(integrationKey),
        Derive.channelAddress(integrationKey),
        createdAt,
        List.of(),
        new RandomBytes(new SecureRandom()));
  }

  /**
   * Publishes the channel on the key's relays.
   *
   * @param value its value
   * @param createdAt its date
   * @return its event
   */
  public NostrEvent publishChannel(JsonValue value, long createdAt) {
    return publishChannel(value, createdAt, keyRelays, teamSecret);
  }

  /**
   * Publishes the channel on {@code relays}, signed by {@code signer}.
   *
   * @param value its value
   * @param createdAt its date
   * @param relays where
   * @param signer the author's secret
   * @return its event
   */
  public NostrEvent publishChannel(
      JsonValue value, long createdAt, List<String> relays, byte[] signer) {
    NostrEvent event = channelEvent(value, createdAt, signer);
    for (String relay : relays) net.get(relay).store(event);
    return event;
  }

  /**
   * Publishes the integration's lot on the key's relays, as the merchant's app does.
   *
   * @param addresses its addresses
   * @param createdAt its date
   * @return its event
   */
  public NostrEvent publishLot(List<String> addresses, long createdAt) {
    return publishLot(addresses, createdAt, keyRelays);
  }

  /**
   * Publishes the integration's lot on {@code relays}.
   *
   * @param addresses its addresses
   * @param createdAt its date
   * @param relays where
   * @return its event
   */
  public NostrEvent publishLot(List<String> addresses, long createdAt, List<String> relays) {
    NostrEvent event =
        Records.seal(
            Constants.LOT_RECORD_TYPE,
            Js.obj("version", 1, "addresses", addresses, "updatedAt", createdAt),
            teamSecret,
            Derive.channelEncryptionKey(integrationKey),
            Derive.lotAddress(integrationKey),
            createdAt,
            List.of(),
            new RandomBytes(new SecureRandom()));
    for (String relay : relays) net.get(relay).store(event);
    return event;
  }

  /**
   * Starts the explorer's WebSocket.
   *
   * @return its server
   */
  public RelayNet.ExplorerSocket startExplorerSocket() {
    return net.addServer(Constants.MEMPOOL_WEBSOCKET_URL);
  }

  /**
   * Marks an invoice paid on breez.tips.
   *
   * @param paymentHash its payment hash
   */
  public void payInvoice(String paymentHash) {
    paid.add(paymentHash);
  }

  /**
   * What the merchant's fakes answer to {@code uri}: for a test's HTTP client of its own.
   *
   * @param uri the URL asked
   * @return the answer
   * @throws Exception no answer
   */
  public FakeHttp.Answer answer(URI uri) throws Exception {
    return route(uri);
  }

  private synchronized FakeHttp.Answer route(URI uri) throws Exception {
    Function<URI, FakeHttp.Answer> overriding = override;
    if (overriding != null) {
      FakeHttp.Answer overridden = overriding.apply(uri);
      if (overridden != null) return overridden;
    }
    FakeHttp.Answer onchain = chain.route(uri);
    if (onchain != null) return onchain;
    String href = uri.toString();
    if (href.equals(PAY_URL)) return FakeHttp.Answer.json(pay);
    String path = uri.getScheme() + "://" + uri.getRawAuthority() + uri.getRawPath();
    if (path.equals(CALLBACK)) {
      FakeHttp.Call call = new FakeHttp.Call(uri, null);
      BigInteger amount = new BigInteger(call.query("amount"));
      String expiryText = call.query("expiry");
      long expiry = expiryText == null ? 3600 : Long.parseLong(expiryText);
      Lightning.Pair pair = Lightning.preimagePair(seed++);
      Lightning.Spec spec = new Lightning.Spec();
      spec.amountMsat = amount;
      spec.paymentHash = pair.paymentHash();
      spec.descriptionHash = Lightning.metadataHash(METADATA);
      spec.timestamp = now;
      spec.expirySeconds = expiry;
      MadeInvoice made =
          new MadeInvoice(
              Lightning.makeInvoice(spec),
              "https://breez.tips/lnurlp/verify/" + pair.paymentHash(),
              pair.paymentHash(),
              pair.preimage(),
              amount,
              expiry,
              call.query("comment"));
      Function<MadeInvoice, MadeInvoice> changing = tamper;
      if (changing != null) made = changing.apply(made);
      invoices.put(made.paymentHash(), made);
      return FakeHttp.Answer.json(
          Js.obj("pr", made.pr(), "verify", made.verify(), "routes", List.of()));
    }
    if (href.startsWith("https://breez.tips/lnurlp/verify/")) {
      String hash = href.substring(href.lastIndexOf('/') + 1);
      MadeInvoice made = invoices.get(hash);
      if (made == null)
        return FakeHttp.Answer.json(Js.obj("status", "ERROR", "reason", "Not found"));
      boolean settled = paid.contains(hash);
      return FakeHttp.Answer.json(
          Js.obj(
              "status",
              "OK",
              "settled",
              settled,
              "preimage",
              settled ? made.preimage() : null,
              "pr",
              made.pr()));
    }
    throw new java.io.IOException("fetch failed: " + href);
  }

  /** A System.Logger that keeps the warnings in a list. */
  public static final class ListLogger implements System.Logger {
    private final List<String> messages;

    /**
     * A logger into {@code messages}.
     *
     * @param messages where the messages go
     */
    public ListLogger(List<String> messages) {
      this.messages = messages;
    }

    @Override
    public String getName() {
      return "test";
    }

    @Override
    public boolean isLoggable(Level level) {
      return true;
    }

    @Override
    public void log(
        Level level, java.util.ResourceBundle bundle, String message, Throwable thrown) {
      messages.add(message);
    }

    @Override
    public void log(Level level, java.util.ResourceBundle bundle, String format, Object... params) {
      messages.add(
          params == null || params.length == 0
              ? format
              : java.text.MessageFormat.format(format, params));
    }
  }
}
