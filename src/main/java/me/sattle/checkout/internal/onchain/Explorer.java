package me.sattle.checkout.internal.onchain;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.sattle.checkout.NetworkException;
import me.sattle.checkout.internal.Constants;
import me.sattle.checkout.internal.Http;
import me.sattle.checkout.internal.Tasks;
import me.sattle.checkout.internal.Text;
import me.sattle.checkout.internal.Urls;
import me.sattle.checkout.internal.json.Json;
import me.sattle.checkout.internal.json.JsonArray;
import me.sattle.checkout.internal.json.JsonBoolean;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonValue;

/**
 * What the blockchain shows (SPEC.md §31, "Explorers"): one explorer of the Esplora API, the
 * explorer, asked over HTTP: mempool.space, or the merchant's Bitcoin node when the channel names
 * one ({@link BitcoinNode}), mempool.space answering in its place when the node fails and its
 * {@code fallback} is {@code "mempool"} ({@link CallExplorer}); and BlockCypher, for when a
 * transaction was received, asked only about a transaction no check saw while its lease ran. Third
 * parties: they see the server's IP address and what it asks about, never a key. Their answers are
 * checked as strictly as a relay's: anything else is a failure ({@link NetworkException}). A
 * payment counts as made on what the explorer read alone shows.
 */
public final class Explorer {
  private Explorer() {}

  /**
   * An explorer a read may ask: mempool.space, or the merchant's Bitcoin node at a node URL. Made
   * from constants or from a parsed {@link NodeUrl} alone: the only URLs the package asks in plain
   * {@code http} are a node's of a local network (SPEC.md §31); every other URL it asks is {@code
   * https}, checked where it comes from.
   */
  public static final class Source {
    /** mempool.space: its REST URL and its WebSocket's. */
    public static final Source MEMPOOL =
        new Source(false, Constants.MEMPOOL_URL, Constants.MEMPOOL_WEBSOCKET_URL);

    private final boolean node;
    private final String rest;
    private final String webSocket;

    private Source(boolean node, String rest, String webSocket) {
      this.node = node;
      this.rest = rest;
      this.webSocket = webSocket;
    }

    /**
     * The merchant's Bitcoin node at {@code url}.
     *
     * @param url its node URL, checked
     * @return the explorer
     */
    public static Source node(NodeUrl url) {
      return new Source(true, url.rest(), url.webSocket());
    }

    /**
     * Whether it is the merchant's Bitcoin node (else mempool.space).
     *
     * @return true for the node
     */
    public boolean isNode() {
      return node;
    }

    /**
     * Its {@code <explorer>} (SPEC.md §31, "Explorers"): {@code https://mempool.space/api}, or the
     * node URL followed by {@code /api}.
     *
     * @return the REST URL
     */
    public String rest() {
      return rest;
    }

    /**
     * Its WebSocket: {@code wss://mempool.space/api/v1/ws}, or the node's ({@code ws://} for an
     * {@code http} node URL, {@code wss://} for an {@code https} one).
     *
     * @return the WebSocket's URL
     */
    public String webSocket() {
      return webSocket;
    }

    /**
     * Its name, as the vectors write it.
     *
     * @return {@code "node"} or {@code "mempool"}
     */
    public String name() {
      return node ? "node" : "mempool";
    }

    @Override
    public String toString() {
      return "Source[" + name() + "]";
    }
  }

  private static final long MAX_VOUT = 4_294_967_295L;

  /**
   * An output of a transaction.
   *
   * @param sats its value
   * @param address its {@code scriptpubkey_address}, or null
   */
  public record Output(long sats, String address) {}

  /**
   * A transaction, as an explorer gives it, checked.
   *
   * @param txid its id
   * @param coins the coins it spends ({@code <txid>:<vout>}), coinbase inputs left out, in order
   * @param outputs its outputs
   * @param blockHeight the height of its block; null while it is not confirmed
   */
  public record Tx(String txid, List<String> coins, List<Output> outputs, Long blockHeight) {
    /**
     * A checked transaction.
     *
     * @param txid its id
     * @param coins the coins it spends
     * @param outputs its outputs
     * @param blockHeight its block's height, or null
     */
    public Tx {
      coins = List.copyOf(coins);
      outputs = List.copyOf(outputs);
    }
  }

