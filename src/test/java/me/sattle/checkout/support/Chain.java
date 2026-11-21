package me.sattle.checkout.support;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.sattle.checkout.internal.Constants;
import me.sattle.checkout.internal.Hex;
import me.sattle.checkout.internal.Text;
import me.sattle.checkout.internal.crypto.Hashes;
import me.sattle.checkout.internal.json.JsonArray;
import me.sattle.checkout.internal.json.JsonObject;

/**
 * The blockchain for tests: deposit addresses (taproot, bech32m) made from a seed, and fakes of the
 * explorers (mempool.space and the merchant's Bitcoin node, Esplora REST, both showing this chain)
 * and of BlockCypher. Never the network.
 */
public final class Chain {
  /** The chain's height. */
  public volatile long height = 970_000;

  /** The transactions of each address, in the explorer's order (newest first). */
  public final Map<String, List<JsonObject>> txs = new ConcurrentHashMap<>();

  /** When BlockCypher received each transaction it knows (RFC 3339). */
  public final Map<String, String> received = new ConcurrentHashMap<>();

  /**
   * The merchant's Bitcoin node's REST URL ({@code <node URL>/api}), which answers as mempool.space
   * does; null: none.
   */
  public volatile String nodeRest;

  /** Services that fail (HTTP 5xx): "explorer" (mempool.space), "node", "blockcypher". */
  public final Set<String> down = ConcurrentHashMap.newKeySet();

  /** What was asked, by URL. */
  public final List<String> asked = new CopyOnWriteArrayList<>();

  /** A chain with no transaction. */
  public Chain() {}

  /**
   * The deposit address ({@code bc1p…}) of a 32-byte program derived from {@code seed}.
   *
   * @param seed the seed
   * @return the address
   */
  public static String depositAddress(Object seed) {
    byte[] program = Hashes.sha256(Text.utf8("deposit address " + seed));
    List<Integer> words = new ArrayList<>(List.of(1));
    words.addAll(Lightning.bytesToWords(program));
    return Lightning.encode("bc", words, 0x2bc830a3);
  }

  /**
   * A txid made from {@code seed}.
   *
   * @param seed the seed
   * @return the txid
   */
  public static String txid(Object seed) {
    return Hex.encode(Hashes.sha256(Text.utf8("transaction " + seed)));
  }

  /**
   * A transaction paying {@code address} {@code sats}, spending one coin of its own, and some
   * change, unconfirmed.
   *
   * @param id its txid
   * @param address the address
   * @param sats what it pays
   * @return the transaction, as Esplora writes it
   */
  public static JsonObject payment(String id, String address, long sats) {
    return payment(id, address, sats, List.of(txid("coin of " + id) + ":0"), null);
  }

  /**
   * A transaction paying {@code address} {@code sats}, spending {@code coins}, and some change.
   *
   * @param id its txid
   * @param address the address
   * @param sats what it pays
   * @param coins the coins it spends ({@code <txid>:<vout>})
   * @param height its block's height, or null when unconfirmed
   * @return the transaction, as Esplora writes it
   */
  public static JsonObject payment(
      String id, String address, long sats, List<String> coins, Long height) {
    JsonArray vin = new JsonArray();
    for (String coin : coins) {
      String[] parts = coin.split(":");
      vin.add(Js.obj("txid", parts[0], "vout", Long.parseLong(parts[1]), "is_coinbase", false));
    }
    JsonArray vout =
        Js.arr(
            Js.obj("value", sats, "scriptpubkey_address", address),
            Js.obj("value", 5000, "scriptpubkey_address", depositAddress("change")));
    JsonObject status =
        height == null
            ? Js.obj("confirmed", false)
            : Js.obj("confirmed", true, "block_height", height);
    return Js.obj("txid", id, "vin", vin, "vout", vout, "status", status);
  }

