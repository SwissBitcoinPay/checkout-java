package me.sattle.checkout.internal;

import java.util.List;
import java.util.Set;

/**
 * Every constant of the protocol, in one place (SPEC.md §28): every implementation, in any
 * language, uses these values. Those marked "never changes" are fixed by data already published
 * (keys, channels, declarations): changing one loses merchants' sales or stops their integrations.
 */
public final class Constants {
  private Constants() {}

  /** The beginning of every integration key. Never changes: a later format takes another prefix. */
  public static final String INTEGRATION_KEY_PREFIX = "sattleme_ck_";

  /** Bytes of the team's public key, of the integration's key, of every derived key. */
  public static final int KEY_BYTES = 32;

  /** HKDF-SHA256 salts (UTF-8). Never change. */
  public static final String INTEGRATION_SALT = "sattle.me/integration";

  public static final String SALE_SALT = "sattle.me/sale";
  public static final String DECLARATION_SALT = "sattle.me/registry-declaration";
  public static final String CHECKOUT_SALT = "sattle.me/checkout";

  /** HKDF-SHA256 {@code info} strings (UTF-8). Never change. */
  public static final String CHANNEL_ADDRESS_INFO = "channel/address/v1";

  public static final String CHANNEL_ENCRYPTION_INFO = "channel/encryption-key/v1";
  public static final String TAKER_INFO = "taker/v1";
  public static final String SALE_NOSTR_SECRET_INFO = "nostr-secret-key/v1";
  public static final String LOT_ADDRESS_INFO = "onchain-lot/address/v1";
  public static final String SEEN_SECRET_INFO = "onchain-seen/nostr-secret-key/v1";
  public static final String SEEN_ADDRESS_KEY_INFO = "onchain-seen/address-key/v1";
  public static final String CHECKOUT_ADDRESS_INFO = "address/v1";
  public static final String CHECKOUT_ENCRYPTION_INFO = "encryption-key/v1";
  public static final String REST_ENCRYPTION_INFO = "rest/encryption-key/v1";
  public static final String ACCEPTANCE_ADDRESS_INFO = "rest/accepted/v1";

  /**
   * The {@code info} of the sale key of {@code ref}.
   *
   * @param ref the order's reference
   * @return {@code sale/<ref>/v1}
   */
  public static String saleInfo(String ref) {
    return "sale/" + ref + "/v1";
  }

  /**
   * The {@code info} of the outer key of a record at {@code address} by {@code author}.
   *
   * @param address the record's {@code d} tag
   * @param author its author (hex)
   * @return {@code outer/v1/<address>/<author>}
   */
  public static String outerInfo(String address, String author) {
    return "outer/v1/" + address + "/" + author;
  }

  /**
   * The {@code info} of the rest key of the invoice of {@code ref} with {@code address} ending at
   * {@code expiresAt}.
   *
   * @param ref the order's reference
   * @param address the deposit address
   * @param expiresAt the invoice's end (Unix seconds)
   * @return {@code checkout-rest/<ref>/<address>/<expiresAt>/key/v1}
   */
  public static String restKeyInfo(String ref, String address, long expiresAt) {
    return "checkout-rest/" + ref + "/" + address + "/" + expiresAt + "/key/v1";
  }

  /**
   * The {@code info} of the rest secret of the invoice of {@code ref} with {@code address} ending
   * at {@code expiresAt}.
   *
   * @param ref the order's reference
   * @param address the deposit address
   * @param expiresAt the invoice's end (Unix seconds)
   * @return {@code checkout-rest/<ref>/<address>/<expiresAt>/nostr-secret-key/v1}
   */
  public static String restSecretInfo(String ref, String address, long expiresAt) {
    return "checkout-rest/" + ref + "/" + address + "/" + expiresAt + "/nostr-secret-key/v1";
  }

  /**
   * The {@code info} of the rest record address of the invoice whose payment hash is {@code
   * paymentHash}.
   *
   * @param paymentHash its payment hash (hex)
   * @return {@code rest/address/<hash>/v1}
   */
  public static String restAddressInfo(String paymentHash) {
    return "rest/address/" + paymentHash + "/v1";
  }