  private static Long safeNatural(JsonValue value) {
    Long number = Json.safeInteger(value);
    return number != null && number >= 0 ? number : null;
  }

  /**
   * An Esplora transaction, checked (SPEC.md §31, "Explorers"): its outputs together hold at most 2
   * 100 000 000 000 000 sats, so that what it pays an address is a witness's {@code sats}.
   *
   * @param value what the explorer sent
   * @return the transaction, or null when it is not one
   */
  public static Tx parseTransaction(JsonValue value) {
    JsonObject object = Json.object(value);
    if (object == null) return null;
    String txid = Json.string(object.get("txid"));
    if (!Text.isHex32(txid)) return null;
    JsonArray vin = Json.array(object.get("vin"));
    JsonArray vout = Json.array(object.get("vout"));
    JsonObject status = Json.object(object.get("status"));
    if (vin == null || vout == null || status == null) return null;
    List<String> coins = new ArrayList<>();
    for (JsonValue inputValue : vin.items()) {
      JsonObject input = Json.object(inputValue);
      if (input == null) return null;
      if (Json.isTrue(input.get("is_coinbase"))) continue;
      String coin = Json.string(input.get("txid"));
      Long index = safeNatural(input.get("vout"));
      if (!Text.isHex32(coin) || index == null || index > MAX_VOUT) return null;
      coins.add(coin + ":" + index);
    }
    List<Output> outputs = new ArrayList<>();
    long total = 0;
    for (JsonValue outputValue : vout.items()) {
      JsonObject output = Json.object(outputValue);
      Long sats = output == null ? null : safeNatural(output.get("value"));
      if (sats == null) return null;
      // More than every bitcoin there will ever be, all outputs together: no transaction, whatever
      // an explorer says (SPEC.md §31). Each at most 2^53 − 1, the sum so far at most 2.1 × 10^15:
      // never past a long.
      total += sats;
      if (total > Constants.MAX_SATS) return null;
      JsonValue address = output.get("scriptpubkey_address");
      if (address != null && Json.string(address) == null) return null;
      outputs.add(new Output(sats, Json.string(address)));
    }
    JsonValue confirmed = status.get("confirmed");
    if (!(confirmed instanceof JsonBoolean)) return null;
    Long blockHeight = null;
    if (confirmed == JsonBoolean.TRUE) {
      blockHeight = safeNatural(status.get("block_height"));
      if (blockHeight == null) return null;
    }
    return new Tx(txid, coins, outputs, blockHeight);
  }

  /**
   * What {@code tx} pays {@code address}: the sum of its outputs to it.
   *
   * @param tx the transaction
   * @param address the address
   * @return the sats
   */
  public static long paysTo(Tx tx, String address) {
    long sum = 0;
    for (Output output : tx.outputs()) {
      if (!address.equals(output.address())) continue;
      // At most 2.1 × 10^15 all together (parseTransaction): never past a long.
      try {
        sum = Math.addExact(sum, output.sats());
      } catch (ArithmeticException e) {
        throw new NetworkException("The explorer gave a transaction that is not one", e);
      }
    }
    return sum;
  }

  /**
   * Its confirmations at the chain's {@code height}: 0 unconfirmed, else height − its block's + 1,
   * at least 1.
   *
   * @param tx the transaction
   * @param height the chain's height
   * @return its confirmations
   */
  public static long confirmationsOf(Tx tx, long height) {
    return tx.blockHeight() == null ? 0 : Math.max(1, height - tx.blockHeight() + 1);
  }

  /**
   * The transactions of {@code address} on {@code explorer}: its first answer (a deposit address is
   * paid a few times at most), in the explorer's order.
   *
   * @param http the HTTP client
   * @param explorer the explorer
   * @param address the address
   * @return the transactions; a {@link NetworkException} for no usable answer
   */
  public static CompletableFuture<List<Tx>> addressTransactions(
      Http http, Source explorer, String address) {
    String url = explorer.rest() + "/address/" + address + "/txs";
    return Tasks.map(
        http.get(url, Map.of()),
        answer -> {
          if (!answer.ok()) {
            throw new NetworkException(Urls.hostOf(url) + " answered HTTP " + answer.status());
          }
          JsonArray body = Json.array(answer.json());
          if (body == null) throw new NetworkException(Urls.hostOf(url) + " gave no transactions");
          List<Tx> txs = new ArrayList<>();
          for (JsonValue value : body.items()) {
            Tx tx = parseTransaction(value);
            if (tx == null) {
              throw new NetworkException(Urls.hostOf(url) + " gave a transaction that is not one");
            }
            txs.add(tx);
          }
          return txs;
        });
  }