  /**
   * Adds {@code tx} to the transactions of {@code address}, first (the newest first).
   *
   * @param address the address
   * @param tx the transaction
   */
  public synchronized void add(String address, JsonObject tx) {
    List<JsonObject> list = new ArrayList<>();
    list.add(tx);
    list.addAll(txs.getOrDefault(address, List.of()));
    txs.put(address, list);
  }

  /**
   * Drops {@code id} from the mempool (replaced, or dropped).
   *
   * @param address the address
   * @param id the txid
   */
  public synchronized void drop(String address, String id) {
    List<JsonObject> list = new ArrayList<>();
    for (JsonObject tx : txs.getOrDefault(address, List.of())) {
      if (!id.equals(Js.text(tx, "txid"))) list.add(tx);
    }
    txs.put(address, list);
  }

  /**
   * Confirms {@code id} at {@code height}.
   *
   * @param address the address
   * @param id the txid
   * @param at the height of its block
   */
  public synchronized void confirm(String address, String id, long at) {
    for (JsonObject tx : txs.getOrDefault(address, List.of())) {
      if (id.equals(Js.text(tx, "txid")))
        tx.put("status", Js.obj("confirmed", true, "block_height", at));
    }
  }

  private static final Pattern ADDRESS = Pattern.compile("/address/([a-z0-9]+)/txs");

  /**
   * The answer to {@code uri}.
   *
   * @param uri the URL asked
   * @return the answer, or null when it is not an explorer's
   */
  public synchronized FakeHttp.Answer route(URI uri) {
    String href = uri.toString();
    String node = nodeRest;
    boolean ofNode = node != null && href.startsWith(node + "/");
    if (href.startsWith(Constants.MEMPOOL_URL + "/") || ofNode) {
      asked.add(href);
      if (down.contains(ofNode ? "node" : "explorer")) return FakeHttp.Answer.text(503, "down");
      String path = href.substring((ofNode ? node : Constants.MEMPOOL_URL).length());
      if (path.equals("/blocks/tip/height")) {
        return FakeHttp.Answer.text(200, Long.toString(height));
      }
      Matcher address = ADDRESS.matcher(path);
      if (address.matches()) {
        JsonArray list = new JsonArray();
        for (JsonObject tx : txs.getOrDefault(address.group(1), List.of())) list.add(tx);
        return FakeHttp.Answer.json(list);
      }
      return FakeHttp.Answer.text(404, "Not found");
    }
    if (href.startsWith(Constants.BLOCKCYPHER_URL)) {
      asked.add(href);
      if (down.contains("blockcypher")) return FakeHttp.Answer.json(500, Js.obj("error", "down"));
      String path = uri.getPath();
      String id = path.substring(path.lastIndexOf('/') + 1);
      String time = received.get(id);
      return time == null
          ? FakeHttp.Answer.json(404, Js.obj("error", "Transaction " + id + " not found."))
          : FakeHttp.Answer.json(Js.obj("hash", id, "received", time, "confirmations", 0));
    }
    return null;
  }

  /**
   * The URLs of BlockCypher asked.
   *
   * @return the URLs
   */
  public List<String> blockcypherAsked() {
    List<String> out = new ArrayList<>();
    for (String href : asked) if (href.startsWith(Constants.BLOCKCYPHER_URL)) out.add(href);
    return out;
  }

  /**
   * The URLs of mempool.space asked.
   *
   * @return the URLs
   */
  public List<String> mempoolAsked() {
    List<String> out = new ArrayList<>();
    for (String href : asked) if (href.startsWith(Constants.MEMPOOL_URL + "/")) out.add(href);
    return out;
  }

  /**
   * The URLs of the merchant's Bitcoin node asked.
   *
   * @return the URLs
   */
  public List<String> nodeAsked() {
    String node = nodeRest;
    List<String> out = new ArrayList<>();
    for (String href : asked) if (node != null && href.startsWith(node + "/")) out.add(href);
    return out;
  }
}