  /** Bytes of HMAC-SHA256(taker key, author) kept as the taker tag. */
  public static final int TAKER_TAG_BYTES = 16;

  /** NIP-78 application data: every record. */
  public static final int RECORD_KIND = 30078;

  /** NIP-42 authentication. */
  public static final int AUTH_KIND = 22242;

  /** A record's plaintext is padded with spaces to a multiple of this many bytes. */
  public static final int RECORD_BLOCK_BYTES = 256;

  public static final String CHANNEL_RECORD_TYPE = "team-channel";
  public static final int CHANNEL_VERSION = 1;

  /** At most this many moves of the merchant's relays are followed. */
  public static final int MAX_RELAY_MOVES = 3;

  /** Longest business name, in code points. */
  public static final int MAX_BUSINESS_NAME_LENGTH = 200;

  public static final String DECLARATION_RECORD_TYPE = "registry-declaration";
  public static final int DECLARATION_VERSION = 1;

  /** The currency of a sale declared in sats. */
  public static final String SATS_CURRENCY = "SAT";

  /** Most decimals a declared price may have. */
  public static final int MAX_DECLARED_DIGITS = 8;

  /** Largest amount in minor units: 2^53 − 1. */
  public static final long MAX_MINOR_UNITS = 9_007_199_254_740_991L;

  public static final String CHECKOUT_RECORD_TYPE = "checkout";
  public static final int CHECKOUT_VERSION = 1;

  /** The version of a checkout link's record for an invoice with an address. */
  public static final int CHECKOUT_ONCHAIN_VERSION = 2;

  /** The first part of a checkout link's fragment. Never changes. */
  public static final String CHECKOUT_MARKER = "c";

  /** The page of the merchant's web app that reads checkout links. */
  public static final String CHECKOUT_PATH = "/pay";

  /** A checkout link's record lives this long after its invoice's end (NIP-40): a week. */
  public static final long CHECKOUT_LIFE_SECONDS = 604_800;

  /** Longest redirect URL, in characters. */
  public static final int MAX_REDIRECT_URL_LENGTH = 2048;

  /** Longest scheme of an app's redirect URL. */
  public static final int MAX_REDIRECT_SCHEME_LENGTH = 32;

  /** Schemes a redirect URL never has (SPEC.md §30); {@code https} follows its own rule. */
  public static final Set<String> REFUSED_REDIRECT_SCHEMES =
      Set.of(
          "about",
          "blob",
          "chrome",
          "chrome-extension",
          "content",
          "data",
          "file",
          "filesystem",
          "javascript",
          "moz-extension",
          "resource",
          "safari-extension",
          "vbscript",
          "view-source",
          "brave",
          "com-apple-mobilesafari-tab",
          "ddgquicklink",
          "firefox",
          "firefox-focus",
          "googlechrome",
          "googlechrome-x-callback",
          "googlechromes",
          "microsoft-edge",
          "microsoft-edge-http",
          "microsoft-edge-https",
          "onionbrowser",
          "onionbrowsers",
          "opera-http",
          "opera-https",
          "x-safari-http",
          "x-safari-https",
          "ftp",
          "http",
          "ws",
          "wss",
          "android-app",
          "intent",
          "bitcoin",
          "keyauth",
          "lightning",
          "liquidnetwork",
          "lnurl",
          "lnurlc",
          "lnurlp",
          "lnurlw",
          "callto",
          "facetime",
          "facetime-audio",
          "mailto",
          "sip",
          "sms",
          "tel",
          "itms-services",
          "ms-msdt",
          "search-ms");

  /** Most relays a list holds. */
  public static final int MAX_RELAYS = 10;

  /** Longest relay address, {@code wss://} included, in code points. */
  public static final int MAX_RELAY_URL_LENGTH = 120;

  /** Longest host of a relay address, a checkout origin, a redirect URL or an https URL. */
  public static final int MAX_HOST_LENGTH = 253;