  private static final Pattern HEIGHT = Pattern.compile("[0-9]{1,9}");

  /**
   * The chain's height on {@code explorer}: a body of 1 to 9 digits (plain text).
   *
   * @param http the HTTP client
   * @param explorer the explorer
   * @return the height
   */
  public static CompletableFuture<Long> chainHeight(Http http, Source explorer) {
    String url = explorer.rest() + "/blocks/tip/height";
    return Tasks.map(
        http.get(url, Map.of()),
        answer -> {
          if (!answer.ok()) {
            throw new NetworkException(Urls.hostOf(url) + " answered HTTP " + answer.status());
          }
          String text = answer.text();
          if (text == null || !HEIGHT.matcher(text).matches()) {
            throw new NetworkException(Urls.hostOf(url) + " gave no height");
          }
          return Long.parseLong(text);
        });
  }

  private static final Pattern BLOCKCYPHER_TIME =
      Pattern.compile(
          "([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})(?:\\.([0-9]{1,9}))?Z");

  /**
   * Unix milliseconds of a time as BlockCypher writes it (UTC, a fraction of 1 to 9 digits or none,
   * {@code Z}), the fraction past milliseconds dropped (never rounded).
   *
   * @param text the time
   * @return its milliseconds, or null when it is not one, or not a real date and time (a month of
   *     01 to 12, a day of that month, hours 00 to 23, minutes and seconds 00 to 59)
   */
  public static Long blockcypherMilliseconds(String text) {
    Matcher match = BLOCKCYPHER_TIME.matcher(text);
    if (!match.matches()) return null;
    int year = Integer.parseInt(match.group(1));
    int month = Integer.parseInt(match.group(2));
    int day = Integer.parseInt(match.group(3));
    int hour = Integer.parseInt(match.group(4));
    int minute = Integer.parseInt(match.group(5));
    int second = Integer.parseInt(match.group(6));
    if (month < 1 || month > 12 || hour > 23 || minute > 59 || second > 59) return null;
    String fraction = match.group(7) == null ? "" : match.group(7);
    StringBuilder millis =
        new StringBuilder(fraction.length() > 3 ? fraction.substring(0, 3) : fraction);
    while (millis.length() < 3) millis.append('0');
    try {
      // The proleptic Gregorian calendar, years 0 to 9999 as written (never 1900 + a short year).
      LocalDateTime time = LocalDateTime.of(year, month, day, hour, minute, second);
      return time.toEpochSecond(ZoneOffset.UTC) * 1000 + Integer.parseInt(millis.toString());
    } catch (DateTimeException e) {
      // A day that is not one of its month.
      return null;
    }
  }

  /**
   * When BlockCypher first received {@code txid}, in Unix milliseconds (it never changes).
   *
   * @param http the HTTP client
   * @param txid the transaction's id
   * @return its time, or null when BlockCypher does not know it yet (HTTP 404)
   */
  public static CompletableFuture<Long> receivedAt(Http http, String txid) {
    String url = Constants.BLOCKCYPHER_URL + "/txs/" + txid + "?limit=1&includeHex=false";
    return Tasks.map(
        http.get(url, Map.of()),
        answer -> {
          if (answer.status() == 404) return null;
          if (!answer.ok()) {
            throw new NetworkException(Urls.hostOf(url) + " answered HTTP " + answer.status());
          }
          JsonObject body = Json.object(answer.json());
          String received = body == null ? null : Json.string(body.get("received"));
          if (body == null || !txid.equals(Json.string(body.get("hash"))) || received == null) {
            throw new NetworkException(Urls.hostOf(url) + " gave no time for the transaction");
          }
          Long time = blockcypherMilliseconds(received);
          if (time == null || time <= 0) {
            throw new NetworkException(Urls.hostOf(url) + " gave no time for the transaction");
          }
          return time;
        });
  }
}