  /** Longest https URL, in characters. */
  public static final int MAX_HTTPS_URL_LENGTH = 2048;

  /** Invoice durations, in seconds. */
  public static final int MIN_DURATION_SECONDS = 60;

  public static final int MAX_DURATION_SECONDS = 3600;
  public static final int DEFAULT_DURATION_SECONDS = 900;

  /** Longest note, in code points. */
  public static final int MAX_NOTE_LENGTH = 100;

  /** After an invoice's end, a payment of its last seconds may still be reported. */
  public static final long GRACE_SECONDS = 120;

  /** An invoice's date may be this far from the server's clock. */
  public static final long CLOCK_TOLERANCE_SECONDS = 300;

  /** The LUD-21 refusal that means "never paid" once the grace period is over. */
  public static final String NOT_FOUND_REASON = "Not found";

  /** A dated price older than this is refused. */
  public static final long MAX_RATE_AGE_SECONDS = 3600;

  /** Longest price text a rate source may give. */
  public static final int MAX_PRICE_LENGTH = 64;

  /** Largest exponent (either sign) of a price in exponent notation. */
  public static final int MAX_PRICE_EXPONENT = 64;

  /** Most events read from one relay for one query. */
  public static final int MAX_EVENTS_PER_QUERY = 1000;

  /** Most pages of a paged or complete read. */
  public static final int MAX_PAGES = 20;

  /** The {@code limit} of each page of a complete read. */
  public static final int COMPLETE_READ_LIMIT = 100;

  /** The User-Agent of every HTTP request and WebSocket handshake. */
  public static final String USER_AGENT = "sattleme-Checkout/1";

  /** Largest message read from a relay, and largest HTTP answer body read. */
  public static final int MAX_RELAY_MESSAGE_BYTES = 1 << 20;

  public static final int MAX_HTTP_BODY_BYTES = 1 << 20;

  /** Earlier invoices of an order checked at once. */
  public static final int MAX_PARALLEL_PAYMENT_CHECKS = 4;

  /** Per request timeouts, in milliseconds. */
  public static final long DEFAULT_TIMEOUT_MS = 10_000;

  public static final long MIN_TIMEOUT_MS = 100;
  public static final long MAX_TIMEOUT_MS = 60_000;

  /** waitForPayment's interval, in milliseconds. */
  public static final long DEFAULT_INTERVAL_MS = 2000;

  public static final long MIN_INTERVAL_MS = 250;
  public static final long MAX_INTERVAL_MS = 60_000;

  /** Longest {@code x} (expiry) field of a BOLT11 invoice, in 5-bit words. */
  public static final int MAX_BOLT11_EXPIRY_WORDS = 7;

  /** Largest BOLT11 amount: 21 million bitcoins, in millisatoshis. */
  public static final long MAX_BOLT11_AMOUNT_MSAT = 2_100_000_000_000_000_000L;

  /** Below this amount, an order is Lightning only. */
  public static final long ONCHAIN_FLOOR_SATS = 1000;

  /** The largest amount of an order: 21 million bitcoins, in sats. */
  public static final long MAX_SATS = 2_100_000_000_000_000L;

  public static final long SATS_PER_BTC = 100_000_000L;

  public static final String LOT_RECORD_TYPE = "integration-onchain-lot";
  public static final int LOT_VERSION = 1;

  /** Most addresses a lot holds. */
  public static final int MAX_LOT_ADDRESSES = 100;

  public static final String LEASE_RECORD_TYPE = "registry-onchain";
  public static final int LEASE_VERSION = 1;

  /** What a deposit address's registry address is the HMAC of: this, then the address. */
  public static final String LEASE_ADDRESS_PREFIX = "onchain/v1/";

  /** Leases read back this far. */
  public static final long LEASE_LOOKBACK_SECONDS = 7200;

  /** Free addresses tried for one order. */
  public static final int MAX_ADDRESS_TRIES = 3;

  public static final String WITNESS_RECORD_TYPE = "integration-onchain-seen";
  public static final int WITNESS_VERSION = 1;

  /** The tag of a witness's order tag and address tag. */
  public static final String ORDER_TAG = "o";

  /** The coins of a transaction a witness keeps. */
  public static final int MAX_WITNESS_INPUTS = 100;

  /** The {@code sale} a witness names when the transaction came at no lease's time. */
  public static final String NO_SALE = "none";

  /** mempool.space (Esplora REST): the explorer, unless the channel names the merchant's node. */
  public static final String MEMPOOL_URL = "https://mempool.space/api";

  /** mempool.space's WebSocket. */
  public static final String MEMPOOL_WEBSOCKET_URL = "wss://mempool.space/api/v1/ws";

  /** The longest node URL of the merchant's Bitcoin node (SPEC.md §31, "A node URL"). */
  public static final int MAX_NODE_URL_LENGTH = 200;

  /**
   * A node that failed a read less than this long ago, in the same call, is asked after
   * mempool.space (its {@code fallback} {@code "mempool"}, SPEC.md §31).
   */
  public static final long NODE_FAILED_RECENTLY_MS = 60_000;

  /** When a transaction was received. */
  public static final String BLOCKCYPHER_URL = "https://api.blockcypher.com/v1/btc/main";

  /** Confirmations of an on-chain payment, on the explorer read. */
  public static final int CONFIRMATIONS = 3;

  /** The on-chain part is checked at least this often while waitForPayment waits. */
  public static final long ONCHAIN_CHECK_INTERVAL_MS = 60_000;

  /** The explorer's WebSocket, lost: connected again after this, doubled at each failure… */
  public static final long RECONNECT_FIRST_MS = 1000;

  /** …up to this. */
  public static final long RECONNECT_MAX_MS = 30_000;

  public static final String REST_RECORD_TYPE = "checkout-rest";
  public static final int REST_VERSION = 1;

  /** A rest invoice is asked only with this long left before the invoice's end. */
  public static final long MIN_REST_SECONDS = 60;

  /** The largest tolerance, in basis points (10 %). */
  public static final int MAX_TOLERANCE = 1000;

  /** Basis points in a whole. */
  public static final long TOLERANCE_SCALE = 10_000;

  public static final String ACCEPTANCE_RECORD_TYPE = "checkout-accepted";
  public static final int ACCEPTANCE_VERSION = 1;

  /**
   * The merchant's acceptance (SPEC.md §31, "The merchant's acceptance"): the merchant accepts by
   * hand, in its app, what an order underpaid on-chain received as paying it. A record of the team
   * (never the integration's: it opens one but cannot make one), sealed with the channel's key, at
   * an address the integration key derives ({@link #INTEGRATION_SALT}, {@link
   * #merchantAcceptanceInfo}). The type never changes: the merchant's app writes the same records.
   */
  public static final String MERCHANT_ACCEPTANCE_RECORD_TYPE = "integration-onchain-accepted";

  public static final int MERCHANT_ACCEPTANCE_VERSION = 1;

  /**
   * The {@code info} of the merchant's acceptance address of the invoice of the sale {@code sale}
   * with the deposit address {@code address} ending at {@code expiresAt}. Never changes.
   *
   * @param sale the sale author (hex)
   * @param address the deposit address
   * @param expiresAt the invoice's end (Unix seconds)
   * @return {@code onchain-accepted/<sale>/<address>/<expiresAt>/v1}
   */
  public static String merchantAcceptanceInfo(String sale, String address, long expiresAt) {
    return "onchain-accepted/" + sale + "/" + address + "/" + expiresAt + "/v1";
  }

  /** The rate sources known by name, as SPEC.md §26 lists them. */
  public static final List<String> RATE_SOURCE_NAMES =
      List.of("coingecko", "mempool", "opennode", "coinbase", "kraken");

  /** The currencies of mempool.space's prices. */
  public static final List<String> MEMPOOL_CURRENCIES =
      List.of("USD", "EUR", "GBP", "CAD", "CHF", "AUD", "JPY");
}
